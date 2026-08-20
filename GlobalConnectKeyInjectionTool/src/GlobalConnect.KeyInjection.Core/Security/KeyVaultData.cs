namespace GlobalConnect.KeyInjection.Core.Security;

public sealed class KeyVaultData
{
    public const int CurrentVersion = 2;

    public int Version { get; set; } = CurrentVersion;
    public DateTime CreatedUtc { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedUtc { get; set; } = DateTime.UtcNow;
    public Dictionary<string, StoredMasterKey> MasterKeys { get; set; } = new(StringComparer.OrdinalIgnoreCase);
    public Dictionary<string, StoredDukptKey> DukptKeys { get; set; } = new(StringComparer.OrdinalIgnoreCase);
    public Dictionary<string, StoredFuturexKey> FuturexKeys { get; set; } = new(StringComparer.OrdinalIgnoreCase);
    public StoredKtk? Ktk { get; set; }

    public int KeyCount => MasterKeys.Count + DukptKeys.Count + FuturexKeys.Count + (Ktk is null ? 0 : 1);
}

public sealed record StoredMasterKey(string Key, string Kcv);
public sealed record StoredDukptKey(string Ipek, string Ksn, string Kcv);
public sealed record StoredKtk(string Key, string Kcv);
public sealed record StoredFuturexKey(
    string KeyType,
    string DestinationIndex,
    string EncryptionMode,
    string KtkSourceIndex,
    string KeyPayload,
    string Ksn,
    string Kcv);
