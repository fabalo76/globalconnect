using System.Text;
using PinpadMediaManager.Core.Models;
using PinpadMediaManager.Core.Protocol;
using PinpadMediaManager.Core.Transport;

namespace PinpadMediaManager.Core;

public sealed partial class PinpadClient(IPinpadTransport transport) : IDisposable
{
    private readonly SemaphoreSlim _operationGate = new(1, 1);
    private IPinpadTransport _transport = transport;

    public event Action<string>? Trace;

    public bool IsConnected => _transport.IsOpen;
    public bool IsTcpConnection => _transport is TcpPinpadTransport;
    public string PortName => _transport.PortName;
    public int BaudRate => _transport.BaudRate;

    public void Connect(string portName, int baudRate)
    {
        if (_transport is TcpPinpadTransport)
        {
            ReplaceTransport(new SerialPinpadTransport());
        }
        _transport.Open(portName, baudRate);
        Log($"Connected to {portName} at {baudRate:N0} bps.");
    }

    public void ConnectTcp(string host, int port)
    {
        ReplaceTransport(new TcpPinpadTransport());
        _transport.Open(host, port);
        Log($"Connected to TCP pinpad {host}:{port}.");
    }

    public void Disconnect()
    {
        _transport.Close();
        Log("Disconnected.");
    }

    public async Task ChangeBaudRateAsync(int baudRate, CancellationToken cancellationToken = default)
    {
        var code = baudRate switch
        {
            1_200 => '1',
            2_400 => '2',
            4_800 => '3',
            9_600 => '4',
            19_200 => '5',
            38_400 => '6',
            57_600 => '7',
            115_200 => '8',
            _ => throw new ArgumentOutOfRangeException(nameof(baudRate), "The terminal supports 1200–115200 bps."),
        };

        await ExecuteAsync(PinpadFrameType.Administration, "13", $"{code}1", cancellationToken);
        _transport.ChangeBaudRate(baudRate);
        Log($"Local and terminal baud changed to {baudRate:N0} bps.");
    }

    public async Task InitializeJpegTableAsync(CancellationToken cancellationToken = default) =>
        EnsureStatus(await ExecuteAsync(PinpadFrameType.Transaction, "J0", "", cancellationToken), "J0", "0");

    public async Task<IReadOnlyList<JpegEntry>> GetJpegTableAsync(CancellationToken cancellationToken = default)
    {
        var response = await ExecuteAsync(PinpadFrameType.Transaction, "J1", "", cancellationToken);
        return PinpadPayloads.ParseJpegTable(response.PayloadAscii);
    }

    public async Task SelectJpegsAsync(IEnumerable<string> names, bool selected, CancellationToken cancellationToken = default)
    {
        var values = names.ToArray();
        if (values.Length == 0)
        {
            throw new ArgumentException("Select at least one JPEG.", nameof(names));
        }

        var payload = (selected ? "1" : "0") + string.Join(PinpadControl.Fs, values);
        var response = await ExecuteAsync(PinpadFrameType.Transaction, "J2", payload, cancellationToken);
        EnsureAllStatuses(response.PayloadAscii, "J2");
    }

    public async Task DeleteJpegsAsync(IEnumerable<string> names, CancellationToken cancellationToken = default)
    {
        var values = names.ToArray();
        if (values.Length == 0)
        {
            throw new ArgumentException("Select at least one JPEG.", nameof(names));
        }

        var response = await ExecuteAsync(
            PinpadFrameType.Transaction,
            "J3",
            string.Join(PinpadControl.Fs, values),
            cancellationToken);
        EnsureAllStatuses(response.PayloadAscii, "J3");
    }

    public async Task UploadJpegAsync(
        string localPath,
        string terminalName,
        bool overwrite,
        IProgress<TransferProgress>? progress = null,
        CancellationToken cancellationToken = default)
    {
        var content = await File.ReadAllBytesAsync(localPath, cancellationToken);
        var packets = PinpadPayloads.JpegDownloadPackets(terminalName, content, overwrite).ToArray();
        await SendDownloadPacketsAsync("J4", packets, content.LongLength, "JPEG upload", progress, cancellationToken);
    }

    public Task<byte[]> DownloadJpegAsync(
        string terminalName,
        IProgress<TransferProgress>? progress = null,
        CancellationToken cancellationToken = default) =>
        ReceiveUploadPacketsAsync("J5", terminalName, sequenceWidth: 3, "JPEG download", progress, cancellationToken);

