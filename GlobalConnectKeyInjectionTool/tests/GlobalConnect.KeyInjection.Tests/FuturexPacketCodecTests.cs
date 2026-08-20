using GlobalConnect.KeyInjection.Core.Protocol;

namespace GlobalConnect.KeyInjection.Tests;

public sealed class FuturexPacketCodecTests
{
    [Fact]
    public void Encode_ReadSerial_MatchesDocumentedFrame()
    {
        var frame = FuturexPacketCodec.Encode("0301");

        Assert.Equal(new byte[] { 0x02, 0x30, 0x33, 0x30, 0x31, 0x03, 0x01 }, frame);
        Assert.Equal("0301", FuturexPacketCodec.Decode(frame));
    }

    [Fact]
    public void Decode_InvalidLrc_IsRejected()
    {
        var frame = FuturexPacketCodec.Encode("0301");
        frame[^1] ^= 0xff;

        Assert.Throws<FuturexProtocolException>(() => FuturexPacketCodec.Decode(frame));
    }

    [Fact]
    public void Decode_InvalidFraming_IsRejected()
    {
        Assert.Throws<FuturexProtocolException>(() => FuturexPacketCodec.Decode([0x30, 0x31, 0x03, 0x02]));
    }
}
