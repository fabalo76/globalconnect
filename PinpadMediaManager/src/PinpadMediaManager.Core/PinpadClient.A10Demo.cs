using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using PinpadMediaManager.Core.Models;
using PinpadMediaManager.Core.Protocol;

namespace PinpadMediaManager.Core;

public sealed partial class PinpadClient
{
    public async Task<A10DeviceProfile> GetA10DeviceProfileAsync(
        CancellationToken cancellationToken = default)
    {
        var serial = await ExecuteA10CommandAsync(
            PinpadFrameType.Administration, "06", "", "06", true, cancellationToken: cancellationToken);
        var versions = new List<string>(4);
        for (var part = 1; part <= 4; part++)
        {
            var response = await ExecuteA10CommandAsync(
                PinpadFrameType.Administration,
                "19",
                part.ToString(CultureInfo.InvariantCulture),
                "19",
                true,
                cancellationToken: cancellationToken);
            versions.Add(response.Payload);
        }

        var capabilities = await ExecuteA10CommandAsync(
            PinpadFrameType.Administration, "1C", "", "1C", true, cancellationToken: cancellationToken);
        return new A10DeviceProfile(
            serial.Payload,
            versions[0],
            versions[1],
            versions[2],
            versions[3],
            capabilities.Payload.Split(PinpadControl.Fs, StringSplitOptions.RemoveEmptyEntries));
    }

    public async Task TestA10ConnectionAsync(CancellationToken cancellationToken = default)
    {
        await ExecuteA10CommandAsync(
            PinpadFrameType.Administration,
            "11",
            "",
            expectedResponseCommand: null,
            readFinalEot: false,
            cancellationToken: cancellationToken);
    }

    public async Task<string> RequestA10RandomNumberAsync(CancellationToken cancellationToken = default)
    {
        var response = await ExecuteA10CommandAsync(
            PinpadFrameType.Administration, "17", "", "17", true, cancellationToken: cancellationToken);
        return response.Payload;
    }

    public async Task SetA10KeypadBeeperAsync(bool enabled, CancellationToken cancellationToken = default)
    {
        var expected = enabled ? "1" : "0";
        var response = await ExecuteA10CommandAsync(
            PinpadFrameType.Administration, "1M", expected, "1M", true, cancellationToken: cancellationToken);
        if (!string.Equals(response.Payload, expected, StringComparison.Ordinal))
        {
            throw new PinpadProtocolException($"The terminal rejected the keypad beeper setting ({response.Payload}).");
        }
    }

    public async Task SoundA10BeeperAsync(
        int count,
        int durationUnits,
        int intervalUnits,
        CancellationToken cancellationToken = default)
    {
        if (count is < 1 or > 9) throw new ArgumentOutOfRangeException(nameof(count));
        if (durationUnits is < 1 or > 99) throw new ArgumentOutOfRangeException(nameof(durationUnits));
        if (intervalUnits is < 0 or > 99) throw new ArgumentOutOfRangeException(nameof(intervalUnits));
        var payload = $"{PinpadControl.Sub}{count}{PinpadControl.Fs}{durationUnits}{PinpadControl.Fs}{intervalUnits}";
        var response = await ExecuteA10CommandAsync(
            PinpadFrameType.Administration, "1P", payload, "1P", true, cancellationToken: cancellationToken);
        if (response.Payload != "0")
        {
            throw new PinpadProtocolException($"The terminal rejected the beeper command ({response.Payload}).");
        }
    }

    public Task SetA10CancelMessageDisplayAsync(bool enabled, CancellationToken cancellationToken = default) =>
        ExecuteA10AckOnlyAsync("Z7", enabled ? "0" : "1", cancellationToken, PinpadFrameType.Transaction);

    public Task SetA10IdlePromptAsync(string text, CancellationToken cancellationToken = default) =>
        ExecuteA10AckOnlyAsync(
            "Z8", ValidateDisplayText(text), cancellationToken, PinpadFrameType.Transaction, utf8Payload: true);

    public Task DisplayA10MessageAsync(string text, CancellationToken cancellationToken = default) =>
        ExecuteA10AckOnlyAsync(
            "Z2", ValidateDisplayText(text), cancellationToken, PinpadFrameType.Transaction, utf8Payload: true);

    public Task DisplayA10PromptLinesAsync(IEnumerable<string> lines, CancellationToken cancellationToken = default)
    {
        var values = lines.Select(ValidateDisplayText).Where(value => value.Length > 0).Take(7).ToArray();
        if (values.Length == 0) throw new ArgumentException("Enter at least one prompt line.", nameof(lines));
        var payload = $"{values.Length}{PinpadControl.Sub}{string.Join(PinpadControl.Fs, values)}";
        return ExecuteA10AckOnlyAsync(
            "Z3", payload, cancellationToken, PinpadFrameType.Transaction, utf8Payload: true);
    }

    public async Task LoadA10ClearMasterKeyAsync(
        char keyId,
        string clearKeyHex,
        string usage,
        char mode,
        char algorithm,
        CancellationToken cancellationToken = default)
    {
        var key = NormalizeHex(clearKeyHex);
        if (key.Length is not (16 or 32 or 48))
            throw new ArgumentException("The clear key must contain 16, 32, or 48 hexadecimal characters.", nameof(clearKeyHex));
        keyId = char.ToUpperInvariant(keyId);
        if (!char.IsAsciiLetterOrDigit(keyId)) throw new ArgumentException("Key slot must be one letter or digit.", nameof(keyId));
        usage = usage.Trim().ToUpperInvariant();
        if (usage.Length != 2 || !usage.All(char.IsAsciiLetterOrDigit)) throw new ArgumentException("Key usage must contain two characters.", nameof(usage));
        mode = char.ToUpperInvariant(mode);
        algorithm = char.ToUpperInvariant(algorithm);
        if (!"BCDEGNSVX".Contains(mode)) throw new ArgumentException("Unsupported key mode.", nameof(mode));
        if (!"ADEHRST".Contains(algorithm)) throw new ArgumentException("Unsupported key algorithm.", nameof(algorithm));
        var payload = $"{keyId}{key}{PinpadControl.Fs}{usage}{mode}{algorithm}";
        var response = await ExecuteA10CommandAsync(PinpadFrameType.Administration, "02", payload, "02", true,
            cancellationToken: cancellationToken);
        if (response.Payload.StartsWith('?')) throw new PinpadProtocolException($"Key injection failed ({response.Payload}).");
    }

