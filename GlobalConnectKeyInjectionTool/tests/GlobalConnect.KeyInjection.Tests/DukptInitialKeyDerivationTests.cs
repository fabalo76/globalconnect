using GlobalConnect.KeyInjection.Core.Crypto;

namespace GlobalConnect.KeyInjection.Tests;

public sealed class DukptInitialKeyDerivationTests
{
    [Fact]
    public void DeriveIpek_MatchesPublishedTdesDukptVector()
    {
        const string bdk = "0123456789ABCDEFFEDCBA9876543210";
        const string ksn = "FFFF9876543210E00008";

        var ipek = DukptInitialKeyDerivation.DeriveIpek(bdk, ksn);

        Assert.Equal("6AC292FAA1315B4D858AB3A3D7D5933A", ipek);
    }

    [Fact]
    public void NormalizeInitialKsn_ClearsOnlyTheLowTwentyOneCounterBits()
    {
        Assert.Equal(
            "FFFF9876543210E00000",
            DukptInitialKeyDerivation.NormalizeInitialKsn("FFFF9876543210FFFFFF"));
        Assert.Equal(0x1FFFFF, DukptInitialKeyDerivation.GetTransactionCounter("FFFF9876543210FFFFFF"));
    }

    [Fact]
    public void Derivation_RejectsTripleLengthBdkAndZeroInitialKsn()
    {
        Assert.Throws<ArgumentException>(() => DukptInitialKeyDerivation.DeriveIpek(
            "0123456789ABCDEFFEDCBA98765432100011223344556677",
            "FFFF9876543210E00000"));
        Assert.Throws<ArgumentException>(() => DukptInitialKeyDerivation.NormalizeInitialKsn("00000000000000000001"));
    }

    [Fact]
    public void BindToDeviceSerial_UsesFinalFiveTrailingDigitsAsTerminalId()
    {
        var result = DukptInitialKeyDerivation.BindToDeviceSerial(
            "FFFF2BED86174F1FFFFF",
            "N960W900629");

        Assert.Equal("00629", result.SerialDigits);
        Assert.Equal(629, result.TerminalId);
        Assert.Equal("004EA", result.DeviceId);
        Assert.Equal("FFFF2BED86004EA00000", result.Ksn);
        Assert.Equal(0, DukptInitialKeyDerivation.GetTransactionCounter(result.Ksn));
    }

    [Theory]
    [InlineData("N96W")]
    [InlineData("N96W00000")]
    public void BindToDeviceSerial_RejectsMissingOrZeroNumericSuffix(string serial)
    {
        Assert.Throws<ArgumentException>(() => DukptInitialKeyDerivation.BindToDeviceSerial(
            "FFFF2BED860000000000",
            serial));
    }
}
