using System.Text.Json;

namespace GlobalConnect.KeyInjection.App;

internal sealed record OperatorSettings(string? PortName, int TimeoutMs)
{
    public static OperatorSettings Default { get; } = new(null, 5000);
}

internal static class OperatorSettingsStore
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    internal static string SettingsPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "GlobalConnect",
        "KeyInjection",
        "settings.json");

    public static OperatorSettings Load()
    {
        try
        {
            if (!File.Exists(SettingsPath)) return OperatorSettings.Default;
            var settings = JsonSerializer.Deserialize<OperatorSettings>(File.ReadAllBytes(SettingsPath), JsonOptions);
            if (settings is null || settings.TimeoutMs is < 500 or > 120000)
                return OperatorSettings.Default;
            return settings;
        }
        catch (IOException) { return OperatorSettings.Default; }
        catch (UnauthorizedAccessException) { return OperatorSettings.Default; }
        catch (JsonException) { return OperatorSettings.Default; }
    }

    public static bool TrySave(string? portName, int timeoutMs)
    {
        string? temporaryPath = null;
        try
        {
            var settings = new OperatorSettings(portName, Math.Clamp(timeoutMs, 500, 120000));
            var directory = Path.GetDirectoryName(SettingsPath)!;
            Directory.CreateDirectory(directory);
            temporaryPath = SettingsPath + ".tmp-" + Guid.NewGuid().ToString("N");
            File.WriteAllBytes(temporaryPath, JsonSerializer.SerializeToUtf8Bytes(settings, JsonOptions));
            File.Move(temporaryPath, SettingsPath, true);
            return true;
        }
        catch (IOException) { return false; }
        catch (UnauthorizedAccessException) { return false; }
        finally
        {
            if (temporaryPath is not null)
            {
                try { if (File.Exists(temporaryPath)) File.Delete(temporaryPath); }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
            }
        }
    }
}
