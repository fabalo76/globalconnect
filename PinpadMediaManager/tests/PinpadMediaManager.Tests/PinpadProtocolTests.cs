using System.Text;
using System.Net;
using System.Net.Sockets;
using PinpadMediaManager.Core;
using PinpadMediaManager.Core.Models;
using PinpadMediaManager.Core.Protocol;
using PinpadMediaManager.Core.Transport;

namespace PinpadMediaManager.Tests;

public sealed class PinpadProtocolTests
{
    [Fact]
    public void LanDiscoveryResponseParsesAdvertisedEndpoint()
    {
        var payload = Encoding.UTF8.GetBytes(
            """
            {"protocol":"globalconnect-pinpad-discovery","version":1,"serialNumber":"CT20P100995","model":"CT20P","address":"192.168.10.55","tcpPort":9100}
            """);

        var result = PinpadLanDiscovery.ParseResponse(payload, IPAddress.Parse("192.168.10.55"));

        Assert.NotNull(result);
        Assert.Equal("CT20P100995", result.SerialNumber);
        Assert.Equal("CT20P", result.Model);
        Assert.Equal("192.168.10.55", result.Address);
        Assert.Equal(9100, result.TcpPort);
    }

    [Fact]
    public void LanDiscoveryIgnoresDifferentProtocols()
    {
        var payload = Encoding.UTF8.GetBytes(
            """{"protocol":"another-device","tcpPort":9100}""");

        Assert.Null(PinpadLanDiscovery.ParseResponse(payload, IPAddress.Loopback));
    }

