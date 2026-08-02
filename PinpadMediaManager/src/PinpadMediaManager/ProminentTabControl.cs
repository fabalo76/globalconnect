namespace PinpadMediaManager;

/// <summary>
/// A higher-contrast tab control that remains legible with the standard Windows
/// light theme. Owner drawing is limited to the headers, so normal TabControl
/// layout, keyboard navigation, and accessibility behaviour are preserved.
/// </summary>
internal sealed class ProminentTabControl : TabControl
{
    private static readonly Color SelectedBackColor = Color.FromArgb(20, 91, 158);
    private static readonly Color SelectedBorderColor = Color.FromArgb(10, 58, 105);
    private static readonly Color InactiveBackColor = Color.FromArgb(232, 237, 243);
    private static readonly Color InactiveBorderColor = Color.FromArgb(112, 126, 140);

    public ProminentTabControl()
    {
        DrawMode = TabDrawMode.OwnerDrawFixed;
        SizeMode = TabSizeMode.Normal;
        Padding = new Point(12, 5);
        ItemSize = new Size(0, 30);
    }

    protected override void OnDrawItem(DrawItemEventArgs eventArgs)
    {
        if (eventArgs.Index < 0 || eventArgs.Index >= TabPages.Count) return;

        var selected = eventArgs.Index == SelectedIndex;
        var bounds = GetTabRect(eventArgs.Index);
        if (selected)
        {
            // Let the active tab visually join the page while retaining a clear outline.
            bounds.Inflate(1, 1);
        }

        using var background = new SolidBrush(selected ? SelectedBackColor : InactiveBackColor);
        using var border = new Pen(selected ? SelectedBorderColor : InactiveBorderColor, selected ? 2f : 1f);
        using var font = new Font(Font, FontStyle.Bold);

        eventArgs.Graphics.FillRectangle(background, bounds);
        eventArgs.Graphics.DrawRectangle(border, bounds.X, bounds.Y, bounds.Width - 1, bounds.Height - 1);
        TextRenderer.DrawText(
            eventArgs.Graphics,
            TabPages[eventArgs.Index].Text,
            font,
            bounds,
            selected ? Color.White : Color.FromArgb(25, 35, 45),
            TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.SingleLine |
            TextFormatFlags.EndEllipsis);

        if (Focused && selected)
        {
            var focusBounds = Rectangle.Inflate(bounds, -4, -4);
            ControlPaint.DrawFocusRectangle(eventArgs.Graphics, focusBounds, Color.White, SelectedBackColor);
        }
    }
}