    public async Task LoadA10SecretMasterKeyAsync(
        int keyOption,
        string secretMasterKeyHex,
        CancellationToken cancellationToken = default)
    {
        if (keyOption is < 0 or > 1) throw new ArgumentOutOfRangeException(nameof(keyOption));
        var key = NormalizeSecretKeyHex(secretMasterKeyHex, nameof(secretMasterKeyHex));
        var payload = keyOption.ToString(CultureInfo.InvariantCulture) + key;
        await ExecuteA10CommandAsync(
            PinpadFrameType.Transaction,
            "20",
            payload,
            "20",
            true,
            TimeSpan.FromMinutes(2),
            cancellationToken,
            expectedResponsePayload: payload);
    }

    public async Task LoadA10SecretSessionKeyAsync(
        int keyId,
        string encryptedSessionKeyHex,
        CancellationToken cancellationToken = default)
    {
        if (keyId is < 0 or > 9) throw new ArgumentOutOfRangeException(nameof(keyId));
        var key = NormalizeSecretKeyHex(encryptedSessionKeyHex, nameof(encryptedSessionKeyHex));
        var payload = keyId.ToString(CultureInfo.InvariantCulture) + key;
        await ExecuteA10CommandAsync(
            PinpadFrameType.Transaction,
            "21",
            payload,
            "21",
            true,
            TimeSpan.FromMinutes(2),
            cancellationToken,
            expectedResponsePayload: payload);
    }

    public async Task<string> CheckA10MasterKeyAsync(char keyId, CancellationToken cancellationToken = default)
    {
        var response = await ExecuteA10CommandAsync(PinpadFrameType.Administration, "04", $"{char.ToUpperInvariant(keyId)}1", "04", true,
            cancellationToken: cancellationToken);
        return response.Payload;
    }

    public async Task<string?> GetA10MasterKeyKcvAsync(char keyId, CancellationToken cancellationToken = default)
    {
        var response = await ExecuteA10CommandAsync(
            PinpadFrameType.Transaction,
            "Z64",
            char.ToUpperInvariant(keyId).ToString(),
            "Z65",
            false,
            cancellationToken: cancellationToken);
        if (response.Payload.Length < 2 || response.Payload[1] == '?') return null;
        return response.Payload[1..];
    }

    public async Task SelectA10MasterKeyAsync(char keyId, CancellationToken cancellationToken = default)
    {
        var response = await ExecuteA10CommandAsync(PinpadFrameType.Administration, "08", char.ToUpperInvariant(keyId).ToString(), "08", true,
            cancellationToken: cancellationToken);
        if (response.Payload != "0") throw new PinpadProtocolException($"The terminal could not select that master key ({response.Payload}).");
    }

    public async Task LoadA10DukptInitialKeyAsync(int keySet, string initialKeyHex, string ksnHex, CancellationToken cancellationToken = default)
    {
        if (keySet is < 0 or > 1) throw new ArgumentOutOfRangeException(nameof(keySet));
        var key = NormalizeHex(initialKeyHex);
        var ksn = NormalizeHex(ksnHex);
        if (key.Length is not (16 or 32 or 48)) throw new ArgumentException("DUKPT initial key must contain 16, 32, or 48 hex characters.", nameof(initialKeyHex));
        if (ksn.Length != 20) throw new ArgumentException("KSN must contain 20 hex characters.", nameof(ksnHex));
        var command = keySet == 0 ? "90" : "94";
        var response = await ExecuteA10CommandAsync(PinpadFrameType.Transaction, command, key + ksn, "91", false,
            cancellationToken: cancellationToken);
        if (!response.Payload.StartsWith('0')) throw new PinpadProtocolException($"DUKPT key injection failed ({response.Payload}).");
    }

    public Task SelectA10DukptKeySetAsync(int keySet, CancellationToken cancellationToken = default)
    {
        if (keySet is < 0 or > 1) throw new ArgumentOutOfRangeException(nameof(keySet));
        return ExecuteA10AckOnlyAsync("96", keySet.ToString(CultureInfo.InvariantCulture), cancellationToken, PinpadFrameType.Transaction);
    }

    public async Task<string?> GetA10DukptKcvAsync(int keySet, CancellationToken cancellationToken = default)
    {
        if (keySet is < 0 or > 1) throw new ArgumentOutOfRangeException(nameof(keySet));
        var response = await ExecuteA10CommandAsync(
            PinpadFrameType.Transaction,
            "98",
            $"{keySet}1",
            "99",
            false,
            cancellationToken: cancellationToken);
        if (!response.Payload.StartsWith('F')) return null;
        var fields = response.Payload.Split(PinpadControl.Fs);
        return fields.Length >= 4 ? fields[3] : null;
    }

