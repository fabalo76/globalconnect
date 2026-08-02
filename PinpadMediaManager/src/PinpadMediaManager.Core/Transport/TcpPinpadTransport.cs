using System.Net.Sockets;

namespace PinpadMediaManager.Core.Transport;

public sealed class TcpPinpadTransport : IPinpadTransport
{
    private const int NominalNetworkBitsPerSecond = 10_000_000;
    private TcpClient? _client;
    private NetworkStream? _stream;
    private string _endpoint = "";

    public bool IsOpen => _client?.Connected == true && _stream is not null;
    public string PortName => _endpoint;
    public int BaudRate => NominalNetworkBitsPerSecond;

    public void Open(string host, int port)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(host);
        ArgumentOutOfRangeException.ThrowIfLessThan(port, 1);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(port, 65_535);
        if (IsOpen)
        {
            throw new InvalidOperationException("A TCP connection is already open.");
        }

        var client = new TcpClient
        {
            NoDelay = true,
        };
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        try
        {
            client.ConnectAsync(host.Trim(), port, timeout.Token).AsTask().GetAwaiter().GetResult();
            client.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.KeepAlive, true);
            _client = client;
            _stream = client.GetStream();
            _endpoint = $"{host.Trim()}:{port}";
        }
        catch
        {
            client.Dispose();
            throw;
        }
    }

    public void Close()
    {
        _stream?.Dispose();
        _client?.Dispose();
        _stream = null;
        _client = null;
        _endpoint = "";
    }

    public void Write(ReadOnlySpan<byte> data)
    {
        var stream = RequireOpenStream();
        try
        {
            stream.Write(data);
            stream.Flush();
        }
        catch
        {
            Close();
            throw;
        }
    }

    public int ReadByte(TimeSpan timeout, CancellationToken cancellationToken)
    {
        var stream = RequireOpenStream();
        using var timeoutSource = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeoutSource.CancelAfter(timeout);
        try
        {
            var buffer = new byte[1];
            var count = stream.ReadAsync(buffer, timeoutSource.Token).AsTask().GetAwaiter().GetResult();
            if (count == 0)
            {
                var endpoint = _endpoint;
                Close();
                throw new IOException($"The pinpad closed TCP connection {endpoint}.");
            }

            return buffer[0];
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            throw new TimeoutException(
                $"No data was received from {_endpoint} within {timeout.TotalSeconds:0.0} seconds.");
        }
        catch (IOException)
        {
            Close();
            throw;
        }
    }

    public void DiscardInput()
    {
        var stream = RequireOpenStream();
        var buffer = new byte[4_096];
        while (stream.DataAvailable)
        {
            _ = stream.Read(buffer, 0, buffer.Length);
        }
    }

    public void ChangeBaudRate(int baudRate)
    {
        // Command 13 changes the terminal's serial interface only. TCP has no baud rate.
    }

    public void Dispose() => Close();

    private NetworkStream RequireOpenStream()
    {
        if (!IsOpen || _stream is null)
        {
            throw new InvalidOperationException("The TCP pinpad connection is not open.");
        }

        return _stream;
    }
}
