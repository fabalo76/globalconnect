using System.Diagnostics;
using System.IO.Ports;

namespace PinpadMediaManager.Core.Transport;

public interface IPinpadTransport : IDisposable
{
    bool IsOpen { get; }
    string PortName { get; }
    int BaudRate { get; }
    void Open(string portName, int baudRate);
    void Close();
    void Write(ReadOnlySpan<byte> data);
    int ReadByte(TimeSpan timeout, CancellationToken cancellationToken);
    void DiscardInput();
    void ChangeBaudRate(int baudRate);
}

public sealed class SerialPinpadTransport : IPinpadTransport
{
    private SerialPort? _port;

    public bool IsOpen => _port?.IsOpen == true;
    public string PortName => _port?.PortName ?? "";
    public int BaudRate => _port?.BaudRate ?? 0;

    public static string[] GetPortNames() =>
        SerialPort.GetPortNames().OrderBy(value => value, StringComparer.OrdinalIgnoreCase).ToArray();

    public void Open(string portName, int baudRate)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(portName);
        if (IsOpen)
        {
            throw new InvalidOperationException("A serial port is already open.");
        }

        _port = new SerialPort(portName, baudRate, Parity.None, 8, StopBits.One)
        {
            Handshake = Handshake.None,
            ReadTimeout = 50,
            WriteTimeout = 5_000,
            DtrEnable = true,
            RtsEnable = false,
        };
        _port.Open();
        _port.DiscardInBuffer();
        _port.DiscardOutBuffer();
    }

    public void Close()
    {
        if (_port is null)
        {
            return;
        }

        if (_port.IsOpen)
        {
            _port.Close();
        }

        _port.Dispose();
        _port = null;
    }

    public void Write(ReadOnlySpan<byte> data)
    {
        var port = RequireOpenPort();
        var buffer = data.ToArray();
        port.Write(buffer, 0, buffer.Length);
    }

    public int ReadByte(TimeSpan timeout, CancellationToken cancellationToken)
    {
        var port = RequireOpenPort();
        var clock = Stopwatch.StartNew();
        while (clock.Elapsed < timeout)
        {
            cancellationToken.ThrowIfCancellationRequested();
            try
            {
                return port.ReadByte();
            }
            catch (TimeoutException)
            {
            }
        }

        throw new TimeoutException($"No data was received from {port.PortName} within {timeout.TotalSeconds:0.0} seconds.");
    }

    public void DiscardInput() => RequireOpenPort().DiscardInBuffer();

    public void ChangeBaudRate(int baudRate)
    {
        RequireOpenPort().BaudRate = baudRate;
    }

    public void Dispose() => Close();

    private SerialPort RequireOpenPort()
    {
        if (_port?.IsOpen != true)
        {
            throw new InvalidOperationException("The serial port is not connected.");
        }

        return _port;
    }
}
