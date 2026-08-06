using PinpadMediaManager.Core.Models;

namespace PinpadMediaManager;

internal sealed partial class A10DemoControl
{
    private Control BuildSecretKeysPage()
    {
        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            RowCount = 2,
            Padding = new Padding(8),
        };
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.Controls.Add(BuildSecretKeyInjectionGroup(), 0, 0);
        root.Controls.Add(BuildSecretPinRequestGroup(), 1, 0);

        var help = InfoLabel(
            "Commands 20 and 21 use the PINPAD's separate Secret MK/SK key family. The terminal may request " +
            "dual-password authorization before accepting each key. Commands 22, 23, and 24 request a PIN " +
            "using a loaded secret session-key slot. Key material, account data, and PIN blocks are redacted " +
            "from the shared protocol trace.");
        root.Controls.Add(help, 0, 1);
        root.SetColumnSpan(help, 2);
        return root;
    }

    private Control BuildSecretKeyInjectionGroup()
    {
        var group = new GroupBox
        {
            Text = "Secret key injection — commands 20 / 21",
            Dock = DockStyle.Fill,
            Padding = new Padding(10),
        };
        var panel = StandardPanel();
        panel.Padding = new Padding(4);
        var form = FormGrid();
        var masterOption = Combo("0 - Clear text", "1 - Encrypted by previous Secret MK");
        var masterKey = SecretValue("0123456789ABCDEFFEDCBA9876543210", 32);
        var sessionKeyId = Combo(Enumerable.Range(0, 10).Select(value => value.ToString()).ToArray());
        var sessionKey = SecretValue("0123456789ABCDEFFEDCBA9876543210", 32);
        var showKeys = new CheckBox { Text = "Show secret key material", AutoSize = true };
        showKeys.CheckedChanged += (_, _) =>
        {
            masterKey.UseSystemPasswordChar = !showKeys.Checked;
            sessionKey.UseSystemPasswordChar = !showKeys.Checked;
        };

        AddRow(form, 0, "Secret MK option", masterOption);
        AddRow(form, 1, "Secret master key", masterKey);
        AddRow(form, 2, "Secret SK slot", sessionKeyId);
        AddRow(form, 3, "Encrypted session key", sessionKey);
        AddRow(form, 4, "", showKeys);
        var actions = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true, WrapContents = true };
        var output = OutputBox();
        actions.Controls.Add(ActionButton("Load Secret MK (20)", async () =>
        {
            await _client.LoadA10SecretMasterKeyAsync(masterOption.SelectedIndex, masterKey.Text);
            output.Text = "Command 20 completed. The PINPAD echoed and accepted the Secret Master Key.";
        }));
        actions.Controls.Add(ActionButton("Load Secret SK (21)", async () =>
        {
            await _client.LoadA10SecretSessionKeyAsync(sessionKeyId.SelectedIndex, sessionKey.Text);
            output.Text = $"Command 21 completed. Secret session-key slot {sessionKeyId.SelectedIndex} was accepted.";
        }));
        form.Controls.Add(actions, 0, 5);
        form.SetColumnSpan(actions, 2);
        panel.Controls.Add(form, 0, 0);
        panel.Controls.Add(output, 0, 1);
        panel.Controls.Add(InfoLabel(
            "Use option 0 only for controlled test injection. Option 1 expects a key encrypted by the previously loaded Secret MK. Loading a new Secret MK clears resident Secret SK slots."), 0, 2);
        group.Controls.Add(panel);
        return group;
    }

    private Control BuildSecretPinRequestGroup()
    {
        var group = new GroupBox
        {
            Text = "Secret PIN request — commands 22 / 23 / 24",
            Dock = DockStyle.Fill,
            Padding = new Padding(10),
        };
        var panel = StandardPanel();
        panel.Padding = new Padding(4);
        var form = FormGrid();
        var promptMode = Combo("22 - Standard prompt", "23 - External prompt", "24 - Custom prompt");
        var account = TextValue("4111111111111111", 19);
        var sessionKeyId = Combo(Enumerable.Range(0, 10).Select(value => value.ToString()).ToArray());
        var amount = TextValue("50.00", 14);
        var minimum = new NumericUpDown { Minimum = 0, Maximum = 12, Value = 4, Dock = DockStyle.Fill };
        var maximum = new NumericUpDown { Minimum = 0, Maximum = 12, Value = 12, Dock = DockStyle.Fill };
        var allowNull = new CheckBox { Text = "Allow Enter without PIN", AutoSize = true };
        var firstPrompt = TextValue("ENTER PIN", 16);
        var secondPrompt = TextValue("PRESS ENTER", 16);
        var completionPrompt = TextValue("PROCESSING", 16);

        AddRow(form, 0, "Command / prompt", promptMode);
        AddRow(form, 1, "Account / PAN", account);
        AddRow(form, 2, "Secret SK slot", sessionKeyId);
        AddRow(form, 3, "Amount (22 only)", amount);
        AddRow(form, 4, "Minimum PIN length", minimum);
        AddRow(form, 5, "Maximum PIN length", maximum);
        AddRow(form, 6, "Null PIN", allowNull);
        AddRow(form, 7, "First / external prompt", firstPrompt);
        AddRow(form, 8, "Second prompt", secondPrompt);
        AddRow(form, 9, "Completion prompt", completionPrompt);

        void UpdateModeControls()
        {
            var mode = (A10PinPromptMode)promptMode.SelectedIndex;
            amount.Enabled = mode == A10PinPromptMode.Standard;
            minimum.Enabled = mode == A10PinPromptMode.CustomPrompt;
            maximum.Enabled = mode == A10PinPromptMode.CustomPrompt;
            allowNull.Enabled = mode == A10PinPromptMode.CustomPrompt;
            firstPrompt.Enabled = mode != A10PinPromptMode.Standard;
            secondPrompt.Enabled = mode == A10PinPromptMode.CustomPrompt;
            completionPrompt.Enabled = mode == A10PinPromptMode.CustomPrompt;
        }
        promptMode.SelectedIndexChanged += (_, _) => UpdateModeControls();
        UpdateModeControls();

        var output = OutputBox();
        var start = ActionButton("Request PIN", async () =>
        {
            var result = await _client.StartA10SecretPinEntryAsync(new A10SecretPinEntryRequest(
                (A10PinPromptMode)promptMode.SelectedIndex,
                account.Text,
                sessionKeyId.SelectedIndex,
                amount.Text,
                (int)minimum.Value,
                (int)maximum.Value,
                allowNull.Checked,
                firstPrompt.Text,
                secondPrompt.Text,
                completionPrompt.Text));
            output.Text = string.Join(
                Environment.NewLine,
                $"Command: {Code(promptMode)}",
                $"Status: {result.Status}",
                $"Encrypted PIN block: {result.EncryptedPinBlock}",
                $"PIN length: {result.PinLength?.ToString() ?? "Not returned"}",
                $"Key identifier: {result.KeyIdentifier ?? "Not returned"}",
                "",
                "Sensitive request and response material is redacted from the shared protocol trace.");
        });
        form.Controls.Add(start, 0, 10);
        form.SetColumnSpan(start, 2);
        panel.Controls.Add(form, 0, 0);
        panel.Controls.Add(output, 0, 1);
        panel.Controls.Add(InfoLabel(
            "Command 23 sends the external prompt before requesting the PIN. Command 24 enables PIN limits, null-PIN control, and custom prompts."), 0, 2);
        group.Controls.Add(panel);
        return group;
    }
}
