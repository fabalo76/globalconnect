using GlobalConnect.KeyInjection.Core.Security;

namespace GlobalConnect.KeyInjection.App.Dialogs;

public sealed class DualPasswordDialog : Form
{
    private readonly TextBox _part1 = PasswordBox();
    private readonly TextBox _part2 = PasswordBox();
    private readonly TextBox? _confirm1;
    private readonly TextBox? _confirm2;

    private DualPasswordDialog(string purpose, bool confirm)
    {
        Text = "Dual-custodian authorization";
        StartPosition = FormStartPosition.CenterParent;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MinimizeBox = false;
        MaximizeBox = false;
        ShowInTaskbar = false;
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        Padding = new Padding(18);
        Font = new Font("Segoe UI", 9.5F);

        var layout = new TableLayoutPanel { AutoSize = true, ColumnCount = 2, RowCount = confirm ? 8 : 6 };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 190));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 300));
        var heading = new Label
        {
            Text = purpose,
            Font = new Font("Segoe UI Semibold", 12F),
            AutoSize = true,
            Margin = new Padding(0, 0, 0, 8)
        };
        layout.Controls.Add(heading, 0, 0);
        layout.SetColumnSpan(heading, 2);
        var note = new Label
        {
            Text = "Both independent password parts are required. Each custodian should enter only their own part.",
            AutoSize = true,
            MaximumSize = new Size(480, 0),
            ForeColor = Color.DimGray,
            Margin = new Padding(0, 0, 0, 14)
        };
        layout.Controls.Add(note, 0, 1);
        layout.SetColumnSpan(note, 2);
        AddRow(layout, 2, "Custodian 1 password", _part1);
        var row = 3;
        if (confirm)
        {
            _confirm1 = PasswordBox();
            AddRow(layout, row++, "Confirm custodian 1", _confirm1);
        }
        AddRow(layout, row++, "Custodian 2 password", _part2);
        if (confirm)
        {
            _confirm2 = PasswordBox();
            AddRow(layout, row++, "Confirm custodian 2", _confirm2);
        }

        var buttons = new FlowLayoutPanel { AutoSize = true, FlowDirection = FlowDirection.RightToLeft, Dock = DockStyle.Fill, Margin = new Padding(0, 14, 0, 0) };
        var okay = new Button { Text = confirm ? "Protect file" : "Unlock file", AutoSize = true, DialogResult = DialogResult.None };
        var cancel = new Button { Text = "Cancel", AutoSize = true, DialogResult = DialogResult.Cancel };
        okay.Click += (_, _) => ValidateAndClose(confirm);
        buttons.Controls.Add(okay);
        buttons.Controls.Add(cancel);
        layout.Controls.Add(buttons, 0, row);
        layout.SetColumnSpan(buttons, 2);
        Controls.Add(layout);
        AcceptButton = okay;
        CancelButton = cancel;
    }

    public static DualPasswordResult? Prompt(IWin32Window owner, string purpose, bool confirm)
    {
        using var dialog = new DualPasswordDialog(purpose, confirm);
        if (dialog.ShowDialog(owner) != DialogResult.OK) return null;
        var result = new DualPasswordResult(dialog._part1.Text, dialog._part2.Text);
        dialog.ClearSecrets();
        return result;
    }

    private void ValidateAndClose(bool confirm)
    {
        try
        {
            ProtectedKeyVault.ValidatePasswords(_part1.Text, _part2.Text);
            if (confirm && (_part1.Text != _confirm1!.Text || _part2.Text != _confirm2!.Text))
                throw new ArgumentException("A password confirmation does not match.");
            DialogResult = DialogResult.OK;
            Close();
        }
        catch (ArgumentException error)
        {
            MessageBox.Show(this, error.Message, Text, MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    private void ClearSecrets()
    {
        _part1.Clear();
        _part2.Clear();
        _confirm1?.Clear();
        _confirm2?.Clear();
    }

    private static TextBox PasswordBox() => new() { Width = 285, UseSystemPasswordChar = true, MaxLength = 128 };

    private static void AddRow(TableLayoutPanel layout, int row, string label, Control control)
    {
        layout.Controls.Add(new Label { Text = label, AutoSize = true, Anchor = AnchorStyles.Left, Margin = new Padding(0, 7, 8, 7) }, 0, row);
        control.Margin = new Padding(0, 4, 0, 4);
        layout.Controls.Add(control, 1, row);
    }
}

public sealed record DualPasswordResult(string Part1, string Part2);
