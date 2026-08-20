namespace GlobalConnect.KeyInjection.App.Controls;

/// <summary>
/// Secure hexadecimal entry split into four-character groups. Only the active
/// group is shown; completed and inactive groups are masked.
/// </summary>
public sealed class SecureHexGroupInput : FlowLayoutPanel
{
    private const int GroupSize = 4;
    private const int GroupCount = 12;
    private readonly TextBox[] _groups = new TextBox[GroupCount];
    private bool _suppressChanges;
    private int _visibleGroupCount = GroupCount;

    public SecureHexGroupInput()
    {
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowAndShrink;
        Dock = DockStyle.Top;
        WrapContents = false;
        Margin = Padding.Empty;
        Padding = Padding.Empty;

        for (var index = 0; index < GroupCount; index++)
        {
            var group = new SecureGroupTextBox
            {
                Width = 43,
                MaxLength = GroupSize,
                CharacterCasing = CharacterCasing.Upper,
                Font = new Font("Consolas", 9.5F),
                Margin = new Padding(0, 0, index == GroupCount - 1 ? 0 : 4, 0),
                TabIndex = index,
                TabStop = true,
                AccessibleName = $"Hex group {index + 1} of {GroupCount}"
            };

            var capturedIndex = index;
            group.Enter += (_, _) => Reveal(group);
            group.Leave += (_, _) => Mask(group);
            group.PasteRequested += (_, _) => PasteClipboardValue(capturedIndex);
            group.KeyPress += (_, args) => HandleKeyPress(capturedIndex, args);
            group.KeyDown += (_, args) => NavigateWithKeyboard(capturedIndex, args);
            group.TextChanged += (_, _) => GroupTextChanged(capturedIndex);
            _groups[index] = group;
            Controls.Add(group);
        }

        VisibleGroupCount = 8;
    }

    public event EventHandler? ValueChanged;

    public int VisibleGroupCount
    {
        get => _visibleGroupCount;
        set
        {
            if (value is not (8 or 12))
                throw new ArgumentOutOfRangeException(nameof(value), "Component entry supports 8 or 12 groups.");
            if (_visibleGroupCount == value) return;

            _suppressChanges = true;
            try
            {
                _visibleGroupCount = value;
                for (var index = 0; index < _groups.Length; index++)
                {
                    var visible = index < value;
                    if (!visible)
                    {
                        _groups[index].Clear();
                        _groups[index].UseSystemPasswordChar = false;
                    }
                    _groups[index].Visible = visible;
                    _groups[index].AccessibleName = $"Hex group {index + 1} of {value}";
                }
            }
            finally
            {
                _suppressChanges = false;
            }

            PerformLayout();
            ValueChanged?.Invoke(this, EventArgs.Empty);
        }
    }

    public int ExpectedHexLength => VisibleGroupCount * GroupSize;

    public string Value
    {
        get
        {
            var visibleGroups = _groups.Take(VisibleGroupCount).ToArray();
            var lastUsedGroup = Array.FindLastIndex(visibleGroups, group => group.TextLength > 0);
            if (lastUsedGroup < 0) return string.Empty;

            for (var index = 0; index < lastUsedGroup; index++)
            {
                if (visibleGroups[index].TextLength != GroupSize)
                    throw new ArgumentException("Enter key material in consecutive four-character groups.");
            }

            return string.Concat(visibleGroups.Take(lastUsedGroup + 1).Select(group => group.Text));
        }
    }

    public string CompleteValue
    {
        get
        {
            if (!IsComplete)
                throw new ArgumentException($"Enter the complete {ExpectedHexLength}-character component.");
            return string.Concat(_groups.Take(VisibleGroupCount).Select(group => group.Text));
        }
    }

    public bool IsComplete => _groups.Take(VisibleGroupCount).All(group => group.TextLength == GroupSize);

    public bool HasInput => _groups.Take(VisibleGroupCount).Any(group => group.TextLength > 0);

    public void ClearSecret()
    {
        _suppressChanges = true;
        try
        {
            foreach (var group in _groups)
            {
                group.Clear();
                group.UseSystemPasswordChar = false;
            }
        }
        finally
        {
            _suppressChanges = false;
        }

        ValueChanged?.Invoke(this, EventArgs.Empty);
    }

    private void GroupTextChanged(int index)
    {
        if (_suppressChanges) return;

        var group = _groups[index];
        var sanitized = new string(group.Text
            .Where(Uri.IsHexDigit)
            .Take(GroupSize)
            .Select(char.ToUpperInvariant)
            .ToArray());

        if (!string.Equals(group.Text, sanitized, StringComparison.Ordinal))
        {
            _suppressChanges = true;
            group.Text = sanitized;
            group.SelectionStart = group.TextLength;
            _suppressChanges = false;
        }

        ValueChanged?.Invoke(this, EventArgs.Empty);

        if (group.Focused && group.TextLength == GroupSize)
        {
            Mask(group);
            if (index + 1 < VisibleGroupCount)
                BeginInvoke(() => FocusGroup(index + 1, selectAll: false));
        }
    }

