using GlobalConnect.KeyInjection.Core.Crypto;

namespace GlobalConnect.KeyInjection.Tests;

public sealed class KeyMaterialTests
{
    [Fact]
    public void CalculateKcv_MatchesNexgoReferenceVector()
    {
        var kcv = KeyMaterial.CalculateKcv("0123456789ABCDEF0123456789ABCDEF");

        Assert.Equal("D5D44FF7", kcv);
    }

    [Fact]
    public void CalculateKcv_ProducesSixDigitDisplayValue()
    {
        var kcv = KeyMaterial.CalculateKcv("0123456789ABCDEF0123456789ABCDEF", 3);

        Assert.Equal("D5D44F", kcv);
    }

    [Fact]
    public void CombineComponents_XorsEveryByte()
    {
        var combined = KeyMaterial.CombineComponents(
            "0123456789ABCDEF0123456789ABCDEF",
            "11111111111111111111111111111111");

        Assert.Equal("1032547698BADCFE1032547698BADCFE", combined);
    }

    [Fact]
    public void CombineComponents_RejectsDifferentLengths()
    {
        Assert.Throws<ArgumentException>(() => KeyMaterial.CombineComponents(
            "0123456789ABCDEF",
            "0123456789ABCDEF0123456789ABCDEF"));
    }

    [Fact]
    public void EncryptUnderTdes_MatchesNexgoReferenceVector()
    {
        var encrypted = KeyMaterial.EncryptUnderTdes(
            "0123456789ABCDEF0123456789ABCDEF",
            "4004A21949A2B6796B250746C12686B0");

        Assert.Equal("4E4EF7BE4F199B144E4EF7BE4F199B14", encrypted);
    }
}
