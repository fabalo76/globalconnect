using PinpadMediaManager.Core.Transport;

namespace PinpadMediaManager;

internal static class PinpadDiscoveryDialog
{
    public static DiscoveredPinpad? SelectDevice(
        IWin32Window owner,
        IReadOnlyList<DiscoveredPinpad> devices)
    {
        using var dialog = new Form
        {
            Text = "Select discovered pinpad",
            ClientSize = new Size(620, 140),
            MinimumSize = new Size(620, 180),
            StartPosition = FormStartPosition.CenterParent,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false,
            MinimizeBox = false,
            ShowInTaskbar = false,
            Font = new Font("Segoe UI", 9F),
        };
        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(12),
            ColumnCount = 2,
            RowCount = 3,
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.Controls.Add(new Label
        {
            Text = "More than one pinpad answered. Select the device to connect:",
            AutoSize = true,
        }, 0, 0);
        layout.SetColumnSpan(layout.GetControlFromPosition(0, 0)!, 2);

        var combo = new ComboBox
        {
            Dock = DockStyle.Top,
            DropDownStyle = ComboBoxStyle.DropDownList,
            DataSource = devices.ToArray(),
        };
        layout.Controls.Add(combo, 0, 1);
        layout.SetColumnSpan(combo, 2);

        var cancel = new Button
        {
            Text = "Cancel",
            AutoSize = true,
            DialogResult = DialogResult.Cancel,
        };
        var select = new Button
        {
            Text = "Select",
            AutoSize = true,
            DialogResult = DialogResult.OK,
        };
        var buttons = new FlowLayoutPanel
        {
            AutoSize = true,
            FlowDirection = FlowDirection.LeftToRight,
        };
        buttons.Controls.Add(cancel);
        buttons.Controls.Add(select);
        layout.Controls.Add(buttons, 1, 2);
        dialog.Controls.Add(layout);
        dialog.AcceptButton = select;
        dialog.CancelButton = cancel;

        return dialog.ShowDialog(owner) == DialogResult.OK
            ? combo.SelectedItem as DiscoveredPinpad
            : null;
    }
}
