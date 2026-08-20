using System.IO.Ports;
using GlobalConnect.KeyInjection.App.Controls;
using GlobalConnect.KeyInjection.App.Dialogs;
using GlobalConnect.KeyInjection.App.Serial;
using GlobalConnect.KeyInjection.Core.Crypto;
using GlobalConnect.KeyInjection.Core.Protocol;
using GlobalConnect.KeyInjection.Core.Security;
using System.Security.Cryptography;

namespace GlobalConnect.KeyInjection.App;

public sealed class MainForm : Form
{
    private static readonly Color Navy = Color.FromArgb(8, 42, 58);
    private static readonly Color Teal = Color.FromArgb(0, 154, 166);
    private static readonly Color Canvas = Color.FromArgb(244, 247, 249);

    private readonly FuturexSerialClient _client = new();
    private readonly ComboBox _ports = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 125 };
    private readonly NumericUpDown _timeout = new() { Minimum = 500, Maximum = 120000, Increment = 500, Value = 5000, Width = 95 };
    private readonly Button _connectButton = SecondaryButton("Connect");
    private readonly Label _connectionStatus = new() { AutoSize = true, Text = "Disconnected", ForeColor = Color.Firebrick, Padding = new Padding(8, 7, 0, 0) };
    private readonly TextBox _log = new() { Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical, Dock = DockStyle.Fill, BackColor = Color.White, Font = new Font("Consolas", 9F) };
    private readonly ToolStripStatusLabel _status = new("Ready");
    private readonly List<Control> _operationControls = [];
    private KeyVaultData _vault = new();
    private string? _vaultPath;
    private readonly Label _vaultStatus = new() { AutoSize = true, Text = "Working vault: empty", ForeColor = Color.DimGray, Padding = new Padding(8, 7, 0, 0) };

    private readonly KeyComponentPanel _masterComponents = new();
    private readonly ComboBox _masterSlot = NexgoDestinationCombo("TMK");
    private readonly Label _masterVaultStatus = VaultStatusLabel();
    private readonly KeyComponentPanel _dukptComponents = new();
    private readonly ComboBox _dukptSlot = NexgoDestinationCombo("TIK");
    private readonly TextBox _ksn = HexBox(20, false);
    private readonly Label _dukptVaultStatus = VaultStatusLabel();

    private readonly KeyComponentPanel _ktkComponents = new();
    private readonly Label _ktkVaultStatus = VaultStatusLabel();
    private readonly KeyComponentPanel _command02KeyComponents = new();
    private readonly Label _command02VaultStatus = VaultStatusLabel();
    private readonly ComboBox _ktkKeySlot = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 270 };
    private readonly ComboBox _ktkSlot = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 270 };
    private readonly ComboBox _keyType = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 310 };
    private readonly ComboBox _encryptionMode = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 310 };
    private readonly TextBox _keyChecksum = HexBox(6, false);
    private readonly TextBox _ktkChecksum = HexBox(4, false);
    private readonly TextBox _ksnPrefix = HexBox(4, false);
    private readonly TextBox _ksnKeySetId = HexBox(6, false);
    private readonly TextBox _ksnDeviceId = HexBox(5, false);
    private readonly TextBox _ksnCounter = HexBox(5, false);
    private readonly TextBox _ktkPayload = HexBox(48, true);
    private readonly CheckedListBox _injectionKeys = new() { CheckOnClick = true, Width = 810, Height = 220, IntegralHeight = false };
    private readonly CheckBox _clearKeysBeforeInjection = new()
    {
        AutoSize = true,
        Text = "Clear terminal keys before injection (Futurex command 05)",
        ForeColor = Color.Firebrick,
        Padding = new Padding(0, 5, 0, 3)
    };
    private readonly Button _stageCommand02KeyButton = PrimaryButton("Stage Key in Protected Vault");
    private readonly string? _preferredPortName;

    public MainForm()
    {
        var settings = OperatorSettingsStore.Load();
        _preferredPortName = settings.PortName;
        _timeout.Value = Math.Clamp(settings.TimeoutMs, (int)_timeout.Minimum, (int)_timeout.Maximum);
        Text = "Global Connect Key Injection";
        MinimumSize = new Size(1040, 760);
        Size = new Size(1180, 860);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Canvas;
        Font = new Font("Segoe UI", 9.5F);

        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 3, BackColor = Canvas };
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 86));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 25));
        layout.Controls.Add(BuildHeader(), 0, 0);
        layout.Controls.Add(BuildTabs(), 0, 1);
        layout.Controls.Add(new StatusStrip { Items = { _status }, SizingGrip = false }, 0, 2);
        Controls.Add(layout);

        _keyType.DataSource = FuturexKeyType.NexgoSupported.ToList();
        _keyType.SelectedIndexChanged += (_, _) => UpdateKtkPolicy();
        _ktkKeySlot.SelectedIndexChanged += (_, _) => RefreshVaultStatus();
        _encryptionMode.DataSource = InjectionModes();
        _encryptionMode.SelectedIndexChanged += (_, _) => UpdateInjectionMode();
        _ksnPrefix.Text = "FFFF";
        _ksnPrefix.Width = 58;
        _ksnPrefix.Enabled = false;
        _ksnKeySetId.Width = 78;
        _ksnDeviceId.Text = "00000";
        _ksnDeviceId.Width = 68;
        _ksnCounter.Text = "00000";
        _ksnCounter.Width = 68;
        _ksnCounter.Enabled = false;
        _ktkChecksum.Text = "0000";
        _keyChecksum.ReadOnly = true;
        _keyChecksum.Width = 90;
        _command02KeyComponents.StateChanged += (_, _) => UpdateCommand02KeyReadiness();
        _ksnKeySetId.TextChanged += (_, _) => UpdateCommand02KeyReadiness();
        _ksnDeviceId.TextChanged += (_, _) => UpdateCommand02KeyReadiness();
        UpdateKtkPolicy();
        UpdateInjectionMode();
        UpdateCommand02KeyReadiness();
        _masterSlot.SelectedIndexChanged += (_, _) => RefreshVaultStatus();
        _dukptSlot.SelectedIndexChanged += (_, _) => RefreshVaultStatus();

        Shown += (_, _) =>
        {
            RefreshPorts();
            if (_preferredPortName is not null && _ports.Items.Contains(_preferredPortName))
                _ports.SelectedItem = _preferredPortName;
        };
        FormClosed += (_, _) =>
        {
            OperatorSettingsStore.TrySave(_ports.SelectedItem as string, (int)_timeout.Value);
            _client.Dispose();
        };
        Log("Application started. Key values and components are never written to this log.");
    }

    private Control BuildHeader()
    {
        var panel = new Panel { Dock = DockStyle.Fill, BackColor = Navy, Padding = new Padding(22, 12, 22, 10) };
        var logo = new PictureBox
        {
            Image = LoadLogo(),
            SizeMode = PictureBoxSizeMode.Zoom,
            BackColor = Color.Transparent,
            Location = new Point(18, 10),
            Size = new Size(270, 64)
        };
        var title = new Label
        {
            Text = "KEY INJECTION",
            ForeColor = Color.White,
            Font = new Font("Segoe UI Semibold", 20F),
            AutoSize = true,
            Location = new Point(315, 17)
        };
        var subtitle = new Label
        {
            Text = "Futurex LKI • Nexgo secure key loading workstation",
            ForeColor = Color.FromArgb(184, 220, 224),
            Font = new Font("Segoe UI", 10F),
            AutoSize = true,
            Anchor = AnchorStyles.Top | AnchorStyles.Right,
            Location = new Point(690, 34)
        };
        panel.Controls.Add(logo);
        panel.Controls.Add(title);
        panel.Controls.Add(subtitle);
        panel.Resize += (_, _) => subtitle.Left = Math.Max(470, panel.ClientSize.Width - subtitle.Width - 24);
        return panel;
    }

    private static Image? LoadLogo()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "Assets", "global-connect-one-logo.png");
        if (!File.Exists(path)) return null;
        using var source = Image.FromFile(path);
        return new Bitmap(source);
    }

    private TabPage BuildDeviceConnectionTab()
    {
        var page = NewPage("Device Connection");
        var content = FormLayout();
        AddWideRow(content, string.Empty, DeviceConnectionHeading());

        var portControls = ButtonRow(_ports, SecondaryButton("Refresh", (_, _) => RefreshPorts()));
        AddRow(content, "COM port", portControls);
        AddRow(content, "Serial settings", new Label
        {
            Text = "9600 baud, 8 data bits, no parity, 1 stop bit (8N1)",
            AutoSize = true,
            ForeColor = Color.DimGray,
            Margin = new Padding(0, 9, 0, 0)
        });
        AddRow(content, "Timeout (ms)", _timeout);
        _connectButton.Click += ConnectButton_Click;
        AddRow(content, "Connection", ButtonRow(_connectButton, _connectionStatus));
        page.Controls.Add(content);
        return page;
    }

    private Control BuildVaultPanel()
    {
        var vault = new FlowLayoutPanel { Dock = DockStyle.Top, Height = 38, WrapContents = false, Margin = Padding.Empty };
        var newVault = SecondaryButton("New / Clear", (_, _) => ClearWorkingVault());
        var openVault = SecondaryButton("Open Protected File", async (_, _) => await OpenVaultAsync());
        var saveVault = PrimaryButton("Save Protected File", async (_, _) => await SaveVaultAsync());
        vault.Controls.AddRange([newVault, openVault, saveVault, _vaultStatus]);
        return vault;
    }

    private TabPage BuildEraseKeysTab()
    {
        var page = NewPage("Erase Keys");
        var content = FormLayout();
        AddWideRow(content, string.Empty, EraseKeysHeading());
        AddWideRow(content, string.Empty, RestrictionNote(
            "This is a destructive terminal operation. The application requires explicit confirmation before sending Futurex command 05."));
        var erase = DangerButton("Erase All Keys from Connected Terminal", async (_, _) => await EraseKeysAsync());
        _operationControls.Add(erase);
        AddWideRow(content, string.Empty, erase);
        page.Controls.Add(content);
        return page;
    }

    private Control BuildTabs()
    {
        var tabs = new TabControl { Dock = DockStyle.Fill, Padding = new Point(16, 7), Margin = new Padding(18, 8, 18, 8) };
        tabs.TabPages.Add(BuildDeviceConnectionTab());
        tabs.TabPages.Add(BuildKtkTab());
        tabs.TabPages.Add(BuildInjectionTab());
        tabs.TabPages.Add(BuildEraseKeysTab());
        tabs.TabPages.Add(BuildAuditLogTab());
        return tabs;
    }

    private TabPage BuildMasterTab()
    {
        var page = NewPage("Master Key");
        var content = FormLayout();
        AddRow(content, "Nexgo TMK destination", _masterSlot);
        AddRow(content, "Protected-vault key", _masterVaultStatus);
        AddWideRow(content, string.Empty, RestrictionNote("Futurex command 01 is a clear TMK load. The Nexgo app rejects it after a TLK has been loaded; use command 02 with a KTK-protected mode in that state."));
        AddWideRow(content, "Two-person component entry", _masterComponents);
        var stage = SecondaryButton("Stage Master Key in Vault", (_, _) => StageMasterKey());
        var inject = PrimaryButton("Inject Master Key", async (_, _) => await InjectMasterAsync());
        _operationControls.Add(inject);
        AddWideRow(content, string.Empty, ButtonRow(stage, inject));
        page.Controls.Add(content);
        return page;
    }

    private TabPage BuildDukptTab()
    {
        var page = NewPage("DUKPT / IPEK");
        var content = FormLayout();
        AddRow(content, "Nexgo TIK destination", _dukptSlot);
        AddRow(content, "Protected-vault key", _dukptVaultStatus);
        AddWideRow(content, string.Empty, RestrictionNote("Futurex command 00 is a clear DUKPT IPEK load. The Nexgo app rejects it after a TLK has been loaded; use command 02 with Futurex type 02, 03, or 08 instead."));
        AddRow(content, "Initial KSN (20 hex)", _ksn);
        AddWideRow(content, "Two-person IPEK components", _dukptComponents);
        var stage = SecondaryButton("Stage DUKPT Key in Vault", (_, _) => StageDukptKey());
        var inject = PrimaryButton("Inject DUKPT IPEK", async (_, _) => await InjectDukptAsync());
        _operationControls.Add(inject);
        AddWideRow(content, string.Empty, ButtonRow(stage, inject));
        page.Controls.Add(content);
        return page;
    }

    private TabPage BuildKtkTab()
    {
        var page = NewPage("Key Input");
        var content = FormLayout();
        AddWideRow(content, string.Empty, InputHeading());
        AddRow(content, "Key vault", BuildVaultPanel());
        AddRow(content, "Key type", _keyType);
        AddRow(content, "Nexgo destination", _ktkKeySlot);
        AddRow(content, "Protected-vault key", _command02VaultStatus);
        AddWideRow(content, "Destination-key components", _command02KeyComponents);
        AddRow(content, "Initial KSN", BuildCommand02KsnFields());
        AddWideRow(content, string.Empty, RestrictionNote("For type 03/08 BDK records, enter only the Key-set ID; Device ID stays 00000 until it is generated from the verified terminal serial. For type 02 IPEK, enter the assigned Device ID (its final hex character must be even for a counter-zero Initial KSN). Counter stays 00000."));
        _stageCommand02KeyButton.Click += (_, _) => StageCommand02Key();
        AddRow(content, "Calculated key KCV (6 hex)", ButtonRow(_keyChecksum, _stageCommand02KeyButton));
        AddRow(content, "Protected-vault KTK", _ktkVaultStatus);
        AddWideRow(content, "Clear KTK components", _ktkComponents);
        var applyKtk = SecondaryButton("Stage Clear KTK", (_, _) => ApplyKtkComponents());
        AddWideRow(content, string.Empty, applyKtk);
        AddBottomScrollBuffer(content);
        page.Controls.Add(content);
        return page;
    }

    private TabPage BuildInjectionTab()
    {
        var page = NewPage("Key Injection — Command 02");
        var content = FormLayout();
        AddWideRow(content, string.Empty, CommandHeading());
        AddRow(content, "Keys to inject", _injectionKeys);
        var selectAll = SecondaryButton("Select All", (_, _) => SetAllInjectionChecks(true));
        var clearAll = SecondaryButton("Clear Selection", (_, _) => SetAllInjectionChecks(false));
        AddWideRow(content, string.Empty, ButtonRow(selectAll, clearAll));
        _operationControls.Add(_clearKeysBeforeInjection);
        AddRow(content, "Pre-injection", _clearKeysBeforeInjection);
        AddRow(content, "Key encryption mode", _encryptionMode);
        AddRow(content, "KTK source", _ktkSlot);
        AddRow(content, "Protected-vault KTK", _ktkVaultStatus);
        AddRow(content, "KTK checksum", _ktkChecksum);
        AddWideRow(content, string.Empty, RestrictionNote("For modes 01 and 02, the staged clear KTK is used to encrypt each selected clear key. Mode 02 also sends that KTK in command field 13; mode 01 sends no KTK payload and requires the same KTK to be preloaded in the selected terminal source."));
        var inject = PrimaryButton("Inject Selected Keys", async (_, _) => await InjectSelectedKeysAsync());
        _operationControls.Add(inject);
        AddWideRow(content, string.Empty, inject);
        page.Controls.Add(content);
        return page;
    }

    private TabPage BuildAuditLogTab()
    {
        var page = NewPage("Audit Log");
        var group = new GroupBox { Text = "Audit log (metadata only)", Dock = DockStyle.Fill, Padding = new Padding(10) };
        group.Controls.Add(_log);
        page.Controls.Add(group);
        return page;
    }

    private async void ConnectButton_Click(object? sender, EventArgs e)
    {
        if (_client.IsConnected)
        {
            _client.Disconnect();
            SetConnectionState(false);
            Log("Disconnected from terminal.");
            return;
        }

        if (_ports.SelectedItem is not string portName)
        {
            MessageBox.Show(this, "Select an available COM port.", Text, MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        try
        {
            SetBusy(true, "Connecting…");
            await _client.ConnectAsync(portName, (int)_timeout.Value);
            OperatorSettingsStore.TrySave(portName, (int)_timeout.Value);
            SetConnectionState(true);
            Log($"Connected to {portName} at 9600 8N1.");
        }
        catch (Exception error)
        {
            SetConnectionState(false);
            ShowError("Unable to connect", error);
        }
        finally { SetBusy(false); }
    }

    private async Task EraseKeysAsync() => await ExecuteAsync(
        "ERASE ALL TERMINAL KEYS",
        FuturexCommandBuilder.EraseAllKeys(),
        true,
        EnsureSuccess,
        MessageBoxIcon.Warning);

    private async Task InjectMasterAsync()
    {
        try
        {
            var slot = Selected(_masterSlot);
            var key = _masterComponents.HasInput
                ? _masterComponents.Combine()
                : _vault.MasterKeys.TryGetValue(slot, out var stored)
                    ? stored.Key
                    : throw new InvalidOperationException("Enter key components or load a protected vault containing this Nexgo TMK index.");
            var kcv = KeyMaterial.CalculateKcv(key)[..4];
            var body = FuturexCommandBuilder.InjectMasterKey(slot, key);
            await ExecuteAsync($"Inject master key into Nexgo TMK index {WireIndexNumber(slot)} (Futurex {slot}, KCV {kcv})", body, true, response => EnsureKcvSuccess(response, kcv));
        }
        catch (Exception error) { ShowError("Invalid master-key entry", error); }
        finally { _masterComponents.ClearSecrets(); }
    }

    private async Task InjectDukptAsync()
    {
        try
        {
            var slot = Selected(_dukptSlot);
            var hasComponents = _dukptComponents.HasInput;
            var stored = _vault.DukptKeys.TryGetValue(slot, out var vaultEntry) ? vaultEntry : null;
            var key = hasComponents
                ? _dukptComponents.Combine()
                : stored?.Ipek ?? throw new InvalidOperationException("Enter IPEK components or load a protected vault containing this Nexgo TIK index.");
            var ksn = hasComponents ? _ksn.Text : stored!.Ksn;
            var kcv = KeyMaterial.CalculateKcv(key)[..4];
            var body = FuturexCommandBuilder.InjectDukptKey(slot, ksn, key);
            await ExecuteAsync($"Inject DUKPT IPEK into Nexgo TIK index {WireIndexNumber(slot)} (Futurex {slot}, KCV {kcv})", body, true, response =>
            {
                if (response is not DukptInjectionResponse dukpt || !dukpt.IsSuccess)
                    throw new FuturexProtocolException("The terminal rejected the DUKPT KSN or IPEK.");
                if (!string.Equals(dukpt.Kcv, kcv, StringComparison.OrdinalIgnoreCase))
                    throw new FuturexProtocolException($"KCV mismatch. Expected {kcv}; terminal returned {dukpt.Kcv}.");
            });
        }
        catch (Exception error) { ShowError("Invalid DUKPT entry", error); }
        finally { _dukptComponents.ClearSecrets(); }
    }

    private async Task InjectSelectedKeysAsync()
    {
        if (!_client.IsConnected)
        {
            MessageBox.Show(this, "Connect to the Nexgo terminal first.", Text, MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        var selected = _injectionKeys.CheckedItems
            .Cast<InjectionKeyItem>()
            .OrderBy(item => NexgoFuturexPolicy.IsTlkDestination(item.KeyType) ? 0 : 1)
            .ThenBy(item => item.KeyType.InjectionPriority())
            .ToList();
        if (selected.Count == 0)
        {
            MessageBox.Show(this, "Select at least one staged key to inject.", Text, MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        try
        {
            var clearKeysBeforeInjection = _clearKeysBeforeInjection.Checked;
            var selectedMode = (_encryptionMode.SelectedItem as Option<FuturexKeyEncryptionMode>)?.Value
                ?? throw new InvalidOperationException("Select a key encryption mode.");
            var ktkSource = Selected(_ktkSlot);
            var requiresKtk = selected.Any(item =>
                NexgoFuturexPolicy.ResolveBatchMode(item.KeyType, selectedMode) != FuturexKeyEncryptionMode.ClearKey);
            var ktk = !requiresKtk
                ? null
                : _vault.Ktk?.Key ?? throw new InvalidOperationException("The selected KTK mode requires a clear KTK in the protected vault.");
            var ktkKcv = ktk is null ? "0000" : KeyMaterial.CalculateKcv(ktk)[..4];

            SetBusy(true, "Reading target terminal serial number…");
            var serialResponse = await _client.ExchangeAsync(FuturexCommandBuilder.ReadSerialNumber()) as SerialNumberResponse
                ?? throw new FuturexProtocolException("The terminal returned an unexpected serial-number response.");
            if (!serialResponse.IsSuccess || string.IsNullOrWhiteSpace(serialResponse.SerialNumber))
                throw new FuturexProtocolException($"Unable to read the terminal serial number (response {serialResponse.ResponseCode}).");
            var terminalSerial = serialResponse.SerialNumber;
            Log($"Injection target verified by command 03: serial {terminalSerial}.");

            var commands = new List<(InjectionKeyItem Item, string Body, FuturexKeyEncryptionMode Mode,
                FuturexKeyType SentType, string ExpectedKcv, string Ksn, DeviceInitialKsn? DeviceKsn)>();
            foreach (var item in selected)
            {
                var effectiveMode = NexgoFuturexPolicy.ResolveBatchMode(item.KeyType, selectedMode);
                var effectiveKtk = effectiveMode == FuturexKeyEncryptionMode.ClearKey ? null : ktk;
                var storedClearKey = KeyMaterial.ValidateKey(item.Entry.KeyPayload, "stored clear key");
                var derivesIpek = item.KeyType.Code is "03" or "08";
                var commandKeyType = derivesIpek ? FuturexKeyType.DukptInitial : item.KeyType;
                var deviceKsn = derivesIpek
                    ? DukptInitialKeyDerivation.BindToDeviceSerial(item.Entry.Ksn, terminalSerial)
                    : null;
                var commandKsn = deviceKsn?.Ksn ?? item.Entry.Ksn;
                var clearKey = derivesIpek
                    ? DukptInitialKeyDerivation.DeriveIpek(storedClearKey, commandKsn)
                    : storedClearKey;
                var expectedKcv = derivesIpek ? KeyMaterial.CalculateKcv(clearKey)[..4] : item.Entry.Kcv;
                var payload = effectiveKtk is null ? clearKey : KeyMaterial.EncryptUnderTdes(clearKey, effectiveKtk);
                var ktkPayload = effectiveMode == FuturexKeyEncryptionMode.SuppliedClearKtk ? effectiveKtk : null;
                var body = FuturexCommandBuilder.InjectKeyUnderKtk(
                    item.Entry.DestinationIndex,
                    effectiveMode == FuturexKeyEncryptionMode.ClearKey ? "00" : ktkSource,
                    commandKeyType,
                    effectiveMode,
                    expectedKcv,
                    effectiveMode == FuturexKeyEncryptionMode.ClearKey ? "0000" : ktkKcv,
                    commandKsn,
                    payload,
                    ktkPayload);
                commands.Add((item, body, effectiveMode, commandKeyType, expectedKcv, commandKsn, deviceKsn));
            }

            var summary = string.Join(Environment.NewLine, commands.Select(command =>
                command.Item.KeyType.Code is "03" or "08"
                    ? $"• Type {command.Item.Entry.KeyType} BDK → Type 02 derived IPEK, {command.Item.DestinationName}, IPEK KCV {command.ExpectedKcv}, Initial KSN {command.Ksn}, serial digits {command.DeviceKsn!.SerialDigits} → Device ID {command.DeviceKsn.DeviceId}, counter 00000, mode {command.Mode.Code()}"
                    : $"• Type {command.Item.Entry.KeyType}, {command.Item.DestinationName}, KCV {command.ExpectedKcv}, mode {command.Mode.Code()}"));
            var eraseNotice = clearKeysBeforeInjection
                ? "WARNING: Command 05 will erase all terminal keys before the first key is injected.\n\n"
                : string.Empty;
            if (MessageBox.Show(this,
                    $"Target terminal serial: {terminalSerial}\n\n{eraseNotice}Operational-key mode: {selectedMode.DisplayName()}\nTLK/default-KTK destinations are automatically sent first in clear mode.\n\nKeys to inject:\n{summary}\n\nContinue?",
                    "Confirm Futurex command 02 injection", MessageBoxButtons.YesNo, MessageBoxIcon.Warning,
                    MessageBoxDefaultButton.Button2) != DialogResult.Yes)
            {
                Log($"Injection cancelled after verifying terminal serial {terminalSerial}.");
                return;
            }

            if (clearKeysBeforeInjection)
            {
                _status.Text = "Clearing terminal keys before injection…";
                Log($"Sending command 05 to serial {terminalSerial} before key injection.");
                var eraseResponse = await _client.ExchangeAsync(FuturexCommandBuilder.EraseAllKeys());
                EnsureSuccess(eraseResponse);
                Log($"Command 05 completed for serial {terminalSerial}; beginning key injection.");
            }

            var completed = 0;
            foreach (var (item, body, effectiveMode, sentType, expectedKcv, commandKsn, deviceKsn) in commands)
            {
                _status.Text = $"Injecting key {completed + 1} of {commands.Count}…";
                var derivationNote = item.KeyType.Code is "03" or "08"
                    ? $"stored type {item.Entry.KeyType} BDK derived locally to type {sentType.Code} IPEK, serial digits {deviceKsn!.SerialDigits} mapped to Device ID {deviceKsn.DeviceId}, Initial KSN {commandKsn}, counter 00000"
                    : $"type {sentType.Code}";
                Log($"Sending command 02 to serial {terminalSerial}: {derivationNote}, {item.DestinationName}, KCV {expectedKcv}, encryption {effectiveMode.Code()}.");
                var response = await _client.ExchangeAsync(body);
                EnsureKcvSuccess(response, expectedKcv);
                completed++;
                Log($"Command 02 completed for type {sentType.Code}, {item.DestinationName}, terminal serial {terminalSerial}.");
            }

            _status.Text = $"Injected {completed} key(s) into terminal {terminalSerial}";
            MessageBox.Show(this, $"Successfully injected {completed} key(s) into terminal {terminalSerial}.",
                Text, MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        catch (Exception error)
        {
            Log("Injection run failed: " + SafeError(error));
            ShowError("Key injection failed", error);
        }
        finally
        {
            SetBusy(false);
            _ktkPayload.Clear();
        }
    }

    private void StageMasterKey()
    {
        try
        {
            var slot = Selected(_masterSlot);
            var key = _masterComponents.Combine();
            var kcv = KeyMaterial.CalculateKcv(key)[..4];
            _vault.MasterKeys[slot] = new StoredMasterKey(key, kcv);
            _vault.UpdatedUtc = DateTime.UtcNow;
            _masterComponents.ClearSecrets();
            RefreshVaultStatus();
            Log($"Master key staged for Nexgo TMK index {WireIndexNumber(slot)} (Futurex {slot}, KCV {kcv}).");
        }
        catch (Exception error) { ShowError("Unable to stage master key", error); }
    }

    private void StageDukptKey()
    {
        try
        {
            var slot = Selected(_dukptSlot);
            var key = _dukptComponents.Combine();
            _ = FuturexCommandBuilder.InjectDukptKey(slot, _ksn.Text, key);
            var kcv = KeyMaterial.CalculateKcv(key)[..4];
            _vault.DukptKeys[slot] = new StoredDukptKey(key, _ksn.Text.Trim().ToUpperInvariant(), kcv);
            _vault.UpdatedUtc = DateTime.UtcNow;
            _dukptComponents.ClearSecrets();
            RefreshVaultStatus();
            Log($"DUKPT key staged for Nexgo TIK index {WireIndexNumber(slot)} (Futurex {slot}, KCV {kcv}).");
        }
        catch (Exception error) { ShowError("Unable to stage DUKPT key", error); }
    }

    private void ApplyKtkComponents()
    {
        try
        {
            var key = _ktkComponents.Combine();
            var kcv = KeyMaterial.CalculateKcv(key)[..4];
            _vault.Ktk = new StoredKtk(key, kcv);
            _vault.UpdatedUtc = DateTime.UtcNow;
            _ktkChecksum.Text = kcv;
            _ktkComponents.ClearSecrets();
            RefreshVaultStatus();
            Log($"Component-derived clear KTK staged in the protected working vault (KCV {kcv}).");
        }
        catch (Exception error) { ShowError("Unable to apply KTK components", error); }
    }

    private void StageCommand02Key()
    {
        try
        {
            var type = _keyType.SelectedItem as FuturexKeyType ?? throw new InvalidOperationException("Select a key type.");
            var destination = Selected(_ktkKeySlot);
            NexgoFuturexPolicy.ValidateDestination(type, destination);
            var payload = _command02KeyComponents.Combine();
            if (type.Code is "03" or "08" && payload.Length != 32)
                throw new ArgumentException("TDES DUKPT BDK records must be double length (32 hexadecimal characters). The workstation derives the 16-byte Initial Key from this BDK.");
            var displayKcv = KeyMaterial.CalculateKcv(payload, 3);
            var protocolKcv = displayKcv[..4];
            var ksn = NexgoFuturexPolicy.RequiresKsn(type) ? Command02Ksn() : "00000000000000000000";
            if (NexgoFuturexPolicy.RequiresKsn(type) && !IsCommand02KsnReady(type))
                throw new ArgumentException(type.Code is "03" or "08"
                    ? "Enter a non-zero six-character Key-set ID. Device ID is generated from the terminal serial during injection."
                    : "Enter a non-zero six-character Key-set ID and a non-zero five-character Device ID for the Initial Key.");
            if (NexgoFuturexPolicy.RequiresKsn(type))
                ksn = DukptInitialKeyDerivation.NormalizeInitialKsn(ksn);
            _vault.FuturexKeys[FuturexVaultId(type.Code, destination)] = new StoredFuturexKey(
                type.Code, destination, "00", "00", payload, ksn, protocolKcv);
            _vault.UpdatedUtc = DateTime.UtcNow;
            _command02KeyComponents.ClearSecrets();
            RefreshVaultStatus();
            Log($"Clear Futurex type {type.Code} key staged for {(_ktkKeySlot.SelectedItem as NexgoIndexOption)?.Name} (KCV {displayKcv}).");
        }
        catch (Exception error) { ShowError("Unable to stage Futurex key", error); }
    }

    private async Task SaveVaultAsync()
    {
        if (_vault.KeyCount == 0)
        {
            MessageBox.Show(this, "Stage at least one Futurex key or KTK before saving.", Text, MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        using var dialog = new SaveFileDialog
        {
            Title = "Save Global Connect Protected Key File",
            Filter = "Global Connect protected key files (*.gckv)|*.gckv",
            DefaultExt = "gckv",
            AddExtension = true,
            FileName = _vaultPath is null ? "GlobalConnectKeys.gckv" : Path.GetFileName(_vaultPath)
        };
        if (dialog.ShowDialog(this) != DialogResult.OK) return;
        var passwords = DualPasswordDialog.Prompt(this, "Protect key file", true);
        if (passwords is null) return;

        try
        {
            SetBusy(true, "Encrypting protected key file…");
            await Task.Run(() => ProtectedKeyVault.Save(dialog.FileName, _vault, passwords.Part1, passwords.Part2));
            _vaultPath = dialog.FileName;
            RefreshVaultStatus();
            Log($"Protected key file saved: {Path.GetFileName(dialog.FileName)} ({_vault.KeyCount} key records)." );
            _status.Text = "Protected key file saved";
        }
        catch (Exception error) { ShowError("Unable to save protected key file", error); }
        finally { SetBusy(false); }
    }

    private async Task OpenVaultAsync()
    {
        using var dialog = new OpenFileDialog
        {
            Title = "Open Global Connect Protected Key File",
            Filter = "Global Connect protected key files (*.gckv)|*.gckv",
            DefaultExt = "gckv",
            CheckFileExists = true
        };
        if (dialog.ShowDialog(this) != DialogResult.OK) return;
        var passwords = DualPasswordDialog.Prompt(this, "Unlock protected key file", false);
        if (passwords is null) return;

        try
        {
            SetBusy(true, "Unlocking protected key file…");
            var loaded = await Task.Run(() => ProtectedKeyVault.Load(dialog.FileName, passwords.Part1, passwords.Part2));
            _vault = loaded;
            MigrateLegacyVaultEntries();
            _vaultPath = dialog.FileName;
            if (_vault.Ktk is not null) _ktkChecksum.Text = _vault.Ktk.Kcv;
            RefreshVaultStatus();
            Log($"Protected key file opened: {Path.GetFileName(dialog.FileName)} ({_vault.KeyCount} key records)." );
            _status.Text = "Protected key file unlocked";
        }
        catch (CryptographicException)
        {
            ShowError("Unable to open protected key file", new InvalidDataException("The password parts are incorrect or the file has been modified."));
        }
        catch (Exception error) { ShowError("Unable to open protected key file", error); }
        finally { SetBusy(false); }
    }

    private void ClearWorkingVault()
    {
        if (_vault.KeyCount > 0 && MessageBox.Show(this,
                "Clear all keys from the working vault? The protected file on disk will not be deleted.", Text,
                MessageBoxButtons.YesNo, MessageBoxIcon.Warning, MessageBoxDefaultButton.Button2) != DialogResult.Yes)
            return;
        _vault = new KeyVaultData();
        _vaultPath = null;
        _ktkPayload.Clear();
        _ktkChecksum.Text = "0000";
        RefreshVaultStatus();
        Log("Working key vault cleared. No file was deleted.");
    }

    private void RefreshVaultStatus()
    {
        var file = _vaultPath is null ? "unsaved" : Path.GetFileName(_vaultPath);
        _vaultStatus.Text = $"Working vault: {_vault.KeyCount} key record(s), {file}";
        var masterSlot = Selected(_masterSlot);
        _masterVaultStatus.Text = _vault.MasterKeys.TryGetValue(masterSlot, out var master)
            ? $"Available — KCV {master.Kcv}" : "No saved key for selected Nexgo index";
        var dukptSlot = Selected(_dukptSlot);
        _dukptVaultStatus.Text = _vault.DukptKeys.TryGetValue(dukptSlot, out var dukpt)
            ? $"Available — KCV {dukpt.Kcv}, KSN {dukpt.Ksn}" : "No saved key for selected Nexgo index";
        _ktkVaultStatus.Text = _vault.Ktk is null ? "No saved KTK" : $"Available — KCV {_vault.Ktk.Kcv}";
        if (_keyType.SelectedItem is FuturexKeyType type)
        {
            var destination = Selected(_ktkKeySlot);
            _command02VaultStatus.Text = _vault.FuturexKeys.TryGetValue(FuturexVaultId(type.Code, destination), out var entry)
                ? $"Available — KCV {KeyMaterial.CalculateKcv(entry.KeyPayload, 3)}, encryption type {entry.EncryptionMode}"
                : "No saved key for selected Futurex type and Nexgo destination";
        }
        RefreshInjectionList();
    }

    private void RefreshInjectionList()
    {
        var checkedIds = _injectionKeys.CheckedItems.Cast<InjectionKeyItem>()
            .Select(item => item.Id).ToHashSet(StringComparer.OrdinalIgnoreCase);
        _injectionKeys.BeginUpdate();
        try
        {
            _injectionKeys.Items.Clear();
            foreach (var (id, entry) in _vault.FuturexKeys.OrderBy(pair => pair.Key, StringComparer.OrdinalIgnoreCase))
            {
                var type = FuturexKeyType.NexgoSupported.Single(item => item.Code == entry.KeyType);
                var item = new InjectionKeyItem(id, type, entry);
                _injectionKeys.Items.Add(item, checkedIds.Contains(id));
            }
        }
        finally { _injectionKeys.EndUpdate(); }
    }

    private void SetAllInjectionChecks(bool isChecked)
    {
        for (var index = 0; index < _injectionKeys.Items.Count; index++)
            _injectionKeys.SetItemChecked(index, isChecked);
    }

    private void MigrateLegacyVaultEntries()
    {
        foreach (var (index, entry) in _vault.MasterKeys)
            _vault.FuturexKeys.TryAdd(FuturexVaultId("01", index), new StoredFuturexKey(
                "01", index, "00", "00", entry.Key, "00000000000000000000", entry.Kcv));
        foreach (var (index, entry) in _vault.DukptKeys)
            _vault.FuturexKeys.TryAdd(FuturexVaultId("02", index), new StoredFuturexKey(
                "02", index, "00", "00", entry.Ipek, entry.Ksn, entry.Kcv));
        _vault.MasterKeys.Clear();
        _vault.DukptKeys.Clear();
    }

    private async Task ExecuteAsync(string operation, string requestBody, bool confirm, Action<FuturexResponse> handle, MessageBoxIcon icon = MessageBoxIcon.Question)
    {
        if (!_client.IsConnected)
        {
            MessageBox.Show(this, "Connect to the Nexgo terminal first.", Text, MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        if (confirm && MessageBox.Show(this, $"Confirm operation:\n\n{operation}\n\nContinue?", Text,
                MessageBoxButtons.YesNo, icon, MessageBoxDefaultButton.Button2) != DialogResult.Yes)
            return;

        try
        {
            SetBusy(true, operation + "…");
            Log(operation + " requested.");
            var response = await _client.ExchangeAsync(requestBody);
            handle(response);
            Log(operation + " completed successfully.");
            _status.Text = "Completed: " + operation;
        }
        catch (Exception error)
        {
            Log(operation + " failed: " + SafeError(error));
            ShowError(operation + " failed", error);
        }
        finally { SetBusy(false); }
    }

    private static void EnsureSuccess(FuturexResponse response)
    {
        if (!response.IsSuccess)
            throw new FuturexProtocolException($"Terminal response code: {response.ResponseCode}.");
    }

    private static void EnsureKcvSuccess(FuturexResponse response, string expectedKcv)
    {
        if (response is not KeyInjectionResponse injection || !injection.IsSuccess)
            throw new FuturexProtocolException($"Terminal response code: {response.ResponseCode}.");
        if (!string.Equals(injection.Kcv, expectedKcv, StringComparison.OrdinalIgnoreCase))
            throw new FuturexProtocolException($"KCV mismatch. Expected {expectedKcv}; terminal returned {injection.Kcv}.");
    }

    private void UpdateInjectionMode()
    {
        var mode = (_encryptionMode.SelectedItem as Option<FuturexKeyEncryptionMode>)?.Value ?? FuturexKeyEncryptionMode.ClearKey;
        var previousSource = Selected(_ktkSlot);
        var sources = mode == FuturexKeyEncryptionMode.ClearKey
            ? new List<NexgoIndexOption> { new("00", "Not used (Futurex 00)") }
            : KtkSourceOptions();
        _ktkSlot.DataSource = sources;
        _ktkSlot.SelectedItem = sources.FirstOrDefault(item => item.Code == previousSource) ?? sources[0];
        _ktkSlot.Enabled = mode != FuturexKeyEncryptionMode.ClearKey;
        _ktkChecksum.ReadOnly = true;
        if (mode == FuturexKeyEncryptionMode.ClearKey)
            _ktkChecksum.Text = "0000";
        else
            _ktkChecksum.Text = _vault.Ktk?.Kcv ?? "KTK required";
    }

    private void UpdateKtkPolicy()
    {
        var type = _keyType.SelectedItem as FuturexKeyType ?? FuturexKeyType.MasterSession;
        var destinations = NexgoFuturexPolicy.IsTlkDestination(type)
            ? new List<NexgoIndexOption> { new("00", "Nexgo TLK — fixed master-key index 0") }
            : NexgoOperationalOptions(NexgoDestinationName(type));
        _ktkKeySlot.DataSource = destinations;

        var needsKsn = NexgoFuturexPolicy.RequiresKsn(type);
        var derivesIpek = type.Code is "03" or "08";
        _ksnPrefix.Text = needsKsn ? "FFFF" : "0000";
        _ksnKeySetId.Enabled = needsKsn;
        _ksnDeviceId.Enabled = needsKsn && !derivesIpek;
        _ksnCounter.Text = "00000";
        if (!needsKsn) _ksnKeySetId.Text = "000000";
        if (!needsKsn || derivesIpek) _ksnDeviceId.Text = "00000";
        UpdateCommand02KeyReadiness();
        RefreshVaultStatus();
    }

    private void UpdateCommand02KeyReadiness()
    {
        var componentsReady = false;
        var displayKcv = string.Empty;
        if (_command02KeyComponents.IsComplete)
        {
            try
            {
                displayKcv = _command02KeyComponents.Kcv;
                componentsReady = true;
            }
            catch (ArgumentException) { }
            catch (CryptographicException) { }
        }

        var type = _keyType.SelectedItem as FuturexKeyType;
        var ksnReady = type is null || IsCommand02KsnReady(type);
        _keyChecksum.Text = displayKcv;
        _stageCommand02KeyButton.Enabled = componentsReady && ksnReady;
    }

    private Control BuildCommand02KsnFields()
    {
        var panel = new FlowLayoutPanel { AutoSize = true, WrapContents = false, Margin = Padding.Empty };
        panel.Controls.AddRange([
            KsnPartLabel("Prefix"), _ksnPrefix,
            KsnPartLabel("Key-set ID"), _ksnKeySetId,
            KsnPartLabel("Device ID"), _ksnDeviceId,
            KsnPartLabel("Counter"), _ksnCounter
        ]);
        return panel;
    }

    private static Label KsnPartLabel(string text) => new()
    {
        Text = text,
        AutoSize = true,
        Anchor = AnchorStyles.Left,
        Margin = new Padding(8, 8, 3, 0)
    };

    private string Command02Ksn() =>
        _ksnPrefix.Text.Trim().ToUpperInvariant() +
        _ksnKeySetId.Text.Trim().ToUpperInvariant() +
        _ksnDeviceId.Text.Trim().ToUpperInvariant() +
        _ksnCounter.Text.Trim().ToUpperInvariant();

    private bool IsCommand02KsnReady(FuturexKeyType type)
    {
        if (!NexgoFuturexPolicy.RequiresKsn(type)) return true;
        var keySetId = _ksnKeySetId.Text.Trim();
        if (keySetId.Length != 6 || !keySetId.All(Uri.IsHexDigit) || keySetId.All(character => character == '0'))
            return false;

        if (type.Code is "03" or "08")
            return _ksnDeviceId.Text == "00000";

        var deviceId = _ksnDeviceId.Text.Trim();
        if (deviceId.Length != 5 || !deviceId.All(Uri.IsHexDigit) || deviceId.All(character => character == '0'))
            return false;

        try { return DukptInitialKeyDerivation.NormalizeInitialKsn(Command02Ksn()) == Command02Ksn(); }
        catch (ArgumentException) { return false; }
    }

    private void RefreshPorts()
    {
        var selected = _ports.SelectedItem as string;
        var ports = SerialPort.GetPortNames().OrderBy(name => name, StringComparer.OrdinalIgnoreCase).ToArray();
        _ports.Items.Clear();
        _ports.Items.AddRange(ports);
        if (selected is not null && ports.Contains(selected)) _ports.SelectedItem = selected;
        else if (ports.Length > 0) _ports.SelectedIndex = 0;
        _status.Text = ports.Length == 0 ? "No COM ports detected" : $"{ports.Length} COM port(s) detected";
    }

    private void SetConnectionState(bool connected)
    {
        _connectionStatus.Text = connected ? $"Connected: {_client.PortName}" : "Disconnected";
        _connectionStatus.ForeColor = connected ? Color.SeaGreen : Color.Firebrick;
        _connectButton.Text = connected ? "Disconnect" : "Connect";
        _ports.Enabled = !connected;
        _timeout.Enabled = !connected;
    }

    private void SetBusy(bool busy, string? message = null)
    {
        UseWaitCursor = busy;
        foreach (var control in _operationControls) control.Enabled = !busy;
        _connectButton.Enabled = !busy;
        if (message is not null) _status.Text = message;
        else if (!busy && _status.Text?.StartsWith("Completed", StringComparison.Ordinal) != true) _status.Text = "Ready";
    }

    private void Log(string message)
    {
        _log.AppendText($"{DateTime.Now:yyyy-MM-dd HH:mm:ss}  {message}{Environment.NewLine}");
    }

    private void ShowError(string title, Exception error)
    {
        _status.Text = title;
        MessageBox.Show(this, SafeError(error), title, MessageBoxButtons.OK, MessageBoxIcon.Error);
    }

    private static string SafeError(Exception error) => error switch
    {
        TimeoutException => "The terminal did not respond before the configured timeout.",
        UnauthorizedAccessException => "Access to the COM port was denied. Close other applications using the port.",
        IOException => "The serial connection was interrupted.",
        _ => error.Message
    };

    private static TabPage NewPage(string text) => new(text) { BackColor = Color.White, Padding = new Padding(18) };

    private static TableLayoutPanel FormLayout()
    {
        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, AutoScroll = true, ColumnCount = 2, Padding = new Padding(12) };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 220));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        return layout;
    }

    private static void AddRow(TableLayoutPanel layout, string label, Control control)
    {
        var row = layout.RowCount++;
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.Controls.Add(new Label { Text = label, AutoSize = true, Anchor = AnchorStyles.Left, Margin = new Padding(0, 8, 12, 8) }, 0, row);
        control.Margin = new Padding(0, 5, 0, 5);
        layout.Controls.Add(control, 1, row);
    }

    private static void AddWideRow(TableLayoutPanel layout, string label, Control control) => AddRow(layout, label, control);

    private static void AddBottomScrollBuffer(TableLayoutPanel layout)
    {
        var row = layout.RowCount++;
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 40));
        layout.Controls.Add(new Panel { Height = 40, Width = 1, Margin = Padding.Empty }, 1, row);
        layout.AutoScrollMargin = new Size(0, 40);
    }

    private static Label RestrictionNote(string text) => new()
    {
        Text = text,
        AutoSize = true,
        MaximumSize = new Size(760, 0),
        ForeColor = Color.FromArgb(145, 82, 0),
        Margin = new Padding(0, 3, 0, 8)
    };

    private static Control CommandHeading()
    {
        var panel = new Panel { Height = 66, Width = 820, BackColor = Color.FromArgb(232, 245, 246), Padding = new Padding(14, 9, 14, 8) };
        panel.Controls.Add(new Label
        {
            Text = "Futurex 02 — Inject Key",
            Font = new Font("Segoe UI Semibold", 13F),
            ForeColor = Navy,
            AutoSize = true,
            Location = new Point(12, 8)
        });
        panel.Controls.Add(new Label
        {
            Text = "Select staged keys and one key encryption mode. The terminal serial is read and logged before any key is sent.",
            AutoSize = true,
            ForeColor = Color.FromArgb(45, 70, 78),
            Location = new Point(13, 38)
        });
        return panel;
    }

    private static Control DeviceConnectionHeading()
    {
        var panel = new Panel { Height = 66, Width = 820, BackColor = Color.FromArgb(232, 245, 246), Padding = new Padding(14, 9, 14, 8) };
        panel.Controls.Add(new Label
        {
            Text = "Device Connection",
            Font = new Font("Segoe UI Semibold", 13F),
            ForeColor = Navy,
            AutoSize = true,
            Location = new Point(12, 8)
        });
        panel.Controls.Add(new Label
        {
            Text = "Select the Nexgo terminal COM port and establish the Futurex LKI serial connection.",
            AutoSize = true,
            ForeColor = Color.FromArgb(45, 70, 78),
            Location = new Point(13, 38)
        });
        return panel;
    }

    private static Control EraseKeysHeading()
    {
        var panel = new Panel { Height = 66, Width = 820, BackColor = Color.FromArgb(253, 239, 240), Padding = new Padding(14, 9, 14, 8) };
        panel.Controls.Add(new Label
        {
            Text = "Erase Keys — Futurex Command 05",
            Font = new Font("Segoe UI Semibold", 13F),
            ForeColor = Color.FromArgb(137, 31, 43),
            AutoSize = true,
            Location = new Point(12, 8)
        });
        panel.Controls.Add(new Label
        {
            Text = "Erase terminal keys only as part of an authorized key-management procedure.",
            AutoSize = true,
            ForeColor = Color.FromArgb(92, 49, 53),
            Location = new Point(13, 38)
        });
        return panel;
    }

    private static Control InputHeading()
    {
        var panel = new Panel { Height = 66, Width = 820, BackColor = Color.FromArgb(232, 245, 246), Padding = new Padding(14, 9, 14, 8) };
        panel.Controls.Add(new Label
        {
            Text = "Key Input — Protected Working Vault",
            Font = new Font("Segoe UI Semibold", 13F),
            ForeColor = Navy,
            AutoSize = true,
            Location = new Point(12, 8)
        });
        panel.Controls.Add(new Label
        {
            Text = "Enter clear key components and stage the resulting key. No terminal communication occurs on this tab.",
            AutoSize = true,
            ForeColor = Color.FromArgb(45, 70, 78),
            Location = new Point(13, 38)
        });
        return panel;
    }

    private static List<Option<FuturexKeyEncryptionMode>> InjectionModes() =>
    [
        new(FuturexKeyEncryptionMode.ClearKey, FuturexKeyEncryptionMode.ClearKey.DisplayName()),
        new(FuturexKeyEncryptionMode.SuppliedClearKtk, FuturexKeyEncryptionMode.SuppliedClearKtk.DisplayName()),
        new(FuturexKeyEncryptionMode.PreloadedKtk, FuturexKeyEncryptionMode.PreloadedKtk.DisplayName())
    ];

    private static Label VaultStatusLabel() => new()
    {
        Text = "No saved key for selected Nexgo index",
        AutoSize = true,
        ForeColor = Color.FromArgb(0, 107, 117),
        Margin = new Padding(0, 8, 0, 8)
    };

    private static FlowLayoutPanel ButtonRow(params Control[] controls)
    {
        var panel = new FlowLayoutPanel { AutoSize = true, WrapContents = false, Margin = Padding.Empty };
        panel.Controls.AddRange(controls);
        return panel;
    }

    private static Button PrimaryButton(string text, EventHandler? click = null) => StyledButton(text, Teal, Color.White, click);
    private static Button SecondaryButton(string text, EventHandler? click = null) => StyledButton(text, Color.White, Navy, click);
    private static Button DangerButton(string text, EventHandler? click = null) => StyledButton(text, Color.FromArgb(174, 44, 56), Color.White, click);

    private static Button StyledButton(string text, Color backColor, Color foreColor, EventHandler? click)
    {
        var button = new Button
        {
            Text = text,
            AutoSize = true,
            MinimumSize = new Size(92, 32),
            BackColor = backColor,
            ForeColor = foreColor,
            FlatStyle = FlatStyle.Flat,
            Cursor = Cursors.Hand,
            Margin = new Padding(6, 2, 6, 2)
        };
        button.FlatAppearance.BorderColor = backColor == Color.White ? Color.FromArgb(180, 190, 196) : backColor;
        if (click is not null) button.Click += click;
        return button;
    }

    private static ComboBox NexgoDestinationCombo(string destinationName)
    {
        var combo = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 270 };
        combo.DataSource = NexgoOperationalOptions(destinationName);
        return combo;
    }

    private static List<NexgoIndexOption> NexgoOperationalOptions(string destinationName) =>
        NexgoFuturexPolicy.OperationalIndices
            .Select(index => new NexgoIndexOption(
                NexgoFuturexPolicy.ToWireIndex(index),
                $"Nexgo {destinationName} index {index} (Futurex {NexgoFuturexPolicy.ToWireIndex(index)})"))
            .ToList();

    private static List<NexgoIndexOption> KtkSourceOptions()
    {
        var options = new List<NexgoIndexOption> { new("00", "TLK — fixed Nexgo master-key index 0") };
        options.AddRange(NexgoOperationalOptions("TMK"));
        return options;
    }

    private static string NexgoDestinationName(FuturexKeyType type) => type.Code switch
    {
        "01" => "TMK",
        "02" or "03" or "08" => "TIK",
        "04" => "TAK",
        "05" => "TPK",
        "06" or "09" => "TLK",
        _ => "key"
    };

    private static TextBox HexBox(int maxLength, bool secret) => new()
    {
        Width = maxLength > 48 ? 620 : Math.Max(210, maxLength * 10),
        MaxLength = maxLength,
        CharacterCasing = CharacterCasing.Upper,
        UseSystemPasswordChar = secret
    };

    private static string Selected(ComboBox combo) => combo.SelectedItem switch
    {
        string value => value,
        NexgoIndexOption option => option.Code,
        _ => "00"
    };

    private static int WireIndexNumber(string wireIndex) => Convert.ToInt32(wireIndex, 16);

    private static string FuturexVaultId(string typeCode, string destinationIndex) => $"{typeCode}:{destinationIndex}";

    private sealed record NexgoIndexOption(string Code, string Name)
    {
        public override string ToString() => Name;
    }

    private sealed record InjectionKeyItem(string Id, FuturexKeyType KeyType, StoredFuturexKey Entry)
    {
        public string DestinationName => NexgoFuturexPolicy.IsTlkDestination(KeyType)
            ? "Nexgo TLK index 0"
            : $"Nexgo {NexgoDestinationName(KeyType)} index {WireIndexNumber(Entry.DestinationIndex)}";

        public override string ToString() =>
            $"Type {Entry.KeyType} — {KeyType.Name} | {DestinationName} | " +
            (KeyType.Code is "03" or "08" ? $"BDK KCV {Entry.Kcv} | IPEK derived at injection" : $"KCV {Entry.Kcv}") +
            (NexgoFuturexPolicy.RequiresKsn(KeyType) ? $" | KSN {Entry.Ksn}" : string.Empty);
    }

    private sealed record Option<T>(T Value, string Name)
    {
        public override string ToString() => Name;
    }
}
