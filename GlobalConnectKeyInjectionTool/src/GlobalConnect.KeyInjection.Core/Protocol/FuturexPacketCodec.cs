using System.Text;

namespace GlobalConnect.KeyInjection.Core.Protocol;

public static class FuturexPacketCodec
{
    public const byte Stx = 0x02;
    public const byte Etx = 0x03;
    public const byte Ack = 0x06;
    public const byte Nak = 0x15;

    public static byte[] Encode(string body)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(body);
        if (body.Any(character => character is < (char)0x20 or > (char)0x7e))
            throw new ArgumentException("Futurex message bodies must contain printable ASCII.", nameof(body));

        var bodyBytes = Encoding.ASCII.GetBytes(body);
        var frame = new byte[bodyBytes.Length + 3];
        frame[0] = Stx;
        bodyBytes.CopyTo(frame, 1);
        frame[^2] = Etx;
        frame[^1] = CalculateLrc(frame.AsSpan(1, frame.Length - 2));
        return frame;
    }

    public static string Decode(ReadOnlySpan<byte> frame)
    {
        if (frame.Length < 4 || frame[0] != Stx || frame[^2] != Etx)
            throw new FuturexProtocolException("The received packet has invalid framing.");

        var expectedLrc = CalculateLrc(frame.Slice(1, frame.Length - 2));
        if (frame[^1] != expectedLrc)
            throw new FuturexProtocolException($"The received packet has an invalid LRC (expected {expectedLrc:X2}).");

        var body = Encoding.ASCII.GetString(frame.Slice(1, frame.Length - 3));
        if (body.Any(character => character is < (char)0x20 or > (char)0x7e))
            throw new FuturexProtocolException("The received packet contains non-printable characters.");
        return body;
    }

    public static byte CalculateLrc(ReadOnlySpan<byte> bodyAndEtx)
    {
        byte result = 0;
        foreach (var value in bodyAndEtx)
            result ^= value;
        return result;
    }
}
