using System.Text;

namespace PinpadMediaManager.Core.Protocol;

public enum PinpadFrameType
{
    Transaction,
    Administration,
}

public sealed record PinpadFrame(PinpadFrameType Type, string CommandId, byte[] Payload)
{
    public string PayloadAscii => Encoding.ASCII.GetString(Payload);

    public static PinpadFrame Ascii(PinpadFrameType type, string commandId, string payload = "") =>
        new(type, commandId, Encoding.ASCII.GetBytes(payload));
}

public static class PinpadControl
{
    public const byte Stx = 0x02;
    public const byte Etx = 0x03;
    public const byte Eot = 0x04;
    public const byte Ack = 0x06;
    public const byte So = 0x0E;
    public const byte Si = 0x0F;
    public const byte Nak = 0x15;
    public const char Fs = '\u001C';
}

public static class PinpadFrameCodec
{
    public static byte[] Encode(PinpadFrame frame)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(frame.CommandId);
        var command = Encoding.ASCII.GetBytes(frame.CommandId);
        var start = frame.Type == PinpadFrameType.Transaction ? PinpadControl.Stx : PinpadControl.Si;
        var end = frame.Type == PinpadFrameType.Transaction ? PinpadControl.Etx : PinpadControl.So;
        var output = new byte[command.Length + frame.Payload.Length + 3];
        output[0] = start;
        command.CopyTo(output, 1);
        frame.Payload.CopyTo(output, 1 + command.Length);
        output[^2] = end;
        output[^1] = CalculateLrc(output.AsSpan(1, output.Length - 2));
        return output;
    }

    public static PinpadFrame Decode(ReadOnlySpan<byte> candidate)
    {
        if (candidate.Length < 5)
        {
            throw new PinpadProtocolException("The response frame is incomplete.");
        }

        var type = candidate[0] switch
        {
            PinpadControl.Stx => PinpadFrameType.Transaction,
            PinpadControl.Si => PinpadFrameType.Administration,
            _ => throw new PinpadProtocolException($"Unexpected frame start 0x{candidate[0]:X2}."),
        };
        var expectedEnd = type == PinpadFrameType.Transaction ? PinpadControl.Etx : PinpadControl.So;
        if (candidate[^2] != expectedEnd)
        {
            throw new PinpadProtocolException("The response frame has an invalid terminator.");
        }

        var expectedLrc = CalculateLrc(candidate[1..^1]);
        if (candidate[^1] != expectedLrc)
        {
            throw new PinpadProtocolException(
                $"The response LRC is invalid. Expected 0x{expectedLrc:X2}, received 0x{candidate[^1]:X2}.");
        }

        var content = candidate[1..^2];
        var commandLength = GetCommandLength(content);
        if (content.Length < commandLength)
        {
            throw new PinpadProtocolException("The response does not contain a complete command identifier.");
        }

        return new PinpadFrame(
            type,
            Encoding.ASCII.GetString(content[..commandLength]),
            content[commandLength..].ToArray());
    }

    public static byte CalculateLrc(ReadOnlySpan<byte> bytes)
    {
        byte result = 0;
        foreach (var value in bytes)
        {
            result ^= value;
        }

        return result;
    }

    private static int GetCommandLength(ReadOnlySpan<byte> content)
    {
        if (content.IsEmpty)
        {
            return 0;
        }

        if (content[0] is (byte)'T' or (byte)'M' or (byte)'I')
        {
            return 3;
        }

        if (content.Length >= 3)
        {
            var prefix = Encoding.ASCII.GetString(content[..3]);
            if (prefix is "Z42" or "Z43" or "Z50" or "Z51" or "Z60" or "Z62" or "Z64" or "Z65" or "Z66" or "Z67")
            {
                return 3;
            }
        }

        return 2;
    }
}

public sealed class PinpadProtocolException : Exception
{
    public PinpadProtocolException(string message) : base(message)
    {
    }

    public PinpadProtocolException(string message, Exception innerException) : base(message, innerException)
    {
    }
}
