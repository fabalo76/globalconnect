using System.Text.Json;

namespace PinpadMediaManager;

internal sealed record ConnectionPreferences(
    string ConnectionType,
    string SerialPort,
    int BaudRate,
    string Host,
    int TcpPort)
{
    public static ConnectionPreferences Default { get; } =
        new("Serial", "", 9_600, "", 9_100);
}

internal static class ConnectionPreferencesStore
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
    };

    private static string SettingsPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Global Connect ONE",
        "Pinpad Demo",
        "connection-settings.json");

    public static ConnectionPreferences Load()
    {
        try
        {
            if (!File.Exists(SettingsPath)) return ConnectionPreferences.Default;
            return JsonSerializer.Deserialize<ConnectionPreferences>(File.ReadAllText(SettingsPath), JsonOptions)
                   ?? ConnectionPreferences.Default;
        }
        catch (Exception)
        {
            return ConnectionPreferences.Default;
        }
    }

    public static void Save(ConnectionPreferences preferences)
    {
        try
        {
            var directory = Path.GetDirectoryName(SettingsPath)!;
            Directory.CreateDirectory(directory);
            var temporaryPath = SettingsPath + ".tmp";
            File.WriteAllText(temporaryPath, JsonSerializer.Serialize(preferences, JsonOptions));
            File.Move(temporaryPath, SettingsPath, overwrite: true);
        }
        catch (Exception)
        {
            // Remembering a connection is optional and must never prevent use or shutdown.
        }
    }
}