    public async Task ShowJpegAsync(string terminalName, CancellationToken cancellationToken = default) =>
        EnsureStatus(await ExecuteAsync(PinpadFrameType.Transaction, "J9", terminalName, cancellationToken), "J9", "0");

    public async Task PlaySelectedJpegsAsync(CancellationToken cancellationToken = default) =>
        await ExecuteOneWayAsync(PinpadFrameType.Transaction, "J6", "", cancellationToken);

    public async Task InitializeMediaTableAsync(CancellationToken cancellationToken = default) =>
        EnsureStatus(await ExecuteAsync(PinpadFrameType.Transaction, "M10", "", cancellationToken), "M10", "0");

    public async Task<IReadOnlyList<MediaEntry>> GetMediaTableAsync(CancellationToken cancellationToken = default)
    {
        var response = await ExecuteAsync(PinpadFrameType.Transaction, "M11", "", cancellationToken);
        return PinpadPayloads.ParseMediaTable(response.PayloadAscii);
    }

    public async Task UploadMediaAsync(
        string localPath,
        string terminalName,
        bool overwrite,
        IProgress<TransferProgress>? progress = null,
        CancellationToken cancellationToken = default)
    {
        var content = await File.ReadAllBytesAsync(localPath, cancellationToken);
        ValidateMediaFile(terminalName, content);
        var packets = PinpadPayloads.MediaDownloadPackets(terminalName, content, overwrite).ToArray();
        await SendDownloadPacketsAsync("M12", packets, content.LongLength, "Media upload", progress, cancellationToken);
    }

    public Task<byte[]> DownloadMediaAsync(
        string terminalName,
        IProgress<TransferProgress>? progress = null,
        CancellationToken cancellationToken = default) =>
        ReceiveUploadPacketsAsync("M13", terminalName, sequenceWidth: 6, "Media download", progress, cancellationToken);

    public async Task PlayMediaAsync(string terminalName, CancellationToken cancellationToken = default) =>
        EnsureStatus(await ExecuteAsync(PinpadFrameType.Transaction, "M14", terminalName, cancellationToken), "M14", "0");

    public async Task DeleteMediaAsync(IEnumerable<string> names, CancellationToken cancellationToken = default)
    {
        var values = names.ToArray();
        if (values.Length == 0)
        {
            throw new ArgumentException("Select at least one media file.", nameof(names));
        }

        var response = await ExecuteAsync(
            PinpadFrameType.Transaction,
            "M16",
            string.Join(PinpadControl.Fs, values),
            cancellationToken);
        EnsureAllStatuses(response.PayloadAscii, "M16");
    }

    public async Task SpeakTextAsync(
        string language,
        string text,
        CancellationToken cancellationToken = default)
    {
        var normalizedLanguage = language.Trim().ToLowerInvariant();
        if (normalizedLanguage is not ("es" or "en"))
        {
            throw new ArgumentException("Language must be either 'es' or 'en'.", nameof(language));
        }

        if (string.IsNullOrWhiteSpace(text) || text.Length > 1_000)
        {
            throw new ArgumentException("Speech text must contain 1–1,000 characters.", nameof(text));
        }

        var encodedText = Convert.ToBase64String(Encoding.UTF8.GetBytes(text));
        EnsureStatus(
            await ExecuteAsync(
                PinpadFrameType.Transaction,
                "M17",
                $"{normalizedLanguage}{PinpadControl.Fs}{encodedText}",
                cancellationToken),
            "M17",
            "0");
    }

    public async Task SetMediaVolumeAsync(int volume, CancellationToken cancellationToken = default)
    {
        if (volume is < 0 or > 99)
        {
            throw new ArgumentOutOfRangeException(nameof(volume), "Media volume must be between 00 and 99.");
        }

        EnsureStatus(
            await ExecuteAsync(
                PinpadFrameType.Transaction,
                "M15",
                volume.ToString("D2"),
                cancellationToken),
            "M15",
            "0");
    }

    public async Task<SignatureCaptureResult> CaptureSignatureAsync(
        int timeoutSeconds,
        SignatureOrientation orientation,
        SignatureImageFormat imageFormat,
        IProgress<TransferProgress>? progress = null,
        CancellationToken cancellationToken = default)
    {
        if (timeoutSeconds is < 5 or > 300)
        {
            throw new ArgumentOutOfRangeException(
                nameof(timeoutSeconds),
                "Signature timeout must be between 5 and 300 seconds.");
        }

        await _operationGate.WaitAsync(cancellationToken);
        try
        {
            return await Task.Run(
                () => CaptureSignature(timeoutSeconds, orientation, imageFormat, progress, cancellationToken),
                cancellationToken);
        }
        finally
        {
            _operationGate.Release();
        }
    }