    public async Task<A10PinEntryResult> StartA10PinEntryAsync(A10PinEntryRequest request, CancellationToken cancellationToken = default)
    {
        if (request.TimeoutSeconds is < 30 or > 270) throw new ArgumentOutOfRangeException(nameof(request.TimeoutSeconds));
        if (request.MinimumLength is < 0 or > 12 || request.MaximumLength < request.MinimumLength || request.MaximumLength > 12)
            throw new ArgumentException("PIN length must be between 0 and 12 digits.");
        var command = request.PromptMode switch { A10PinPromptMode.Standard => "70", A10PinPromptMode.ExternalPrompt => "Z60", _ => "Z62" };
        var units = Math.Clamp((int)Math.Ceiling(request.TimeoutSeconds / 30d), 1, 9).ToString(CultureInfo.InvariantCulture);
        var account = new string(request.AccountNumber.Where(char.IsDigit).ToArray());
        if (account.Length is < 12 or > 19) throw new ArgumentException("Account number must contain 12-19 digits.");
        var session = request.KeyScheme == A10PinKeyScheme.MasterSession ? NormalizeHex(request.SessionKey) : "";
        if (request.KeyScheme == A10PinKeyScheme.MasterSession && session.Length is not (16 or 32 or 48))
            throw new ArgumentException("The encrypted session PIN key must contain 16, 32, or 48 hex characters.");
        var prefix = request.KeyScheme == A10PinKeyScheme.MasterSession ? "." : "";
        string payload;
        if (request.PromptMode == A10PinPromptMode.CustomPrompt)
        {
            var control = $"{request.MinimumLength:D2}{request.MaximumLength:D2}{(request.AllowNullPin ? 'Y' : 'N')}";
            payload = prefix + account + PinpadControl.Fs + session + control + ValidateDisplayText(request.FirstPrompt) +
                      PinpadControl.Fs + ValidateDisplayText(request.SecondPrompt) + PinpadControl.Fs +
                      ValidateDisplayText(request.CompletionPrompt) + PinpadControl.Fs + units;
        }
        else
        {
            payload = prefix + account + PinpadControl.Fs + session + PinpadControl.Fs + units;
        }
        try
        {
            var response = await ExecuteA10CommandAsync(PinpadFrameType.Transaction, command, payload, "71", false,
                TimeSpan.FromSeconds(request.TimeoutSeconds + 15), cancellationToken);
            return ParseA10PinResult(request.KeyScheme, response.Payload);
        }
        catch (PinpadProtocolException error) when (error.Message.Contains("0x04", StringComparison.OrdinalIgnoreCase))
        {
            return new A10PinEntryResult("Cancelled or timed out", "<EOT>", "", null, null, null);
        }
    }

    public async Task<A10PinEntryResult> StartA10NewPinEntryAsync(
        A10PinEntryRequest request,
        CancellationToken cancellationToken = default)
    {
        if (request.TimeoutSeconds is < 30 or > 270) throw new ArgumentOutOfRangeException(nameof(request.TimeoutSeconds));
        var account = new string(request.AccountNumber.Where(char.IsDigit).ToArray());
        if (account.Length is < 12 or > 19) throw new ArgumentException("Account number must contain 12-19 digits.");
        var session = request.KeyScheme == A10PinKeyScheme.MasterSession ? NormalizeHex(request.SessionKey) : "";
        if (request.KeyScheme == A10PinKeyScheme.MasterSession && session.Length is not (16 or 32 or 48))
            throw new ArgumentException("The encrypted session PIN key must contain 16, 32, or 48 hex characters.");
        var units = Math.Clamp((int)Math.Ceiling(request.TimeoutSeconds / 30d), 1, 9).ToString(CultureInfo.InvariantCulture);
        var payload = (request.KeyScheme == A10PinKeyScheme.MasterSession ? "." : "") +
                      account + PinpadControl.Fs + session + PinpadControl.Fs + units;
        var command = request.KeyScheme == A10PinKeyScheme.MasterSession ? "7G" : "7H";
        try
        {
            var response = await ExecuteA10CommandAsync(
                PinpadFrameType.Transaction,
                command,
                payload,
                "71",
                false,
                TimeSpan.FromSeconds(request.TimeoutSeconds * 2 + 30),
                cancellationToken);
            return ParseA10PinResult(request.KeyScheme, response.Payload);
        }
        catch (PinpadProtocolException error) when (error.Message.Contains("0x04", StringComparison.OrdinalIgnoreCase))
        {
            return new A10PinEntryResult("Cancelled or timed out", "<EOT>", "", null, null, null);
        }
    }

    public async Task<A10PinEntryResult> StartA10SecretPinEntryAsync(
        A10SecretPinEntryRequest request,
        CancellationToken cancellationToken = default)
    {
        if (request.SecretSessionKeyId is < 0 or > 9)
            throw new ArgumentOutOfRangeException(nameof(request.SecretSessionKeyId));
        var account = new string(request.AccountNumber.Where(char.IsDigit).ToArray());
        if (account.Length is < 8 or > 19)
            throw new ArgumentException("Account number must contain 8-19 digits.", nameof(request));
        var validPinBounds = request.AllowNullPin && request.MinimumLength == 0 && request.MaximumLength == 0 ||
                             request.MinimumLength is >= 4 and <= 12 &&
                             request.MaximumLength >= request.MinimumLength && request.MaximumLength <= 12;
        if (request.PromptMode == A10PinPromptMode.CustomPrompt && !validPinBounds)
            throw new ArgumentException("PIN length must be 4-12 digits, or 00/00 for a null-only PIN request.", nameof(request));

        var command = request.PromptMode switch
        {
            A10PinPromptMode.Standard => "22",
            A10PinPromptMode.ExternalPrompt => "23",
            A10PinPromptMode.CustomPrompt => "24",
            _ => throw new ArgumentOutOfRangeException(nameof(request)),
        };
        string payload;
        if (request.PromptMode == A10PinPromptMode.Standard)
        {
            var amount = request.Amount.Trim();
            if (amount.Length is > 0 and < 4 or > 14)
                throw new ArgumentException("Amount must be empty or contain 4-14 display characters.", nameof(request));
            payload = account + PinpadControl.Fs + request.SecretSessionKeyId + amount;
        }
        else if (request.PromptMode == A10PinPromptMode.ExternalPrompt)
        {
            await DisplayA10MessageAsync(ValidateSecretPinPrompt(request.FirstPrompt, nameof(request.FirstPrompt)), cancellationToken);
            payload = "." + account + PinpadControl.Fs + request.SecretSessionKeyId;
        }
        else
        {
            var control = $"{request.MinimumLength:D2}{request.MaximumLength:D2}{(request.AllowNullPin ? 'Y' : 'N')}";
            payload = "." + account + PinpadControl.Fs + request.SecretSessionKeyId + control +
                      ValidateSecretPinPrompt(request.FirstPrompt, nameof(request.FirstPrompt)) + PinpadControl.Fs +
                      ValidateSecretPinPrompt(request.SecondPrompt, nameof(request.SecondPrompt)) + PinpadControl.Fs +
                      ValidateSecretPinPrompt(request.CompletionPrompt, nameof(request.CompletionPrompt));
        }

        try
        {
            var response = await ExecuteA10CommandAsync(
                PinpadFrameType.Transaction,
                command,
                payload,
                "71",
                false,
                TimeSpan.FromSeconds(75),
                cancellationToken);
            return ParseA10PinResult(A10PinKeyScheme.MasterSession, response.Payload);
        }
        catch (PinpadProtocolException error) when (error.Message.Contains("0x04", StringComparison.OrdinalIgnoreCase))
        {
            return new A10PinEntryResult("Cancelled or timed out", "<EOT>", "", null, null, null);
        }
    }

