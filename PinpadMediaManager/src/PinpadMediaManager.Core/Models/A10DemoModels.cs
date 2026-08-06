namespace PinpadMediaManager.Core.Models;

public sealed record A10DeviceProfile(
    string SerialNumber,
    string SystemCoreVersion,
    string ServiceRoutineVersion,
    string TransactionTerminalVersion,
    string PromptApplicationVersion,
    IReadOnlyList<string> HardwareCapabilities);

public enum A10EmvConfigurationType
{
    DataFormats,
    Terminal,
    ContactCaKey,
    ContactApplication,
    ContactlessCaKey,
    ContactlessApplication,
}

public enum A10HostDecision
{
    Approve,
    Decline,
    NoResponse,
}

public sealed record A10EmvTransactionRequest(
    decimal Amount,
    decimal CashbackAmount,
    string CurrencyCode,
    string TransactionType,
    string TransactionInformation,
    string AccountType,
    bool ForceOnline,
    A10HostDecision HostDecision)
{
    public static A10EmvTransactionRequest Default => new(
        Amount: 50.00m,
        CashbackAmount: 0m,
        CurrencyCode: "2840",
        TransactionType: "00",
        TransactionInformation: "40",
        AccountType: "00",
        ForceOnline: false,
        HostDecision: A10HostDecision.Approve);
}

public sealed record A10EmvTransactionResult(
    string Interface,
    string Status,
    string InitialResponse,
    string FinalResponse,
    string OnlineAuthorizationData,
    IReadOnlyDictionary<string, string> Tags)
{
    public bool Approved => Status.Contains("Approved", StringComparison.OrdinalIgnoreCase);
}

public sealed record A10SmartCardResponse(
    string Command,
    string Status,
    string Data);

public sealed record A10CommandResponse(
    string Command,
    string Payload);

public enum A10PinKeyScheme
{
    MasterSession,
    Dukpt,
}

public enum A10PinPromptMode
{
    Standard,
    ExternalPrompt,
    CustomPrompt,
}

public sealed record A10PinEntryRequest(
    A10PinKeyScheme KeyScheme,
    A10PinPromptMode PromptMode,
    string AccountNumber,
    string SessionKey,
    int TimeoutSeconds,
    int MinimumLength,
    int MaximumLength,
    bool AllowNullPin,
    string FirstPrompt,
    string SecondPrompt,
    string CompletionPrompt);

public sealed record A10SecretPinEntryRequest(
    A10PinPromptMode PromptMode,
    string AccountNumber,
    int SecretSessionKeyId,
    string Amount,
    int MinimumLength,
    int MaximumLength,
    bool AllowNullPin,
    string FirstPrompt,
    string SecondPrompt,
    string CompletionPrompt);

public sealed record A10PinEntryResult(
    string Status,
    string RawResponse,
    string EncryptedPinBlock,
    string? Ksn,
    int? PinLength,
    string? KeyIdentifier);
