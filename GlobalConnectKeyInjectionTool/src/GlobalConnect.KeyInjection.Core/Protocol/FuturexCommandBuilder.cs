using System.Globalization;

namespace GlobalConnect.KeyInjection.Core.Protocol;

public static class FuturexCommandBuilder
{
    private const string ZeroKsn = "00000000000000000000";

    public static string ReadSerialNumber() => "0301";
    public static string EraseAllKeys() => "0501";

    public static string WriteSerialNumber(string serialNumber)
    {
        serialNumber = (serialNumber ?? string.Empty).Trim();
        if (serialNumber.Length is < 1 or > 16 || !serialNumber.All(c => c is >= (char)0x20 and <= (char)0x7e))
            throw new ArgumentException("The serial number must contain 1–16 printable ASCII characters.", nameof(serialNumber));
        return "0401" + serialNumber.PadRight(16);
    }

    public static string InjectMasterKey(string slot, string key)
    {
        slot = Hex(slot, 2, nameof(slot));
        NexgoFuturexPolicy.ValidateMasterIndex(slot);
        return "01" + slot + Key(key, nameof(key));
    }

    public static string InjectDukptKey(string slot, string ksn, string ipek)
    {
        slot = Hex(slot, 2, nameof(slot));
        ksn = Hex(ksn, 20, nameof(ksn));
        NexgoFuturexPolicy.ValidateDukptIndex(slot);
        if (ksn == ZeroKsn) throw new ArgumentException("A non-zero KSN is required for a Nexgo DUKPT destination.", nameof(ksn));
        return "00" + slot + ksn + Key(ipek, nameof(ipek));
    }

    public static string InjectKeyUnderKtk(
        string keySlot,
        string ktkSlot,
        FuturexKeyType keyType,
        FuturexKeyEncryptionMode encryptionMode,
        string keyChecksum,
        string? ktkChecksum,
        string? ksn,
        string keyPayload,
        string? ktkPayload)
    {
        ArgumentNullException.ThrowIfNull(keyType);
        if (!FuturexKeyType.ProtocolDefined.Any(item => item.Code == keyType.Code))
            throw new ArgumentException("Unsupported Futurex key type.", nameof(keyType));

        keyPayload = Key(keyPayload, nameof(keyPayload));
        var normalizedKtkPayload = string.Empty;
        if (encryptionMode == FuturexKeyEncryptionMode.SuppliedClearKtk)
            normalizedKtkPayload = Key(ktkPayload ?? string.Empty, nameof(ktkPayload));
        else if (!string.IsNullOrWhiteSpace(ktkPayload))
            throw new ArgumentException("A KTK payload is only valid for supplied-clear-KTK mode.", nameof(ktkPayload));

        var normalizedKsn = string.IsNullOrWhiteSpace(ksn) ? ZeroKsn : Hex(ksn, 20, nameof(ksn));
        var normalizedKtkChecksum = string.IsNullOrWhiteSpace(ktkChecksum) ? "0000" : Hex(ktkChecksum, 4, nameof(ktkChecksum));
        var normalizedKeySlot = Hex(keySlot, 2, nameof(keySlot));
        var normalizedKtkSlot = Hex(ktkSlot, 2, nameof(ktkSlot));
        NexgoFuturexPolicy.ValidateCommand02(
            keyType, normalizedKeySlot, encryptionMode, normalizedKtkSlot,
            normalizedKsn, normalizedKtkPayload);

        return "0201"
            + normalizedKeySlot
            + normalizedKtkSlot
            + keyType.Code
            + encryptionMode.Code()
            + Hex(keyChecksum, 4, nameof(keyChecksum))
            + normalizedKtkChecksum
            + normalizedKsn
            + LengthField(keyPayload.Length)
            + keyPayload
            + LengthField(normalizedKtkPayload.Length)
            + normalizedKtkPayload;
    }

    private static string Key(string value, string parameterName)
    {
        var normalized = Hex(value, parameterName);
        if (normalized.Length is not (16 or 32 or 48))
            throw new ArgumentException("A key must contain 16, 32, or 48 hexadecimal characters.", parameterName);
        return normalized;
    }

    private static string Hex(string value, int length, string parameterName)
    {
        var normalized = Hex(value, parameterName);
        if (normalized.Length != length)
            throw new ArgumentException($"The value must contain exactly {length} hexadecimal characters.", parameterName);
        return normalized;
    }

    private static string Hex(string value, string parameterName)
    {
        var normalized = (value ?? string.Empty).Trim().ToUpperInvariant();
        if (normalized.Length == 0 || !normalized.All(Uri.IsHexDigit))
            throw new ArgumentException("The value must contain only hexadecimal characters.", parameterName);
        return normalized;
    }

    private static string LengthField(int length)
    {
        if (length is < 0 or > 0xFFF)
            throw new ArgumentOutOfRangeException(nameof(length));
        return length.ToString("X3", CultureInfo.InvariantCulture);
    }
}
