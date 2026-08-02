namespace PinpadMediaManager.Core.Models;

public sealed record JpegEntry(string Name, bool Selected);

public enum MediaType
{
    Mp3,
    Mp4,
}

public sealed record MediaEntry(string Name, MediaType Type, long SizeBytes);

public enum SignatureOrientation
{
    Horizontal,
    Vertical,
}

public enum SignatureImageFormat
{
    Png,
    Jpeg,
}

public enum SignatureCaptureStatus
{
    Captured = 1,
    Cancelled = 2,
    Timeout = 3,
    Error = 4,
}

public sealed record SignatureCaptureResult(
    SignatureCaptureStatus Status,
    SignatureImageFormat ImageFormat,
    byte[] ImageBytes);

public enum CameraFacing
{
    Front,
    Back,
}

public enum PhotoCaptureStatus
{
    Captured = 1,
    Cancelled = 2,
    Timeout = 3,
    Error = 4,
}

public sealed record PhotoCaptureResult(
    PhotoCaptureStatus Status,
    byte[] JpegBytes);

public enum QrOperationStatus
{
    Completed = 1,
    Cancelled = 2,
    Timeout = 3,
    Error = 4,
}

public sealed record QrScanResult(
    QrOperationStatus Status,
    string? Value);

public sealed record TransferProgress(string Operation, long Completed, long Total)
{
    public int Percentage => Total <= 0 ? 0 : (int)Math.Clamp(Completed * 100 / Total, 0, 100);
}