    public async Task<PhotoCaptureResult> CapturePhotoAsync(
        int timeoutSeconds,
        CameraFacing facing,
        int jpegQuality,
        IProgress<TransferProgress>? progress = null,
        CancellationToken cancellationToken = default)
    {
        ValidateVisualTimeout(timeoutSeconds);
        if (jpegQuality is < 10 or > 100)
        {
            throw new ArgumentOutOfRangeException(nameof(jpegQuality), "JPEG quality must be between 10 and 100.");
        }

        await _operationGate.WaitAsync(cancellationToken);
        try
        {
            return await Task.Run(
                () => CapturePhoto(timeoutSeconds, facing, jpegQuality, progress, cancellationToken),
                cancellationToken);
        }
        finally
        {
            _operationGate.Release();
        }
    }

    public async Task<QrOperationStatus> ShowQrCodeAsync(
        string value,
        int timeoutSeconds,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(value);
        ValidateVisualTimeout(timeoutSeconds);
        var valueBytes = Encoding.UTF8.GetBytes(value);
        if (valueBytes.Length > 2_048)
        {
            throw new ArgumentOutOfRangeException(nameof(value), "QR content cannot exceed 2,048 UTF-8 bytes.");
        }

        await _operationGate.WaitAsync(cancellationToken);
        try
        {
            return await Task.Run(
                () =>
                {
                    var payload =
                        $"{timeoutSeconds:D3}{PinpadControl.Fs}{Convert.ToBase64String(valueBytes)}";
                    var response = ExecuteInteractiveRequest(
                        "QR1",
                        "QR2",
                        payload,
                        timeoutSeconds,
                        cancellationToken);
                    return ParseQrStatus(response.PayloadAscii);
                },
                cancellationToken);
        }
        finally
        {
            _operationGate.Release();
        }
    }

    public async Task<QrScanResult> ScanQrCodeAsync(
        int timeoutSeconds,
        CameraFacing facing,
        CancellationToken cancellationToken = default)
    {
        ValidateVisualTimeout(timeoutSeconds);
        await _operationGate.WaitAsync(cancellationToken);
        try
        {
            return await Task.Run(
                () =>
                {
                    var facingCode = facing == CameraFacing.Front ? 'F' : 'B';
                    var response = ExecuteInteractiveRequest(
                        "QR3",
                        "QR4",
                        $"{timeoutSeconds:D3}{PinpadControl.Fs}{facingCode}",
                        timeoutSeconds,
                        cancellationToken);
                    return ParseQrScanResult(response.PayloadAscii);
                },
                cancellationToken);
        }
        finally
        {
            _operationGate.Release();
        }
    }

    public void Dispose()
    {
        _transport.Dispose();
        _operationGate.Dispose();
    }

    private async Task SendDownloadPacketsAsync(
        string command,
        IReadOnlyList<string> packets,
        long sourceBytes,
        string operation,
        IProgress<TransferProgress>? progress,
        CancellationToken cancellationToken)
    {
        for (var index = 0; index < packets.Count; index++)
        {
            var response = await ExecuteAsync(PinpadFrameType.Transaction, command, packets[index], cancellationToken);
            var expected = index == packets.Count - 1 ? "F" : "0";
            EnsureStatus(response, command, expected);
            progress?.Report(new TransferProgress(operation, index + 1, packets.Count));
        }

        Log($"{operation} completed ({sourceBytes:N0} bytes, {packets.Count:N0} packets).");
    }