    private async Task ExecuteA10AckOnlyAsync(
        string command,
        string payload,
        CancellationToken cancellationToken,
        PinpadFrameType type = PinpadFrameType.Administration,
        bool utf8Payload = false)
    {
        await ExecuteA10CommandAsync(
            type,
            command,
            payload,
            null,
            false,
            cancellationToken: cancellationToken,
            utf8Payload: utf8Payload);
    }

    private static string ValidateDisplayText(string text)
    {
        text ??= "";
        if (Encoding.UTF8.GetByteCount(text) > 512) throw new ArgumentException("Display text cannot exceed 512 UTF-8 bytes.");
        return text;
    }

    private static A10PinEntryResult ParseA10PinResult(A10PinKeyScheme scheme, string payload)
    {
        if (payload.Length == 1) return new A10PinEntryResult($"Terminal error {payload}", payload, "", null, null, null);
        if (scheme == A10PinKeyScheme.MasterSession)
        {
            var data = payload.TrimStart('.');
            if (data.Length < 5 || data[0] != '0') return new A10PinEntryResult("Invalid response", payload, "", null, null, null);
            _ = int.TryParse(data.Substring(1, 2), out var length);
            return new A10PinEntryResult("PIN captured", payload, data.Length > 5 ? data[5..] : "", null, length, data.Substring(3, 2));
        }
        if (!payload.StartsWith('0') || payload.Length < 37) return new A10PinEntryResult("Invalid response", payload, "", null, null, null);
        return new A10PinEntryResult("PIN captured", payload, payload[^16..], payload.Substring(1, payload.Length - 17), null, null);
    }

    public async Task ApplyA10EmvConfigurationFileAsync(
        A10EmvConfigurationType type,
        string path,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        var bytes = await File.ReadAllBytesAsync(path, cancellationToken);
        if (bytes.Length == 0 || bytes.Length > 256 * 1024)
        {
            throw new InvalidDataException("EMV configuration files must contain 1-262,144 bytes.");
        }

        var typeCode = type switch
        {
            A10EmvConfigurationType.DataFormats => 'D',
            A10EmvConfigurationType.Terminal => 'T',
            A10EmvConfigurationType.ContactCaKey => 'K',
            A10EmvConfigurationType.ContactApplication => 'A',
            A10EmvConfigurationType.ContactlessCaKey => 'R',
            A10EmvConfigurationType.ContactlessApplication => 'L',
            _ => throw new ArgumentOutOfRangeException(nameof(type)),
        };
        var payload = string.Concat(
            typeCode,
            PinpadControl.Fs,
            Convert.ToBase64String(Encoding.UTF8.GetBytes(Path.GetFileName(path))),
            PinpadControl.Fs,
            Convert.ToBase64String(bytes));
        var response = await ExecuteA10CommandAsync(
            PinpadFrameType.Transaction,
            "T90",
            payload,
            "T91",
            false,
            responseTimeout: TimeSpan.FromSeconds(60),
            cancellationToken: cancellationToken);
        if (!response.Payload.StartsWith('0'))
        {
            throw new PinpadProtocolException($"The terminal rejected {type}: {response.Payload}");
        }
    }

    public async Task<A10EmvConfigurationSnapshot> QueryA10EmvConfigurationAsync(
        CancellationToken cancellationToken = default)
    {
        var response = await ExecuteA10CommandAsync(
            PinpadFrameType.Transaction,
            "T92",
            "",
            "T93",
            false,
            cancellationToken: cancellationToken);
        var prefix = $"0{PinpadControl.Fs}";
        if (!response.Payload.StartsWith(prefix, StringComparison.Ordinal))
        {
            throw new PinpadProtocolException($"The terminal could not query the EMV configuration ({response.Payload}).");
        }

        try
        {
            var json = Encoding.UTF8.GetString(Convert.FromBase64String(response.Payload[prefix.Length..]));
            return JsonSerializer.Deserialize<A10EmvConfigurationSnapshot>(
                       json,
                       new JsonSerializerOptions { PropertyNameCaseInsensitive = true })
                   ?? throw new InvalidDataException("The terminal returned an empty EMV configuration response.");
        }
        catch (Exception error) when (error is FormatException or JsonException)
        {
            throw new InvalidDataException("The terminal returned an invalid EMV configuration response.", error);
        }
    }

    public async Task ClearAllA10EmvConfigurationAsync(CancellationToken cancellationToken = default)
    {
        var response = await ExecuteA10CommandAsync(
            PinpadFrameType.Transaction,
            "T94",
            "ALL",
            "T95",
            false,
            cancellationToken: cancellationToken);
        if (!response.Payload.StartsWith('0'))
        {
            throw new PinpadProtocolException($"The terminal could not clear the EMV configuration ({response.Payload}).");
        }
    }

