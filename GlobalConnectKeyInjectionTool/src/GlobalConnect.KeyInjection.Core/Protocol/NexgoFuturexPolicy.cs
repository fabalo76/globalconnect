namespace GlobalConnect.KeyInjection.Core.Protocol;

public static class NexgoFuturexPolicy
{
    public const int MinimumOperationalIndex = 1;
    public const int MaximumOperationalIndex = 10;
    public const string TlkIndex = "00";

    private static readonly HashSet<string> DukptTypes = ["02", "03", "08"];
    private static readonly HashSet<string> WorkingKeyTypes = ["04", "05"];
    private static readonly HashSet<string> TlkTypes = ["06", "09"];

    public static IReadOnlyList<int> OperationalIndices { get; } =
        Enumerable.Range(MinimumOperationalIndex, MaximumOperationalIndex).ToArray();

    public static void ValidateMasterIndex(string wireIndex) =>
        ValidateOperationalIndex(wireIndex, "TMK destination index");

    public static void ValidateDukptIndex(string wireIndex) =>
        ValidateOperationalIndex(wireIndex, "TIK destination index");

    public static void ValidateCommand02(
        FuturexKeyType keyType,
        string destinationIndex,
        FuturexKeyEncryptionMode mode,
        string ktkSourceIndex,
        string ksn,
        string ktkPayload)
    {
        if (!FuturexKeyType.NexgoSupported.Any(type => type.Code == keyType.Code))
            throw new ArgumentException($"Futurex key type {keyType.Code} is not supported by the Nexgo Android application.", nameof(keyType));

        if (TlkTypes.Contains(keyType.Code))
        {
            if (destinationIndex != TlkIndex)
                throw new ArgumentException("Nexgo stores TLK at the fixed master-key index 0.", nameof(destinationIndex));
            if (mode != FuturexKeyEncryptionMode.ClearKey)
                throw new ArgumentException("The Nexgo application only supports TLK/default-KTK destination loading in clear-key mode.", nameof(mode));
        }
        else
        {
            ValidateOperationalIndex(destinationIndex, "destination index");
        }

        if (mode == FuturexKeyEncryptionMode.ClearKey)
        {
            if (ktkSourceIndex != TlkIndex)
                throw new ArgumentException("KTK source must be 'not used' for clear-key mode.", nameof(ktkSourceIndex));
        }
        else
        {
            ValidateKtkSourceIndex(ktkSourceIndex);
        }

        if (mode == FuturexKeyEncryptionMode.SuppliedClearKtk && ktkPayload.Length is not (32 or 48))
            throw new ArgumentException("The Nexgo application accepts a supplied clear KTK only as a 16-byte or 24-byte TDES key.", nameof(ktkPayload));

        if (DukptTypes.Contains(keyType.Code) && ksn == "00000000000000000000")
            throw new ArgumentException("A non-zero 20-hex-character KSN is required for Nexgo DUKPT destinations.", nameof(ksn));
    }

    public static void ValidateDestination(FuturexKeyType keyType, string destinationIndex)
    {
        if (!FuturexKeyType.NexgoSupported.Any(type => type.Code == keyType.Code))
            throw new ArgumentException($"Futurex key type {keyType.Code} is not supported by the Nexgo Android application.", nameof(keyType));
        if (TlkTypes.Contains(keyType.Code))
        {
            if (destinationIndex != TlkIndex)
                throw new ArgumentException("Nexgo TLK is fixed at master-key index 0.", nameof(destinationIndex));
            return;
        }
        ValidateOperationalIndex(destinationIndex, "destination index");
    }

    public static void ValidateKtkSourceIndex(string wireIndex)
    {
        if (wireIndex == TlkIndex) return;
        ValidateOperationalIndex(wireIndex, "KTK source TMK index");
    }

    public static bool IsTlkDestination(FuturexKeyType keyType) => TlkTypes.Contains(keyType.Code);

    public static bool RequiresKsn(FuturexKeyType keyType) => DukptTypes.Contains(keyType.Code);

    public static FuturexKeyEncryptionMode ResolveBatchMode(
        FuturexKeyType keyType,
        FuturexKeyEncryptionMode selectedMode) =>
        IsTlkDestination(keyType) ? FuturexKeyEncryptionMode.ClearKey : selectedMode;

    public static bool SupportsMode(FuturexKeyType keyType, FuturexKeyEncryptionMode mode)
    {
        if (TlkTypes.Contains(keyType.Code)) return mode == FuturexKeyEncryptionMode.ClearKey;
        if (WorkingKeyTypes.Contains(keyType.Code)) return true;
        return FuturexKeyType.NexgoSupported.Any(type => type.Code == keyType.Code);
    }

    public static string ToWireIndex(int index)
    {
        if (index is < MinimumOperationalIndex or > MaximumOperationalIndex)
            throw new ArgumentOutOfRangeException(nameof(index), "Nexgo operational key indices are 1–10.");
        return index.ToString("X2");
    }

    private static void ValidateOperationalIndex(string wireIndex, string field)
    {
        if (wireIndex.Length != 2 || !wireIndex.All(Uri.IsHexDigit) ||
            !int.TryParse(wireIndex, System.Globalization.NumberStyles.HexNumber, null, out var index) ||
            index is < MinimumOperationalIndex or > MaximumOperationalIndex)
            throw new ArgumentException($"Nexgo {field} must be an index from 1 through 10.", field);
    }
}
