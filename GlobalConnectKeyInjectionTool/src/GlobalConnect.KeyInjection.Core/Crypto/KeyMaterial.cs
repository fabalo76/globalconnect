using System.Security.Cryptography;

namespace GlobalConnect.KeyInjection.Core.Crypto;

public static class KeyMaterial
{
    public static string CombineComponents(params string[] components)
    {
        if (components is null || components.Length is < 2 or > 3)
            throw new ArgumentException("Enter two or three key components.", nameof(components));

        var normalized = components.Select((value, index) => ValidateKey(value, $"component {index + 1}")).ToArray();
        if (normalized.Any(value => value.Length != normalized[0].Length))
            throw new ArgumentException("All key components must have the same length.", nameof(components));

        var result = Convert.FromHexString(normalized[0]);
        foreach (var component in normalized.Skip(1))
        {
            var bytes = Convert.FromHexString(component);
            for (var index = 0; index < result.Length; index++)
                result[index] ^= bytes[index];
            CryptographicOperations.ZeroMemory(bytes);
        }

        try { return Convert.ToHexString(result); }
        finally { CryptographicOperations.ZeroMemory(result); }
    }

    public static string CalculateKcv(string key, int bytes = 4)
    {
        if (bytes is < 2 or > 8)
            throw new ArgumentOutOfRangeException(nameof(bytes));

        var normalized = ValidateKey(key, nameof(key));
        var source = Convert.FromHexString(normalized);
        try
        {
            var key1 = source.AsSpan(0, 8).ToArray();
            var key2 = source.Length >= 16 ? source.AsSpan(8, 8).ToArray() : source.AsSpan(0, 8).ToArray();
            var key3 = source.Length == 24 ? source.AsSpan(16, 8).ToArray() : source.AsSpan(0, 8).ToArray();
            var block = new byte[8];
            try
            {
                block = DesTransform(block, key1, true);
                block = DesTransform(block, key2, false);
                block = DesTransform(block, key3, true);
                return Convert.ToHexString(block.AsSpan(0, bytes));
            }
            finally
            {
                CryptographicOperations.ZeroMemory(key1);
                CryptographicOperations.ZeroMemory(key2);
                CryptographicOperations.ZeroMemory(key3);
                CryptographicOperations.ZeroMemory(block);
            }
        }
        finally
        {
            CryptographicOperations.ZeroMemory(source);
        }
    }

    public static string EncryptUnderTdes(string clearKey, string ktk)
    {
        var clear = Convert.FromHexString(ValidateKey(clearKey, nameof(clearKey)));
        var protector = Convert.FromHexString(ValidateKey(ktk, nameof(ktk)));
        if (protector.Length is not (16 or 24))
            throw new ArgumentException("A Nexgo KTK must be a 16-byte or 24-byte TDES key.", nameof(ktk));

        var key1 = protector.AsSpan(0, 8).ToArray();
        var key2 = protector.AsSpan(8, 8).ToArray();
        var key3 = protector.Length == 24 ? protector.AsSpan(16, 8).ToArray() : protector.AsSpan(0, 8).ToArray();
        var output = new byte[clear.Length];
        try
        {
            for (var offset = 0; offset < clear.Length; offset += 8)
            {
                var block = clear.AsSpan(offset, 8).ToArray();
                block = DesTransform(block, key1, true);
                block = DesTransform(block, key2, false);
                block = DesTransform(block, key3, true);
                block.CopyTo(output, offset);
                CryptographicOperations.ZeroMemory(block);
            }
            return Convert.ToHexString(output);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(clear);
            CryptographicOperations.ZeroMemory(protector);
            CryptographicOperations.ZeroMemory(key1);
            CryptographicOperations.ZeroMemory(key2);
            CryptographicOperations.ZeroMemory(key3);
            CryptographicOperations.ZeroMemory(output);
        }
    }

    public static string ValidateKey(string value, string name)
    {
        var normalized = (value ?? string.Empty).Trim().ToUpperInvariant();
        if (normalized.Length is not (16 or 32 or 48) || !normalized.All(Uri.IsHexDigit))
            throw new ArgumentException("Keys and components must contain 16, 32, or 48 hexadecimal characters.", name);
        return normalized;
    }

    private static byte[] DesTransform(byte[] block, byte[] key, bool encrypt)
    {
        using var algorithm = DES.Create();
        algorithm.Mode = CipherMode.ECB;
        algorithm.Padding = PaddingMode.None;
        algorithm.Key = key;
        using var transform = encrypt ? algorithm.CreateEncryptor() : algorithm.CreateDecryptor();
        var result = transform.TransformFinalBlock(block, 0, block.Length);
        CryptographicOperations.ZeroMemory(block);
        return result;
    }
}