    private void NavigateWithKeyboard(int index, KeyEventArgs args)
    {
        if ((args.Control && args.KeyCode == Keys.V) || (args.Shift && args.KeyCode == Keys.Insert))
        {
            args.SuppressKeyPress = true;
            PasteClipboardValue(index);
            return;
        }

        var group = _groups[index];
        if (args.KeyCode == Keys.Back && group.TextLength == 0 && index > 0)
        {
            args.SuppressKeyPress = true;
            FocusGroup(index - 1, selectAll: false, caretAtEnd: true);
            return;
        }

        if (args.KeyCode == Keys.Left && group.SelectionStart == 0 && index > 0)
        {
            args.SuppressKeyPress = true;
            FocusGroup(index - 1, selectAll: false, caretAtEnd: true);
            return;
        }

        if (args.KeyCode == Keys.Right && group.SelectionStart == group.TextLength && index + 1 < VisibleGroupCount)
        {
            args.SuppressKeyPress = true;
            FocusGroup(index + 1, selectAll: false);
        }
    }

    private void HandleKeyPress(int index, KeyPressEventArgs args)
    {
        if (char.IsControl(args.KeyChar)) return;

        if (!Uri.IsHexDigit(args.KeyChar))
        {
            args.Handled = true;
            return;
        }

        var current = _groups[index];
        if (current.TextLength < GroupSize || current.SelectionLength > 0) return;

        args.Handled = true;
        var nextIndex = -1;
        for (var candidate = index + 1; candidate < VisibleGroupCount; candidate++)
        {
            if (_groups[candidate].TextLength >= GroupSize) continue;
            nextIndex = candidate;
            break;
        }
        if (nextIndex < 0) return;

        var next = _groups[nextIndex];
        FocusGroup(nextIndex, selectAll: false, caretAtEnd: true);
        next.SelectedText = char.ToUpperInvariant(args.KeyChar).ToString();
    }

    private void PasteClipboardValue(int startIndex)
    {
        string clipboardText;
        try
        {
            if (!Clipboard.ContainsText()) return;
            clipboardText = Clipboard.GetText();
        }
        catch (System.Runtime.InteropServices.ExternalException)
        {
            System.Media.SystemSounds.Beep.Play();
            return;
        }

        var compact = new string(clipboardText.Where(character => !char.IsWhiteSpace(character)).ToArray());
        if (compact.Length == 0 || compact.Any(character => !Uri.IsHexDigit(character)))
        {
            System.Media.SystemSounds.Beep.Play();
            return;
        }

        var normalized = compact.ToUpperInvariant();
        var availableCharacters = (VisibleGroupCount - startIndex) * GroupSize;
        if (normalized.Length > availableCharacters)
        {
            System.Media.SystemSounds.Beep.Play();
            return;
        }

        PopulateFromPaste(startIndex, normalized);
    }

    private void PopulateFromPaste(int startIndex, string normalized)
    {
        _suppressChanges = true;
        try
        {
            for (var index = startIndex; index < VisibleGroupCount; index++)
            {
                var offset = (index - startIndex) * GroupSize;
                var remaining = normalized.Length - offset;
                _groups[index].Text = remaining <= 0
                    ? string.Empty
                    : normalized.Substring(offset, Math.Min(GroupSize, remaining));
                Mask(_groups[index]);
            }
        }
        finally
        {
            _suppressChanges = false;
        }

        ValueChanged?.Invoke(this, EventArgs.Empty);

        var populatedGroups = (normalized.Length + GroupSize - 1) / GroupSize;
        var finalIndex = startIndex + populatedGroups - 1;
        if (normalized.Length % GroupSize != 0)
            FocusGroup(finalIndex, selectAll: false, caretAtEnd: true);
        else if (finalIndex + 1 < VisibleGroupCount)
            FocusGroup(finalIndex + 1, selectAll: false);
    }

    private void FocusGroup(int index, bool selectAll, bool caretAtEnd = false)
    {
        var group = _groups[index];
        group.Focus();
        if (selectAll)
            group.SelectAll();
        else
            group.SelectionStart = caretAtEnd ? group.TextLength : 0;
    }

    private static void Reveal(TextBox group)
    {
        group.UseSystemPasswordChar = false;
        group.SelectionStart = group.TextLength;
    }

    private static void Mask(TextBox group)
    {
        if (group.TextLength > 0)
            group.UseSystemPasswordChar = true;
    }

    private sealed class SecureGroupTextBox : TextBox
    {
        private const int WmPaste = 0x0302;

        public event EventHandler? PasteRequested;

        protected override void WndProc(ref Message message)
        {
            if (message.Msg == WmPaste)
            {
                PasteRequested?.Invoke(this, EventArgs.Empty);
                return;
            }

            base.WndProc(ref message);
        }
    }
}
