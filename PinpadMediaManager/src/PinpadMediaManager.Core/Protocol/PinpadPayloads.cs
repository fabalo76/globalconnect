using PinpadMediaManager.Core.Models;

namespace PinpadMediaManager.Core.Protocol;

public static class PinpadPayloads
{
    public const int TransferChunkCharacters = 8_192;
    private const int DownloadSizeFieldWidth = 4;

    public static IEnumerable<string> JpegDownloadPackets(string fileName, byte[] content, bool overwrite)
    {
        ValidateName(fileName, 15);
        return BuildDownloadPackets(fileName, content, overwrite, sequenceWidth: 3);
    }

    public static IEnumerable<string> MediaDownloadPackets(string fileName, byte[] content, bool overwrite)
    {
        ValidateName(fileName, 64);
        var extension = Path.GetExtension(fileName);
        if (!extension.Equals(".mp3", StringComparison.OrdinalIgnoreCase) &&
            !extension.Equals(".mp4", StringComparison.OrdinalIgnoreCase))
        {
            throw new ArgumentException("Media files must use the .mp3 or .mp4 extension.", nameof(fileName));
        }

        return BuildDownloadPackets(fileName, content, overwrite, sequenceWidth: 6);
    }

    public static (bool IsLast, int Sequence, string Data) ParseUploadPacket(string payload, int sequenceWidth)
    {
        var headerLength = 1 + sequenceWidth + 3;
        if (payload.Length < headerLength)
        {
            throw new PinpadProtocolException("The upload response is shorter than its packet header.");
        }

        var type = payload[0];
        if (type is not ('0' or '1'))
        {
            throw new PinpadProtocolException($"The terminal returned upload status '{type}'.");
        }

        if (!int.TryParse(payload.AsSpan(1, sequenceWidth), out var sequence) ||
            !int.TryParse(payload.AsSpan(1 + sequenceWidth, 3), out var size))
        {
            throw new PinpadProtocolException("The upload response contains an invalid sequence or size.");
        }

        var data = payload[headerLength..];
        if (data.Length != size)
        {
            throw new PinpadProtocolException($"The upload packet declares {size} characters but contains {data.Length}.");
        }

        return (type == '1', sequence, data);
    }

    public static IReadOnlyList<JpegEntry> ParseJpegTable(string payload)
    {
        if (payload.Length == 0 || payload[0] is not ('0' or '1'))
        {
            throw new PinpadProtocolException("The terminal returned an invalid JPEG table.");
        }

        if (payload.Length == 1)
        {
            return [];
        }

        return payload[1..]
            .TrimStart(PinpadControl.Fs)
            .Split(PinpadControl.Fs, StringSplitOptions.RemoveEmptyEntries)
            .Where(value => value.Length > 1)
            .Select(value => new JpegEntry(value[1..], value[0] == '1'))
            .ToArray();
    }

    public static IReadOnlyList<MediaEntry> ParseMediaTable(string payload)
    {
        if (payload.Length == 0 || payload[0] != '0')
        {
            throw new PinpadProtocolException("The terminal returned an invalid media table.");
        }

        if (payload.Length == 1)
        {
            return [];
        }

        var entries = new List<MediaEntry>();
        foreach (var value in payload[1..].TrimStart(PinpadControl.Fs)
                     .Split(PinpadControl.Fs, StringSplitOptions.RemoveEmptyEntries))
        {
            if (value.Length < 12 || !long.TryParse(value.AsSpan(1, 10), out var size))
            {
                throw new PinpadProtocolException("The terminal returned a malformed media table entry.");
            }

            var type = value[0] switch
            {
                '3' => MediaType.Mp3,
                '4' => MediaType.Mp4,
                _ => throw new PinpadProtocolException($"Unknown media type '{value[0]}'."),
            };
            entries.Add(new MediaEntry(value[11..], type, size));
        }

        return entries;
    }

    private static IEnumerable<string> BuildDownloadPackets(
        string fileName,
        byte[] content,
        bool overwrite,
        int sequenceWidth)
    {
        ArgumentNullException.ThrowIfNull(content);
        if (content.Length == 0)
        {
            throw new ArgumentException("The selected file is empty.", nameof(content));
        }

        var encoded = Convert.ToBase64String(content);
        var sequence = 0;
        for (var offset = 0; offset < encoded.Length; offset += TransferChunkCharacters, sequence++)
        {
            var count = Math.Min(TransferChunkCharacters, encoded.Length - offset);
            var isLast = offset + count >= encoded.Length;
            var name = sequence == 0 ? fileName : "";
            yield return string.Concat(
                isLast ? "1" : "0",
                sequence.ToString($"D{sequenceWidth}"),
                overwrite ? "1" : "0",
                PinpadControl.Fs,
                name,
                PinpadControl.Fs,
                count.ToString($"D{DownloadSizeFieldWidth}"),
                encoded.Substring(offset, count));
        }
    }

    private static void ValidateName(string fileName, int maximumLength)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(fileName);
        if (fileName.Length > maximumLength || fileName.IndexOfAny(['/', '\\']) >= 0 ||
            fileName.Any(value => char.IsControl(value)))
        {
            throw new ArgumentException($"The terminal file name must be 1–{maximumLength} safe characters.", nameof(fileName));
        }
    }
}