    public async Task<A10EmvTransactionResult> RunA10ContactTransactionAsync(
        A10EmvTransactionRequest request,
        CancellationToken cancellationToken = default)
    {
        ValidateA10TransactionRequest(request);
        await ExecuteA10CommandAsync(
            PinpadFrameType.Transaction,
            "T11",
            "",
            "T12",
            readFinalEot: false,
            responseTimeout: TimeSpan.FromSeconds(120),
            cancellationToken: cancellationToken);

        var initial = await ExecuteA10CommandAsync(
            PinpadFrameType.Transaction,
            "T15",
            BuildA10TransactionPayload(request, contactless: false),
            "T16",
            readFinalEot: false,
            responseTimeout: TimeSpan.FromSeconds(120),
            cancellationToken: cancellationToken);

        var onlineData = "";
        var final = initial;
        if (IsOnlineRequest(initial.Payload))
        {
            onlineData = (await ExecuteA10CommandAsync(
                PinpadFrameType.Transaction, "T27", "", "T28", false,
                cancellationToken: cancellationToken)).Payload;
            final = await ExecuteA10CommandAsync(
                PinpadFrameType.Transaction,
                "T17",
                BuildHostResponse(request.HostDecision),
                "T16",
                readFinalEot: false,
                responseTimeout: TimeSpan.FromSeconds(120),
                cancellationToken: cancellationToken);
        }

        var tagResponse = await ExecuteA10CommandAsync(
            PinpadFrameType.Transaction,
            "T21",
            JoinTags("95", "5A", "9F34", "9B", "4F", "57", "9A", "9F26", "9F36"),
            "T22",
            false,
            cancellationToken: cancellationToken);
        await CompleteA10TransactionAsync(cancellationToken);
        return new A10EmvTransactionResult(
            "ICC",
            DescribeEmvStatus(final.Payload),
            initial.Payload,
            final.Payload,
            onlineData,
            ParseA10TagResponse(tagResponse.Payload));
    }

    public async Task<A10EmvTransactionResult> RunA10ContactlessTransactionAsync(
        A10EmvTransactionRequest request,
        CancellationToken cancellationToken = default)
    {
        ValidateA10TransactionRequest(request);
        var initial = await ExecuteA10CommandAsync(
            PinpadFrameType.Transaction,
            "T61",
            BuildA10TransactionPayload(request, contactless: true),
            "T62",
            readFinalEot: false,
            responseTimeout: TimeSpan.FromSeconds(120),
            cancellationToken: cancellationToken);

        var onlineData = "";
        var final = initial;
        if (IsOnlineRequest(initial.Payload))
        {
            onlineData = (await ExecuteA10CommandAsync(
                PinpadFrameType.Transaction, "T65", "0", "T66", false,
                cancellationToken: cancellationToken)).Payload;
            final = await ExecuteA10CommandAsync(
                PinpadFrameType.Transaction,
                "T71",
                BuildHostResponse(request.HostDecision),
                "T62",
                readFinalEot: false,
                responseTimeout: TimeSpan.FromSeconds(120),
                cancellationToken: cancellationToken);
        }

        var tagResponse = await ExecuteA10CommandAsync(
            PinpadFrameType.Transaction,
            "T63",
            "0" + JoinTags("95", "5A", "9F34", "9B", "9F06", "57", "9A", "9F26", "9F36"),
            "T64",
            false,
            cancellationToken: cancellationToken);
        await CompleteA10TransactionAsync(cancellationToken);
        return new A10EmvTransactionResult(
            "PCD",
            DescribeEmvStatus(final.Payload),
            initial.Payload,
            final.Payload,
            onlineData,
            ParseA10TagResponse(tagResponse.Payload));
    }

    /// <summary>Runs offline PIN verification, new-PIN capture, and PIN-change issuer-script processing.</summary>
    public Task<A10OfflinePinChangeResult> RunA10OfflinePinChangeAsync(
        A10OfflinePinChangeRequest request,
        CancellationToken cancellationToken = default) =>
        RunA10OfflinePinMaintenanceAsync(request, request.Api, '1', "changed", cancellationToken);

    /// <summary>Runs the no-CVM, MAC-only offline PIN-unblock issuer-script flow without capturing a PIN.</summary>
    public Task<A10OfflinePinChangeResult> RunA10OfflinePinUnblockAsync(
        A10OfflinePinUnblockRequest request,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        return RunA10OfflinePinMaintenanceAsync(null, request.Api, '2', "unblocked", cancellationToken);
    }

