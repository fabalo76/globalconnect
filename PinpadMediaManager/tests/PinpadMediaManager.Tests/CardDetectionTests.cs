using PinpadMediaManager.Core;
using PinpadMediaManager.Core.Models;
using PinpadMediaManager.Core.Protocol;
using PinpadMediaManager.Core.Transport;

namespace PinpadMediaManager.Tests;

public sealed class CardDetectionTests
{
    [Theory]
    [InlineData("1", "Magnetic stripe")]
    [InlineData("2", "Chip card")]
    [InlineData("3", "Contactless")]
    [InlineData("00", "could not enable")]
    [InlineData("03", "canceled or timed out")]
    public async Task SendsQkAndDecodesTerminalResult(string payload, string description)
    {
        using var transport = new DetectionTransport(payload);
        using var client = new PinpadClient(transport);
        client.Connect("test", 9600);
        var result = await client.DetectA10CardAsync();
        Assert.Contains(description, result.Description);
        Assert.Equal(payload, result.Payload);
        var request = Assert.Single(transport.Requests);
        Assert.Equal("QK", request.CommandId);
        Assert.Equal("", request.PayloadAscii);
        Assert.Equal(PinpadFrameType.Transaction, request.Type);
    }

    [Theory]
    [InlineData("")]
    [InlineData("4")]
    [InlineData("23")]
    public void RejectsMalformedResponses(string payload) =>
        Assert.Throws<PinpadProtocolException>(() => A10CardDetectionResult.Parse(payload));

    [Theory]
    [InlineData(null, null, null, "")]
    [InlineData("001", null, null, "001")]
    [InlineData("011", "Venta", "US$10.00", "011\u001cVenta\u001cUS$10.00")]
    [InlineData(null, null, "L 25.00", "\u001c\u001cL 25.00")]
    public void FormatsOptionalFields(string? mask, string? name, string? amount, string expected) =>
        Assert.Equal(expected, QkDetectionRequest.BuildPayload(mask, name, amount));

    [Theory]
    [InlineData("000")]
    [InlineData("11")]
    [InlineData("102")]
    public void RejectsInvalidReaderMasks(string mask) =>
        Assert.Throws<ArgumentException>(() => QkDetectionRequest.BuildPayload(mask, null, null));

    [Fact]
    public async Task SendsDisplayTextAsUtf8()
    {
        using var transport = new DetectionTransport("2");
        using var client = new PinpadClient(transport);
        client.Connect("test", 9600);
        await client.DetectA10CardAsync("011", "Devolución", "€10,00");
        Assert.Equal("011\u001cDevolución\u001c€10,00", System.Text.Encoding.UTF8.GetString(Assert.Single(transport.Requests).Payload));
    }

    private sealed class DetectionTransport(string response) : IPinpadTransport
    {
        private readonly Queue<byte> incoming = new();
        public List<PinpadFrame> Requests { get; } = [];
        public bool IsOpen { get; private set; }
        public string PortName => "test";
        public int BaudRate => 9600;
        public void Open(string portName, int baudRate) => IsOpen = true;
        public void Close() => IsOpen = false;
        public void ChangeBaudRate(int baudRate) { }
        public void DiscardInput() => incoming.Clear();
        public void Write(ReadOnlySpan<byte> bytes)
        {
            if (bytes.Length == 1) return;
            var request = PinpadFrameCodec.Decode(bytes);
            Requests.Add(request);
            incoming.Enqueue(PinpadControl.Ack);
            foreach (var b in PinpadFrameCodec.Encode(PinpadFrame.Ascii(PinpadFrameType.Transaction, "QK", response))) incoming.Enqueue(b);
        }
        public int ReadByte(TimeSpan timeout, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            return incoming.Count == 0 ? throw new TimeoutException() : incoming.Dequeue();
        }
        public void Dispose() => Close();
    }
}
