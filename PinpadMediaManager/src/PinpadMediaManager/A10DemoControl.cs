using System.Globalization;
using PinpadMediaManager.Core;
using PinpadMediaManager.Core.Models;
using PinpadMediaManager.Core.Protocol;

namespace PinpadMediaManager;

internal sealed partial class A10DemoControl : UserControl
{
    private readonly PinpadClient _client;
    private readonly RichTextBox _deviceOutput = OutputBox();
    private readonly RichTextBox _configOutput = OutputBox();
    private readonly RichTextBox _smartCardOutput = OutputBox();
    private readonly RichTextBox _rawOutput = OutputBox();
    private readonly Label _status = new() { AutoSize = true, Text = "Ready." };
    private readonly List<Control> _actionControls = [];
    private bool _busy;

    public A10DemoControl(PinpadClient client)
    {
        _client = client;
        Dock = DockStyle.Fill;
        Controls.Add(BuildLayout());
    }

    private Control BuildLayout()
    {
        var root = new TableLayoutPanel { Dock = DockStyle.Fill, RowCount = 2, ColumnCount = 1 };
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        var tabs = new ProminentTabControl { Dock = DockStyle.Fill };
        tabs.TabPages.Add(Page("Device & hardware", BuildDevicePage()));
        tabs.TabPages.Add(Page("Display tests", BuildDisplayPage()));
        tabs.TabPages.Add(Page("Key injection", BuildKeyInjectionPage()));
        tabs.TabPages.Add(Page("PIN entry", BuildPinEntryPage()));
        tabs.TabPages.Add(Page("EMV data setup", BuildConfigurationPage()));
        tabs.TabPages.Add(Page("ICC transaction", BuildTransactionPage(contactless: false)));
        tabs.TabPages.Add(Page("Contactless transaction", BuildTransactionPage(contactless: true)));
        tabs.TabPages.Add(Page("ICC / SAM card", BuildSmartCardPage()));
        tabs.TabPages.Add(Page("Command console", BuildRawCommandPage()));
        root.Controls.Add(tabs, 0, 0);
        var footer = new Panel { Dock = DockStyle.Fill, Height = 30, Padding = new Padding(8, 5, 8, 0) };
        footer.Controls.Add(_status);
        root.Controls.Add(footer, 0, 1);
        return root;
    }

