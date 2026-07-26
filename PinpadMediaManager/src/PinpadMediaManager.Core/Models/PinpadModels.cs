namespace PinpadMediaManager.Core.Models;

public sealed record JpegEntry(string Name, bool Selected);

public enum MediaType
{
    Mp3,
    Mp4,
}

public sealed record MediaEntry(string Name, MediaType Type, long SizeBytes);

public sealed record TransferProgress(string Operation, long Completed, long Total)
{
    public int Percentage => Total <= 0 ? 0 : (int)Math.Clamp(Completed * 100 / Total, 0, 100);
}
