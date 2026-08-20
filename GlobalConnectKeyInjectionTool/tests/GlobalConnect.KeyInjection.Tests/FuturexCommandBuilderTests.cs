using GlobalConnect.KeyInjection.Core.Protocol;

namespace GlobalConnect.KeyInjection.Tests;

public sealed class FuturexCommandBuilderTests
{
    [Fact]
    public void SerialReadAndEraseCommands_MatchDeviceProtocol()
    {
        Assert.Equal("0301", FuturexCommandBuilder.ReadSerialNumber());
        Assert.Equal("0501", FuturexCommandBuilder.EraseAllKeys());
    }

    [Fact]
    public void MasterAndDukptCommands_MatchProtocolLayout()
    {
        Assert.Equal(
            "01010123456789ABCDEF0123456789ABCDEF",
            FuturexCommandBuilder.InjectMasterKey("01", "0123456789ABCDEF0123456789ABCDEF"));
        Assert.Equal(
            "0001FFFF0121010003400000FDA51E1A6B62CDE853C7BFD8807D4500",
            FuturexCommandBuilder.InjectDukptKey("01", "FFFF0121010003400000", "FDA51E1A6B62CDE853C7BFD8807D4500"));
    }

    [Fact]
    public void KeyUnderPreloadedKtk_MatchesNexgoReferencePacket()
    {
        var body = FuturexCommandBuilder.InjectKeyUnderKtk(
            "01", "00", FuturexKeyType.MasterSession, FuturexKeyEncryptionMode.PreloadedKtk,
            "D5D4", "611F", null,
            "4E4EF7BE4F199B144E4EF7BE4F199B14", null);

        Assert.Equal(
            "020101000101D5D4611F000000000000000000000204E4EF7BE4F199B144E4EF7BE4F199B14000",
            body);
    }

    [Fact]
    public void KeyUnderSuppliedKtk_UsesAsciiHexLengths()
    {
        var body = FuturexCommandBuilder.InjectKeyUnderKtk(
            "01", "00", FuturexKeyType.Dukpt3DesBdk, FuturexKeyEncryptionMode.SuppliedClearKtk,
            "8787", "611F", "FFFF0121010003400000",
            "FC9FEEC63FBE86B1B956B7E8CF2A94A8",
            "4004A21949A2B6796B250746C12686B0");

        Assert.Equal(
            "0201010008028787611FFFFF0121010003400000020FC9FEEC63FBE86B1B956B7E8CF2A94A80204004A21949A2B6796B250746C12686B0",
            body);
    }
}
