using System.Text;
using PinpadMediaManager.Core;
using PinpadMediaManager.Core.Protocol;
using PinpadMediaManager.Core.Transport;

namespace PinpadMediaManager.Tests;

public sealed class AudioRecordingTests
{
    private const string Name = "audio-1790000000000-00000000-0000-0000-0000-000000000001.wav";
    private const char Fs = PinpadControl.Fs;

    [Fact] public async Task CommandsUseExpectedWireIdsAndPayloads()
    {
        using var transport = new AudioTransport(request => request.CommandId switch {
            "M20" or "M21" => $"0{Fs}{Name}",
            "M22" => $"0{Fs}S|32044|1000|{Name}",
            _ => "0"
        });
        using var client = new PinpadClient(transport);
        client.Connect("test", 115200);
        Assert.Equal(Name, await client.StartAudioRecordingAsync());
        Assert.Equal(Name, await client.StopAudioRecordingAsync());
        var entry = Assert.Single(await client.ListAudioRecordingsAsync());
        Assert.Equal("Stopped", entry.State);
        Assert.Equal(32044, entry.SizeBytes);
        await client.DeleteAudioRecordingAsync(Name);
        await client.ResetAudioRecordingsAsync();
        Assert.Equal(new[] { "M20", "M21", "M22", "M24", "M25" }, transport.Requests.Select(x => x.CommandId));
        Assert.Equal(new[] { "", "", "", Name, "" }, transport.Requests.Select(x => x.PayloadAscii));
    }

    [Fact] public async Task UnsupportedIsAnExplicitUserFacingError()
    {
        using var transport = new AudioTransport(_ => "U");
        using var client = new PinpadClient(transport);
        client.Connect("test", 115200);
        var exception = await Assert.ThrowsAsync<PinpadProtocolException>(() => client.StartAudioRecordingAsync());
        Assert.Contains("Unsupported", exception.Message);
        Assert.Contains("N6 Pro", exception.Message);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task DownloadStreamsMultipleIndependentBase64PacketsWithExactOffsets(bool aac)
    {
        var content = Enumerable.Range(0, 10000).Select(i => (byte)(i % 251)).ToArray();
        Encoding.ASCII.GetBytes("RIFF").CopyTo(content, 0);
        Encoding.ASCII.GetBytes("WAVE").CopyTo(content, 8);
        var name = aac ? Name.Replace(".wav", ".aac") : Name;
        if (aac) new byte[] { 0xff, 0xf1, 0x60, 0x40, 0, 0xff, 0xfc }.CopyTo(content, 0);
        using var transport = new AudioTransport(request => {
            Assert.Equal("M23", request.CommandId);
            var fields = request.PayloadAscii.Split(Fs);
            Assert.Equal(name, fields[0]);
            var offset = int.Parse(fields[1]);
            return $"0{Fs}{offset}{Fs}{content.Length}{Fs}{Convert.ToBase64String(content.Skip(offset).Take(1024).ToArray())}";
        });
        using var client = new PinpadClient(transport);
        client.Connect("test", 115200);
        using var output = new MemoryStream();
        await client.DownloadAudioRecordingAsync(name, output);
        Assert.Equal(content, output.ToArray());
        Assert.Equal(Enumerable.Range(0, 10).Select(index => $"{name}{Fs}{index * 1024}"), transport.Requests.Select(x => x.PayloadAscii));
    }

    [Theory]
    [InlineData("1")]
    [InlineData("3")]
    [InlineData("4")]
    [InlineData("U")]
    [InlineData("0\u001c1\u001c44\u001cAAAA")]
    [InlineData("0\u001c0\u001c999999999\u001cAAAA")]
    [InlineData("0\u001c0\u001c44\u001c!!!")]
    [InlineData("0\u001c0\u001c44\u001c")]
    public void RejectsErrorAndMalformedPackets(string response) =>
        Assert.Throws<PinpadProtocolException>(() => AudioRecordingProtocol.ParsePacket(response, 0, null));

    [Fact] public void RejectsChangedLengthTraversalAndDuplicateListEntries()
    {
        Assert.False(AudioRecordingProtocol.ValidName("../" + Name));
        Assert.Throws<PinpadProtocolException>(() => AudioRecordingProtocol.ParsePacket($"0{Fs}0{Fs}44{Fs}AAAA", 0, 45));
        Assert.Throws<PinpadProtocolException>(() => AudioRecordingProtocol.ParseList($"0{Fs}S|44|0|{Name}{Fs}S|44|0|{Name}"));
    }

    private sealed class AudioTransport(Func<PinpadFrame, string> response) : IPinpadTransport
    {
        private readonly Queue<byte> incoming = new();
        public List<PinpadFrame> Requests { get; } = [];
        public bool IsOpen { get; private set; }
        public string PortName => "test";
        public int BaudRate => 115200;
        public void Open(string portName, int baudRate) => IsOpen = true;
        public void Close() => IsOpen = false;
        public void ChangeBaudRate(int baudRate) { }
        public void DiscardInput() => incoming.Clear();
        public void Write(ReadOnlySpan<byte> bytes) {
            if (bytes.Length == 1) return;
            var request = PinpadFrameCodec.Decode(bytes);
            Requests.Add(request);
            incoming.Enqueue(PinpadControl.Ack);
            foreach (var b in PinpadFrameCodec.Encode(PinpadFrame.Ascii(PinpadFrameType.Transaction, request.CommandId, response(request)))) incoming.Enqueue(b);
        }
        public int ReadByte(TimeSpan timeout, CancellationToken token) {
            token.ThrowIfCancellationRequested();
            return incoming.Count == 0 ? throw new TimeoutException() : incoming.Dequeue();
        }
        public void Dispose() => Close();
    }
}
