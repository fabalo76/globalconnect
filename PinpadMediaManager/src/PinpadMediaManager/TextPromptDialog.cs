namespace PinpadMediaManager;

internal sealed class TextPromptDialog : Form
{
    private readonly TextBox _valueTextBox = new();

    private TextPromptDialog(string title, string label, string initialValue, int maximumLength)
    {
        Text = title;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        StartPosition = FormStartPosition.CenterParent;
        MaximizeBox = false;
        MinimizeBox = false;
        ShowInTaskbar = false;
        ClientSize = new Size(450, 145);
        AutoScaleMode = AutoScaleMode.Dpi;

        var labelControl = new Label
        {
            AutoSize = true,
            Text = label,
            Location = new Point(16, 16),
        };
        _valueTextBox.Location = new Point(16, 43);
        _valueTextBox.Size = new Size(418, 27);
        _valueTextBox.MaxLength = maximumLength;
        _valueTextBox.Text = initialValue;
        _valueTextBox.SelectAll();

        var cancelButton = new Button
        {
            Text = "Cancel",
            DialogResult = DialogResult.Cancel,
            Location = new Point(254, 91),
            Size = new Size(85, 34),
        };
        var okButton = new Button
        {
            Text = "OK",
            DialogResult = DialogResult.OK,
            Location = new Point(349, 91),
            Size = new Size(85, 34),
        };

        AcceptButton = okButton;
        CancelButton = cancelButton;
        Controls.AddRange([labelControl, _valueTextBox, cancelButton, okButton]);
    }

    public static string? ShowPrompt(
        IWin32Window owner,
        string title,
        string label,
        string initialValue,
        int maximumLength)
    {
        using var dialog = new TextPromptDialog(title, label, initialValue, maximumLength);
        return dialog.ShowDialog(owner) == DialogResult.OK
            ? dialog._valueTextBox.Text.Trim()
            : null;
    }
}
