using System.Text;
using PinpadMediaManager.Core.Models;
using PinpadMediaManager.Core.Protocol;
using PinpadMediaManager.Core.Transport;

namespace PinpadMediaManager.Core;

public sealed class PinpadClient(IPinpadTransport transport) : IDisposable
{
    private readonly SemaphoreSlim _operationGate = new(1, 1);

    public event Action<string>? Trace;

    public bool IsConnected => transport.IsOpen;
    public string PortName => transport.PortName;
    public int BaudRate => transport.BaudRate;

    public void Connect(string portName, int baudRate)
    {
        transport.Open(portName, baudRate);
        Log($"Connected to {portName} at {baudRate:N0} bps.");
    }

    public void Disconnect()
    {
        transport.Close();
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
        transport.ChangeBaudRate(baudRate);
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

    public void Dispose()
    {
        transport.Dispose();
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
        if (!transport.IsOpen)
        {
            throw new InvalidOperationException("Connect to a pinpad first.");
        }

        transport.DiscardInput();
        var request = PinpadFrame.Ascii(type, command, payload);
        var encoded = PinpadFrameCodec.Encode(request);
        Log($"> {command} {DescribePayload(payload)}");

        for (var attempt = 1; attempt <= 3; attempt++)
        {
            transport.Write(encoded);
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
            Log($"< {command} ACK");
            return PinpadFrame.Ascii(type, command);
        }

        var response = ReadFrame(TimeSpan.FromSeconds(15), cancellationToken);
        if (!string.Equals(response.CommandId, command, StringComparison.Ordinal))
        {
            throw new PinpadProtocolException(
                $"Expected response {command}, received {response.CommandId}.");
        }

        transport.Write([PinpadControl.Ack]);
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

        Log($"< {response.CommandId} {DescribePayload(response.PayloadAscii)}");
        return response;
    }

    private TimeSpan AcknowledgementTimeout(int frameBytes)
    {
        var baudRate = Math.Max(transport.BaudRate, 1);
        var transmissionSeconds = frameBytes * 10d / baudRate;
        return TimeSpan.FromSeconds(Math.Max(4d, transmissionSeconds + 2d));
    }

    private byte ReadControl(TimeSpan timeout, CancellationToken cancellationToken)
    {
        var value = transport.ReadByte(timeout, cancellationToken);
        return checked((byte)value);
    }

    private PinpadFrame ReadFrame(TimeSpan timeout, CancellationToken cancellationToken)
    {
        var start = transport.ReadByte(timeout, cancellationToken);
        while (start is not (PinpadControl.Stx or PinpadControl.Si))
        {
            if (start is PinpadControl.Nak or PinpadControl.Eot)
            {
                throw new PinpadProtocolException($"The terminal ended the response with 0x{start:X2}.");
            }

            start = transport.ReadByte(timeout, cancellationToken);
        }

        var end = start == PinpadControl.Stx ? PinpadControl.Etx : PinpadControl.So;
        using var frame = new MemoryStream();
        frame.WriteByte((byte)start);
        while (true)
        {
            var value = transport.ReadByte(timeout, cancellationToken);
            frame.WriteByte((byte)value);
            if (value == end)
            {
                frame.WriteByte((byte)transport.ReadByte(timeout, cancellationToken));
                break;
            }

            if (frame.Length > 4 * 1024)
            {
                throw new PinpadProtocolException("The terminal response exceeded the 4 KiB frame limit.");
            }
        }

        return PinpadFrameCodec.Decode(frame.ToArray());
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

    private void Log(string message) => Trace?.Invoke($"{DateTime.Now:HH:mm:ss.fff}  {message}");
}
