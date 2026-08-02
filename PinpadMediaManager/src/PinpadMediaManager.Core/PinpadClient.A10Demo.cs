using System.Globalization;
using System.Text;
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
        ExecuteA10AckOnlyAsync("Z8", ValidateDisplayText(text), cancellationToken, PinpadFrameType.Transaction);

    public Task DisplayA10MessageAsync(string text, CancellationToken cancellationToken = default) =>
        ExecuteA10AckOnlyAsync("Z2", ValidateDisplayText(text), cancellationToken, PinpadFrameType.Transaction);

    public Task DisplayA10PromptLinesAsync(IEnumerable<string> lines, CancellationToken cancellationToken = default)
    {
        var values = lines.Select(ValidateDisplayText).Where(value => value.Length > 0).Take(7).ToArray();
        if (values.Length == 0) throw new ArgumentException("Enter at least one prompt line.", nameof(lines));
        var payload = $"{values.Length}{PinpadControl.Sub}{string.Join(PinpadControl.Fs, values)}";
        return ExecuteA10AckOnlyAsync("Z3", payload, cancellationToken, PinpadFrameType.Transaction);
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

    private async Task ExecuteA10AckOnlyAsync(string command, string payload, CancellationToken cancellationToken, PinpadFrameType type = PinpadFrameType.Administration)
    {
        await ExecuteA10CommandAsync(type, command, payload, null, false, cancellationToken: cancellationToken);
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
        CancellationToken cancellationToken = default)
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
                    cancellationToken),
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
        CancellationToken cancellationToken)
    {
        if (!_transport.IsOpen) throw new InvalidOperationException("Connect to a pinpad first.");
        _transport.DiscardInput();
        var encoded = PinpadFrameCodec.Encode(PinpadFrame.Ascii(type, command, payload));
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
            "");
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
}


