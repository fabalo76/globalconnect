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

public sealed record A10EmvConfigurationSnapshot(
    bool TerminalConfigurationPresent,
    int TerminalConfigurationBytes,
    IReadOnlyList<string> DataFormatTags,
    IReadOnlyList<string> CapkIds,
    IReadOnlyList<string> ContactAidIds,
    IReadOnlyList<string> ContactlessAidIds,
    IReadOnlyList<string> ContactlessDrlIds);

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
    A10PinKeyScheme OnlinePinKeyScheme,
    string EncryptedSessionKey,
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
        OnlinePinKeyScheme: A10PinKeyScheme.MasterSession,
        EncryptedSessionKey: "",
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

public sealed record EmvOperationsApiOptions(
    string BaseAddress,
    string ApiKey,
    string? ClientCertificatePath = null,
    string? ClientCertificatePassword = null);

public sealed record A10OfflinePinChangeRequest(
    A10PinKeyScheme PinKeyScheme,
    string EncryptedSessionKey,
    string PinProfileId,
    EmvOperationsApiOptions Api);

public sealed record A10OfflinePinUnblockRequest(EmvOperationsApiOptions Api);

public sealed record A10OfflinePinChangeResult(
    string Status,
    string InitialResponse,
    string FinalResponse,
    string PinEntryResponse,
    string ApiResponseCode,
    string Field55,
    IReadOnlyDictionary<string, string> Tags);
