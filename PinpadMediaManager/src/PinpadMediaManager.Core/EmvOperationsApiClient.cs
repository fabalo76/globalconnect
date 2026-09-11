using System.Net.Http.Json;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using PinpadMediaManager.Core.Models;

namespace PinpadMediaManager.Core;

internal sealed class EmvOperationsApiClient : IDisposable
{
    private readonly HttpClient client;

    internal EmvOperationsApiClient(EmvOperationsApiOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);
        var handler = new HttpClientHandler();
        if (!string.IsNullOrWhiteSpace(options.ClientCertificatePath))
        {
            handler.ClientCertificates.Add(X509CertificateLoader.LoadPkcs12FromFile(
                options.ClientCertificatePath,
                options.ClientCertificatePassword));
        }
        client = new HttpClient(handler) { BaseAddress = new Uri(options.BaseAddress, UriKind.Absolute) };
        client.DefaultRequestHeaders.Add("X-EMV-API-Key", options.ApiKey);
    }

    internal async Task<EmvOperationsResponse> ChangeOfflinePinAsync(
        string requestId,
        string pan,
        string panSequenceNumber,
        string encryptedPinBlock,
        string pinProfileId,
        string? ksn,
        IReadOnlyDictionary<string, string> tags,
        CancellationToken cancellationToken)
        => await MaintainOfflinePinAsync(
            "api/v1/emv/offline-pin/change", requestId, pan, panSequenceNumber,
            encryptedPinBlock, pinProfileId, ksn, tags, cancellationToken);

    internal async Task<EmvOperationsResponse> UnblockOfflinePinAsync(
        string requestId,
        string pan,
        string panSequenceNumber,
        IReadOnlyDictionary<string, string> tags,
        CancellationToken cancellationToken)
    {
        var request = new
        {
            requestId,
            pan,
            panSequenceNumber,
            entryMode = "051",
            emvTags = tags,
        };
        using var response = await client.PostAsJsonAsync(
            "api/v1/emv/offline-pin/unblock", request, cancellationToken);
        return await ReadResponseAsync(response, cancellationToken);
    }

    private async Task<EmvOperationsResponse> MaintainOfflinePinAsync(
        string endpoint,
        string requestId,
        string pan,
        string panSequenceNumber,
        string encryptedPinBlock,
        string pinProfileId,
        string? ksn,
        IReadOnlyDictionary<string, string> tags,
        CancellationToken cancellationToken)
    {
        var request = new
        {
            requestId,
            pan,
            panSequenceNumber,
            entryMode = "051",
            encryptedPinBlock,
            pinProfileId,
            ksn,
            emvTags = tags,
        };
        using var response = await client.PostAsJsonAsync(endpoint, request, cancellationToken);
        return await ReadResponseAsync(response, cancellationToken);
    }

    private static async Task<EmvOperationsResponse> ReadResponseAsync(
        HttpResponseMessage response,
        CancellationToken cancellationToken)
    {
        if (!response.IsSuccessStatusCode)
        {
            var error = await response.Content.ReadAsStringAsync(cancellationToken);
            throw new HttpRequestException($"EMV Operations API returned {(int)response.StatusCode}: {error}");
        }
        return await response.Content.ReadFromJsonAsync<EmvOperationsResponse>(
                   new JsonSerializerOptions { PropertyNameCaseInsensitive = true }, cancellationToken)
               ?? throw new InvalidDataException("The EMV Operations API returned an empty response.");
    }

    public void Dispose() => client.Dispose();
}

internal sealed record EmvOperationsResponse(
    string RequestId,
    string BinProfileId,
    bool Approved,
    string ResponseCode,
    Dictionary<string, string> EmvTags,
    string Field55);