    private SignatureCaptureResult CaptureSignature(
        int timeoutSeconds,
        SignatureOrientation orientation,
        SignatureImageFormat imageFormat,
        IProgress<TransferProgress>? progress,
        CancellationToken cancellationToken)
    {
        if (!_transport.IsOpen)
        {
            throw new InvalidOperationException("Connect to a pinpad first.");
        }

        var orientationCode = orientation == SignatureOrientation.Horizontal ? 'H' : 'V';
        var formatCode = imageFormat == SignatureImageFormat.Png ? 'P' : 'J';
        var payload = $"{timeoutSeconds:D3}{PinpadControl.Fs}{orientationCode}{PinpadControl.Fs}{formatCode}";
        var request = PinpadFrame.Ascii(PinpadFrameType.Transaction, "S1", payload);
        var encoded = PinpadFrameCodec.Encode(request);
        _transport.DiscardInput();

        try
        {
            for (var attempt = 1; attempt <= 3; attempt++)
            {
                WriteWithTrace(encoded);
                var control = ReadControl(AcknowledgementTimeout(encoded.Length), cancellationToken);
                if (control == PinpadControl.Ack)
                {
                    break;
                }

                if (control != PinpadControl.Nak || attempt == 3)
                {
                    throw new PinpadProtocolException(
                        $"The terminal did not acknowledge S1; received 0x{control:X2}.");
                }
            }

            using var base64 = new MemoryStream();
            var expectedPacket = 1;
            var expectedTotal = 0;
            var responseTimeout = TimeSpan.FromSeconds(timeoutSeconds + 15);
            while (true)
            {
                var response = ReadFrame(responseTimeout, cancellationToken);
                responseTimeout = TimeSpan.FromSeconds(15);
                if (!string.Equals(response.CommandId, "S2", StringComparison.Ordinal))
                {
                    throw new PinpadProtocolException(
                        $"Expected signature response S2, received {response.CommandId}.");
                }

                var packet = ParseSignaturePacket(response.PayloadAscii);
                if (packet.Status != SignatureCaptureStatus.Captured)
                {
                    WriteWithTrace([PinpadControl.Ack]);
                    ReadExpectedEot("S2", cancellationToken);
                    return new SignatureCaptureResult(packet.Status, imageFormat, []);
                }

                if (packet.Packet != expectedPacket)
                {
                    throw new PinpadProtocolException(
                        $"The terminal returned signature packet {packet.Packet}, expected {expectedPacket}.");
                }

                if (expectedTotal == 0)
                {
                    expectedTotal = packet.TotalPackets;
                }
                else if (packet.TotalPackets != expectedTotal)
                {
                    throw new PinpadProtocolException(
                        $"Signature packet total changed from {expectedTotal} to {packet.TotalPackets}.");
                }

                var data = Encoding.ASCII.GetBytes(packet.Base64Data);
                base64.Write(data);
                progress?.Report(new TransferProgress("Signature capture", packet.Packet, packet.TotalPackets));
                WriteWithTrace([PinpadControl.Ack]);
                if (packet.Packet >= packet.TotalPackets)
                {
                    ReadExpectedEot("S2", cancellationToken);
                    break;
                }

                expectedPacket++;
            }

            try
            {
                var imageBytes = Convert.FromBase64String(Encoding.ASCII.GetString(base64.ToArray()));
                Log($"Signature capture completed ({imageBytes.LongLength:N0} bytes, {expectedTotal:N0} packets).");
                return new SignatureCaptureResult(SignatureCaptureStatus.Captured, imageFormat, imageBytes);
            }
            catch (FormatException error)
            {
                throw new PinpadProtocolException($"The terminal returned invalid signature Base64 data: {error.Message}");
            }
        }
        catch
        {
            TryEndSignatureSession();
            throw;
        }
    }

    private PhotoCaptureResult CapturePhoto(
        int timeoutSeconds,
        CameraFacing facing,
        int jpegQuality,
        IProgress<TransferProgress>? progress,
        CancellationToken cancellationToken)
    {
        if (!_transport.IsOpen)
        {
            throw new InvalidOperationException("Connect to a pinpad first.");
        }

        var facingCode = facing == CameraFacing.Front ? 'F' : 'B';
        var payload =
            $"{timeoutSeconds:D3}{PinpadControl.Fs}{facingCode}{PinpadControl.Fs}{jpegQuality:D2}";
        var request = PinpadFrame.Ascii(PinpadFrameType.Transaction, "PH1", payload);
        var encoded = PinpadFrameCodec.Encode(request);
        _transport.DiscardInput();

        try
        {
            WriteRequestAndReadAck(encoded, "PH1", cancellationToken);
            using var base64 = new MemoryStream();
            var expectedPacket = 1;
            var expectedTotal = 0;
            var responseTimeout = TimeSpan.FromSeconds(timeoutSeconds + 15);
            while (true)
            {
                var response = ReadFrame(responseTimeout, cancellationToken);
                responseTimeout = TimeSpan.FromSeconds(15);
                if (!string.Equals(response.CommandId, "PH2", StringComparison.Ordinal))
                {
                    throw new PinpadProtocolException(
                        $"Expected photo response PH2, received {response.CommandId}.");
                }

                var packet = ParsePhotoPacket(response.PayloadAscii);
                if (packet.Status != PhotoCaptureStatus.Captured)
                {
                    WriteWithTrace([PinpadControl.Ack]);
                    ReadExpectedEot("PH2", cancellationToken);
                    return new PhotoCaptureResult(packet.Status, []);
                }

                if (packet.Packet != expectedPacket)
                {
                    throw new PinpadProtocolException(
                        $"The terminal returned photo packet {packet.Packet}, expected {expectedPacket}.");
                }
                if (expectedTotal == 0)
                {
                    expectedTotal = packet.TotalPackets;
                }
                else if (packet.TotalPackets != expectedTotal)
                {
                    throw new PinpadProtocolException(
                        $"Photo packet total changed from {expectedTotal} to {packet.TotalPackets}.");
                }

                var data = Encoding.ASCII.GetBytes(packet.Base64Data);
                base64.Write(data);
                progress?.Report(new TransferProgress("Photo capture", packet.Packet, packet.TotalPackets));
                WriteWithTrace([PinpadControl.Ack]);
                if (packet.Packet >= packet.TotalPackets)
                {
                    ReadExpectedEot("PH2", cancellationToken);
                    break;
                }
                expectedPacket++;
            }

            try
            {
                var jpegBytes = Convert.FromBase64String(Encoding.ASCII.GetString(base64.ToArray()));
                Log($"Photo capture completed ({jpegBytes.LongLength:N0} bytes, {expectedTotal:N0} packets).");
                return new PhotoCaptureResult(PhotoCaptureStatus.Captured, jpegBytes);
            }
            catch (FormatException error)
            {
                throw new PinpadProtocolException($"The terminal returned invalid photo Base64 data: {error.Message}");
            }
        }
        catch
        {
            TryEndSignatureSession();
            throw;
        }
    }

