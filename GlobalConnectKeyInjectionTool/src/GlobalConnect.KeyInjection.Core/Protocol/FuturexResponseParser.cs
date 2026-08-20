namespace GlobalConnect.KeyInjection.Core.Protocol;

public abstract record FuturexResponse(string Command, string ResponseCode)
{
    public bool IsSuccess => ResponseCode == "00";
}

public sealed record SerialNumberResponse(string ResponseCode, string SerialNumber)
    : FuturexResponse("03", ResponseCode);

public sealed record KeyInjectionResponse(string Command, string ResponseCode, string Kcv)
    : FuturexResponse(Command, ResponseCode);

public sealed record DukptInjectionResponse(string KsnResponseCode, string KeyResponseCode, string Kcv)
    : FuturexResponse("00", KeyResponseCode)
{
    public new bool IsSuccess => KsnResponseCode == "00" && KeyResponseCode == "00";
}

public sealed record BasicResponse(string Command, string ResponseCode)
    : FuturexResponse(Command, ResponseCode);

public static class FuturexResponseParser
{
    public static FuturexResponse Parse(string body)
    {
        if (string.IsNullOrWhiteSpace(body) || body.Length < 4)
            throw new FuturexProtocolException("The terminal returned an incomplete response.");

        var command = body[..2].ToUpperInvariant();
        return command switch
        {
            "00" when body.Length == 10 => new DukptInjectionResponse(Code(body[2..4]), Code(body[4..6]), Kcv(body[6..10])),
            "01" or "02" when body.Length == 8 => new KeyInjectionResponse(command, Code(body[2..4]), Kcv(body[4..8])),
            "03" when body.Length == 20 => new SerialNumberResponse(Code(body[2..4]), body[4..].TrimEnd()),
            "04" or "05" when body.Length == 4 => new BasicResponse(command, Code(body[2..4])),
            _ => throw new FuturexProtocolException($"The terminal returned an unexpected response for command {command}.")
        };
    }

    private static string Code(string value) => Hex(value, 2, "response code");
    private static string Kcv(string value) => Hex(value, 4, "KCV");

    private static string Hex(string value, int length, string field)
    {
        var normalized = value.ToUpperInvariant();
        if (normalized.Length != length || !normalized.All(Uri.IsHexDigit))
            throw new FuturexProtocolException($"The terminal returned an invalid {field}.");
        return normalized;
    }
}
