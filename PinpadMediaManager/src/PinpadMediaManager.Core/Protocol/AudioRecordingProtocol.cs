using System.Globalization;
using System.Text.RegularExpressions;

namespace PinpadMediaManager.Core.Protocol;

public sealed record AudioRecordingEntry(string Name, string State, long SizeBytes, long DurationMs)
{
    public string Duration => TimeSpan.FromMilliseconds(DurationMs).ToString(@"hh\:mm\:ss");
}

public static class AudioRecordingProtocol
{
    public const long MaximumFileBytes = 30L * 60 * 16_000 * 2 + 44;
    public static bool ValidName(string name) => Regex.IsMatch(name,
        @"\Aaudio-[0-9]{13}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(?:wav|aac)\z");

    public static bool ValidHeader(string name, ReadOnlySpan<byte> data) => name.EndsWith(".wav", StringComparison.Ordinal)
        ? data.Length >= 44 && data[..4].SequenceEqual("RIFF"u8) && data.Slice(8, 4).SequenceEqual("WAVE"u8)
        : name.EndsWith(".aac", StringComparison.Ordinal) && data.Length >= 7 &&
          data[0] == 0xff && (data[1] & 0xf6) == 0xf0 && (data[2] >> 6) == 1 &&
          ((data[2] >> 2) & 15) == 8 && (((data[2] & 1) << 2) | (data[3] >> 6)) == 1;

    public static string[] Success(string payload)
    {
        var fields = payload.Split(PinpadControl.Fs);
        if (fields[0] == "0") return fields;
        var description = fields[0] switch
        {
            "U" => "Unsupported: audio recording requires N6 Pro / N6ProLite firmware." +
                (fields.Length > 1 ? " Reported model: " + fields[1] : ""),
            "1" => "Invalid recording command, filename, or offset.",
            "2" => "Microphone permission required. Open Pinpad App on the terminal and allow microphone access.",
            "3" => "Recording is busy. Stop capture before downloading or deleting the active file.",
            "4" => "Audio recording not found. Refresh the list; older files may have been rotated out.",
            "5" => "No active recording.",
            "6" => "Audio capture or storage failed. Check free storage, microphone availability, and that Pinpad App is visible when starting capture.",
            _ => "Unknown audio recording status: " + fields[0],
        };
        throw new PinpadProtocolException(description);
    }

    public static string StartedFile(string payload)
    {
        var fields = Success(payload);
        if (fields.Length != 2 || !ValidName(fields[1])) throw new PinpadProtocolException("Invalid recording filename response.");
        return fields[1];
    }

    public static IReadOnlyList<AudioRecordingEntry> ParseList(string payload)
    {
        var fields = Success(payload);
        if (fields.Length > 11) throw new PinpadProtocolException("Recording list exceeds ten files.");
        var entries = new List<AudioRecordingEntry>();
        foreach (var value in fields.Skip(1))
        {
            var row = value.Split('|');
            if (row.Length != 4 || row[0] is not ("R" or "S" or "F") || !ValidName(row[3]) ||
                !long.TryParse(row[1], NumberStyles.None, CultureInfo.InvariantCulture, out var size) || size < 0 || size > MaximumFileBytes ||
                !long.TryParse(row[2], NumberStyles.None, CultureInfo.InvariantCulture, out var duration) || duration > 1_800_000 ||
                entries.Any(entry => entry.Name == row[3])) throw new PinpadProtocolException("Invalid audio recording list.");
            entries.Add(new(row[3], row[0] switch { "R" => "Recording", "F" => "Failed", _ => "Stopped" }, size, duration));
        }
        return entries;
    }

    public static (byte[] Data, long Total) ParsePacket(string payload, long expectedOffset, long? expectedTotal)
    {
        var fields = Success(payload);
        if (fields.Length != 4 ||
            !long.TryParse(fields[1], NumberStyles.None, CultureInfo.InvariantCulture, out var offset) || offset != expectedOffset ||
            !long.TryParse(fields[2], NumberStyles.None, CultureInfo.InvariantCulture, out var total) || total < 7 || total > MaximumFileBytes ||
            (expectedTotal.HasValue && total != expectedTotal) || fields[3].Length > 2732)
            throw new PinpadProtocolException("Invalid audio packet offset or file size.");
        byte[] bytes;
        try { bytes = Convert.FromBase64String(fields[3]); }
        catch (FormatException error) { throw new PinpadProtocolException("Invalid audio packet Base64.", error); }
        if (bytes.Length == 0 || bytes.Length > 2048 || offset + bytes.Length > total)
            throw new PinpadProtocolException("Invalid audio packet length.");
        return (bytes, total);
    }
}