    private PinpadFrame ExecuteInteractiveRequest(
        string requestCommand,
        string responseCommand,
        string payload,
        int timeoutSeconds,
        CancellationToken cancellationToken)
    {
        if (!_transport.IsOpen)
        {
            throw new InvalidOperationException("Connect to a pinpad first.");
        }
        var encoded = PinpadFrameCodec.Encode(
            PinpadFrame.Ascii(PinpadFrameType.Transaction, requestCommand, payload));
        _transport.DiscardInput();
        try
        {
            WriteRequestAndReadAck(encoded, requestCommand, cancellationToken);
            var response = ReadFrame(TimeSpan.FromSeconds(timeoutSeconds + 15), cancellationToken);
            if (!string.Equals(response.CommandId, responseCommand, StringComparison.Ordinal))
            {
                throw new PinpadProtocolException(
                    $"Expected response {responseCommand}, received {response.CommandId}.");
            }
            WriteWithTrace([PinpadControl.Ack]);
            ReadExpectedEot(responseCommand, cancellationToken);
            return response;
        }
        catch
        {
            TryEndSignatureSession();
            throw;
        }
    }

    private void WriteRequestAndReadAck(
        byte[] encoded,
        string command,
        CancellationToken cancellationToken)
    {
        for (var attempt = 1; attempt <= 3; attempt++)
        {
            WriteWithTrace(encoded);
            var control = ReadControl(AcknowledgementTimeout(encoded.Length), cancellationToken);
            if (control == PinpadControl.Ack)
            {
                return;
            }
            if (control != PinpadControl.Nak || attempt == 3)
            {
                throw new PinpadProtocolException(
                    $"The terminal did not acknowledge {command}; received 0x{control:X2}.");
            }
        }
    }

    private static PhotoPacket ParsePhotoPacket(string payload)
    {
        if (payload.Length < 9 || payload[0] is < '1' or > '4')
        {
            throw new PinpadProtocolException("The terminal returned an invalid PH2 photo header.");
        }
        if (!int.TryParse(payload.AsSpan(1, 4), out var packet) ||
            !int.TryParse(payload.AsSpan(5, 4), out var totalPackets))
        {
            throw new PinpadProtocolException("The terminal returned invalid PH2 packet numbers.");
        }

        var status = (PhotoCaptureStatus)(payload[0] - '0');
        if (status == PhotoCaptureStatus.Captured)
        {
            if (packet < 1 || totalPackets < 1 || packet > totalPackets || payload.Length == 9)
            {
                throw new PinpadProtocolException("The terminal returned invalid captured-photo packet metadata.");
            }
        }
        else if (packet != 0 || totalPackets != 0)
        {
            throw new PinpadProtocolException("A non-captured PH2 result must use zero packet numbers.");
        }
        return new PhotoPacket(status, packet, totalPackets, payload[9..]);
    }

