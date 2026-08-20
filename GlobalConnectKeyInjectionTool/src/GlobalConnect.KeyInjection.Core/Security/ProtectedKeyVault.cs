using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using GlobalConnect.KeyInjection.Core.Protocol;

namespace GlobalConnect.KeyInjection.Core.Security;

public static class ProtectedKeyVault
{
    private const string Magic = "GCKV";
    private const int FormatVersion = 1;
    private const int Iterations = 600_000;
    private const int SaltSize = 16;
    private const int NonceSize = 12;
    private const int TagSize = 16;
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web) { WriteIndented = false };

    public static void Save(string path, KeyVaultData vault, string passwordPart1, string passwordPart2)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        ArgumentNullException.ThrowIfNull(vault);
        ValidatePasswords(passwordPart1, passwordPart2);
        ValidateVault(vault);

        vault.UpdatedUtc = DateTime.UtcNow;
        var plaintext = JsonSerializer.SerializeToUtf8Bytes(vault, JsonOptions);
        var salt1 = RandomNumberGenerator.GetBytes(SaltSize);
        var salt2 = RandomNumberGenerator.GetBytes(SaltSize);
        var nonce = RandomNumberGenerator.GetBytes(NonceSize);
        var tag = new byte[TagSize];
        var ciphertext = new byte[plaintext.Length];
        var key = DeriveKey(passwordPart1, passwordPart2, salt1, salt2, Iterations);
        var associatedData = AssociatedData(FormatVersion, Iterations, salt1, salt2);

        try
        {
            using (var aes = new AesGcm(key, TagSize))
                aes.Encrypt(nonce, plaintext, ciphertext, tag, associatedData);

            var envelope = new VaultEnvelope
            {
                Magic = Magic,
                Version = FormatVersion,
                Kdf = "PBKDF2-SHA256-DUAL",
                Iterations = Iterations,
                Salt1 = Convert.ToBase64String(salt1),
                Salt2 = Convert.ToBase64String(salt2),
                Cipher = "AES-256-GCM",
                Nonce = Convert.ToBase64String(nonce),
                Tag = Convert.ToBase64String(tag),
                Ciphertext = Convert.ToBase64String(ciphertext)
            };

            var directory = Path.GetDirectoryName(Path.GetFullPath(path));
            if (!string.IsNullOrEmpty(directory)) Directory.CreateDirectory(directory);
            var temporaryPath = path + ".tmp-" + Guid.NewGuid().ToString("N");
            try
            {
                File.WriteAllBytes(temporaryPath, JsonSerializer.SerializeToUtf8Bytes(envelope, JsonOptions));
                File.Move(temporaryPath, path, true);
            }
            finally
            {
                if (File.Exists(temporaryPath)) File.Delete(temporaryPath);
            }
        }
        finally
        {
            CryptographicOperations.ZeroMemory(plaintext);
            CryptographicOperations.ZeroMemory(ciphertext);
            CryptographicOperations.ZeroMemory(key);
            CryptographicOperations.ZeroMemory(tag);
        }
    }

    public static KeyVaultData Load(string path, string passwordPart1, string passwordPart2)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        ValidatePasswords(passwordPart1, passwordPart2);

        var envelope = JsonSerializer.Deserialize<VaultEnvelope>(File.ReadAllBytes(path), JsonOptions)
            ?? throw new InvalidDataException("The protected key file is empty.");
        if (envelope.Magic != Magic || envelope.Version != FormatVersion ||
            envelope.Kdf != "PBKDF2-SHA256-DUAL" || envelope.Cipher != "AES-256-GCM" ||
            envelope.Iterations < 100_000)
            throw new InvalidDataException("The protected key file format is not supported.");

        byte[] salt1;
        byte[] salt2;
        byte[] nonce;
        byte[] tag;
        byte[] ciphertext;
        try
        {
            salt1 = Convert.FromBase64String(envelope.Salt1);
            salt2 = Convert.FromBase64String(envelope.Salt2);
            nonce = Convert.FromBase64String(envelope.Nonce);
            tag = Convert.FromBase64String(envelope.Tag);
            ciphertext = Convert.FromBase64String(envelope.Ciphertext);
        }
        catch (FormatException error)
        {
            throw new InvalidDataException("The protected key file is malformed.", error);
        }

        if (salt1.Length != SaltSize || salt2.Length != SaltSize || nonce.Length != NonceSize || tag.Length != TagSize)
            throw new InvalidDataException("The protected key file has invalid cryptographic parameters.");

        var key = DeriveKey(passwordPart1, passwordPart2, salt1, salt2, envelope.Iterations);
        var plaintext = new byte[ciphertext.Length];
        try
        {
            using (var aes = new AesGcm(key, TagSize))
                aes.Decrypt(nonce, ciphertext, tag, plaintext,
                    AssociatedData(envelope.Version, envelope.Iterations, salt1, salt2));

            var vault = JsonSerializer.Deserialize<KeyVaultData>(plaintext, JsonOptions)
                ?? throw new InvalidDataException("The protected key file contains no key configuration.");
            ValidateVault(vault);
            return vault;
        }
        finally
        {
            CryptographicOperations.ZeroMemory(key);
            CryptographicOperations.ZeroMemory(plaintext);
            CryptographicOperations.ZeroMemory(ciphertext);
        }
    }

    public static void ValidatePasswords(string passwordPart1, string passwordPart2)
    {
        if (string.IsNullOrEmpty(passwordPart1) || passwordPart1.Length is < 8 or > 128)
            throw new ArgumentException("Password part 1 must contain 8–128 characters.", nameof(passwordPart1));
        if (string.IsNullOrEmpty(passwordPart2) || passwordPart2.Length is < 8 or > 128)
            throw new ArgumentException("Password part 2 must contain 8–128 characters.", nameof(passwordPart2));
        if (CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(passwordPart1), Encoding.UTF8.GetBytes(passwordPart2)))
            throw new ArgumentException("The two password parts must be different.", nameof(passwordPart2));
    }

    private static byte[] DeriveKey(string passwordPart1, string passwordPart2, byte[] salt1, byte[] salt2, int iterations)
    {
        var part1 = Rfc2898DeriveBytes.Pbkdf2(passwordPart1, salt1, iterations, HashAlgorithmName.SHA256, 32);
        var part2 = Rfc2898DeriveBytes.Pbkdf2(passwordPart2, salt2, iterations, HashAlgorithmName.SHA256, 32);
        var context = Encoding.ASCII.GetBytes("GlobalConnect-KeyVault-v1");
        var combined = new byte[part1.Length + part2.Length + context.Length];
        try
        {
            part1.CopyTo(combined, 0);
            part2.CopyTo(combined, part1.Length);
            context.CopyTo(combined, part1.Length + part2.Length);
            return SHA256.HashData(combined);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(part1);
            CryptographicOperations.ZeroMemory(part2);
            CryptographicOperations.ZeroMemory(combined);
        }
    }

    private static byte[] AssociatedData(int version, int iterations, byte[] salt1, byte[] salt2) =>
        Encoding.ASCII.GetBytes($"{Magic}|{version}|{iterations}|{Convert.ToBase64String(salt1)}|{Convert.ToBase64String(salt2)}");

    private static void ValidateVault(KeyVaultData vault)
    {
        if (vault.Version != KeyVaultData.CurrentVersion)
            throw new InvalidDataException("The key-vault data version is unsupported.");
        vault.MasterKeys ??= new(StringComparer.OrdinalIgnoreCase);
        vault.DukptKeys ??= new(StringComparer.OrdinalIgnoreCase);
        vault.FuturexKeys ??= new(StringComparer.OrdinalIgnoreCase);
        foreach (var (slot, entry) in vault.MasterKeys)
        {
            ValidateSlot(slot);
            ValidateKey(entry.Key);
            ValidateKcv(entry.Kcv);
        }
        foreach (var (slot, entry) in vault.DukptKeys)
        {
            ValidateSlot(slot);
            ValidateKey(entry.Ipek);
            if (entry.Ksn.Length != 20 || !entry.Ksn.All(Uri.IsHexDigit)) throw new InvalidDataException("A stored KSN is invalid.");
            ValidateKcv(entry.Kcv);
        }
        foreach (var entry in vault.FuturexKeys.Values)
        {
            var type = FuturexKeyType.NexgoSupported.SingleOrDefault(item => item.Code == entry.KeyType)
                ?? throw new InvalidDataException("A stored Futurex key type is not supported by the Nexgo application.");
            NexgoFuturexPolicy.ValidateDestination(type, entry.DestinationIndex);
            if (entry.EncryptionMode != "00" || entry.KtkSourceIndex != "00")
                throw new InvalidDataException("Protected vault version 2 stores only clear key records; KTK encryption is selected at injection time.");
            NexgoFuturexPolicy.ValidateKtkSourceIndex(entry.KtkSourceIndex);
            ValidateKey(entry.KeyPayload);
            if (type.Code is "03" or "08" && entry.KeyPayload.Length != 32)
                throw new InvalidDataException("A stored TDES DUKPT BDK must be double length (32 hexadecimal characters).");
            ValidateKcv(entry.Kcv);
            if (entry.Ksn.Length != 20 || !entry.Ksn.All(Uri.IsHexDigit))
                throw new InvalidDataException("A stored Futurex KSN is invalid.");
            if (NexgoFuturexPolicy.RequiresKsn(type) && entry.Ksn.All(character => character == '0'))
                throw new InvalidDataException("A stored Nexgo DUKPT key requires a non-zero KSN.");
        }
        if (vault.Ktk is not null)
        {
            ValidateKey(vault.Ktk.Key);
            ValidateKcv(vault.Ktk.Kcv);
        }
    }

    private static void ValidateSlot(string slot)
    {
        try { NexgoFuturexPolicy.ValidateMasterIndex(slot); }
        catch (ArgumentException error) { throw new InvalidDataException("A stored Nexgo key index is invalid.", error); }
    }

    private static void ValidateKey(string key)
    {
        if (key.Length is not (16 or 32 or 48) || !key.All(Uri.IsHexDigit)) throw new InvalidDataException("A stored key is invalid.");
    }

    private static void ValidateKcv(string kcv)
    {
        if (kcv.Length != 4 || !kcv.All(Uri.IsHexDigit)) throw new InvalidDataException("A stored KCV is invalid.");
    }

    private sealed class VaultEnvelope
    {
        public string Magic { get; set; } = string.Empty;
        public int Version { get; set; }
        public string Kdf { get; set; } = string.Empty;
        public int Iterations { get; set; }
        public string Salt1 { get; set; } = string.Empty;
        public string Salt2 { get; set; } = string.Empty;
        public string Cipher { get; set; } = string.Empty;
        public string Nonce { get; set; } = string.Empty;
        public string Tag { get; set; } = string.Empty;
        public string Ciphertext { get; set; } = string.Empty;
    }
}
