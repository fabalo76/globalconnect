using System.Text;
using PinpadMediaManager.Core;
using PinpadMediaManager.Core.Models;
using PinpadMediaManager.Core.Protocol;
using PinpadMediaManager.Core.Transport;

namespace PinpadMediaManager.Tests;

public sealed class PinpadProtocolTests
{
    [Fact]
    public void TransactionFrameRoundTripsWithExpectedLrc()
    {
        var frame = PinpadFrame.Ascii(PinpadFrameType.Transaction, "J9", "LOGO");

        var encoded = PinpadFrameCodec.Encode(frame);
        var decoded = PinpadFrameCodec.Decode(encoded);

        Assert.Equal(PinpadControl.Stx, encoded[0]);
        Assert.Equal(PinpadControl.Etx, encoded[^2]);
        Assert.Equal(PinpadFrameCodec.CalculateLrc(encoded.AsSpan(1, encoded.Length - 2)), encoded[^1]);
        Assert.Equal("J9", decoded.CommandId);
        Assert.Equal("LOGO", decoded.PayloadAscii);
    }

    [Fact]
    public void ThreeCharacterMediaCommandRoundTrips()
    {
        var encoded = PinpadFrameCodec.Encode(
            PinpadFrame.Ascii(PinpadFrameType.Transaction, "M14", "welcome.mp3"));

        var decoded = PinpadFrameCodec.Decode(encoded);

        Assert.Equal("M14", decoded.CommandId);
        Assert.Equal("welcome.mp3", decoded.PayloadAscii);
    }

    [Fact]
    public void JpegDownloadPacketsMatchTerminalLayout()
    {
        var content = Enumerable.Range(0, 6_500).Select(value => (byte)value).ToArray();

        var packets = PinpadPayloads.JpegDownloadPackets("LOGO", content, overwrite: true).ToArray();

        Assert.Equal(2, packets.Length);
        Assert.StartsWith($"00001{PinpadControl.Fs}LOGO{PinpadControl.Fs}8192", packets[0]);
        Assert.StartsWith($"10011{PinpadControl.Fs}{PinpadControl.Fs}0476", packets[1]);
        Assert.Equal(Convert.ToBase64String(content), string.Concat(packets.Select(PacketData)));
    }

    [Fact]
    public void MediaDownloadPacketsUseSixDigitSequence()
    {
        var content = Encoding.ASCII.GetBytes("ID3" + new string('x', 6_500));

        var packets = PinpadPayloads.MediaDownloadPackets("welcome.mp3", content, overwrite: false).ToArray();

        Assert.StartsWith($"00000000{PinpadControl.Fs}welcome.mp3{PinpadControl.Fs}8192", packets[0]);
        Assert.StartsWith($"10000010{PinpadControl.Fs}{PinpadControl.Fs}0480", packets[1]);
    }

    [Fact]
    public void ParsesCurrentPinpadTables()
    {
        var jpegPayload = $"1{PinpadControl.Fs}1LOGO{PinpadControl.Fs}0SALE";
        var mediaPayload =
            $"0{PinpadControl.Fs}30000001234welcome.mp3{PinpadControl.Fs}40000005678promo.mp4";

        var jpegs = PinpadPayloads.ParseJpegTable(jpegPayload);
        var media = PinpadPayloads.ParseMediaTable(mediaPayload);

        Assert.Equal([new JpegEntry("LOGO", true), new JpegEntry("SALE", false)], jpegs);
        Assert.Equal(
            [
                new MediaEntry("welcome.mp3", MediaType.Mp3, 1234),
                new MediaEntry("promo.mp4", MediaType.Mp4, 5678),
            ],
            media);
    }

    [Fact]
    public async Task ClientCompletesTransactionAfterAckingResponseWithoutEot()
    {
        using var transport = new ScriptedTransport(
            command => command == "M11"
                ? PinpadFrame.Ascii(
                    PinpadFrameType.Transaction,
                    "M11",
                    $"0{PinpadControl.Fs}30000000003a.mp3")
                : throw new InvalidOperationException(command));
        transport.Open("TEST", 9_600);
        using var client = new PinpadClient(transport);

        var media = await client.GetMediaTableAsync();

        var entry = Assert.Single(media);
        Assert.Equal("a.mp3", entry.Name);
        Assert.Equal(3, entry.SizeBytes);
        Assert.Equal(2, transport.Writes.Count);
        Assert.Equal(PinpadControl.Ack, Assert.Single(transport.Writes[1]));
    }

    [Fact]
    public async Task ClientWaitsForFinalEotAfterAdministrationResponse()
    {
        using var transport = new ScriptedTransport(
            command => command == "13"
                ? PinpadFrame.Ascii(PinpadFrameType.Administration, "13", "0")
                : throw new InvalidOperationException(command));
        transport.Open("TEST", 9_600);
        using var client = new PinpadClient(transport);

        await client.ChangeBaudRateAsync(115_200);

        Assert.Equal(115_200, transport.BaudRate);
        Assert.Equal(2, transport.Writes.Count);
        Assert.Equal(PinpadControl.Ack, Assert.Single(transport.Writes[1]));
    }