    private static QrOperationStatus ParseQrStatus(string payload)
    {
        if (payload.Length != 1 || payload[0] is < '1' or > '4')
        {
            throw new PinpadProtocolException("The terminal returned an invalid QR operation status.");
        }
        return (QrOperationStatus)(payload[0] - '0');
    }

    private static QrScanResult ParseQrScanResult(string payload)
    {
        if (string.IsNullOrEmpty(payload))
        {
            throw new PinpadProtocolException("The terminal returned an empty QR4 response.");
        }
        var status = ParseQrStatus(payload[..1]);
        if (status != QrOperationStatus.Completed)
        {
            if (payload.Length != 1)
            {
                throw new PinpadProtocolException("A non-completed QR4 response cannot contain QR data.");
            }
            return new QrScanResult(status, null);
        }
        if (payload.Length < 3 || payload[1] != PinpadControl.Fs)
        {
            throw new PinpadProtocolException("The completed QR4 response has no QR data.");
        }
        try
        {
            var value = Encoding.UTF8.GetString(Convert.FromBase64String(payload[2..]));
            return new QrScanResult(status, value);
        }
        catch (FormatException error)
        {
            throw new PinpadProtocolException($"The terminal returned invalid QR Base64 data: {error.Message}");
        }
    }

    private static void ValidateVisualTimeout(int timeoutSeconds)
    {
        if (timeoutSeconds is < 5 or > 300)
        {
            throw new ArgumentOutOfRangeException(
                nameof(timeoutSeconds),
                "Visual operation timeout must be between 5 and 300 seconds.");
        }
    }

    private static SignaturePacket ParseSignaturePacket(string payload)
    {
        if (payload.Length < 9 || payload[0] is < '1' or > '4')
        {
            throw new PinpadProtocolException("The terminal returned an invalid S2 signature header.");
        }

        if (!int.TryParse(payload.AsSpan(1, 4), out var packet) ||
            !int.TryParse(payload.AsSpan(5, 4), out var totalPackets))
        {
            throw new PinpadProtocolException("The terminal returned invalid S2 packet numbers.");
        }

        var status = (SignatureCaptureStatus)(payload[0] - '0');
        if (status == SignatureCaptureStatus.Captured)
        {
            if (packet < 1 || totalPackets < 1 || packet > totalPackets || payload.Length == 9)
            {
                throw new PinpadProtocolException("The terminal returned invalid captured-signature packet metadata.");
            }
        }
        else if (packet != 0 || totalPackets != 0)
        {
            throw new PinpadProtocolException("A non-captured S2 result must use zero packet numbers.");
        }

        return new SignaturePacket(status, packet, totalPackets, payload[9..]);
    }

    private void ReadExpectedEot(string command, CancellationToken cancellationToken)
    {
        var eot = ReadControl(TimeSpan.FromSeconds(5), cancellationToken);
        if (eot != PinpadControl.Eot)
        {
            throw new PinpadProtocolException($"Expected EOT after {command}, received 0x{eot:X2}.");
        }
    }

    private void TryEndSignatureSession()
    {
        try
        {
            if (_transport.IsOpen)
            {
                WriteWithTrace([PinpadControl.Eot]);
            }
        }
        catch
        {
            // Preserve the original capture/transport failure.
        }
    }

    private async Task<byte[]> ReceiveUploadPacketsAsync(
        string command,
        string terminalName,
        int sequenceWidth,
        string operation,
        IProgress<TransferProgress>? progress,
        CancellationToken cancellationToken)
    {
        using var encoded = new MemoryStream();
        var expectedSequence = 0;
        var request = $"0{PinpadControl.Fs}{terminalName}";
        while (true)
        {
            var response = await ExecuteAsync(PinpadFrameType.Transaction, command, request, cancellationToken);
            var packet = PinpadPayloads.ParseUploadPacket(response.PayloadAscii, sequenceWidth);
            if (packet.Sequence != expectedSequence)
            {
                throw new PinpadProtocolException(
                    $"The terminal returned packet {packet.Sequence}, expected {expectedSequence}.");
            }

            var bytes = Encoding.ASCII.GetBytes(packet.Data);
            await encoded.WriteAsync(bytes, cancellationToken);
            expectedSequence++;
            progress?.Report(new TransferProgress(operation, expectedSequence, packet.IsLast ? expectedSequence : 0));
            if (packet.IsLast)
            {
                break;
            }

            request = "1";
        }

        try
        {
            var decoded = Convert.FromBase64String(Encoding.ASCII.GetString(encoded.ToArray()));
            Log($"{operation} completed ({decoded.LongLength:N0} bytes, {expectedSequence:N0} packets).");
            return decoded;
        }
        catch (FormatException error)
        {
            throw new PinpadProtocolException($"The terminal returned invalid Base64 data: {error.Message}");
        }
    }

