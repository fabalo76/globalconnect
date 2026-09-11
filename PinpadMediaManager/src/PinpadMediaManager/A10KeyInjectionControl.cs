using System.Security.Cryptography;
using System.Text.Json;
using PinpadMediaManager.Core.Protocol;

namespace PinpadMediaManager;

internal sealed partial class A10DemoControl
{
    private const string KeySelectedColumn = "Selected";
    private const string KeyIdColumn = "KeyId";
    private const string KeyMaterialColumn = "KeyMaterial";
    private const string KeyAlgorithmColumn = "Algorithm";
    private const string KeyUsageColumn = "Usage";
    private const string KeyModeColumn = "Mode";
    private const string KeyVersionColumn = "Version";
    private const string KeyResultColumn = "Result";
    private const string KeyKcvColumn = "Kcv";
    private static readonly JsonSerializerOptions KeyJsonOptions = new() { WriteIndented = true };

    private static string PersistentKeySettingsPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Global Connect ONE",
        "Pinpad Demo",
        "key-injection-test-settings.json");

    private Control BuildKeyInjectionPage()
    {
        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            RowCount = 3,
            Padding = new Padding(8),
        };
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 220));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 62));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 38));

        var showKeys = new CheckBox { Text = "Show clear key material", AutoSize = true, Checked = true };
        var sessionKey = SharedSessionKeyEditor();
        var activeMaster = Combo("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
        var activeDukpt = Combo("0", "1");
        sessionKey.Width = 330;
        sessionKey.Dock = DockStyle.None;
        activeMaster.Width = 65;
        activeDukpt.Width = 65;
        activeMaster.Dock = DockStyle.None;
        activeDukpt.Dock = DockStyle.None;
        var toolbar = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, WrapContents = true };
        toolbar.Controls.Add(new Label { Text = "Session key", AutoSize = true, Margin = new Padding(3, 9, 4, 0) });
        toolbar.Controls.Add(sessionKey);
        toolbar.Controls.Add(CreateKeyLengthLabel(sessionKey));
        toolbar.Controls.Add(showKeys);
        toolbar.Controls.Add(new Label { Text = "Active master key", AutoSize = true, Margin = new Padding(14, 9, 4, 0) });
        toolbar.Controls.Add(activeMaster);
        toolbar.Controls.Add(new Label { Text = "Active DUKPT set", AutoSize = true, Margin = new Padding(14, 9, 4, 0) });
        toolbar.Controls.Add(activeDukpt);
        root.Controls.Add(toolbar, 0, 0);
        root.SetColumnSpan(toolbar, 2);

        var masterGrid = BuildMasterKeyGrid(showKeys);
        var dukptGrid = BuildDukptKeyGrid(showKeys);
        showKeys.CheckedChanged += (_, _) =>
        {
            masterGrid.Invalidate();
            dukptGrid.Invalidate();
        };

        root.Controls.Add(masterGrid, 0, 1);
        root.Controls.Add(dukptGrid, 0, 2);

        var actions = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            AutoScroll = true,
            Padding = new Padding(8, 0, 0, 0),
            BorderStyle = BorderStyle.FixedSingle,
        };
        actions.Controls.Add(LocalButton("Check all keys", () =>
        {
            SetAllSelections(masterGrid, true);
            SetAllSelections(dukptGrid, true);
        }));
        actions.Controls.Add(LocalButton("Uncheck all keys", () =>
        {
            SetAllSelections(masterGrid, false);
            SetAllSelections(dukptGrid, false);
        }));
        actions.Controls.Add(ActionButton("Load selected master keys", () =>
            LoadSelectedMasterKeysAsync(masterGrid)));
        actions.Controls.Add(ActionButton("Check selected master keys", () =>
            CheckSelectedMasterKeysAsync(masterGrid)));
        actions.Controls.Add(ActionButton("Load selected DUKPT keys", () =>
            LoadSelectedDukptKeysAsync(dukptGrid)));
        actions.Controls.Add(ActionButton("Check selected DUKPT keys", () =>
            CheckSelectedDukptKeysAsync(dukptGrid)));
        actions.Controls.Add(LocalButton("Read configuration", () =>
            ReadKeyConfiguration(masterGrid, dukptGrid, sessionKey, activeMaster, activeDukpt)));
        actions.Controls.Add(LocalButton("Save configuration", () =>
            SaveKeyConfiguration(masterGrid, dukptGrid, sessionKey, activeMaster, activeDukpt)));
        root.Controls.Add(actions, 1, 1);
        root.SetRowSpan(actions, 2);

        InitializeKeyPersistence(root, masterGrid, dukptGrid, sessionKey, activeMaster, activeDukpt);
        activeMaster.SelectionChangeCommitted += async (_, _) =>
            await RunAsync(() => _client.SelectA10MasterKeyAsync(activeMaster.Text[0]));
        activeDukpt.SelectionChangeCommitted += async (_, _) =>
            await RunAsync(() => _client.SelectA10DukptKeySetAsync(activeDukpt.SelectedIndex));
        _actionControls.Add(activeMaster);
        _actionControls.Add(activeDukpt);

        return root;
    }

    private static DataGridView BuildMasterKeyGrid(CheckBox showKeys)
    {
        var grid = BaseKeyGrid();
        grid.Columns.Add(new DataGridViewCheckBoxColumn { Name = KeySelectedColumn, HeaderText = "Select", Width = 52 });
        grid.Columns.Add(new DataGridViewTextBoxColumn { Name = KeyIdColumn, HeaderText = "Master key", ReadOnly = true, Width = 82 });
        grid.Columns.Add(new DataGridViewTextBoxColumn { Name = KeyMaterialColumn, HeaderText = "Clear master key", AutoSizeMode = DataGridViewAutoSizeColumnMode.Fill, MinimumWidth = 230 });
        grid.Columns.Add(ComboColumn(KeyAlgorithmColumn, "Alg", 48, "T", "A", "D"));
        grid.Columns.Add(ComboColumn(KeyUsageColumn, "Usage", 58, "K0", "P0", "M3", "K1", "D0"));
        grid.Columns.Add(ComboColumn(KeyModeColumn, "Mode", 52, "D", "E", "G", "B"));
        grid.Columns.Add(ComboColumn(KeyVersionColumn, "Ver", 50, "01"));
        grid.Columns.Add(new DataGridViewTextBoxColumn { Name = KeyResultColumn, HeaderText = "Result", ReadOnly = true, Width = 92 });
        grid.Columns.Add(new DataGridViewTextBoxColumn { Name = KeyKcvColumn, HeaderText = "KCV", ReadOnly = true, Width = 72 });

        var defaultKeys = new Dictionary<char, string>
        {
            ['0'] = "0123456789ABCDE90123456789ABCDEF",
            ['1'] = "123456789ABCDE90123456789ABCDEF0",
            ['2'] = "23456789ABCDE90123456789ABCDEF01",
            ['3'] = "3456789ABCDE90123456789ABCDEF012",
            ['4'] = "456789ABCDE90123456789ABCDEF0123",
            ['5'] = "56789ABCDE90123456789ABCDEF01234",
            ['6'] = "6789ABCDE90123456789ABCDEF012345",
            ['7'] = "789ABCDE90123456789ABCDEF0123456",
            ['8'] = "89ABCDE90123456789ABCDEF01234567",
            ['9'] = "9ABCDE90123456789ABCDEF012345678",
            ['B'] = "CDE90123456789ABCDEF0123456789AB",
            ['C'] = "DE90123456789ABCDEF0123456789ABC",
            ['D'] = "E90123456789ABCDEF0123456789ABCD",
            ['E'] = "90123456789ABCDEF0123456789ABCDE",
            ['F'] = "0123456789ABCDEF0123456789ABCDE0",
            ['G'] = "123456789ABCDEF0123456789ABCDF01",
        };
        foreach (var keyId in "0123456789BCDEFG")
        {
            var usage = keyId switch
            {
                >= '0' and <= '9' => keyId == '1' ? "P0" : "K0",
                >= 'B' and <= 'E' => "M3",
                'F' => "K1",
                'G' => "D0",
                _ => "K0",
            };
            var mode = usage switch { "P0" or "D0" => "E", "M3" => "G", _ => "D" };
            var key = defaultKeys[keyId];
            grid.Rows.Add(true, keyId.ToString(), key, "T", usage, mode, "01", "", CalculateKeyKcv(key, "T"));
        }
        ConfigureKeyMasking(grid, showKeys);
        return grid;
    }

    private static DataGridView BuildDukptKeyGrid(CheckBox showKeys)
    {
        var grid = BaseKeyGrid();
        grid.Columns.Add(new DataGridViewCheckBoxColumn { Name = KeySelectedColumn, HeaderText = "Select", Width = 52 });
        grid.Columns.Add(new DataGridViewTextBoxColumn { Name = KeyIdColumn, HeaderText = "DUKPT set", ReadOnly = true, Width = 80 });
        grid.Columns.Add(new DataGridViewTextBoxColumn { Name = KeyMaterialColumn, HeaderText = "TDES DUKPT initial key", AutoSizeMode = DataGridViewAutoSizeColumnMode.Fill, MinimumWidth = 230 });
        grid.Columns.Add(new DataGridViewTextBoxColumn { Name = "Ksn", HeaderText = "KSN", Width = 190 });
        grid.Columns.Add(ComboColumn(KeyVersionColumn, "Ver", 50, "01"));
        grid.Columns.Add(new DataGridViewTextBoxColumn { Name = KeyResultColumn, HeaderText = "Result", ReadOnly = true, Width = 92 });
        grid.Columns.Add(new DataGridViewTextBoxColumn { Name = KeyKcvColumn, HeaderText = "KCV", ReadOnly = true, Width = 72 });
        const string dukpt0 = "ABCDEF0123456789FEDCBA9876543210";
        const string dukpt1 = "01234567890123456789012345678912";
        grid.Rows.Add(true, "0", dukpt0, "FFFF9876543210E00000", "01", "", CalculateKeyKcv(dukpt0, "T"));
        grid.Rows.Add(true, "1", dukpt1, "0123456789ABCDE00000", "01", "", CalculateKeyKcv(dukpt1, "T"));
        ConfigureKeyMasking(grid, showKeys);
        return grid;
    }

    private async Task LoadSelectedMasterKeysAsync(DataGridView masterGrid)
    {
        var failures = new List<string>();
        foreach (DataGridViewRow row in masterGrid.Rows)
        {
            if (!CellBool(row, KeySelectedColumn)) continue;
            var keyId = CellText(row, KeyIdColumn)[0];
            try
            {
                row.Cells[KeyResultColumn].Value = "Loading...";
                await _client.LoadA10ClearMasterKeyAsync(
                    keyId,
                    CellText(row, KeyMaterialColumn),
                    CellText(row, KeyUsageColumn),
                    CellText(row, KeyModeColumn)[0],
                    CellText(row, KeyAlgorithmColumn)[0]);
                row.Cells[KeyResultColumn].Value = "Loaded";
                row.Cells[KeyKcvColumn].Value = CalculateKeyKcv(
                    CellText(row, KeyMaterialColumn), CellText(row, KeyAlgorithmColumn));
            }
            catch (Exception error)
            {
                row.Cells[KeyResultColumn].Value = "Failed";
                failures.Add($"MK{keyId}: {error.Message}");
            }
        }
        if (failures.Count > 0) throw new InvalidOperationException(string.Join(Environment.NewLine, failures));
    }

    private async Task CheckSelectedMasterKeysAsync(DataGridView masterGrid)
    {
        foreach (DataGridViewRow row in masterGrid.Rows)
        {
            if (!CellBool(row, KeySelectedColumn)) continue;
            var keyId = CellText(row, KeyIdColumn)[0];
            var status = await _client.CheckA10MasterKeyAsync(keyId);
            var loaded = status.StartsWith('F');
            row.Cells[KeyResultColumn].Value = loaded ? "Loaded" : "Not loaded";
            row.Cells[KeyKcvColumn].Value = loaded
                ? CalculateKeyKcv(CellText(row, KeyMaterialColumn), CellText(row, KeyAlgorithmColumn))
                : "";
        }
    }

    private async Task LoadSelectedDukptKeysAsync(DataGridView dukptGrid)
    {
        var failures = new List<string>();
        foreach (DataGridViewRow row in dukptGrid.Rows)
        {
            if (!CellBool(row, KeySelectedColumn)) continue;
            var keySet = int.Parse(CellText(row, KeyIdColumn));
            try
            {
                row.Cells[KeyResultColumn].Value = "Loading...";
                await _client.LoadA10DukptInitialKeyAsync(
                    keySet, CellText(row, KeyMaterialColumn), CellText(row, "Ksn"));
                row.Cells[KeyResultColumn].Value = "Loaded";
                row.Cells[KeyKcvColumn].Value = await TryReadDukptKcvAsync(keySet) ??
                                                    CalculateKeyKcv(CellText(row, KeyMaterialColumn), "T");
            }
            catch (Exception error)
            {
                row.Cells[KeyResultColumn].Value = "Failed";
                failures.Add($"DUKPT{keySet}: {error.Message}");
            }
        }
        if (failures.Count > 0) throw new InvalidOperationException(string.Join(Environment.NewLine, failures));
    }

    private async Task CheckSelectedDukptKeysAsync(DataGridView dukptGrid)
    {
        foreach (DataGridViewRow row in dukptGrid.Rows)
        {
            if (!CellBool(row, KeySelectedColumn)) continue;
            var keySet = int.Parse(CellText(row, KeyIdColumn));
            var kcv = await TryReadDukptKcvAsync(keySet);
            row.Cells[KeyResultColumn].Value = kcv is null ? "Not loaded / unavailable" : "Loaded";
            row.Cells[KeyKcvColumn].Value = kcv ?? "";
        }
    }
    private async Task<string?> TryReadDukptKcvAsync(int keySet)
    {
        try { return await _client.GetA10DukptKcvAsync(keySet); }
        catch (PinpadProtocolException) { return null; }
    }

    private void ReadKeyConfiguration(
        DataGridView masterGrid,
        DataGridView dukptGrid,
        TextBox sessionKey,
        ComboBox activeMaster,
        ComboBox activeDukpt)
    {
        using var dialog = new OpenFileDialog
        {
            Title = "Read key injection configuration",
            Filter = "Global Connect key configuration (*.gckeys.json)|*.gckeys.json|JSON (*.json)|*.json|All files (*.*)|*.*",
            CheckFileExists = true,
        };
        if (dialog.ShowDialog(this) != DialogResult.OK) return;
        var config = JsonSerializer.Deserialize<KeyInjectionConfiguration>(File.ReadAllText(dialog.FileName))
                     ?? throw new InvalidDataException("The key configuration file is empty.");
        sessionKey.Text = InitialSessionPinKey(config.SessionKey);
        SelectCombo(activeMaster, config.ActiveMasterKey);
        SelectCombo(activeDukpt, config.ActiveDukptKeySet.ToString());
        ApplyMasterConfiguration(masterGrid, config.MasterKeys);
        ApplyDukptConfiguration(dukptGrid, config.DukptKeys);
        SavePersistentKeyConfiguration(CaptureKeyConfiguration(
            masterGrid, dukptGrid, sessionKey, activeMaster, activeDukpt));
        SetStatus($"Read key configuration: {Path.GetFileName(dialog.FileName)}");
    }

    private void SaveKeyConfiguration(
        DataGridView masterGrid,
        DataGridView dukptGrid,
        TextBox sessionKey,
        ComboBox activeMaster,
        ComboBox activeDukpt)
    {
        if (MessageBox.Show(this,
                "This file contains clear test keys. Save it only in a protected development location.",
                "Save key configuration",
                MessageBoxButtons.OKCancel,
                MessageBoxIcon.Warning) != DialogResult.OK) return;
        using var dialog = new SaveFileDialog
        {
            Title = "Save key injection configuration",
            Filter = "Global Connect key configuration (*.gckeys.json)|*.gckeys.json|JSON (*.json)|*.json",
            DefaultExt = "gckeys.json",
            AddExtension = true,
            FileName = "pinpad-test-keys.gckeys.json",
        };
        if (dialog.ShowDialog(this) != DialogResult.OK) return;
        var config = CaptureKeyConfiguration(masterGrid, dukptGrid, sessionKey, activeMaster, activeDukpt);
        File.WriteAllText(dialog.FileName, JsonSerializer.Serialize(config, KeyJsonOptions));
        SetStatus($"Saved key configuration: {Path.GetFileName(dialog.FileName)}");
    }

    private static void InitializeKeyPersistence(
        Control owner,
        DataGridView masterGrid,
        DataGridView dukptGrid,
        TextBox sessionKey,
        ComboBox activeMaster,
        ComboBox activeDukpt)
    {
        var loading = true;
        var persisted = LoadPersistentKeyConfiguration();
        var repairPersistedSessionKey = false;
        if (persisted is not null)
        {
            repairPersistedSessionKey = string.IsNullOrWhiteSpace(persisted.SessionKey);
            sessionKey.Text = InitialSessionPinKey(persisted.SessionKey);
            SelectCombo(activeMaster, persisted.ActiveMasterKey);
            SelectCombo(activeDukpt, persisted.ActiveDukptKeySet.ToString());
            ApplyMasterConfiguration(masterGrid, persisted.MasterKeys);
            ApplyDukptConfiguration(dukptGrid, persisted.DukptKeys);
        }

        var timer = new System.Windows.Forms.Timer { Interval = 350 };
        void SaveNow()
        {
            if (loading) return;
            SavePersistentKeyConfiguration(CaptureKeyConfiguration(
                masterGrid, dukptGrid, sessionKey, activeMaster, activeDukpt));
        }
        void ScheduleSave()
        {
            if (loading) return;
            timer.Stop();
            timer.Start();
        }

        timer.Tick += (_, _) =>
        {
            timer.Stop();
            SaveNow();
        };
        void GridValueChanged(object? sender, DataGridViewCellEventArgs eventArgs)
        {
            if (eventArgs.RowIndex < 0) return;
            var grid = (DataGridView)sender!;
            var column = grid.Columns[eventArgs.ColumnIndex].Name;
            if (column is KeyResultColumn or KeyKcvColumn) return;
            if (column is KeyMaterialColumn or KeyAlgorithmColumn)
            {
                var row = grid.Rows[eventArgs.RowIndex];
                var algorithm = grid.Columns.Contains(KeyAlgorithmColumn)
                    ? CellText(row, KeyAlgorithmColumn)
                    : "T";
                row.Cells[KeyKcvColumn].Value = CalculateKeyKcv(
                    CellText(row, KeyMaterialColumn), algorithm);
            }
            ScheduleSave();
        }
        void CommitDirtyCell(object? sender, EventArgs eventArgs)
        {
            var grid = (DataGridView)sender!;
            if (grid.IsCurrentCellDirty) grid.CommitEdit(DataGridViewDataErrorContexts.Commit);
        }
        masterGrid.CellValueChanged += GridValueChanged;
        dukptGrid.CellValueChanged += GridValueChanged;
        masterGrid.CurrentCellDirtyStateChanged += CommitDirtyCell;
        dukptGrid.CurrentCellDirtyStateChanged += CommitDirtyCell;
        sessionKey.TextChanged += (_, _) => ScheduleSave();
        activeMaster.SelectedIndexChanged += (_, _) => ScheduleSave();
        activeDukpt.SelectedIndexChanged += (_, _) => ScheduleSave();
        owner.Disposed += (_, _) =>
        {
            timer.Stop();
            try { SaveNow(); }
            catch (Exception) { /* Closing the utility must not fail if controls were already disposed. */ }
            timer.Dispose();
        };

        loading = false;
        if (persisted is null || repairPersistedSessionKey) SaveNow();
    }

    private static string InitialSessionPinKey(string? value) =>
        string.IsNullOrWhiteSpace(value) ? DefaultSessionPinKey : value;

    private static KeyInjectionConfiguration CaptureKeyConfiguration(
        DataGridView masterGrid,
        DataGridView dukptGrid,
        TextBox sessionKey,
        ComboBox activeMaster,
        ComboBox activeDukpt) => new()
    {
        SessionKey = sessionKey.Text,
        ActiveMasterKey = activeMaster.Text,
        ActiveDukptKeySet = activeDukpt.SelectedIndex,
        MasterKeys = masterGrid.Rows.Cast<DataGridViewRow>().Select(row => new MasterKeyConfiguration
        {
            Selected = CellBool(row, KeySelectedColumn),
            KeyId = CellText(row, KeyIdColumn),
            Key = CellText(row, KeyMaterialColumn),
            Algorithm = CellText(row, KeyAlgorithmColumn),
            Usage = CellText(row, KeyUsageColumn),
            Mode = CellText(row, KeyModeColumn),
            Version = CellText(row, KeyVersionColumn),
        }).ToList(),
        DukptKeys = dukptGrid.Rows.Cast<DataGridViewRow>().Select(row => new DukptKeyConfiguration
        {
            Selected = CellBool(row, KeySelectedColumn),
            KeySet = int.Parse(CellText(row, KeyIdColumn)),
            Key = CellText(row, KeyMaterialColumn),
            Ksn = CellText(row, "Ksn"),
            Version = CellText(row, KeyVersionColumn),
        }).ToList(),
    };

    private static KeyInjectionConfiguration? LoadPersistentKeyConfiguration()
    {
        try
        {
            if (!File.Exists(PersistentKeySettingsPath)) return null;
            return JsonSerializer.Deserialize<KeyInjectionConfiguration>(
                File.ReadAllText(PersistentKeySettingsPath), KeyJsonOptions);
        }
        catch (Exception)
        {
            return null;
        }
    }

    private static void SavePersistentKeyConfiguration(KeyInjectionConfiguration configuration)
    {
        try
        {
            var directory = Path.GetDirectoryName(PersistentKeySettingsPath)!;
            Directory.CreateDirectory(directory);
            var temporaryPath = PersistentKeySettingsPath + ".tmp";
            File.WriteAllText(temporaryPath, JsonSerializer.Serialize(configuration, KeyJsonOptions));
            File.Move(temporaryPath, PersistentKeySettingsPath, overwrite: true);
        }
        catch (Exception)
        {
            // Persistence is useful for testing but must never prevent use of the PINPAD tool.
        }
    }

    private Button LocalButton(string text, Action action)
    {
        var button = new Button { Text = text, AutoSize = true, Height = 34, Margin = new Padding(4) };
        button.Click += (_, _) =>
        {
            try { action(); }
            catch (Exception error) { MessageBox.Show(this, error.Message, "Key injection", MessageBoxButtons.OK, MessageBoxIcon.Error); }
        };
        _actionControls.Add(button);
        return button;
    }

    private static DataGridView BaseKeyGrid() => new()
    {
        Dock = DockStyle.Fill,
        AllowUserToAddRows = false,
        AllowUserToDeleteRows = false,
        AllowUserToResizeRows = false,
        RowHeadersVisible = false,
        SelectionMode = DataGridViewSelectionMode.CellSelect,
        MultiSelect = false,
        AutoGenerateColumns = false,
        BackgroundColor = SystemColors.Window,
        BorderStyle = BorderStyle.Fixed3D,
    };

    private static DataGridViewComboBoxColumn ComboColumn(string name, string header, int width, params string[] values)
    {
        var column = new DataGridViewComboBoxColumn { Name = name, HeaderText = header, Width = width, FlatStyle = FlatStyle.Flat };
        column.Items.AddRange(values);
        return column;
    }

    private static void ConfigureKeyMasking(DataGridView grid, CheckBox showKeys)
    {
        grid.CellFormatting += (_, eventArgs) =>
        {
            if (showKeys.Checked || eventArgs.RowIndex < 0 || grid.Columns[eventArgs.ColumnIndex].Name != KeyMaterialColumn) return;
            if (eventArgs.Value is string value && value.Length > 0)
            {
                eventArgs.Value = new string('●', Math.Min(value.Length, 32));
                eventArgs.FormattingApplied = true;
            }
        };
        grid.CellBeginEdit += (_, eventArgs) =>
        {
            if (!showKeys.Checked && grid.Columns[eventArgs.ColumnIndex].Name == KeyMaterialColumn)
            {
                eventArgs.Cancel = true;
                MessageBox.Show(grid, "Enable 'Show key material' before editing a key.", "Key injection",
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
        };
    }

    private static void SetAllSelections(DataGridView grid, bool selected)
    {
        foreach (DataGridViewRow row in grid.Rows) row.Cells[KeySelectedColumn].Value = selected;
    }

    private static bool CellBool(DataGridViewRow row, string column) =>
        row.Cells[column].Value is true;

    private static string CellText(DataGridViewRow row, string column) =>
        Convert.ToString(row.Cells[column].Value)?.Trim() ?? "";

    private static void SelectCombo(ComboBox combo, string? value)
    {
        var index = combo.FindStringExact(value ?? "");
        if (index >= 0) combo.SelectedIndex = index;
    }

    private static string CalculateKeyKcv(string keyHex, string algorithm)
    {
        try
        {
            var normalized = string.Concat(keyHex.Where(ch => !char.IsWhiteSpace(ch)));
            if (normalized.Length == 0 || normalized.Length % 2 != 0 || !normalized.All(Uri.IsHexDigit)) return "";
            var key = Convert.FromHexString(normalized);
            byte[] encrypted;
            if (algorithm.Equals("A", StringComparison.OrdinalIgnoreCase))
            {
                using var aes = Aes.Create();
                aes.Mode = CipherMode.ECB;
                aes.Padding = PaddingMode.None;
                aes.Key = key;
                encrypted = aes.EncryptEcb(new byte[16], PaddingMode.None);
            }
            else if (key.Length == 8)
            {
                using var des = DES.Create();
                des.Mode = CipherMode.ECB;
                des.Padding = PaddingMode.None;
                des.Key = key;
                encrypted = des.EncryptEcb(new byte[8], PaddingMode.None);
            }
            else
            {
                var tdesKey = key.Length == 16 ? [.. key, .. key.AsSpan(0, 8)] : key;
                using var tdes = TripleDES.Create();
                tdes.Mode = CipherMode.ECB;
                tdes.Padding = PaddingMode.None;
                tdes.Key = tdesKey;
                encrypted = tdes.EncryptEcb(new byte[8], PaddingMode.None);
            }
            return Convert.ToHexString(encrypted.AsSpan(0, 3));
        }
        catch (CryptographicException)
        {
            return "";
        }
    }

    private static void ApplyMasterConfiguration(DataGridView grid, IEnumerable<MasterKeyConfiguration>? configurations)
    {
        foreach (var config in configurations ?? [])
        {
            var row = grid.Rows.Cast<DataGridViewRow>().FirstOrDefault(item => CellText(item, KeyIdColumn) == config.KeyId);
            if (row is null) continue;
            row.Cells[KeySelectedColumn].Value = config.Selected;
            row.Cells[KeyMaterialColumn].Value = config.Key;
            row.Cells[KeyAlgorithmColumn].Value = config.Algorithm;
            row.Cells[KeyUsageColumn].Value = config.Usage;
            row.Cells[KeyModeColumn].Value = config.Mode;
            row.Cells[KeyVersionColumn].Value = config.Version;
            row.Cells[KeyResultColumn].Value = "";
            row.Cells[KeyKcvColumn].Value = CalculateKeyKcv(config.Key, config.Algorithm);
        }
    }

    private static void ApplyDukptConfiguration(DataGridView grid, IEnumerable<DukptKeyConfiguration>? configurations)
    {
        foreach (var config in configurations ?? [])
        {
            var row = grid.Rows.Cast<DataGridViewRow>().FirstOrDefault(item => CellText(item, KeyIdColumn) == config.KeySet.ToString());
            if (row is null) continue;
            row.Cells[KeySelectedColumn].Value = config.Selected;
            row.Cells[KeyMaterialColumn].Value = config.Key;
            row.Cells["Ksn"].Value = config.Ksn;
            row.Cells[KeyVersionColumn].Value = config.Version;
            row.Cells[KeyResultColumn].Value = "";
            row.Cells[KeyKcvColumn].Value = CalculateKeyKcv(config.Key, "T");
        }
    }

    private sealed class KeyInjectionConfiguration
    {
        public string? SessionKey { get; set; }
        public string ActiveMasterKey { get; set; } = "0";
        public int ActiveDukptKeySet { get; set; }
        public List<MasterKeyConfiguration> MasterKeys { get; set; } = [];
        public List<DukptKeyConfiguration> DukptKeys { get; set; } = [];
    }

    private sealed class MasterKeyConfiguration
    {
        public bool Selected { get; set; }
        public string KeyId { get; set; } = "";
        public string Key { get; set; } = "";
        public string Algorithm { get; set; } = "T";
        public string Usage { get; set; } = "K0";
        public string Mode { get; set; } = "D";
        public string Version { get; set; } = "01";
    }

    private sealed class DukptKeyConfiguration
    {
        public bool Selected { get; set; }
        public int KeySet { get; set; }
        public string Key { get; set; } = "";
        public string Ksn { get; set; } = "";
        public string Version { get; set; } = "01";
    }
}


