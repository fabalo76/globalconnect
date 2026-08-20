using System.Security.Cryptography;

namespace GlobalConnect.KeyInjection.Core.Crypto;

/// <summary>Classic ANSI X9.24 TDES DUKPT initial-key derivation.</summary>
public static class DukptInitialKeyDerivation
{
    private const int MaximumTerminalId = 0x7FFFF;
    private const int SerialTerminalIdDigits = 5;

    private static readonly byte[] BdkMask =
    [
        0xC0, 0xC0, 0xC0, 0xC0, 0x00, 0x00, 0x00, 0x00,
        0xC0, 0xC0, 0xC0, 0xC0, 0x00, 0x00, 0x00, 0x00
    ];

    public static string DeriveIpek(string bdk, string ksn)
    {
        var normalizedBdk = KeyMaterial.ValidateKey(bdk, nameof(bdk));
        if (normalizedBdk.Length != 32)
            throw new ArgumentException("TDES DUKPT IPEK derivation requires a double-length 16-byte BDK.", nameof(bdk));

        var initialKsn = NormalizeInitialKsn(ksn);
        var bdkBytes = Convert.FromHexString(normalizedBdk);
        var maskedBdk = new byte[bdkBytes.Length];
        var ksnBytes = Convert.FromHexString(initialKsn);
        try
        {
            for (var index = 0; index < bdkBytes.Length; index++)
                maskedBdk[index] = (byte)(bdkBytes[index] ^ BdkMask[index]);

            var derivationData = Convert.ToHexString(ksnBytes.AsSpan(0, 8));
            var left = KeyMaterial.EncryptUnderTdes(derivationData, normalizedBdk);
            var right = KeyMaterial.EncryptUnderTdes(derivationData, Convert.ToHexString(maskedBdk));
            return left + right;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(bdkBytes);
            CryptographicOperations.ZeroMemory(maskedBdk);
            CryptographicOperations.ZeroMemory(ksnBytes);
        }
    }

    public static string NormalizeInitialKsn(string ksn)
    {
        var normalized = (ksn ?? string.Empty).Trim().ToUpperInvariant();
        if (normalized.Length != 20 || !normalized.All(Uri.IsHexDigit))
            throw new ArgumentException("A TDES DUKPT KSN must contain exactly 20 hexadecimal characters.", nameof(ksn));

        var bytes = Convert.FromHexString(normalized);
        try
        {
            // The low 21 bits are the transaction counter. IPEK derivation and
            // terminal initialization use the counter-zero Initial KSN.
            bytes[7] &= 0xE0;
            bytes[8] = 0;
            bytes[9] = 0;
            if (bytes.All(value => value == 0))
                throw new ArgumentException("The counter-zero Initial KSN cannot be all zeros.", nameof(ksn));
            return Convert.ToHexString(bytes);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(bytes);
        }
    }

    public static int GetTransactionCounter(string ksn)
    {
        var normalized = (ksn ?? string.Empty).Trim();
        if (normalized.Length != 20 || !normalized.All(Uri.IsHexDigit))
            throw new ArgumentException("A TDES DUKPT KSN must contain exactly 20 hexadecimal characters.", nameof(ksn));
        var bytes = Convert.FromHexString(normalized);
        try { return ((bytes[7] & 0x1F) << 16) | (bytes[8] << 8) | bytes[9]; }
        finally { CryptographicOperations.ZeroMemory(bytes); }
    }

    /// <summary>
    /// Replaces the 19-bit terminal identifier in a counter-zero TDES DUKPT KSN
    /// with the final five digits of the terminal's trailing decimal serial suffix.
    /// </summary>
    public static DeviceInitialKsn BindToDeviceSerial(string baseKsn, string deviceSerial)
    {
        var serial = (deviceSerial ?? string.Empty).Trim();
        var suffixStart = serial.Length;
        while (suffixStart > 0 && char.IsAsciiDigit(serial[suffixStart - 1])) suffixStart--;
        var trailingDigits = serial[suffixStart..];
        if (trailingDigits.Length == 0)
            throw new ArgumentException("The terminal serial number must end with decimal digits for DUKPT KSN allocation.", nameof(deviceSerial));

        var selectedDigits = trailingDigits.Length > SerialTerminalIdDigits
            ? trailingDigits[^SerialTerminalIdDigits..]
            : trailingDigits.PadLeft(SerialTerminalIdDigits, '0');
        var terminalId = int.Parse(selectedDigits, System.Globalization.CultureInfo.InvariantCulture);
        if (terminalId is <= 0 or > MaximumTerminalId)
            throw new ArgumentException("The final five terminal-serial digits must identify a non-zero DUKPT terminal.", nameof(deviceSerial));

        var initialKsn = Convert.FromHexString(NormalizeInitialKsn(baseKsn));
        try
        {
            // The first 40 bits retain the fixed prefix and BDK/key-set identifier.
            // Bits 39-21 carry the 19-bit terminal ID; bits 20-0 are the counter.
            initialKsn[5] = (byte)(terminalId >> 11);
            initialKsn[6] = (byte)(terminalId >> 3);
            initialKsn[7] = (byte)((terminalId & 0x07) << 5);
            initialKsn[8] = 0;
            initialKsn[9] = 0;
            var result = Convert.ToHexString(initialKsn);
            return new DeviceInitialKsn(result, selectedDigits, terminalId, result.Substring(10, 5));
        }
        finally
        {
            CryptographicOperations.ZeroMemory(initialKsn);
        }
    }
}

public sealed record DeviceInitialKsn(string Ksn, string SerialDigits, int TerminalId, string DeviceId);