    private async Task<A10OfflinePinChangeResult> RunA10OfflinePinMaintenanceAsync(
        A10OfflinePinChangeRequest? changeRequest,
        EmvOperationsApiOptions apiOptions,
        char operation,
        string completedAction,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(apiOptions);
        if (operation == '1') ArgumentNullException.ThrowIfNull(changeRequest);
        var initial = await ExecuteA10CommandAsync(
            PinpadFrameType.Transaction,
            "T37",
            PinpadControl.Sub + operation.ToString(),
            "T38",
            readFinalEot: false,
            responseTimeout: TimeSpan.FromSeconds(120),
            cancellationToken: cancellationToken);
        var terminalClosed = false;
        try
        {
            if (!IsOnlineRequest(initial.Payload))
            {
                terminalClosed = true;
                return new A10OfflinePinChangeResult(
                    operation == '1'
                        ? "Current offline PIN verification did not produce an online request."
                        : "Offline PIN unblock did not produce an online request.",
                    initial.Payload, initial.Payload, "", "", "", new Dictionary<string, string>());
            }

            var onlineData = (await ExecuteA10CommandAsync(
                PinpadFrameType.Transaction, "T27", "", "T28", false,
                cancellationToken: cancellationToken)).Payload;
            var tags = ParseBerTlv(onlineData);
            var supplemental = await ExecuteA10CommandAsync(
                PinpadFrameType.Transaction,
                "T21",
                JoinTags("5A", "57", "5F34", "9F02", "9F03", "9F1A", "95", "5F2A", "9A", "9C", "9F37", "82", "9F36", "9F10", "9F26", "9F27", "9F34"),
                "T22",
                false,
                cancellationToken: cancellationToken);
            foreach (var item in ParseA10TagResponse(supplemental.Payload)) tags[item.Key] = item.Value;
            var pan = ExtractPan(tags);

            A10PinEntryResult? pin = null;
            if (operation == '1')
            {
                pin = await StartA10NewPinEntryAsync(new A10PinEntryRequest(
                    changeRequest!.PinKeyScheme,
                    A10PinPromptMode.Standard,
                    pan,
                    changeRequest.EncryptedSessionKey,
                    60,
                    4,
                    12,
                    false,
                    "ENTER NEW PIN",
                    "CONFIRM NEW PIN",
                    "PROCESSING"), cancellationToken);
                if (string.IsNullOrWhiteSpace(pin.EncryptedPinBlock))
                {
                    return new A10OfflinePinChangeResult(
                        pin.Status, initial.Payload, pin.RawResponse, pin.RawResponse, "", "", tags);
                }
            }

            using var api = new EmvOperationsApiClient(apiOptions);
            EmvOperationsResponse host;
            if (operation == '2')
            {
                host = await api.UnblockOfflinePinAsync(
                    Guid.NewGuid().ToString(),
                    pan,
                    tags.TryGetValue("5F34", out var unblockSequence) ? unblockSequence : "00",
                    tags,
                    cancellationToken);
            }
            else
            {
                host = await api.ChangeOfflinePinAsync(
                    Guid.NewGuid().ToString(),
                    pan,
                    tags.TryGetValue("5F34", out var sequence) ? sequence : "00",
                    pin!.EncryptedPinBlock,
                    changeRequest!.PinProfileId,
                    pin.Ksn,
                    tags,
                    cancellationToken);
            }
            if (!host.EmvTags.TryGetValue("71", out var scriptValue) ||
                !host.EmvTags.TryGetValue("91", out var issuerAuthenticationData))
            {
                throw new InvalidDataException("The EMV Operations API response did not contain tags 71 and 91.");
            }

            var scriptTlv = EncodeTlv("71", scriptValue);
            var scriptResponse = await ExecuteA10CommandAsync(
                PinpadFrameType.Transaction, "T19", scriptTlv, "T20", false,
                cancellationToken: cancellationToken);
            if (!scriptResponse.Payload.StartsWith('0'))
                throw new PinpadProtocolException($"The terminal rejected the issuer script ({scriptResponse.Payload}).");

            var final = await ExecuteA10CommandAsync(
                PinpadFrameType.Transaction,
                "T17",
                $"1{PinpadControl.Sub}{host.ResponseCode}{PinpadControl.Sub}{EncodeTlv("91", issuerAuthenticationData)}",
                "T38",
                readFinalEot: false,
                responseTimeout: TimeSpan.FromSeconds(120),
                cancellationToken: cancellationToken);
            var completion = await ExecuteA10CommandAsync(
                PinpadFrameType.Transaction,
                "T21",
                JoinTags("95", "9B", "9F27", "9F34", "9F26", "9F36"),
                "T22",
                false,
                cancellationToken: cancellationToken);
            var completionTags = ParseA10TagResponse(completion.Payload);

            // T17 completes the ICC transaction and owns the terminal's sensory/success display.
            // Q2 is an MSR completion command; sending it here replaces that display with THANK YOU.
            terminalClosed = true;
            return new A10OfflinePinChangeResult(
                final.Payload.StartsWith("0V0", StringComparison.Ordinal)
                    ? $"Offline PIN {completedAction}"
                    : $"Offline PIN {(operation == '2' ? "unblock" : "change")} failed ({final.Payload})",
                initial.Payload,
                final.Payload,
                pin?.RawResponse ?? "",
                host.ResponseCode,
                host.Field55,
                completionTags);
        }
        finally
        {
            if (!terminalClosed)
            {
                try
                {
                    await ExecuteA10CommandAsync(
                        PinpadFrameType.Transaction,
                        "T1C",
                        "",
                        expectedResponseCommand: null,
                        readFinalEot: false,
                        cancellationToken: CancellationToken.None);
                }
                catch
                {
                    // Preserve the original operation failure when best-effort terminal cleanup also fails.
                }
            }
        }
    }

    /// <summary>Runs the self-contained T37 offline-PIN verification operation.</summary>
    public Task<A10CommandResponse> VerifyA10OfflinePinAsync(CancellationToken cancellationToken = default) =>
        RunA10OfflinePinManagementAsync('3', cancellationToken);

    /// <summary>
    /// Starts the T37 offline-PIN unblock operation. A response of 0A1 means that the
    /// host must provide the issuer's unblock script before the EMV transaction can finish.
    /// </summary>
    public Task<A10CommandResponse> StartA10OfflinePinUnblockAsync(CancellationToken cancellationToken = default) =>
        RunA10OfflinePinManagementAsync('2', cancellationToken);

    /// <summary>Cancels ICC processing, PIN entry, and finally restores the terminal idle state.</summary>
    public async Task CancelA10PinManagementAsync(CancellationToken cancellationToken = default)
    {
        foreach (var command in new[] { "T1C", "72", "Z1" })
        {
            await ExecuteA10CommandAsync(
                PinpadFrameType.Transaction,
                command,
                "",
                expectedResponseCommand: null,
                readFinalEot: false,
                cancellationToken: cancellationToken);
        }
    }

    private Task<A10CommandResponse> RunA10OfflinePinManagementAsync(
        char operation,
        CancellationToken cancellationToken) =>
        ExecuteA10CommandAsync(
            PinpadFrameType.Transaction,
            "T37",
            PinpadControl.Sub + operation.ToString(),
            "T38",
            readFinalEot: false,
            responseTimeout: TimeSpan.FromSeconds(120),
            cancellationToken: cancellationToken);

    public async Task<A10SmartCardResponse> CheckA10SmartCardAsync(CancellationToken cancellationToken = default) =>
        ParseSmartCardResponse(await ExecuteA10CommandAsync(
            PinpadFrameType.Transaction, "I00", "", "I00", false, cancellationToken: cancellationToken));

    public async Task<A10SmartCardResponse> ResetA10SmartCardAsync(CancellationToken cancellationToken = default) =>
        ParseSmartCardResponse(await ExecuteA10CommandAsync(
            PinpadFrameType.Transaction, "I01", "", "I02", false, cancellationToken: cancellationToken));

    public async Task<A10SmartCardResponse> DeactivateA10SmartCardAsync(CancellationToken cancellationToken = default) =>
        ParseSmartCardResponse(await ExecuteA10CommandAsync(
            PinpadFrameType.Transaction, "I04", "", "I04", false, cancellationToken: cancellationToken));

    public async Task<A10SmartCardResponse> ExchangeA10SmartCardApduAsync(
        string apduHex,
        CancellationToken cancellationToken = default)
    {
        var normalized = NormalizeHex(apduHex);
        return ParseSmartCardResponse(await ExecuteA10CommandAsync(
            PinpadFrameType.Transaction, "I06", normalized, "I07", false, cancellationToken: cancellationToken));
    }

