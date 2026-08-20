using GlobalConnect.KeyInjection.Core.Protocol;

namespace GlobalConnect.KeyInjection.Tests;

public sealed class NexgoFuturexPolicyTests
{
    [Theory]
    [InlineData(1, "01")]
    [InlineData(10, "0A")]
    public void OperationalIndices_AreOneThroughTen(int index, string wireValue)
    {
        Assert.Equal(wireValue, NexgoFuturexPolicy.ToWireIndex(index));
        NexgoFuturexPolicy.ValidateMasterIndex(wireValue);
        NexgoFuturexPolicy.ValidateDukptIndex(wireValue);
    }

    [Theory]
    [InlineData("00")]
    [InlineData("0B")]
    [InlineData("0F")]
    [InlineData("C6")]
    public void OperationalIndices_RejectLegacyOrOutOfAppRange(string wireValue)
    {
        Assert.Throws<ArgumentException>(() => NexgoFuturexPolicy.ValidateMasterIndex(wireValue));
    }

    [Theory]
    [InlineData("07")]
    [InlineData("0A")]
    [InlineData("0B")]
    public void AndroidUnsupportedFuturexTypes_AreRejected(string code)
    {
        var type = FuturexKeyType.ProtocolDefined.Single(item => item.Code == code);
        Assert.Throws<ArgumentException>(() => FuturexCommandBuilder.InjectKeyUnderKtk(
            "01", "00", type, FuturexKeyEncryptionMode.ClearKey,
            "D5D4", "0000", null, "0123456789ABCDEF0123456789ABCDEF", null));
    }

    [Fact]
    public void TlkDestination_IsFixedAtZeroAndClearMode()
    {
        var body = FuturexCommandBuilder.InjectKeyUnderKtk(
            "00", "00", FuturexKeyType.DefaultKtk, FuturexKeyEncryptionMode.ClearKey,
            "611F", "0000", null, "4004A21949A2B6796B250746C12686B0", null);

        Assert.StartsWith("020100000900611F", body);
        Assert.Throws<ArgumentException>(() => FuturexCommandBuilder.InjectKeyUnderKtk(
            "01", "00", FuturexKeyType.DefaultKtk, FuturexKeyEncryptionMode.ClearKey,
            "611F", "0000", null, "4004A21949A2B6796B250746C12686B0", null));
    }

    [Fact]
    public void ClearWorkingKeyLoad_IsAcceptedForAndroidTransientMasterBridge()
    {
        var body = FuturexCommandBuilder.InjectKeyUnderKtk(
            "01", "00", FuturexKeyType.Pin, FuturexKeyEncryptionMode.ClearKey,
            "D5D4", "0000", null, "0123456789ABCDEF0123456789ABCDEF", null);

        Assert.StartsWith("020101000500D5D40000", body);
        Assert.True(NexgoFuturexPolicy.SupportsMode(FuturexKeyType.Pin, FuturexKeyEncryptionMode.ClearKey));
        Assert.True(NexgoFuturexPolicy.SupportsMode(FuturexKeyType.Mac, FuturexKeyEncryptionMode.ClearKey));
    }

    [Fact]
    public void MixedBatch_OverridesTlkToClearButKeepsOperationalMode()
    {
        Assert.Equal(
            FuturexKeyEncryptionMode.ClearKey,
            NexgoFuturexPolicy.ResolveBatchMode(FuturexKeyType.DefaultKtk, FuturexKeyEncryptionMode.SuppliedClearKtk));
        Assert.Equal(
            FuturexKeyEncryptionMode.SuppliedClearKtk,
            NexgoFuturexPolicy.ResolveBatchMode(FuturexKeyType.Pin, FuturexKeyEncryptionMode.SuppliedClearKtk));
    }

    [Fact]
    public void DukptDestination_RequiresNonZeroKsn()
    {
        Assert.Throws<ArgumentException>(() => FuturexCommandBuilder.InjectDukptKey(
            "01", "00000000000000000000", "0123456789ABCDEF0123456789ABCDEF"));
    }

    [Fact]
    public void Command02_RejectsTr31TextBecauseAndroidConsumesHexBytes()
    {
        Assert.Throws<ArgumentException>(() => FuturexCommandBuilder.InjectKeyUnderKtk(
            "01", "00", FuturexKeyType.MasterSession, FuturexKeyEncryptionMode.ClearKey,
            "D5D4", "0000", null, "B0080K0TB00E0000NOT-A-HEX-KEY-BLOCK", null));
    }
}
