using PinpadMediaManager.Core.Models;
using PinpadMediaManager.Core.Protocol;

namespace PinpadMediaManager.Core;

public sealed partial class PinpadClient
{
    public Task<A10CardDetectionResult> DetectA10CardAsync(CancellationToken cancellationToken = default) =>
        DetectA10CardAsync(null, null, null, cancellationToken);

    public async Task<A10CardDetectionResult> DetectA10CardAsync(
        string? interfaces, string? transactionName, string? formattedAmount,
        CancellationToken cancellationToken = default)
    {
        var payload = QkDetectionRequest.BuildPayload(interfaces, transactionName, formattedAmount);
        try
        {
            var response = await ExecuteA10CommandAsync(PinpadFrameType.Transaction,
                "QK", payload, "QK", readFinalEot: false,
                responseTimeout: TimeSpan.FromSeconds(75), cancellationToken: cancellationToken, utf8Payload: true);
            return A10CardDetectionResult.Parse(response.Payload);
        }
        catch (Exception error) when (error is OperationCanceledException or TimeoutException)
        {
            // Release the QK wait before resetting the terminal through the same operation gate.
            await ExecuteA10CommandAsync(PinpadFrameType.Transaction, "Z1", "", null,
                readFinalEot: false, cancellationToken: CancellationToken.None);
            throw;
        }
    }
}
