using System.Security.Cryptography;
using GlobalConnect.KeyInjection.Core.Security;

namespace GlobalConnect.KeyInjection.Tests;

public sealed class ProtectedKeyVaultTests : IDisposable
{
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "GlobalConnectVaultTests-" + Guid.NewGuid().ToString("N"));

    public ProtectedKeyVaultTests() => Directory.CreateDirectory(_directory);

    [Fact]
    public void SaveAndLoad_RequiresBothPasswordsAndPreservesKeys()
    {
        var path = Path.Combine(_directory, "keys.gckv");
        var vault = SampleVault();

        ProtectedKeyVault.Save(path, vault, "Custodian-One-Password", "Custodian-Two-Password");
        var loaded = ProtectedKeyVault.Load(path, "Custodian-One-Password", "Custodian-Two-Password");

        Assert.Equal("0123456789ABCDEF0123456789ABCDEF", loaded.MasterKeys["01"].Key);
        Assert.Equal("FFFF0121010003400000", loaded.DukptKeys["02"].Ksn);
        Assert.Equal("05", loaded.FuturexKeys["05:03"].KeyType);
        Assert.Equal("00", loaded.FuturexKeys["05:03"].EncryptionMode);
        Assert.Equal("611F", loaded.Ktk!.Kcv);
        Assert.DoesNotContain("0123456789ABCDEF", File.ReadAllText(path), StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void Load_WithEitherWrongPassword_IsRejected()
    {
        var path = Path.Combine(_directory, "keys.gckv");
        ProtectedKeyVault.Save(path, SampleVault(), "Custodian-One-Password", "Custodian-Two-Password");

        Assert.Throws<AuthenticationTagMismatchException>(() =>
            ProtectedKeyVault.Load(path, "Wrong-One-Password", "Custodian-Two-Password"));
        Assert.Throws<AuthenticationTagMismatchException>(() =>
            ProtectedKeyVault.Load(path, "Custodian-One-Password", "Wrong-Two-Password"));
    }

    [Fact]
    public void Load_AfterCiphertextTampering_IsRejected()
    {
        var path = Path.Combine(_directory, "keys.gckv");
        ProtectedKeyVault.Save(path, SampleVault(), "Custodian-One-Password", "Custodian-Two-Password");
        var bytes = File.ReadAllBytes(path);
        var text = System.Text.Encoding.UTF8.GetString(bytes);
        var ciphertextIndex = text.IndexOf("ciphertext", StringComparison.Ordinal);
        Assert.True(ciphertextIndex > 0);
        var mutationIndex = text.IndexOf(':', ciphertextIndex) + 2;
        bytes[mutationIndex] = bytes[mutationIndex] == (byte)'A' ? (byte)'B' : (byte)'A';
        File.WriteAllBytes(path, bytes);

        Assert.ThrowsAny<CryptographicException>(() =>
            ProtectedKeyVault.Load(path, "Custodian-One-Password", "Custodian-Two-Password"));
    }

    [Fact]
    public void PasswordParts_MustBeLongAndDifferent()
    {
        Assert.Throws<ArgumentException>(() => ProtectedKeyVault.ValidatePasswords("short", "another-password"));
        Assert.Throws<ArgumentException>(() => ProtectedKeyVault.ValidatePasswords("same-password", "same-password"));
    }

    private static KeyVaultData SampleVault() => new()
    {
        MasterKeys = { ["01"] = new StoredMasterKey("0123456789ABCDEF0123456789ABCDEF", "D5D4") },
        DukptKeys = { ["02"] = new StoredDukptKey("FDA51E1A6B62CDE853C7BFD8807D4500", "FFFF0121010003400000", "8787") },
        FuturexKeys =
        {
            ["05:03"] = new StoredFuturexKey(
                "05", "03", "00", "00",
                "0123456789ABCDEF0123456789ABCDEF",
                "00000000000000000000", "D5D4")
        },
        Ktk = new StoredKtk("4004A21949A2B6796B250746C12686B0", "611F")
    };

    public void Dispose()
    {
        if (Directory.Exists(_directory)) Directory.Delete(_directory, true);
    }
}