    public Task<A10CommandResponse> ExecuteA10RawCommandAsync(
        PinpadFrameType type,
        string command,
        string payload,
        string? expectedResponseCommand,
        bool readFinalEot,
        CancellationToken cancellationToken = default) =>
        ExecuteA10CommandAsync(
            type,
            command,
            payload,
            expectedResponseCommand,
            readFinalEot,
            cancellationToken: cancellationToken);

    private async Task CompleteA10TransactionAsync(CancellationToken cancellationToken)
    {
        await ExecuteA10CommandAsync(
            PinpadFrameType.Transaction,
            "Q2",
            "",
            expectedResponseCommand: null,
            readFinalEot: false,
            cancellationToken: cancellationToken);
    }

    private async Task<A10CommandResponse> ExecuteA10CommandAsync(
        PinpadFrameType type,
        string command,
        string payload,
        string? expectedResponseCommand,
        bool readFinalEot,
        TimeSpan? responseTimeout = null,
        CancellationToken cancellationToken = default,
        string? expectedResponsePayload = null,
        bool utf8Payload = false)
    {
        await _operationGate.WaitAsync(cancellationToken);
        try
        {
            return await Task.Run(
                () => ExecuteA10Command(
                    type,
                    command,
                    payload,
                    expectedResponseCommand,
                    readFinalEot,
                    responseTimeout ?? TimeSpan.FromSeconds(15),
                    cancellationToken,
                    expectedResponsePayload,
                    utf8Payload),
                cancellationToken);
        }
        finally
        {
            _operationGate.Release();
        }
    }

    private A10CommandResponse ExecuteA10Command(
        PinpadFrameType type,
        string command,
        string payload,
        string? expectedResponseCommand,
        bool readFinalEot,
        TimeSpan responseTimeout,
        CancellationToken cancellationToken,
        string? expectedResponsePayload,
        bool utf8Payload)
    {
        if (!_transport.IsOpen) throw new InvalidOperationException("Connect to a pinpad first.");
        _transport.DiscardInput();
        var request = new PinpadFrame(
            type,
            command,
            utf8Payload ? Encoding.UTF8.GetBytes(payload) : Encoding.ASCII.GetBytes(payload));
        var encoded = PinpadFrameCodec.Encode(request);
        for (var attempt = 1; attempt <= 3; attempt++)
        {
            WriteWithTrace(encoded);
            var control = ReadControl(AcknowledgementTimeout(encoded.Length), cancellationToken);
            if (control == PinpadControl.Ack) break;
            if (control != PinpadControl.Nak || attempt == 3)
            {
                throw new PinpadProtocolException(
                    $"The terminal did not acknowledge {command}; received 0x{control:X2}.");
            }
        }

        if (expectedResponseCommand is null)
        {
            return new A10CommandResponse(command, "");
        }

        var response = ReadFrame(responseTimeout, cancellationToken);
        if (!string.Equals(response.CommandId, expectedResponseCommand, StringComparison.Ordinal))
        {
            throw new PinpadProtocolException(
                $"Expected response {expectedResponseCommand}, received {response.CommandId}.");
        }
        if (expectedResponsePayload is not null &&
            !CryptographicOperations.FixedTimeEquals(response.Payload, Encoding.ASCII.GetBytes(expectedResponsePayload)))
        {
            WriteWithTrace([PinpadControl.Eot]);
            throw new PinpadProtocolException($"The {command} key echo did not match the request; key loading was cancelled.");
        }
        WriteWithTrace([PinpadControl.Ack]);
        if (readFinalEot) ReadExpectedEot(expectedResponseCommand, cancellationToken);
        response = NormalizeA10Response(response);
        return new A10CommandResponse(response.CommandId, response.PayloadAscii);
    }

    private static PinpadFrame NormalizeA10Response(PinpadFrame response)
    {
        if (response.CommandId == "19" && response.Payload.Length > 0 && response.Payload[0] == (byte)'.')
        {
            return response with { Payload = response.Payload[1..] };
        }
        return response;
    }

    private static string BuildA10TransactionPayload(A10EmvTransactionRequest request, bool contactless)
    {
        var amount = ToMinorUnits(request.Amount);
        var cashback = contactless && request.CashbackAmount == 0
            ? "FFFFFFFFFFFF"
            : ToMinorUnits(request.CashbackAmount);
        var sessionKey = request.OnlinePinKeyScheme == A10PinKeyScheme.MasterSession
            ? NormalizeOptionalSessionKey(request.EncryptedSessionKey)
            : "";
        return string.Join(
            PinpadControl.Sub,
            "",
            amount,
            cashback,
            request.CurrencyCode,
            request.TransactionType,
            request.TransactionInformation,
            request.AccountType,
            request.ForceOnline ? "1" : "0",
            sessionKey,
            request.OnlinePinKeyScheme == A10PinKeyScheme.Dukpt ? "1" : "0");
    }

    private static string BuildHostResponse(A10HostDecision decision) => decision switch
    {
        A10HostDecision.Approve => $"1{PinpadControl.Sub}00{PinpadControl.Sub}",
        A10HostDecision.Decline => $"1{PinpadControl.Sub}05{PinpadControl.Sub}",
        A10HostDecision.NoResponse => "0",
        _ => throw new ArgumentOutOfRangeException(nameof(decision)),
    };

    private static string JoinTags(params string[] tags) => string.Join(PinpadControl.Sub, tags);

    private static string ToMinorUnits(decimal amount)
    {
        if (amount is < 0 or > 9_999_999_999.99m) throw new ArgumentOutOfRangeException(nameof(amount));
        return decimal.Round(amount * 100m, 0, MidpointRounding.AwayFromZero)
            .ToString("000000000000", CultureInfo.InvariantCulture);
    }

    private static void ValidateA10TransactionRequest(A10EmvTransactionRequest request)
    {
        _ = ToMinorUnits(request.Amount);
        _ = ToMinorUnits(request.CashbackAmount);
        if (request.CurrencyCode.Length != 4 || !request.CurrencyCode.All(char.IsDigit))
            throw new ArgumentException("Currency code must contain the exponent plus ISO numeric code, for example 2840.");
        foreach (var value in new[] { request.TransactionType, request.TransactionInformation, request.AccountType })
        {
            if (value.Length != 2 || !value.All(Uri.IsHexDigit))
                throw new ArgumentException("Transaction and account codes must contain two hexadecimal characters.");
        }
        if (!Enum.IsDefined(request.OnlinePinKeyScheme))
            throw new ArgumentOutOfRangeException(nameof(request.OnlinePinKeyScheme));
        if (request.OnlinePinKeyScheme == A10PinKeyScheme.MasterSession)
            _ = NormalizeOptionalSessionKey(request.EncryptedSessionKey);
    }

