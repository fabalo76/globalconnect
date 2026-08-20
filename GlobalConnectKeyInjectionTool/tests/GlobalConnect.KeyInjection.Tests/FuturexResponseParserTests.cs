using GlobalConnect.KeyInjection.Core.Protocol;

namespace GlobalConnect.KeyInjection.Tests;

public sealed class FuturexResponseParserTests
{
    [Fact]
    public void Parse_MasterResponse_ReturnsKcv()
    {
        var response = Assert.IsType<KeyInjectionResponse>(FuturexResponseParser.Parse("0100D5D4"));
        Assert.True(response.IsSuccess);
        Assert.Equal("D5D4", response.Kcv);
    }

    [Fact]
    public void Parse_DukptResponse_RequiresBothStepsToSucceed()
    {
        var response = Assert.IsType<DukptInjectionResponse>(FuturexResponseParser.Parse("0000008787"));
        Assert.True(response.IsSuccess);
        Assert.Equal("8787", response.Kcv);
    }

    [Fact]
    public void Parse_SerialResponse_TrimsRightPadding()
    {
        var response = Assert.IsType<SerialNumberResponse>(FuturexResponseParser.Parse("0300N96-1234        "));
        Assert.True(response.IsSuccess);
        Assert.Equal("N96-1234", response.SerialNumber);
    }
}
