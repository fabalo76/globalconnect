using PinpadMediaManager.Core.Protocol;

namespace PinpadMediaManager.Core.Models;

public sealed record A10CardDetectionResult(string Payload, string Description)
{
    public static A10CardDetectionResult Parse(string payload) => new(payload, payload switch
    {
        "1" => "Magnetic stripe card detected (MSR).",
        "2" => "Chip card detected (ICC).",
        "3" => "Contactless card detected.",
        "00" => "The terminal could not enable card detection.",
        "03" => "Card detection canceled or timed out.",
        _ => throw new PinpadProtocolException("Unexpected QK card detection response."),
    });
}