    [Fact]
    public async Task MediaVolumeUsesTwoDigitM15Payload()
    {
        using var transport = new ScriptedTransport(
            command => command == "M15"
                ? PinpadFrame.Ascii(PinpadFrameType.Transaction, "M15", "0")
                : throw new InvalidOperationException(command));
        transport.Open("TEST", 9_600);
        using var client = new PinpadClient(transport);

        await client.SetMediaVolumeAsync(7);

        var request = PinpadFrameCodec.Decode(transport.Writes[0]);
        Assert.Equal("M15", request.CommandId);
        Assert.Equal("07", request.PayloadAscii);
    }

    [Fact]
    public async Task MediaDeleteUsesFsSeparatedM16Payload()
    {
        using var transport = new ScriptedTransport(
            command => command == "M16"
                ? PinpadFrame.Ascii(
                    PinpadFrameType.Transaction,
                    "M16",
                    $"0{PinpadControl.Fs}0")
                : throw new InvalidOperationException(command));
        transport.Open("TEST", 9_600);
        using var client = new PinpadClient(transport);

        await client.DeleteMediaAsync(["welcome.mp3", "promo.mp4"]);

        var request = PinpadFrameCodec.Decode(transport.Writes[0]);
        Assert.Equal("M16", request.CommandId);
        Assert.Equal($"welcome.mp3{PinpadControl.Fs}promo.mp4", request.PayloadAscii);
    }

    [Fact]
    public async Task TextToSpeechUsesLanguageAndUtf8Base64M17Payload()
    {
        using var transport = new ScriptedTransport(
            command => command == "M17"
                ? PinpadFrame.Ascii(PinpadFrameType.Transaction, "M17", "0")
                : throw new InvalidOperationException(command));
        transport.Open("TEST", 9_600);
        using var client = new PinpadClient(transport);

        await client.SpeakTextAsync("es", "¡Hola, señor!");

        var request = PinpadFrameCodec.Decode(transport.Writes[0]);
        Assert.Equal("M17", request.CommandId);
        Assert.Equal($"es{PinpadControl.Fs}wqFIb2xhLCBzZcOxb3Ih", request.PayloadAscii);
    }

    [Theory]
    [InlineData("es-MX")]
    [InlineData("es-419")]
    [InlineData("en-US")]
    public async Task TextToSpeechRequiresSimpleLanguageCode(string language)
    {
        using var transport = new ScriptedTransport(
            _ => throw new InvalidOperationException("No command should be transmitted."));
        transport.Open("TEST", 9_600);
        using var client = new PinpadClient(transport);

        var exception = await Assert.ThrowsAsync<ArgumentException>(
            () => client.SpeakTextAsync(language, "Hello"));

        Assert.Equal("language", exception.ParamName);
        Assert.Empty(transport.Writes);
    }

    private static string PacketData(string packet)
    {
        var secondFs = packet.IndexOf(PinpadControl.Fs, packet.IndexOf(PinpadControl.Fs) + 1);
        return packet[(secondFs + 5)..];
    }

    private sealed class ScriptedTransport(Func<string, PinpadFrame> responseFactory) : IPinpadTransport
    {
        private readonly Queue<byte> _incoming = new();
        private PinpadFrameType? _pendingResponseType;
        public List<byte[]> Writes { get; } = [];

        public bool IsOpen { get; private set; }
        public string PortName { get; private set; } = "";
        public int BaudRate { get; private set; }

        public void Open(string portName, int baudRate)
        {
            IsOpen = true;
            PortName = portName;
            BaudRate = baudRate;
        }

        public void Close() => IsOpen = false;

        public void Write(ReadOnlySpan<byte> data)
        {
            var copy = data.ToArray();
            Writes.Add(copy);
            if (copy.Length == 1 && copy[0] == PinpadControl.Ack)
            {
                if (_pendingResponseType == PinpadFrameType.Administration)
                {
                    _incoming.Enqueue(PinpadControl.Eot);
                }

                _pendingResponseType = null;
                return;
            }

            var request = PinpadFrameCodec.Decode(copy);
            var response = responseFactory(request.CommandId);
            _incoming.Enqueue(PinpadControl.Ack);
            foreach (var value in PinpadFrameCodec.Encode(response))
            {
                _incoming.Enqueue(value);
            }
            _pendingResponseType = response.Type;
        }

        public int ReadByte(TimeSpan timeout, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return _incoming.Count > 0
                ? _incoming.Dequeue()
                : throw new TimeoutException();
        }

        public void DiscardInput()
        {
        }

        public void ChangeBaudRate(int baudRate) => BaudRate = baudRate;
        public void Dispose() => Close();
    }
}
