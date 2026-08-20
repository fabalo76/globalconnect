using System.IO.Ports;
using GlobalConnect.KeyInjection.Core.Protocol;

namespace GlobalConnect.KeyInjection.App.Serial;

public sealed class FuturexSerialClient : IDisposable
{
    private const int MaxNakRetries = 3;
    private readonly SemaphoreSlim _exchangeLock = new(1, 1);
    private SerialPort? _port;

    public bool IsConnected => _port?.IsOpen == true;
    public string? PortName => _port?.PortName;

    public async Task ConnectAsync(string portName, int timeoutMilliseconds, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(portName))
            throw new ArgumentException("Select a COM port.", nameof(portName));
        if (timeoutMilliseconds is < 500 or > 120_000)
            throw new ArgumentOutOfRangeException(nameof(timeoutMilliseconds), "Timeout must be between 500 and 120,000 milliseconds.");

        Disconnect();
        var port = new SerialPort(portName, 9600, Parity.None, 8, StopBits.One)
        {
            Handshake = Handshake.None,
            ReadTimeout = timeoutMilliseconds,
            WriteTimeout = timeoutMilliseconds,
            DtrEnable = true,
            RtsEnable = true
        };

        try
        {
            port.Open();
            _port = port;
            await Task.Delay(150, cancellationToken).ConfigureAwait(false);
            DrainInput(port);
        }
        catch
        {
            port.Dispose();
            _port = null;
            throw;
        }
    }

    public void Disconnect()
    {
        var port = Interlocked.Exchange(ref _port, null);
        if (port is null) return;
        try { if (port.IsOpen) port.Close(); }
        finally { port.Dispose(); }
    }

    public async Task<FuturexResponse> ExchangeAsync(string requestBody, CancellationToken cancellationToken = default)
    {
        await _exchangeLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            var port = _port;
            if (port?.IsOpen != true)
                throw new InvalidOperationException("Connect to a terminal first.");

            return await Task.Run(() => Exchange(port, requestBody, cancellationToken), cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _exchangeLock.Release();
        }
    }

    private static FuturexResponse Exchange(SerialPort port, string requestBody, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        DrainInput(port);
        var requestFrame = FuturexPacketCodec.Encode(requestBody);
        var acknowledged = false;

        for (var attempt = 0; attempt <= MaxNakRetries; attempt++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            port.Write(requestFrame, 0, requestFrame.Length);
            var control = ReadControl(port, cancellationToken);
            if (control == FuturexPacketCodec.Ack)
            {
                acknowledged = true;
                break;
            }
            if (control != FuturexPacketCodec.Nak)
                throw new FuturexProtocolException($"The terminal returned unexpected control byte 0x{control:X2}.");
        }

        if (!acknowledged)
            throw new FuturexProtocolException("The terminal rejected the request after three retries.");

        for (var attempt = 0; attempt <= MaxNakRetries; attempt++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var responseFrame = ReadFrame(port, cancellationToken);
            try
            {
                var responseBody = FuturexPacketCodec.Decode(responseFrame);
                port.Write([FuturexPacketCodec.Ack], 0, 1);
                return FuturexResponseParser.Parse(responseBody);
            }
            catch (FuturexProtocolException) when (attempt < MaxNakRetries)
            {
                port.Write([FuturexPacketCodec.Nak], 0, 1);
            }
        }

        port.Write([FuturexPacketCodec.Nak], 0, 1);
        throw new FuturexProtocolException("The terminal returned an invalid response after three retries.");
    }

    private static byte ReadControl(SerialPort port, CancellationToken cancellationToken)
    {
        while (true)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var value = port.ReadByte();
            if (value < 0) throw new TimeoutException("Timed out waiting for the terminal acknowledgment.");
            if (value is FuturexPacketCodec.Ack or FuturexPacketCodec.Nak)
                return (byte)value;
        }
    }

    private static byte[] ReadFrame(SerialPort port, CancellationToken cancellationToken)
    {
        var frame = new List<byte>(256);
        while (true)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var value = port.ReadByte();
            if (value < 0) throw new TimeoutException("Timed out waiting for a Futurex response packet.");
            if (value == FuturexPacketCodec.Stx)
            {
                frame.Add((byte)value);
                break;
            }
        }

        while (frame.Count < 4096)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var value = port.ReadByte();
            if (value < 0) throw new TimeoutException("Timed out while receiving a Futurex response packet.");
            frame.Add((byte)value);
            if (value == FuturexPacketCodec.Etx)
            {
                var lrc = port.ReadByte();
                if (lrc < 0) throw new TimeoutException("Timed out waiting for the Futurex LRC.");
                frame.Add((byte)lrc);
                return frame.ToArray();
            }
        }

        throw new FuturexProtocolException("The received Futurex packet exceeded the maximum size.");
    }

    private static void DrainInput(SerialPort port)
    {
        while (port.IsOpen && port.BytesToRead > 0)
            _ = port.ReadByte();
    }

    public void Dispose()
    {
        Disconnect();
        _exchangeLock.Dispose();
    }
}