    private Control BuildDevicePage()
    {
        var panel = StandardPanel();
        var buttons = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, WrapContents = true };
        buttons.Controls.Add(ActionButton("Read device profile", ReadDeviceProfileAsync));
        buttons.Controls.Add(ActionButton("Connection test", async () =>
        {
            await _client.TestA10ConnectionAsync();
            _deviceOutput.Text = "Device acknowledged administration command 11.";
        }));
        buttons.Controls.Add(ActionButton("Random number", async () =>
            _deviceOutput.Text = "Random: " + await _client.RequestA10RandomNumberAsync()));
        buttons.Controls.Add(ActionButton("Enable keypad beep", async () =>
        {
            await _client.SetA10KeypadBeeperAsync(true);
            _deviceOutput.Text = "Keypad beeper enabled.";
        }));
        buttons.Controls.Add(ActionButton("Disable keypad beep", async () =>
        {
            await _client.SetA10KeypadBeeperAsync(false);
            _deviceOutput.Text = "Keypad beeper disabled.";
        }));
        buttons.Controls.Add(ActionButton("Sound beeper", async () =>
        {
            await _client.SoundA10BeeperAsync(2, 10, 5);
            _deviceOutput.Text = "Beeper test accepted.";
        }));
        panel.Controls.Add(buttons, 0, 0);
        panel.Controls.Add(_deviceOutput, 0, 1);
        panel.Controls.Add(InfoLabel(
            "Reproduces the A10 demo's reader-alive, version-query, random-number, capability, and beeper tests."), 0, 2);
        return panel;
    }

    private Control BuildDisplayPage()
    {
        var panel = StandardPanel();
        var editor = new TableLayoutPanel { Dock = DockStyle.Top, AutoSize = true, ColumnCount = 3 };
        editor.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        editor.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        editor.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        var idle = TextValue("BIENVENIDO", 120);
        var message = TextValue("Global Connect One", 512);
        var lines = new TextBox { Text = "Line one\r\nLine two\r\nLine three", Multiline = true, Height = 110, ScrollBars = ScrollBars.Vertical, Dock = DockStyle.Fill };
        AddWideRow(editor, 0, "Idle prompt", idle, ActionButton("Set idle prompt", () => _client.SetA10IdlePromptAsync(idle.Text)));
        AddWideRow(editor, 1, "Message", message, ActionButton("Display message", () => _client.DisplayA10MessageAsync(message.Text)));
        AddWideRow(editor, 2, "Prompt lines (1-7)", lines, ActionButton("Display lines", () => _client.DisplayA10PromptLinesAsync(lines.Lines)));
        var controls = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        controls.Controls.Add(ActionButton("Enable Cancel message", () => _client.SetA10CancelMessageDisplayAsync(true)));
        controls.Controls.Add(ActionButton("Disable Cancel message", () => _client.SetA10CancelMessageDisplayAsync(false)));
        editor.Controls.Add(controls, 1, 3);
        editor.SetColumnSpan(controls, 2);
        panel.Controls.Add(editor, 0, 0);
        panel.Controls.Add(new Panel { Dock = DockStyle.Fill }, 0, 1);
        panel.Controls.Add(InfoLabel("Exercises the A10 Z2, Z3, Z7, and Z8 display commands. Multi-line prompts accept up to seven non-empty lines."), 0, 2);
        return panel;
    }

    private Control BuildSimpleKeyInjectionPage()
    {
        var root = new SplitContainer { Dock = DockStyle.Fill, Orientation = Orientation.Vertical, SplitterDistance = 510 };
        var master = FormGrid();
        var keyId = TextValue("0", 1);
        var key = SecretValue("0123456789ABCDEFFEDCBA9876543210", 64);
        var show = new CheckBox { Text = "Show clear key", AutoSize = true };
        show.CheckedChanged += (_, _) => key.UseSystemPasswordChar = !show.Checked;
        var usage = Combo("P0 - PIN encryption", "M3 - MAC", "D0 - Data encryption", "K0 - Key encryption");
        var mode = Combo("E - Encrypt", "D - Decrypt", "B - Both", "G - Generate");
        var algorithm = Combo("T - TDES", "A - AES", "D - DES");
        AddRow(master, 0, "Master key slot", keyId);
        AddRow(master, 1, "Clear key (hex)", key);
        AddRow(master, 2, "", show);
        AddRow(master, 3, "Usage", usage);
        AddRow(master, 4, "Mode", mode);
        AddRow(master, 5, "Algorithm", algorithm);
        var masterButtons = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        masterButtons.Controls.Add(ActionButton("Load clear master key", async () =>
        {
            await _client.LoadA10ClearMasterKeyAsync(keyId.Text[0], key.Text, Code(usage), Code(mode)[0], Code(algorithm)[0]);
            MessageBox.Show(this, "Master key loaded. Key material was not written to the protocol log.", "Key injection");
        }));
        masterButtons.Controls.Add(ActionButton("Check key", async () =>
            MessageBox.Show(this, "Key status: " + await _client.CheckA10MasterKeyAsync(keyId.Text[0]), "Key injection")));
        masterButtons.Controls.Add(ActionButton("Select active key", () => _client.SelectA10MasterKeyAsync(keyId.Text[0])));
        master.Controls.Add(masterButtons, 0, 6); master.SetColumnSpan(masterButtons, 2);

        var dukpt = FormGrid();
        var keySet = Combo("0", "1");
        var ipek = SecretValue("0123456789ABCDEFFEDCBA9876543210", 48);
        var ksn = TextValue("FFFF9876543210E00000", 20);
        var showIpek = new CheckBox { Text = "Show initial key", AutoSize = true };
        showIpek.CheckedChanged += (_, _) => ipek.UseSystemPasswordChar = !showIpek.Checked;
        AddRow(dukpt, 0, "DUKPT key set", keySet);
        AddRow(dukpt, 1, "Initial key (hex)", ipek);
        AddRow(dukpt, 2, "", showIpek);
        AddRow(dukpt, 3, "KSN (20 hex)", ksn);
        var dukptButtons = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        dukptButtons.Controls.Add(ActionButton("Load DUKPT key", () => _client.LoadA10DukptInitialKeyAsync(keySet.SelectedIndex, ipek.Text, ksn.Text)));
        dukptButtons.Controls.Add(ActionButton("Select key set", () => _client.SelectA10DukptKeySetAsync(keySet.SelectedIndex)));
        dukpt.Controls.Add(dukptButtons, 0, 4); dukpt.SetColumnSpan(dukptButtons, 2);
        root.Panel1.Padding = new Padding(10); root.Panel1.Controls.Add(master);
        root.Panel2.Padding = new Padding(10); root.Panel2.Controls.Add(dukpt);
        var wrapper = new TableLayoutPanel { Dock = DockStyle.Fill, RowCount = 2, ColumnCount = 1 };
        wrapper.RowStyles.Add(new RowStyle(SizeType.Percent, 100)); wrapper.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        wrapper.Controls.Add(root, 0, 0);
        wrapper.Controls.Add(InfoLabel("Security: first enter authenticated Key Injection Mode on the terminal (Clear+2, or the terminal menu). The mode closes after one minute of inactivity. Clear keys are masked and redacted from the protocol trace."), 0, 1);
        return wrapper;
    }

    private Control BuildPinEntryPage()
    {
        var root = new SplitContainer { Dock = DockStyle.Fill, Orientation = Orientation.Vertical, SplitterDistance = 500 };
        var form = FormGrid();
        var scheme = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Fill };
        scheme.Items.AddRange(Enum.GetValues<A10PinKeyScheme>().Cast<object>().ToArray()); scheme.SelectedIndex = 0;
        var promptMode = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Fill };
        promptMode.Items.AddRange(Enum.GetValues<A10PinPromptMode>().Cast<object>().ToArray()); promptMode.SelectedIndex = 0;
        var account = TextValue("4111111111111111", 19);
        var session = SecretValue("0123456789ABCDEFFEDCBA9876543210", 48);
        var timeout = new NumericUpDown { Minimum = 30, Maximum = 270, Increment = 30, Value = 60, Dock = DockStyle.Fill };
        var minimum = new NumericUpDown { Minimum = 0, Maximum = 12, Value = 4, Dock = DockStyle.Fill };
        var maximum = new NumericUpDown { Minimum = 4, Maximum = 12, Value = 12, Dock = DockStyle.Fill };
        var allowNull = new CheckBox { Text = "Allow Enter without PIN", AutoSize = true };
        var first = TextValue("ENTER PIN", 80); var second = TextValue("PRESS ENTER", 80); var completion = TextValue("PROCESSING", 80);
        AddRow(form, 0, "Key scheme", scheme); AddRow(form, 1, "Prompt mode", promptMode);
        AddRow(form, 2, "Account / PAN", account); AddRow(form, 3, "Encrypted session key", session);
        AddRow(form, 4, "Timeout (seconds)", timeout); AddRow(form, 5, "Minimum PIN length", minimum);
        AddRow(form, 6, "Maximum PIN length", maximum); AddRow(form, 7, "Null PIN", allowNull);
        AddRow(form, 8, "First prompt", first); AddRow(form, 9, "Second prompt", second); AddRow(form, 10, "Completion prompt", completion);
        var output = OutputBox();
        var start = ActionButton("Start PIN entry", async () =>
        {
            var result = await _client.StartA10PinEntryAsync(new A10PinEntryRequest(
                (A10PinKeyScheme)scheme.SelectedItem!, (A10PinPromptMode)promptMode.SelectedItem!, account.Text, session.Text,
                (int)timeout.Value, (int)minimum.Value, (int)maximum.Value, allowNull.Checked, first.Text, second.Text, completion.Text));
            output.Text = string.Join(Environment.NewLine, $"Status: {result.Status}", $"Encrypted PIN block: {result.EncryptedPinBlock}",
                $"KSN: {result.Ksn ?? "Not applicable"}", $"PIN length: {result.PinLength?.ToString() ?? "Not returned"}",
                $"Key identifier: {result.KeyIdentifier ?? "Not returned"}", "", "Sensitive response material is redacted from the shared protocol log.");
        });
        form.Controls.Add(start, 0, 11); form.SetColumnSpan(start, 2);
        root.Panel1.Padding = new Padding(10); root.Panel1.Controls.Add(form);
        root.Panel2.Padding = new Padding(10); root.Panel2.Controls.Add(output);
        return root;
    }

    private Control BuildConfigurationPage()
    {
        var panel = StandardPanel();
        var grid = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 2,
            Padding = new Padding(0, 0, 0, 8),
        };
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        AddConfigButton(grid, 0, 0, "Load data formats", A10EmvConfigurationType.DataFormats);
        AddConfigButton(grid, 1, 0, "Load terminal configuration", A10EmvConfigurationType.Terminal);
        AddConfigButton(grid, 0, 1, "Add contact CA key", A10EmvConfigurationType.ContactCaKey);
        AddConfigButton(grid, 1, 1, "Add contact application", A10EmvConfigurationType.ContactApplication);
        AddConfigButton(grid, 0, 2, "Add contactless CA key", A10EmvConfigurationType.ContactlessCaKey);
        AddConfigButton(grid, 1, 2, "Add contactless application", A10EmvConfigurationType.ContactlessApplication);
        panel.Controls.Add(grid, 0, 0);
        panel.Controls.Add(_configOutput, 0, 1);
        panel.Controls.Add(InfoLabel(
            "Select the same legacy text files used by the A10 configuration tool. Files are parsed and applied directly by the Android PINPAD; private key material is not involved."), 0, 2);
        return panel;
    }

    private Control BuildTransactionPage(bool contactless)
    {
        var root = new SplitContainer
        {
            Dock = DockStyle.Fill,
            Orientation = Orientation.Vertical,
            SplitterDistance = 430,
        };
        var editor = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            AutoScroll = true,
            Padding = new Padding(12),
            ColumnCount = 2,
            RowCount = 10,
        };
        editor.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 165));
        editor.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));

        var amount = MoneyBox(50m);
        var cashback = MoneyBox(0m);
        var currency = TextValue("2840", 4);
        var transactionType = Combo("00 - Goods", "01 - Cash", "09 - Cashback");
        var transactionInfo = Combo("40 - Cardholder present", "00 - Default");
        var accountType = Combo("00 - Default", "10 - Savings", "20 - Checking", "30 - Credit");
        var forceOnline = new CheckBox { Text = "Force online", AutoSize = true };
        var hostDecision = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Fill };
        hostDecision.Items.AddRange(Enum.GetValues<A10HostDecision>().Cast<object>().ToArray());
        hostDecision.SelectedItem = A10HostDecision.Approve;
        AddRow(editor, 0, "Purchase amount", amount);
        AddRow(editor, 1, "Cashback / amount other", cashback);
        AddRow(editor, 2, "Currency code", currency);
        AddRow(editor, 3, "Transaction type", transactionType);
        AddRow(editor, 4, "Transaction information", transactionInfo);
        AddRow(editor, 5, "Account type", accountType);
        AddRow(editor, 6, "Host response", hostDecision);
        AddRow(editor, 7, "Processing", forceOnline);

        var resultBox = OutputBox();
        resultBox.Text = contactless
            ? "Present a contactless card after starting the transaction."
            : "Insert a chip card after application selection starts.";
        var start = ActionButton(contactless ? "Start contactless transaction" : "Select app and start ICC transaction", async () =>
        {
            var request = new A10EmvTransactionRequest(
                amount.Value,
                cashback.Value,
                currency.Text,
                Code(transactionType),
                Code(transactionInfo),
                Code(accountType),
                forceOnline.Checked,
                (A10HostDecision)hostDecision.SelectedItem!);
            var result = contactless
                ? await _client.RunA10ContactlessTransactionAsync(request)
                : await _client.RunA10ContactTransactionAsync(request);
            resultBox.Text = FormatTransactionResult(result);
        });
        start.Dock = DockStyle.Fill;
        editor.Controls.Add(start, 0, 8);
        editor.SetColumnSpan(start, 2);
        var note = InfoLabel(
            "The built-in host simulator returns approval, decline, or no-response and then reads the standard receipt tags.");
        editor.Controls.Add(note, 0, 9);
        editor.SetColumnSpan(note, 2);
        root.Panel1.Controls.Add(editor);
        root.Panel2.Padding = new Padding(8);
        root.Panel2.Controls.Add(resultBox);
        return root;
    }

    private Control BuildSmartCardPage()
    {
        var panel = StandardPanel();
        var commandPanel = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, WrapContents = true };
        var apdu = new TextBox
        {
            Width = 350,
            Text = "00A404000E315041592E5359532E4444463031",
        };
        commandPanel.Controls.Add(ActionButton("Check card", async () => ShowSmartCard(await _client.CheckA10SmartCardAsync())));
        commandPanel.Controls.Add(ActionButton("Cold reset", async () => ShowSmartCard(await _client.ResetA10SmartCardAsync())));
        commandPanel.Controls.Add(ActionButton("Deactivate", async () => ShowSmartCard(await _client.DeactivateA10SmartCardAsync())));
        commandPanel.Controls.Add(new Label { Text = "APDU", AutoSize = true, Margin = new Padding(12, 9, 3, 0) });
        commandPanel.Controls.Add(apdu);
        commandPanel.Controls.Add(ActionButton("Send APDU", async () =>
            ShowSmartCard(await _client.ExchangeA10SmartCardApduAsync(apdu.Text))));
        panel.Controls.Add(commandPanel, 0, 0);
        panel.Controls.Add(_smartCardOutput, 0, 1);
        panel.Controls.Add(InfoLabel("Provides the A10 demo's ICC presence, cold reset, deactivate, and raw APDU tests."), 0, 2);
        return panel;
    }

    private Control BuildRawCommandPage()
    {
        var root = StandardPanel();
        var editor = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 4,
        };
        editor.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        editor.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        editor.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        editor.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));

        var frameType = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Fill };
        frameType.Items.AddRange(Enum.GetValues<PinpadFrameType>().Cast<object>().ToArray());
        frameType.SelectedItem = PinpadFrameType.Administration;
        var command = TextValue("19", 3);
        var expected = TextValue("19", 3);
        var readEot = new CheckBox { Text = "Read final EOT", Checked = true, AutoSize = true };
        var payload = new TextBox
        {
            Text = "1",
            Multiline = true,
            ScrollBars = ScrollBars.Vertical,
            Dock = DockStyle.Fill,
            Height = 72,
        };

        editor.Controls.Add(new Label { Text = "Frame type", AutoSize = true, Margin = new Padding(3, 9, 8, 3) }, 0, 0);
        editor.Controls.Add(frameType, 1, 0);
        editor.Controls.Add(new Label { Text = "Command", AutoSize = true, Margin = new Padding(12, 9, 8, 3) }, 2, 0);
        editor.Controls.Add(command, 3, 0);
        editor.Controls.Add(new Label { Text = "Expected response", AutoSize = true, Margin = new Padding(3, 9, 8, 3) }, 0, 1);
        editor.Controls.Add(expected, 1, 1);
        editor.Controls.Add(readEot, 3, 1);
        editor.Controls.Add(new Label { Text = "Payload", AutoSize = true, Margin = new Padding(3, 9, 8, 3) }, 0, 2);
        editor.Controls.Add(payload, 1, 2);
        editor.SetColumnSpan(payload, 3);

        var send = ActionButton("Send command", async () =>
        {
            var response = await _client.ExecuteA10RawCommandAsync(
                (PinpadFrameType)frameType.SelectedItem!,
                command.Text.Trim(),
                ExpandControlTokens(payload.Text),
                string.IsNullOrWhiteSpace(expected.Text) ? null : expected.Text.Trim(),
                readEot.Checked);
            _rawOutput.Text = $"Command: {response.Command}{Environment.NewLine}" +
                              $"Payload: {Printable(response.Payload)}";
        });
        send.Dock = DockStyle.Right;
        editor.Controls.Add(send, 3, 3);

        root.Controls.Add(editor, 0, 0);
        root.Controls.Add(_rawOutput, 0, 1);
        root.Controls.Add(InfoLabel(
            "For A10 diagnostics not exposed above. Use <FS>, <SUB>, and <RS> for control separators. Leave Expected response empty for ACK-only commands. The PINPAD still enforces protected key-injection mode."), 0, 2);
        return root;
    }

    private async Task ReadDeviceProfileAsync()
    {
        var profile = await _client.GetA10DeviceProfileAsync();
        _deviceOutput.Text = string.Join(Environment.NewLine,
            $"Serial number: {profile.SerialNumber}",
            $"System core: {profile.SystemCoreVersion}",
            $"Service routine: {profile.ServiceRoutineVersion}",
            $"Transaction terminal: {profile.TransactionTerminalVersion}",
            $"Prompt application: {profile.PromptApplicationVersion}",
            $"Capabilities: {string.Join(", ", profile.HardwareCapabilities)}");
    }

    private void AddConfigButton(
        TableLayoutPanel panel,
        int column,
        int row,
        string text,
        A10EmvConfigurationType type)
    {
        var button = ActionButton(text, () => LoadConfigurationFileAsync(type));
        button.Dock = DockStyle.Fill;
        button.Margin = new Padding(4);
        panel.Controls.Add(button, column, row);
    }

    private async Task LoadConfigurationFileAsync(A10EmvConfigurationType type)
    {
        using var dialog = new OpenFileDialog
        {
            Title = $"Select {type} configuration",
            Filter = "Configuration text (*.txt;*.cfg;*.conf)|*.txt;*.cfg;*.conf|All files (*.*)|*.*",
            CheckFileExists = true,
        };
        if (dialog.ShowDialog(this) != DialogResult.OK) return;
        await _client.ApplyA10EmvConfigurationFileAsync(type, dialog.FileName);
        _configOutput.AppendText($"{DateTime.Now:T}  Applied {type}: {dialog.FileName}{Environment.NewLine}");
    }

    private Button ActionButton(string text, Func<Task> action)
    {
        var button = new Button { Text = text, AutoSize = true, Height = 34, Margin = new Padding(4) };
        button.Click += async (_, _) => await RunAsync(action);
        _actionControls.Add(button);
        return button;
    }

    private async Task RunAsync(Func<Task> action)
    {
        if (_busy) return;
        if (!_client.IsConnected)
        {
            MessageBox.Show(this, "Connect to a PINPAD first.", "A10 Demo", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        _busy = true;
        SetActionsEnabled(false);
        _status.Text = "Working...";
        try
        {
            await action();
            _status.Text = "Completed.";
        }
        catch (Exception error)
        {
            _status.Text = "Failed: " + error.Message;
            MessageBox.Show(this, error.Message, "A10 Demo", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        finally
        {
            _busy = false;
            SetActionsEnabled(true);
        }
    }

    private void SetActionsEnabled(bool enabled)
    {
        foreach (var control in _actionControls) control.Enabled = enabled;
    }

    private void ShowSmartCard(A10SmartCardResponse response)
    {
        _smartCardOutput.Text = $"Command: {response.Command}{Environment.NewLine}" +
                                $"Status: {response.Status}{Environment.NewLine}" +
                                $"Data: {response.Data}";
    }

    private static string FormatTransactionResult(A10EmvTransactionResult result)
    {
        var lines = new List<string>
        {
            $"Interface: {result.Interface}",
            $"Result: {result.Status}",
            $"Initial response: {Printable(result.InitialResponse)}",
            $"Final response: {Printable(result.FinalResponse)}",
            $"Online authorization data: {Printable(result.OnlineAuthorizationData)}",
            "",
            "Receipt / EMV tags",
        };
        lines.AddRange(result.Tags.OrderBy(item => item.Key).Select(item => $"{item.Key}: {item.Value}"));
        return string.Join(Environment.NewLine, lines);
    }

    private static string Printable(string value) => value
        .Replace(PinpadControl.Sub.ToString(), "<SUB>")
        .Replace(PinpadControl.Fs.ToString(), "<FS>")
        .Replace(PinpadControl.Rs.ToString(), "<RS>");

    private static string ExpandControlTokens(string value) => value
        .Replace("<SUB>", PinpadControl.Sub.ToString(), StringComparison.OrdinalIgnoreCase)
        .Replace("<FS>", PinpadControl.Fs.ToString(), StringComparison.OrdinalIgnoreCase)
        .Replace("<RS>", PinpadControl.Rs.ToString(), StringComparison.OrdinalIgnoreCase);

    private static string Code(ComboBox combo) => combo.Text.Split(' ', 2)[0];

    private static NumericUpDown MoneyBox(decimal value) => new()
    {
        DecimalPlaces = 2,
        Maximum = 9_999_999_999.99m,
        Value = value,
        ThousandsSeparator = true,
        Dock = DockStyle.Fill,
    };

    private static TextBox TextValue(string text, int maxLength) => new()
    {
        Text = text,
        MaxLength = maxLength,
        Dock = DockStyle.Fill,
    };

    private static TextBox SecretValue(string text, int maxLength) => new()
    {
        Text = text,
        MaxLength = maxLength,
        Dock = DockStyle.Fill,
        UseSystemPasswordChar = true,
        AutoCompleteMode = AutoCompleteMode.None,
    };

    private static ComboBox Combo(params string[] values)
    {
        var combo = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Fill };
        combo.Items.AddRange(values);
        combo.SelectedIndex = 0;
        return combo;
    }

    private static void AddRow(TableLayoutPanel panel, int row, string label, Control control)
    {
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        panel.Controls.Add(new Label { Text = label, AutoSize = true, Margin = new Padding(3, 9, 8, 3) }, 0, row);
        control.Margin = new Padding(3, 4, 3, 4);
        panel.Controls.Add(control, 1, row);
    }

    private static void AddWideRow(TableLayoutPanel panel, int row, string label, Control control, Control action)
    {
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        panel.Controls.Add(new Label { Text = label, AutoSize = true, Margin = new Padding(3, 9, 8, 3) }, 0, row);
        control.Margin = new Padding(3, 4, 3, 4);
        action.Margin = new Padding(8, 4, 3, 4);
        panel.Controls.Add(control, 1, row);
        panel.Controls.Add(action, 2, row);
    }

    private static TableLayoutPanel FormGrid()
    {
        var grid = new TableLayoutPanel { Dock = DockStyle.Top, AutoSize = true, ColumnCount = 2 };
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 175));
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        return grid;
    }

    private static TableLayoutPanel StandardPanel()
    {
        var panel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            RowCount = 3,
            ColumnCount = 1,
            Padding = new Padding(10),
        };
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        panel.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        return panel;
    }

    private static RichTextBox OutputBox() => new()
    {
        Dock = DockStyle.Fill,
        ReadOnly = true,
        Font = new Font("Consolas", 9F),
        BackColor = Color.White,
        DetectUrls = false,
    };

    private static Label InfoLabel(string text) => new()
    {
        Text = text,
        AutoSize = true,
        MaximumSize = new Size(980, 0),
        Padding = new Padding(4, 8, 4, 4),
        ForeColor = Color.DimGray,
    };

    private static TabPage Page(string title, Control content)
    {
        var page = new TabPage(title) { Padding = new Padding(6) };
        page.Controls.Add(content);
        return page;
    }
}