    [Fact]
    public async Task TcpTransportRoundTripsProtocolBytes()
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        try
        {
            var port = ((IPEndPoint)listener.LocalEndpoint).Port;
            var accept = listener.AcceptTcpClientAsync();
            using var transport = new TcpPinpadTransport();
            transport.Open("127.0.0.1", port);
            using var server = await accept;
            await using var serverStream = server.GetStream();

            await serverStream.WriteAsync(new byte[] { 0x06 });
            Assert.Equal(0x06, transport.ReadByte(TimeSpan.FromSeconds(1), CancellationToken.None));

            transport.Write(new byte[] { 0x04 });
            var response = new byte[1];
            Assert.Equal(1, await serverStream.ReadAsync(response));
            Assert.Equal(0x04, response[0]);
        }
        finally
        {
            listener.Stop();
        }
    }

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
    public void ProtocolLogFormatterMakesFramingAndSeparatorsVisible()
    {
        var transaction = PinpadFrameCodec.Encode(PinpadFrame.Ascii(
            PinpadFrameType.Transaction,
            "J1",
            $"A{PinpadControl.Fs}B{PinpadControl.Gs}C"));
        var administration = PinpadFrameCodec.Encode(PinpadFrame.Ascii(
            PinpadFrameType.Administration,
            "19",
            ".A10.1"));

        var transactionText = PinpadProtocolText.FormatBytes(transaction);
        var administrationText = PinpadProtocolText.FormatBytes(administration);

        Assert.StartsWith("<STX>J1A<FS>B<GS>C<ETX><LRC:", transactionText);
        Assert.StartsWith("<SI>19.A10.1<SO><LRC:", administrationText);
        Assert.Equal(
            "<ACK><EOT><NACK>",
            PinpadProtocolText.FormatBytes([PinpadControl.Ack, PinpadControl.Eot, PinpadControl.Nak]));
    }

    [Theory]
    [InlineData("PH1")]
    [InlineData("PH2")]
    [InlineData("QR1")]
    [InlineData("QR2")]
    [InlineData("QR3")]
    [InlineData("QR4")]
    public void ThreeCharacterCameraAndQrCommandsRoundTrip(string command)
    {
        var encoded = PinpadFrameCodec.Encode(
            PinpadFrame.Ascii(PinpadFrameType.Transaction, command, "payload"));

        var decoded = PinpadFrameCodec.Decode(encoded);

        Assert.Equal(command, decoded.CommandId);
        Assert.Equal("payload", decoded.PayloadAscii);
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
    public async Task A10DeviceProfileUsesLegacyVersionAndCapabilityCommands()
    {
        using var transport = new ScriptedTransport(command => command switch
        {
            "06" => PinpadFrame.Ascii(PinpadFrameType.Administration, "06", "A10-123456"),
            "19" => PinpadFrame.Ascii(PinpadFrameType.Administration, "19", ".A10-DEMO-1.0"),
            "1C" => PinpadFrame.Ascii(
                PinpadFrameType.Administration,
                "1C",
                $"MSR{PinpadControl.Fs}ICC{PinpadControl.Fs}PCD"),
            _ => throw new InvalidOperationException(command),
        });
        transport.Open("TEST", 9_600);
        using var client = new PinpadClient(transport);
        var trace = new List<string>();
        client.Trace += trace.Add;

        var profile = await client.GetA10DeviceProfileAsync();

        Assert.Equal("A10-123456", profile.SerialNumber);
        Assert.Equal("A10-DEMO-1.0", profile.SystemCoreVersion);
        Assert.Equal(["MSR", "ICC", "PCD"], profile.HardwareCapabilities);
        Assert.Equal(["06", "19", "19", "19", "19", "1C"],
            transport.Writes
                .Where(write => write.Length > 1)
                .Select(write => PinpadFrameCodec.Decode(write).CommandId));
        Assert.DoesNotContain(trace, line => line.Contains("  > ", StringComparison.Ordinal));
        Assert.DoesNotContain(trace, line => line.Contains("  < ", StringComparison.Ordinal));
    }

    [Fact]
    public async Task A10ConfigurationUploadUsesT90Base64Bridge()
    {
        var path = Path.Combine(Path.GetTempPath(), $"pinpad-a10-{Guid.NewGuid():N}.txt");
        await File.WriteAllTextAsync(path, "9F1A=0840\n5F2A=0840");
        try
        {
            using var transport = new ScriptedTransport(command => command == "T90"
                ? PinpadFrame.Ascii(PinpadFrameType.Transaction, "T91", "0")
                : throw new InvalidOperationException(command));
            transport.Open("TEST", 9_600);
            using var client = new PinpadClient(transport);

            await client.ApplyA10EmvConfigurationFileAsync(A10EmvConfigurationType.Terminal, path);

            var request = PinpadFrameCodec.Decode(transport.Writes[0]);
            var fields = request.PayloadAscii.Split(PinpadControl.Fs);
            Assert.Equal("T90", request.CommandId);
            Assert.Equal("T", fields[0]);
            Assert.Equal(Path.GetFileName(path), Encoding.UTF8.GetString(Convert.FromBase64String(fields[1])));
            Assert.Equal("9F1A=0840\n5F2A=0840", Encoding.UTF8.GetString(Convert.FromBase64String(fields[2])));
        }
        finally
        {
            File.Delete(path);
        }
    }

    [Fact]
    public async Task A10ClearKeyInjectionUses02AndRedactsTrace()
    {
        const string clearKey = "0123456789ABCDEFFEDCBA9876543210";
        using var transport = new ScriptedTransport(command => command == "02"
            ? PinpadFrame.Ascii(PinpadFrameType.Administration, "02", $"0{clearKey}{PinpadControl.Fs}P0ET")
            : throw new InvalidOperationException(command));
        transport.Open("TEST", 9_600);
        using var client = new PinpadClient(transport);
        var trace = new List<string>();
        client.Trace += trace.Add;

        await client.LoadA10ClearMasterKeyAsync('0', clearKey, "P0", 'E', 'T');

        var request = PinpadFrameCodec.Decode(transport.Writes[0]);
        Assert.Equal("02", request.CommandId);
        Assert.Equal($"0{clearKey}{PinpadControl.Fs}P0ET", request.PayloadAscii);
        Assert.DoesNotContain(trace, line => line.Contains(clearKey, StringComparison.Ordinal));
        Assert.Contains(trace, line => line.Contains("<REDACTED:", StringComparison.Ordinal));
    }

    [Fact]
    public async Task A10MasterSessionPinEntryBuilds70AndParses71()
    {
        const string sessionKey = "0123456789ABCDEFFEDCBA9876543210";
        const string pinBlock = "1122334455667788";
        using var transport = new ScriptedTransport(command => command == "70"
            ? PinpadFrame.Ascii(PinpadFrameType.Transaction, "71", $".00400{pinBlock}")
            : throw new InvalidOperationException(command));
        transport.Open("TEST", 9_600);
        using var client = new PinpadClient(transport);
        var trace = new List<string>();
        client.Trace += trace.Add;

        var result = await client.StartA10PinEntryAsync(new A10PinEntryRequest(
            A10PinKeyScheme.MasterSession, A10PinPromptMode.Standard, "4111111111111111", sessionKey,
            60, 4, 12, false, "ENTER PIN", "PRESS ENTER", "PROCESSING"));

        var request = PinpadFrameCodec.Decode(transport.Writes[0]);
        Assert.Equal("70", request.CommandId);
        Assert.Equal($".4111111111111111{PinpadControl.Fs}{sessionKey}{PinpadControl.Fs}2", request.PayloadAscii);
        Assert.Equal("PIN captured", result.Status);
        Assert.Equal(pinBlock, result.EncryptedPinBlock);
        Assert.Equal(4, result.PinLength);
        Assert.Equal("00", result.KeyIdentifier);
        Assert.DoesNotContain(trace, line => line.Contains(sessionKey, StringComparison.Ordinal));
        Assert.DoesNotContain(trace, line => line.Contains(pinBlock, StringComparison.Ordinal));
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

    [Fact]
    public async Task SignatureCaptureReassemblesS2PacketsAndAcksEachPacket()
    {
        var image = Enumerable.Range(0, 2_100).Select(value => (byte)(value % 251)).ToArray();
        using var transport = new SignatureScriptedTransport(
            SignatureCaptureStatus.Captured,
            Convert.ToBase64String(image));
        transport.Open("TEST", 9_600);
        using var client = new PinpadClient(transport);

        var result = await client.CaptureSignatureAsync(
            60,
            SignatureOrientation.Horizontal,
            SignatureImageFormat.Png);

        Assert.Equal(SignatureCaptureStatus.Captured, result.Status);
        Assert.Equal(SignatureImageFormat.Png, result.ImageFormat);
        Assert.Equal(image, result.ImageBytes);
        var request = PinpadFrameCodec.Decode(transport.Writes[0]);
        Assert.Equal("S1", request.CommandId);
        Assert.Equal($"060{PinpadControl.Fs}H{PinpadControl.Fs}P", request.PayloadAscii);
        Assert.Equal(4, transport.Writes.Count);
        Assert.All(transport.Writes.Skip(1), write => Assert.Equal(PinpadControl.Ack, Assert.Single(write)));
    }

    [Fact]
    public async Task SignatureCaptureReturnsCancellationWithoutImage()
    {
        using var transport = new SignatureScriptedTransport(SignatureCaptureStatus.Cancelled, "");
        transport.Open("TEST", 9_600);
        using var client = new PinpadClient(transport);

        var result = await client.CaptureSignatureAsync(
            30,
            SignatureOrientation.Vertical,
            SignatureImageFormat.Jpeg);

        Assert.Equal(SignatureCaptureStatus.Cancelled, result.Status);
        Assert.Empty(result.ImageBytes);
        var request = PinpadFrameCodec.Decode(transport.Writes[0]);
        Assert.Equal($"030{PinpadControl.Fs}V{PinpadControl.Fs}J", request.PayloadAscii);
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

    private sealed class SignatureScriptedTransport : IPinpadTransport
    {
        private readonly Queue<byte> _incoming = new();
        private readonly Queue<byte[]> _responseFrames = new();
        public List<byte[]> Writes { get; } = [];

        public SignatureScriptedTransport(SignatureCaptureStatus status, string base64)
        {
            if (status == SignatureCaptureStatus.Captured)
            {
                var chunks = base64.Chunk(1_024)
                    .Select(chars => new string(chars))
                    .ToArray();
                for (var index = 0; index < chunks.Length; index++)
                {
                    _responseFrames.Enqueue(
                        PinpadFrameCodec.Encode(
                            PinpadFrame.Ascii(
                                PinpadFrameType.Transaction,
                                "S2",
                                $"1{index + 1:D4}{chunks.Length:D4}{chunks[index]}")));
                }
            }
            else
            {
                _responseFrames.Enqueue(
                    PinpadFrameCodec.Encode(
                        PinpadFrame.Ascii(
                            PinpadFrameType.Transaction,
                            "S2",
                            $"{(int)status}00000000")));
            }
        }

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
                if (_responseFrames.Count > 0)
                {
                    Enqueue(_responseFrames.Dequeue());
                }
                else
                {
                    _incoming.Enqueue(PinpadControl.Eot);
                }
                return;
            }

            var request = PinpadFrameCodec.Decode(copy);
            Assert.Equal("S1", request.CommandId);
            _incoming.Enqueue(PinpadControl.Ack);
            Enqueue(_responseFrames.Dequeue());
        }

        public int ReadByte(TimeSpan timeout, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return _incoming.Count > 0
                ? _incoming.Dequeue()
                : throw new TimeoutException();
        }

        public void DiscardInput() => _incoming.Clear();
        public void ChangeBaudRate(int baudRate) => BaudRate = baudRate;
        public void Dispose() => Close();

        private void Enqueue(IEnumerable<byte> bytes)
        {
            foreach (var value in bytes)
            {
                _incoming.Enqueue(value);
            }
        }
    }
}
