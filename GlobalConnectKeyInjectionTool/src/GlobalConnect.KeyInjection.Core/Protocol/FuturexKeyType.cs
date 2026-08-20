namespace GlobalConnect.KeyInjection.Core.Protocol;

public sealed record FuturexKeyType(string Code, string Name, int Modifier)
{
    public static readonly FuturexKeyType MasterSession = new("01", "Master Session Key → Nexgo TMK", 0);
    public static readonly FuturexKeyType DukptInitial = new("02", "DUKPT Initial Key → Nexgo TIK", 8);
    public static readonly FuturexKeyType DukptBdk = new("03", "DUKPT BDK Key → Nexgo TIK", 8);
    public static readonly FuturexKeyType Mac = new("04", "MAC Key → Nexgo TAK", 3);
    public static readonly FuturexKeyType Pin = new("05", "PIN Encryption Key → Nexgo TPK", 1);
    public static readonly FuturexKeyType KeyExchange = new("06", "Key Exchange Key → Nexgo TLK (index 0)", 0);
    public static readonly FuturexKeyType HostVerificationKtk = new("07", "Host Verification KTK", 0);
    public static readonly FuturexKeyType Dukpt3DesBdk = new("08", "DUKPT 3DES BDK Key → Nexgo TIK", 8);
    public static readonly FuturexKeyType DefaultKtk = new("09", "Default KTK → Nexgo TLK (index 0)", 0);
    public static readonly FuturexKeyType BalanceDecryption = new("0A", "Balance Decryption Key", 0);
    public static readonly FuturexKeyType TdrDukptBdk = new("0B", "TDR DUKPT BDK", 0);

    public static IReadOnlyList<FuturexKeyType> ProtocolDefined { get; } =
    [
        MasterSession, DukptInitial, DukptBdk, Mac, Pin, KeyExchange,
        HostVerificationKtk, Dukpt3DesBdk, DefaultKtk, BalanceDecryption, TdrDukptBdk
    ];

    public static IReadOnlyList<FuturexKeyType> NexgoSupported { get; } =
    [
        MasterSession, DukptInitial, DukptBdk, Mac, Pin, KeyExchange,
        Dukpt3DesBdk, DefaultKtk
    ];

    public static IReadOnlyList<FuturexKeyType> All => ProtocolDefined;

    public override string ToString() => $"{Code} — {Name}";
}

public enum FuturexKeyEncryptionMode
{
    ClearKey,
    PreloadedKtk,
    SuppliedClearKtk
}

public static class FuturexKeyEncryptionModeExtensions
{
    public static string Code(this FuturexKeyEncryptionMode mode) => mode switch
    {
        FuturexKeyEncryptionMode.ClearKey => "00",
        FuturexKeyEncryptionMode.PreloadedKtk => "01",
        FuturexKeyEncryptionMode.SuppliedClearKtk => "02",
        _ => throw new ArgumentOutOfRangeException(nameof(mode))
    };

    public static string DisplayName(this FuturexKeyEncryptionMode mode) => mode switch
    {
        FuturexKeyEncryptionMode.ClearKey => "00 — Clear key",
        FuturexKeyEncryptionMode.PreloadedKtk => "01 — Under preloaded KTK",
        FuturexKeyEncryptionMode.SuppliedClearKtk => "02 — Under clear KTK (KTK supplied in field 13)",
        _ => mode.ToString()
    };
}

public static class FuturexKeyTypeExtensions
{
    /// <summary>
    /// Determines whether a Futurex key type represents a key transport key.
    /// </summary>
    /// <param name="keyType">The Futurex key type to inspect.</param>
    /// <returns><see langword="true"/> for host-verification and default KTK types; otherwise <see langword="false"/>.</returns>
    public static bool IsKtk(this FuturexKeyType keyType)
    {
        ArgumentNullException.ThrowIfNull(keyType);
        return keyType.Code is "07" or "09";
    }

    /// <summary>
    /// Returns the stable injection priority for a Futurex key type.
    /// </summary>
    /// <param name="keyType">The Futurex key type that will be injected.</param>
    /// <returns>Zero for a KTK so it is injected first; one for every other key type.</returns>
    public static int InjectionPriority(this FuturexKeyType keyType)
    {
        ArgumentNullException.ThrowIfNull(keyType);
        return keyType.IsKtk() ? 0 : 1;
    }
}