    private async Task<PinpadFrame> ExecuteAsync(
        PinpadFrameType type,
        string command,
        string payload,
        CancellationToken cancellationToken)
    {
        await _operationGate.WaitAsync(cancellationToken);
        try
        {
            return await Task.Run(() => Execute(type, command, payload, expectResponse: true, cancellationToken), cancellationToken);
        }
        finally
        {
            _operationGate.Release();
        }
    }

    private async Task ExecuteOneWayAsync(
        PinpadFrameType type,
        string command,
        string payload,
        CancellationToken cancellationToken)
    {
        await _operationGate.WaitAsync(cancellationToken);
        try
        {
            await Task.Run(() => Execute(type, command, payload, expectResponse: false, cancellationToken), cancellationToken);
        }
        finally
        {
            _operationGate.Release();
        }
    }

    private PinpadFrame Execute(
        PinpadFrameType type,
        string command,
        string payload,
        bool expectResponse,
        CancellationToken cancellationToken)
    {
        if (!_transport.IsOpen)
        {
            throw new InvalidOperationException("Connect to a pinpad first.");
        }

        _transport.DiscardInput();
        var request = PinpadFrame.Ascii(type, command, payload);
        var encoded = PinpadFrameCodec.Encode(request);

        for (var attempt = 1; attempt <= 3; attempt++)
        {
            WriteWithTrace(encoded);
            var control = ReadControl(AcknowledgementTimeout(encoded.Length), cancellationToken);
            if (control == PinpadControl.Ack)
            {
                break;
            }

            if (control != PinpadControl.Nak || attempt == 3)
            {
                throw new PinpadProtocolException(
                    $"The terminal did not acknowledge {command}; received 0x{control:X2}.");
            }
        }

        if (!expectResponse)
        {
            return PinpadFrame.Ascii(type, command);
        }

        var response = ReadFrame(TimeSpan.FromSeconds(15), cancellationToken);
        if (!string.Equals(response.CommandId, command, StringComparison.Ordinal))
        {
            throw new PinpadProtocolException(
                $"Expected response {command}, received {response.CommandId}.");
        }

        WriteWithTrace([PinpadControl.Ack]);
        if (response.Type == PinpadFrameType.Administration)
        {
            byte eot;
            try
            {
                eot = ReadControl(TimeSpan.FromSeconds(5), cancellationToken);
            }
            catch (TimeoutException error)
            {
                throw new PinpadProtocolException(
                    $"The terminal did not send the final EOT after administration command {command}.",
                    error);
            }

            if (eot != PinpadControl.Eot)
            {
                throw new PinpadProtocolException($"Expected EOT after {command}, received 0x{eot:X2}.");
            }
        }

        return response;
    }

    private TimeSpan AcknowledgementTimeout(int frameBytes)
    {
        var baudRate = Math.Max(_transport.BaudRate, 1);
        var transmissionSeconds = frameBytes * 10d / baudRate;
        return TimeSpan.FromSeconds(Math.Max(4d, transmissionSeconds + 2d));
    }

    private byte ReadControl(TimeSpan timeout, CancellationToken cancellationToken)
    {
        var value = _transport.ReadByte(timeout, cancellationToken);
        var control = checked((byte)value);
        Log($"RX {PinpadProtocolText.FormatBytes([control])}");
        return control;
    }

    private PinpadFrame ReadFrame(TimeSpan timeout, CancellationToken cancellationToken)
    {
        var start = _transport.ReadByte(timeout, cancellationToken);
        while (start is not (PinpadControl.Stx or PinpadControl.Si))
        {
            if (start is PinpadControl.Nak or PinpadControl.Eot)
            {
                throw new PinpadProtocolException($"The terminal ended the response with 0x{start:X2}.");
            }

            start = _transport.ReadByte(timeout, cancellationToken);
        }

        var end = start == PinpadControl.Stx ? PinpadControl.Etx : PinpadControl.So;
        using var frame = new MemoryStream();
        frame.WriteByte((byte)start);
        while (true)
        {
            var value = _transport.ReadByte(timeout, cancellationToken);
            frame.WriteByte((byte)value);
            if (value == end)
            {
                frame.WriteByte((byte)_transport.ReadByte(timeout, cancellationToken));
                break;
            }

            if (frame.Length > 4 * 1024)
            {
                throw new PinpadProtocolException("The terminal response exceeded the 4 KiB frame limit.");
            }
        }

        var encoded = frame.ToArray();
        var decoded = PinpadFrameCodec.Decode(encoded);
        Log($"RX {FormatTraceFrame(encoded, decoded)}");
        return decoded;
    }

