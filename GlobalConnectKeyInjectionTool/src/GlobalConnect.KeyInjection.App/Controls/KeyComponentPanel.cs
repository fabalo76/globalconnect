using GlobalConnect.KeyInjection.Core.Crypto;
using System.Security.Cryptography;

namespace GlobalConnect.KeyInjection.App.Controls;

public sealed class KeyComponentPanel : TableLayoutPanel
{
    private readonly SecureHexGroupInput _component1 = new();
    private readonly SecureHexGroupInput _component2 = new();
    private readonly SecureHexGroupInput _component3 = new();
    private readonly CheckBox _useThird = new() { Text = "Use third component", AutoSize = true };
    private readonly Label _kcv = new() { Text = "—", AutoSize = true, ForeColor = Color.FromArgb(0, 107, 117) };
    private readonly Label _component1Kcv = ComponentKcvLabel();
    private readonly Label _component2Kcv = ComponentKcvLabel();
    private readonly Label _component3Kcv = ComponentKcvLabel();
    private readonly ComboBox _keyLength = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 250 };

    public event EventHandler? StateChanged;

    public KeyComponentPanel()
    {
        AutoSize = true;
        Dock = DockStyle.Top;
        ColumnCount = 3;
        ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 160));
        ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 100));
        Controls.Add(Label("Key length"), 0, 0);
        Controls.Add(_keyLength, 1, 0);
        SetColumnSpan(_keyLength, 2);
        Controls.Add(Label("Component 1"), 0, 1);
        Controls.Add(_component1, 1, 1);
        Controls.Add(_component1Kcv, 2, 1);
        Controls.Add(Label("Component 2"), 0, 2);
        Controls.Add(_component2, 1, 2);
        Controls.Add(_component2Kcv, 2, 2);
        Controls.Add(new Label(), 0, 3);
        Controls.Add(_useThird, 1, 3);
        Controls.Add(Label("Component 3"), 0, 4);
        Controls.Add(_component3, 1, 4);
        Controls.Add(_component3Kcv, 2, 4);
        Controls.Add(Label("Combined-key KCV"), 0, 5);
        Controls.Add(_kcv, 1, 5);

        _keyLength.Items.AddRange([
            new KeyLengthOption(8, "Double length — 32 hex (8 groups)"),
            new KeyLengthOption(12, "Triple length — 48 hex (12 groups)")
        ]);
        _keyLength.SelectedIndexChanged += (_, _) => UpdateKeyLength();
        _keyLength.SelectedIndex = 0;

        _component3.Enabled = false;
        _component3Kcv.Enabled = false;
        _useThird.CheckedChanged += (_, _) =>
        {
            _component3.Enabled = _useThird.Checked;
            _component3Kcv.Enabled = _useThird.Checked;
            UpdateKcv();
        };
        foreach (var component in new[] { _component1, _component2, _component3 })
            component.ValueChanged += (_, _) => UpdateKcv();
    }

    public string Combine()
    {
        var components = _useThird.Checked
            ? new[] { _component1.CompleteValue, _component2.CompleteValue, _component3.CompleteValue }
            : new[] { _component1.CompleteValue, _component2.CompleteValue };
        return KeyMaterial.CombineComponents(components);
    }

    public string Kcv => KeyMaterial.CalculateKcv(Combine(), 3);

    public bool IsComplete =>
        _component1.IsComplete &&
        _component2.IsComplete &&
        (!_useThird.Checked || _component3.IsComplete);

    public bool HasInput => _component1.HasInput || _component2.HasInput || _component3.HasInput;

    public void ClearSecrets()
    {
        _component1.ClearSecret();
        _component2.ClearSecret();
        _component3.ClearSecret();
        _kcv.Text = "—";
    }

    private void UpdateKcv()
    {
        _component1Kcv.Text = ComponentKcv(_component1);
        _component2Kcv.Text = ComponentKcv(_component2);
        _component3Kcv.Text = ComponentKcv(_component3);
        try { _kcv.Text = Kcv; }
        catch (ArgumentException) { _kcv.Text = "—"; }
        catch (CryptographicException) { _kcv.Text = "—"; }
        StateChanged?.Invoke(this, EventArgs.Empty);
    }

    private static string ComponentKcv(SecureHexGroupInput component)
    {
        if (!component.IsComplete) return "KCV: —";
        try { return $"KCV: {KeyMaterial.CalculateKcv(component.CompleteValue, 3)}"; }
        catch (ArgumentException) { return "KCV: —"; }
        catch (CryptographicException) { return "KCV: —"; }
    }

    private void UpdateKeyLength()
    {
        if (_keyLength.SelectedItem is not KeyLengthOption option) return;
        foreach (var component in new[] { _component1, _component2, _component3 })
            component.VisibleGroupCount = option.GroupCount;
        UpdateKcv();
    }

    private static Label ComponentKcvLabel() => new()
    {
        Text = "KCV: —",
        AutoSize = true,
        Anchor = AnchorStyles.Left,
        ForeColor = Color.FromArgb(0, 107, 117),
        Margin = new Padding(8, 6, 0, 6)
    };

    private static Label Label(string text) => new()
    {
        Text = text,
        AutoSize = true,
        Anchor = AnchorStyles.Left,
        Margin = new Padding(0, 6, 8, 6)
    };

    private sealed record KeyLengthOption(int GroupCount, string Name)
    {
        public override string ToString() => Name;
    }
}
