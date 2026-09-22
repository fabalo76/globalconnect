namespace PinpadMediaManager.Core.Models;

public static class QkDetectionRequest
{
    public static string BuildPayload(string? interfaces, string? transactionName, string? formattedAmount)
    {
        interfaces ??= "";
        transactionName ??= "";
        formattedAmount ??= "";
        if (interfaces.Length != 0 && (interfaces.Length != 3 || interfaces.Any(c => c != '0' && c != '1') || interfaces == "000"))
            throw new ArgumentException("Select at least one reader; the mask must contain three 0/1 digits in MSR, CHIP, CONTACTLESS order.");
        if (transactionName.Length > 64 || formattedAmount.Length > 48 || transactionName.Any(char.IsControl) || formattedAmount.Any(char.IsControl))
            throw new ArgumentException("Use up to 64 characters for the transaction name and 48 for the amount, without control characters.");
        if (formattedAmount.Length != 0) return $"{interfaces}\u001c{transactionName}\u001c{formattedAmount}";
        if (transactionName.Length != 0) return $"{interfaces}\u001c{transactionName}";
        return interfaces;
    }
}