    private static void EnsureStatus(PinpadFrame response, string command, params string[] accepted)
    {
        var status = response.PayloadAscii;
        if (!accepted.Contains(status, StringComparer.Ordinal))
        {
            throw new PinpadProtocolException(
                $"{command} failed with terminal status '{status}'.");
        }
    }

    private sealed record SignaturePacket(
        SignatureCaptureStatus Status,
        int Packet,
        int TotalPackets,
        string Base64Data);

    private sealed record PhotoPacket(
        PhotoCaptureStatus Status,
        int Packet,
        int TotalPackets,
        string Base64Data);

    private static void EnsureAllStatuses(string payload, string command)
    {
        var statuses = payload.Split(PinpadControl.Fs, StringSplitOptions.RemoveEmptyEntries);
        if (statuses.Length == 0 || statuses.Any(value => value != "0"))
        {
            throw new PinpadProtocolException($"{command} failed with terminal status '{payload}'.");
        }
    }

    private static void ValidateMediaFile(string terminalName, byte[] content)
    {
        var extension = Path.GetExtension(terminalName);
        if (extension.Equals(".mp3", StringComparison.OrdinalIgnoreCase))
        {
            var valid = content.Length >= 3 &&
                        (content.AsSpan(0, 3).SequenceEqual("ID3"u8) ||
                         content.Length >= 2 && content[0] == 0xFF && (content[1] & 0xE0) == 0xE0);
            if (!valid)
            {
                throw new ArgumentException("The selected file does not contain a valid MP3 header.");
            }

            if (content.LongLength > 16L * 1024 * 1024)
            {
                throw new ArgumentException("MP3 files are limited to 16 MiB.");
            }
        }
        else if (extension.Equals(".mp4", StringComparison.OrdinalIgnoreCase))
        {
            if (content.Length < 8 || !content.AsSpan(4, 4).SequenceEqual("ftyp"u8))
            {
                throw new ArgumentException("The selected file does not contain a valid MP4 header.");
            }

            if (content.LongLength > 64L * 1024 * 1024)
            {
                throw new ArgumentException("MP4 files are limited to 64 MiB.");
            }
        }
    }

    private static string DescribePayload(string payload)
    {
        if (payload.Length == 0)
        {
            return "(empty)";
        }

        var visible = payload.Replace(PinpadControl.Fs.ToString(), "<FS>");
        return visible.Length <= 120 ? visible : $"{visible[..120]}… ({payload.Length:N0} chars)";
    }

    private void ReplaceTransport(IPinpadTransport replacement)
    {
        _transport.Close();
        _transport.Dispose();
        _transport = replacement;
    }

    private void WriteWithTrace(ReadOnlySpan<byte> data)
    {
        var formatted = PinpadProtocolText.FormatBytes(data);
        if (data.Length >= 5 && data[0] is PinpadControl.Stx or PinpadControl.Si)
        {
            try
            {
                var frame = PinpadFrameCodec.Decode(data);
                formatted = FormatTraceFrame(data, frame);
            }
            catch (PinpadProtocolException)
            {
                // Preserve normal trace output for partial or intentionally non-frame writes.
            }
        }
        Log($"TX {formatted}");
        _transport.Write(data);
    }

    private static string FormatTraceFrame(ReadOnlySpan<byte> encoded, PinpadFrame frame)
    {
        if (!SensitiveTraceCommands.Contains(frame.CommandId)) return PinpadProtocolText.FormatBytes(encoded);
        var start = frame.Type == PinpadFrameType.Transaction ? "<STX>" : "<SI>";
        var end = frame.Type == PinpadFrameType.Transaction ? "<ETX>" : "<SO>";
        return $"{start}{frame.CommandId}<REDACTED:{frame.Payload.Length} bytes>{end}<LRC:{encoded[^1]:X2}>";
    }

    private static readonly HashSet<string> SensitiveTraceCommands = new(StringComparer.Ordinal)
    {
        "02", "20", "21", "22", "23", "24", "70", "71", "78", "90", "94", "Z60", "Z62",
    };

    private void Log(string message) => Trace?.Invoke($"{DateTime.Now:HH:mm:ss.fff}  {message}");
}