    private static bool IsOnlineRequest(string payload) => payload.StartsWith("0A1", StringComparison.Ordinal);

    private static string DescribeEmvStatus(string payload) => payload.Length >= 3 ? payload[..3] switch
    {
        "0Y1" => "Offline Approved",
        "0Z1" => "Offline Declined",
        "0Y3" => "Unable Online, Offline Approved",
        "0Z3" => "Unable Online, Offline Declined",
        "0Y4" => "Online Approved",
        "0Z4" => "Online Declined",
        "0A1" => "Online Authorization Required",
        "0A4" => "Application Blocked",
        _ when payload.StartsWith('1') => $"Error ({payload})",
        _ => payload,
    } : payload;

    private static IReadOnlyDictionary<string, string> ParseA10TagResponse(string payload)
    {
        var result = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var row in payload.TrimStart('0', '1', PinpadControl.Fs, PinpadControl.Sub)
                     .Split(PinpadControl.Sub, StringSplitOptions.RemoveEmptyEntries))
        {
            var fields = row.Split(PinpadControl.Fs);
            if (fields.Length >= 3 && fields[0].All(Uri.IsHexDigit))
                result[fields[0]] = fields[2];
        }
        return result;
    }

    private static Dictionary<string, string> ParseBerTlv(string hexadecimal)
    {
        var result = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var data = Convert.FromHexString(string.Concat(hexadecimal.Where(character => !char.IsWhiteSpace(character))));
        var offset = 0;
        while (offset < data.Length)
        {
            var tagStart = offset++;
            if ((data[tagStart] & 0x1F) == 0x1F)
            {
                while (offset < data.Length && (data[offset++] & 0x80) != 0) { }
            }
            var tagEnd = offset;
            if (offset >= data.Length) throw new InvalidDataException("The terminal returned malformed EMV TLV data.");
            var length = (int)data[offset++];
            if ((length & 0x80) != 0)
            {
                var count = length & 0x7F;
                if (count is < 1 or > 2 || offset + count > data.Length) throw new InvalidDataException("The terminal returned an unsupported EMV length.");
                length = 0;
                for (var index = 0; index < count; index++) length = (length << 8) | data[offset++];
            }
            if (offset + length > data.Length) throw new InvalidDataException("The terminal returned truncated EMV TLV data.");
            result[Convert.ToHexString(data[tagStart..tagEnd])] = Convert.ToHexString(data.AsSpan(offset, length));
            offset += length;
        }
        return result;
    }

    private static string ExtractPan(IReadOnlyDictionary<string, string> tags)
    {
        var pan = tags.TryGetValue("5A", out var tag5A)
            ? tag5A.TrimEnd('F')
            : tags.TryGetValue("57", out var tag57)
                ? tag57.Split('D', 2)[0].TrimEnd('F')
                : "";
        if (pan.Length is < 13 or > 19 || !pan.All(char.IsDigit))
            throw new InvalidDataException("The terminal did not return a valid PAN in tag 5A or 57.");
        return pan;
    }

    private static string EncodeTlv(string tag, string value)
    {
        var bytes = value.Length / 2;
        var length = bytes < 0x80 ? bytes.ToString("X2") : "81" + bytes.ToString("X2");
        return tag + length + value;
    }

    private static A10SmartCardResponse ParseSmartCardResponse(A10CommandResponse response)
    {
        if (response.Command is "I02" or "I04" or "I07" or "I12" or "I14" or "I15" or "I17")
        {
            return new A10SmartCardResponse(response.Command, "OK", response.Payload);
        }
        var status = response.Payload.FirstOrDefault() switch
        {
            '0' or 'F' => "OK",
            '1' => "Card not ready",
            '2' => "Invalid APDU",
            '3' => "Card not powered",
            '4' => "Invalid data",
            '5' => "Interface error",
            '6' => "Timeout",
            '7' => "Reset failed",
            _ => "Unknown",
        };
        return new A10SmartCardResponse(
            response.Command,
            status,
            response.Payload.Length > 1 ? response.Payload[1..] : "");
    }

    private static string NormalizeHex(string value)
    {
        var normalized = string.Concat(value.Where(ch => !char.IsWhiteSpace(ch))).ToUpperInvariant();
        if (normalized.Length == 0 || normalized.Length % 2 != 0 || !normalized.All(Uri.IsHexDigit))
            throw new ArgumentException("APDU must contain an even number of hexadecimal characters.", nameof(value));
        return normalized;
    }

    private static string NormalizeSecretKeyHex(string value, string parameterName)
    {
        var normalized = string.Concat(value.Where(ch => !char.IsWhiteSpace(ch))).ToUpperInvariant();
        if (normalized.Length is not (16 or 32) || !normalized.All(Uri.IsHexDigit))
            throw new ArgumentException("Secret keys must contain 16 or 32 hexadecimal characters.", parameterName);
        return normalized;
    }

    private static string NormalizeOptionalSessionKey(string value)
    {
        var normalized = string.Concat((value ?? "").Where(ch => !char.IsWhiteSpace(ch))).ToUpperInvariant();
        if (normalized.Length != 0 &&
            (normalized.Length is not (16 or 32 or 48) || !normalized.All(Uri.IsHexDigit)))
        {
            throw new ArgumentException(
                "The encrypted session PIN key must be empty or contain 16, 32, or 48 hexadecimal characters.",
                nameof(value));
        }
        return normalized;
    }

    private static string ValidateSecretPinPrompt(string value, string parameterName)
    {
        value = value.Trim();
        if (value.Length is < 1 or > 16)
            throw new ArgumentException("Secret PIN prompts must contain 1-16 characters.", parameterName);
        return value;
    }
}


