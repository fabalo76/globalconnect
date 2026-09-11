using System.ComponentModel;
using PinpadMediaManager.Core;
using PinpadMediaManager.Core.Models;
using PinpadMediaManager.Core.Transport;

namespace PinpadMediaManager;

public sealed class MainForm : Form
{
    private const int DefaultBaudRate = 9_600;
    private static readonly int[] SupportedBaudRates =
        [1_200, 2_400, 4_800, 9_600, 19_200, 38_400, 57_600, 115_200];
    private static readonly QrSample[] QrSamples =
    [
        new("Plain text", "Global Connect ONE — PINPAD QR test"),
        new("Website URL", "https://portal.globalconnect.one"),
        new("Wi-Fi network", "WIFI:T:WPA;S:GlobalConnect-Demo;P:12345678;;"),
        new("Email", "mailto:support@globalconnect.one?subject=PINPAD%20QR%20test"),
        new("Phone number", "tel:+50640000000"),
        new("SMS message", "SMSTO:+50640000000:Global Connect QR test"),
        new(
            "Payment reference",
            """{"reference":"GC-DEMO-0001","amount":"100.00","currency":"USD"}"""),
        new("Spanish / UTF-8", "Prueba de código QR — Español: áéíóú ñ"),
    ];

    private readonly PinpadClient _client;
    private readonly ComboBox _connectionTypeCombo = new();
    private readonly ComboBox _portCombo = new();
    private readonly ComboBox _baudCombo = new();
    private readonly Label _serialPortLabel = new();
    private readonly Label _baudLabel = new();
    private readonly Label _hostLabel = new();
    private readonly Label _tcpPortLabel = new();
    private readonly TextBox _hostTextBox = new();
    private readonly NumericUpDown _tcpPort = new();
    private readonly Button _discoverButton = new();
    private readonly Button _connectButton = new();
    private readonly Button _refreshPortsButton = new();
    private readonly Button _applyBaudButton = new();
    private readonly Label _connectionLabel = new();
    private readonly DataGridView _jpegGrid = CreateGrid();
    private readonly DataGridView _mediaGrid = CreateGrid();
    private readonly ComboBox _mediaVolumeCombo = new();
    private readonly Button _setMediaVolumeButton = new();
    private readonly ComboBox _speechLanguageCombo = new();
    private readonly TextBox _speechTextBox = new();
    private readonly Button _speakTextButton = new();
    private readonly PictureBox _imagePreview = new();
    private readonly Label _previewLabel = new();
    private readonly NumericUpDown _signatureTimeout = new();
    private readonly ComboBox _signatureOrientation = new();
    private readonly ComboBox _signatureFormat = new();
    private readonly Button _captureSignatureButton = new();
    private readonly Button _saveSignatureButton = new();
    private readonly PictureBox _signaturePreview = new();
    private readonly Label _signaturePreviewLabel = new();
    private readonly NumericUpDown _photoTimeout = new();
    private readonly ComboBox _photoFacing = new();
    private readonly NumericUpDown _photoQuality = new();
    private readonly Button _capturePhotoButton = new();
    private readonly Button _savePhotoButton = new();
    private readonly PictureBox _photoPreview = new();
    private readonly Label _photoPreviewLabel = new();
    private readonly NumericUpDown _qrTimeout = new();
    private readonly ComboBox _qrFacing = new();
    private readonly ComboBox _qrSampleCombo = new();
    private readonly TextBox _qrValueTextBox = new();
    private readonly TextBox _qrResultTextBox = new();
    private readonly Button _showQrButton = new();
    private readonly Button _scanQrButton = new();
    private readonly ProgressBar _progressBar = new();
    private readonly Label _statusLabel = new();
    private readonly Button _cancelButton = new();
    private readonly RichTextBox _logTextBox = new();
    private readonly List<Control> _operationControls = [];
    private CancellationTokenSource? _operationCancellation;
    private bool _operationInProgress;
    private byte[]? _capturedSignature;
    private SignatureImageFormat _capturedSignatureFormat = SignatureImageFormat.Png;
    private byte[]? _capturedPhoto;

    public MainForm()
    {
        _client = new PinpadClient(new SerialPinpadTransport());
        _client.Trace += AppendLog;

        Text = "Global Connect ONE — Pinpad Demo";
        MinimumSize = new Size(1040, 720);
        ClientSize = new Size(1260, 820);
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        Font = new Font("Segoe UI", 9F);

        BuildLayout();
        ConfigureGrids();
        var preferences = ConnectionPreferencesStore.Load();
        RestoreConnectionPreferences(preferences);
        RefreshPorts(preferences.SerialPort);
        SetConnectedState(false);

        FormClosing += OnFormClosing;
    }

    private void BuildLayout()
    {
        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 3,
            Padding = new Padding(12),
        };
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 210));

        root.Controls.Add(BuildConnectionPanel(), 0, 0);
        root.Controls.Add(BuildWorkspace(), 0, 1);
        root.Controls.Add(BuildActivityPanel(), 0, 2);
        Controls.Add(root);
    }

    private Control BuildConnectionPanel()
    {
        var panel = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            WrapContents = true,
            FlowDirection = FlowDirection.LeftToRight,
            Padding = new Padding(8),
            BackColor = Color.FromArgb(245, 247, 250),
            Margin = new Padding(0, 0, 0, 10),
        };

        panel.Controls.Add(new Label {
            Text = "Connection",
            AutoSize = true,
            Margin = new Padding(3, 8, 3, 3),
        });
        _connectionTypeCombo.DropDownStyle = ComboBoxStyle.DropDownList;
        _connectionTypeCombo.Width = 105;
        _connectionTypeCombo.Items.AddRange(["Serial", "IP"]);
        _connectionTypeCombo.SelectedIndex = 0;
        _connectionTypeCombo.SelectedIndexChanged += (_, _) => UpdateConnectionModeControls();
        panel.Controls.Add(_connectionTypeCombo);

        _serialPortLabel.Text = "Serial port";
        _serialPortLabel.AutoSize = true;
        _serialPortLabel.Margin = new Padding(12, 8, 3, 3);
        panel.Controls.Add(_serialPortLabel);
        _portCombo.DropDownStyle = ComboBoxStyle.DropDownList;
        _portCombo.Width = 125;
        panel.Controls.Add(_portCombo);

        _refreshPortsButton.Text = "Refresh";
        _refreshPortsButton.AutoSize = true;
        _refreshPortsButton.Click += (_, _) => RefreshPorts();
        panel.Controls.Add(_refreshPortsButton);

        _baudLabel.Text = "Baud";
        _baudLabel.AutoSize = true;
        _baudLabel.Margin = new Padding(12, 8, 3, 3);
        panel.Controls.Add(_baudLabel);
        _baudCombo.DropDownStyle = ComboBoxStyle.DropDownList;
        _baudCombo.Width = 110;
        _baudCombo.Items.AddRange(SupportedBaudRates.Cast<object>().ToArray());
        _baudCombo.Format += (_, eventArgs) =>
        {
            if (eventArgs.ListItem is int value)
            {
                eventArgs.Value = value.ToString("N0");
            }
        };
        _baudCombo.SelectedItem = DefaultBaudRate;
        panel.Controls.Add(_baudCombo);

        _hostLabel.Text = "Pinpad address";
        _hostLabel.AutoSize = true;
        _hostLabel.Margin = new Padding(12, 8, 3, 3);
        panel.Controls.Add(_hostLabel);
        _hostTextBox.Width = 145;
        _hostTextBox.PlaceholderText = "192.168.1.50";
        panel.Controls.Add(_hostTextBox);

        _tcpPortLabel.Text = "TCP port";
        _tcpPortLabel.AutoSize = true;
        _tcpPortLabel.Margin = new Padding(12, 8, 3, 3);
        panel.Controls.Add(_tcpPortLabel);
        _tcpPort.Minimum = 1;
        _tcpPort.Maximum = 65_535;
        _tcpPort.Value = 9_100;
        _tcpPort.Width = 80;
        panel.Controls.Add(_tcpPort);

        _discoverButton.Text = "Discover";
        _discoverButton.AutoSize = true;
        _discoverButton.Click += async (_, _) => await DiscoverPinpadsAsync();
        panel.Controls.Add(_discoverButton);

        _connectButton.Text = "Connect";
        _connectButton.AutoSize = true;
        _connectButton.Click += ConnectButtonOnClick;
        panel.Controls.Add(_connectButton);

        _applyBaudButton.Text = "Apply baud to terminal";
        _applyBaudButton.AutoSize = true;
        _applyBaudButton.Click += async (_, _) => await ApplyBaudAsync();
        panel.Controls.Add(_applyBaudButton);

        _connectionLabel.AutoSize = true;
        _connectionLabel.Margin = new Padding(12, 8, 3, 3);
        _connectionLabel.Font = new Font(Font, FontStyle.Bold);
        panel.Controls.Add(_connectionLabel);

        _operationControls.AddRange(
            [
                _connectionTypeCombo, _portCombo, _baudCombo, _refreshPortsButton,
                _hostTextBox, _tcpPort, _discoverButton, _connectButton, _applyBaudButton,
            ]);
        UpdateConnectionModeControls();
        return panel;
    }

    private Control BuildWorkspace()
    {
        var tabs = new ProminentTabControl { Dock = DockStyle.Fill };
        var jpegPage = new TabPage("Images — J commands") { Padding = new Padding(8) };
        var mediaPage = new TabPage("Media — M commands") { Padding = new Padding(8) };
        var signaturePage = new TabPage("Signature — S commands") { Padding = new Padding(8) };
        var cameraQrPage = new TabPage("Camera & QR — PH/QR commands") { Padding = new Padding(8) };
        var a10DemoPage = new TabPage("Pinpad Demo") { Padding = new Padding(8) };
        var offlinePinChangePage = new TabPage("Offline PIN change") { Padding = new Padding(8) };
        var a10Demo = new A10DemoControl(_client, status => _statusLabel.Text = status);
        jpegPage.Controls.Add(BuildJpegPage());
        mediaPage.Controls.Add(BuildMediaPage());
        signaturePage.Controls.Add(BuildSignaturePage());
        cameraQrPage.Controls.Add(BuildCameraQrPage());
        a10DemoPage.Controls.Add(a10Demo);
        offlinePinChangePage.Controls.Add(a10Demo.OfflinePinChangeContent);
        tabs.TabPages.Add(jpegPage);
        tabs.TabPages.Add(mediaPage);
        tabs.TabPages.Add(signaturePage);
        tabs.TabPages.Add(cameraQrPage);
        tabs.TabPages.Add(a10DemoPage);
        tabs.TabPages.Add(offlinePinChangePage);
        return tabs;
    }

    private Control BuildJpegPage()
    {
        var split = new SplitContainer
        {
            Dock = DockStyle.Fill,
            Orientation = Orientation.Vertical,
            SplitterDistance = 790,
            FixedPanel = FixedPanel.Panel2,
        };

        var left = new TableLayoutPanel { Dock = DockStyle.Fill, RowCount = 2, ColumnCount = 1 };
        left.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        left.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        left.Controls.Add(BuildButtonBar(
            ("Refresh", async () => await RefreshJpegsAsync()),
            ("Upload JPEG", async () => await UploadJpegAsync()),
            ("Download", async () => await DownloadJpegAsync()),
            ("Show", async () => await ShowJpegAsync()),
            ("Select", async () => await SetJpegSelectionAsync(true)),
            ("Unselect", async () => await SetJpegSelectionAsync(false)),
            ("Play selected", async () => await PlaySelectedJpegsAsync()),
            ("Delete", async () => await DeleteJpegsAsync()),
            ("Initialize table", async () => await InitializeJpegsAsync())), 0, 0);
        left.Controls.Add(_jpegGrid, 0, 1);
        split.Panel1.Controls.Add(left);

        var previewPanel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            RowCount = 3,
            ColumnCount = 1,
            Padding = new Padding(10),
        };
        previewPanel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        previewPanel.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        previewPanel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        previewPanel.Controls.Add(
            new Label { Text = "Image preview", AutoSize = true, Font = new Font(Font, FontStyle.Bold) }, 0, 0);
        _imagePreview.Dock = DockStyle.Fill;
        _imagePreview.SizeMode = PictureBoxSizeMode.Zoom;
        _imagePreview.BackColor = Color.FromArgb(235, 238, 242);
        _imagePreview.BorderStyle = BorderStyle.FixedSingle;
        previewPanel.Controls.Add(_imagePreview, 0, 1);
        _previewLabel.Text = "Upload or download an image to preview it.";
        _previewLabel.AutoSize = true;
        _previewLabel.MaximumSize = new Size(320, 0);
        previewPanel.Controls.Add(_previewLabel, 0, 2);
        split.Panel2.Controls.Add(previewPanel);

        return split;
    }

    private Control BuildMediaPage()
    {
        var panel = new TableLayoutPanel { Dock = DockStyle.Fill, RowCount = 4, ColumnCount = 1 };
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        panel.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        var commandBar = BuildButtonBar(
            ("Refresh", async () => await RefreshMediaAsync()),
            ("Upload MP3/MP4", async () => await UploadMediaAsync()),
            ("Download", async () => await DownloadMediaAsync()),
            ("Play once", async () => await PlayMediaAsync()),
            ("Delete", async () => await DeleteMediaAsync()),
            ("Initialize table", async () => await InitializeMediaAsync()));
        commandBar.Controls.Add(new Label
        {
            Text = "Volume (steps of 5)",
            AutoSize = true,
            Margin = new Padding(12, 9, 3, 0),
        });
        _mediaVolumeCombo.DropDownStyle = ComboBoxStyle.DropDownList;
        _mediaVolumeCombo.Width = 64;
        _mediaVolumeCombo.Margin = new Padding(3, 5, 3, 0);
        _mediaVolumeCombo.Items.AddRange(
            Enumerable.Range(0, 20)
                .Select(step => step * 5)
                .Append(99)
                .Select(value => (object)value.ToString("D2"))
                .ToArray());
        _mediaVolumeCombo.SelectedItem = "50";
        commandBar.Controls.Add(_mediaVolumeCombo);
        _setMediaVolumeButton.Text = "Set volume";
        _setMediaVolumeButton.AutoSize = true;
        _setMediaVolumeButton.Height = 34;
        _setMediaVolumeButton.Click += async (_, _) => await SetMediaVolumeAsync();
        commandBar.Controls.Add(_setMediaVolumeButton);
        _operationControls.AddRange([_mediaVolumeCombo, _setMediaVolumeButton]);
        panel.Controls.Add(commandBar, 0, 0);
        panel.Controls.Add(_mediaGrid, 0, 1);
        panel.Controls.Add(BuildSpeechBar(), 0, 2);
        panel.Controls.Add(new Label
        {
            AutoSize = true,
            Padding = new Padding(4, 8, 4, 4),
            Text = "M10 initializes the table, M11 lists files, M12 uploads, M13 downloads, M14 plays one file, " +
                   "M15 sets media volume, M16 deletes selected files, and M17 speaks text. " +
                   "MP3 is limited to 16 MiB and MP4 to 64 MiB by the terminal.",
        }, 0, 3);
        return panel;
    }

    private Control BuildSignaturePage()
    {
        var panel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            RowCount = 3,
            ColumnCount = 1,
            Padding = new Padding(12),
        };
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        panel.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));

        var controls = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            AutoSize = true,
            WrapContents = true,
            Padding = new Padding(0, 0, 0, 8),
        };
        controls.Controls.Add(new Label
        {
            Text = "Timeout (seconds)",
            AutoSize = true,
            Margin = new Padding(4, 9, 4, 0),
        });
        _signatureTimeout.Minimum = 5;
        _signatureTimeout.Maximum = 300;
        _signatureTimeout.Value = 60;
        _signatureTimeout.Width = 72;
        _signatureTimeout.Margin = new Padding(3, 5, 12, 0);
        controls.Controls.Add(_signatureTimeout);

        controls.Controls.Add(new Label
        {
            Text = "Direction",
            AutoSize = true,
            Margin = new Padding(4, 9, 4, 0),
        });
        _signatureOrientation.DropDownStyle = ComboBoxStyle.DropDownList;
        _signatureOrientation.Items.AddRange(Enum.GetValues<SignatureOrientation>().Cast<object>().ToArray());
        _signatureOrientation.SelectedItem = SignatureOrientation.Horizontal;
        _signatureOrientation.Width = 110;
        _signatureOrientation.Margin = new Padding(3, 5, 12, 0);
        controls.Controls.Add(_signatureOrientation);

        controls.Controls.Add(new Label
        {
            Text = "Image format",
            AutoSize = true,
            Margin = new Padding(4, 9, 4, 0),
        });
        _signatureFormat.DropDownStyle = ComboBoxStyle.DropDownList;
        _signatureFormat.Items.AddRange(Enum.GetValues<SignatureImageFormat>().Cast<object>().ToArray());
        _signatureFormat.SelectedItem = SignatureImageFormat.Png;
        _signatureFormat.Width = 90;
        _signatureFormat.Margin = new Padding(3, 5, 12, 0);
        controls.Controls.Add(_signatureFormat);

        _captureSignatureButton.Text = "Capture signature";
        _captureSignatureButton.AutoSize = true;
        _captureSignatureButton.Height = 34;
        _captureSignatureButton.Click += async (_, _) => await CaptureSignatureAsync();
        controls.Controls.Add(_captureSignatureButton);
        _saveSignatureButton.Text = "Save signature";
        _saveSignatureButton.AutoSize = true;
        _saveSignatureButton.Height = 34;
        _saveSignatureButton.Enabled = false;
        _saveSignatureButton.Click += async (_, _) => await SaveCapturedSignatureAsync();
        controls.Controls.Add(_saveSignatureButton);
        _operationControls.AddRange(
            [_signatureTimeout, _signatureOrientation, _signatureFormat, _captureSignatureButton, _saveSignatureButton]);
        panel.Controls.Add(controls, 0, 0);

        _signaturePreview.Dock = DockStyle.Fill;
        _signaturePreview.SizeMode = PictureBoxSizeMode.Zoom;
        _signaturePreview.BackColor = Color.White;
        _signaturePreview.BorderStyle = BorderStyle.FixedSingle;
        panel.Controls.Add(_signaturePreview, 0, 1);

        _signaturePreviewLabel.Text =
            "S1 starts capture. S2 returns 1,024-character Base64 packets, which are reassembled here.";
        _signaturePreviewLabel.AutoSize = true;
        _signaturePreviewLabel.Padding = new Padding(4, 8, 4, 4);
        panel.Controls.Add(_signaturePreviewLabel, 0, 2);
        return panel;
    }

    private Control BuildCameraQrPage()
    {
        var split = new SplitContainer
        {
            Dock = DockStyle.Fill,
            Orientation = Orientation.Vertical,
            SplitterDistance = 610,
        };

        var photoPanel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            RowCount = 4,
            ColumnCount = 1,
            Padding = new Padding(10),
        };
        photoPanel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        photoPanel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        photoPanel.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        photoPanel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        photoPanel.Controls.Add(new Label
        {
            Text = "Photo capture",
            AutoSize = true,
            Font = new Font(Font, FontStyle.Bold),
        }, 0, 0);
        var photoControls = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            AutoSize = true,
            WrapContents = true,
            Padding = new Padding(0, 6, 0, 8),
        };
        photoControls.Controls.Add(FormLabel("Timeout"));
        ConfigureTimeout(_photoTimeout);
        photoControls.Controls.Add(_photoTimeout);
        photoControls.Controls.Add(FormLabel("Camera"));
        ConfigureFacing(_photoFacing);
        photoControls.Controls.Add(_photoFacing);
        photoControls.Controls.Add(FormLabel("JPEG quality"));
        _photoQuality.Minimum = 10;
        _photoQuality.Maximum = 100;
        _photoQuality.Value = 80;
        _photoQuality.Width = 65;
        _photoQuality.Margin = new Padding(3, 5, 10, 0);
        photoControls.Controls.Add(_photoQuality);
        _capturePhotoButton.Text = "Capture photo";
        _capturePhotoButton.AutoSize = true;
        _capturePhotoButton.Height = 34;
        _capturePhotoButton.Click += async (_, _) => await CapturePhotoAsync();
        photoControls.Controls.Add(_capturePhotoButton);
        _savePhotoButton.Text = "Save photo";
        _savePhotoButton.AutoSize = true;
        _savePhotoButton.Height = 34;
        _savePhotoButton.Enabled = false;
        _savePhotoButton.Click += async (_, _) => await SaveCapturedPhotoAsync();
        photoControls.Controls.Add(_savePhotoButton);
        photoPanel.Controls.Add(photoControls, 0, 1);
        _photoPreview.Dock = DockStyle.Fill;
        _photoPreview.SizeMode = PictureBoxSizeMode.Zoom;
        _photoPreview.BackColor = Color.FromArgb(235, 238, 242);
        _photoPreview.BorderStyle = BorderStyle.FixedSingle;
        photoPanel.Controls.Add(_photoPreview, 0, 2);
        _photoPreviewLabel.Text = "PH1 starts capture; PH2 returns the accepted JPEG in Base64 packets.";
        _photoPreviewLabel.AutoSize = true;
        _photoPreviewLabel.Padding = new Padding(4, 8, 4, 4);
        photoPanel.Controls.Add(_photoPreviewLabel, 0, 3);
        split.Panel1.Controls.Add(photoPanel);

        var qrPanel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            RowCount = 6,
            ColumnCount = 1,
            Padding = new Padding(10),
        };
        qrPanel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        qrPanel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        qrPanel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        qrPanel.RowStyles.Add(new RowStyle(SizeType.Percent, 45));
        qrPanel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        qrPanel.RowStyles.Add(new RowStyle(SizeType.Percent, 55));
        qrPanel.Controls.Add(new Label
        {
            Text = "QR generation and reading",
            AutoSize = true,
            Font = new Font(Font, FontStyle.Bold),
        }, 0, 0);
        var qrControls = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            AutoSize = true,
            WrapContents = true,
            Padding = new Padding(0, 6, 0, 6),
        };
        qrControls.Controls.Add(FormLabel("Timeout"));
        ConfigureTimeout(_qrTimeout);
        qrControls.Controls.Add(_qrTimeout);
        qrControls.Controls.Add(FormLabel("Scan camera"));
        ConfigureFacing(_qrFacing);
        qrControls.Controls.Add(_qrFacing);
        qrControls.Controls.Add(FormLabel("Sample"));
        _qrSampleCombo.DropDownStyle = ComboBoxStyle.DropDownList;
        _qrSampleCombo.Width = 180;
        _qrSampleCombo.Items.AddRange(QrSamples.Cast<object>().ToArray());
        _qrSampleCombo.SelectedIndexChanged += (_, _) =>
        {
            if (_qrSampleCombo.SelectedItem is QrSample sample)
            {
                _qrValueTextBox.Text = sample.Value;
            }
        };
        _qrSampleCombo.SelectedIndex = 0;
        qrControls.Controls.Add(_qrSampleCombo);
        _showQrButton.Text = "Show QR";
        _showQrButton.AutoSize = true;
        _showQrButton.Height = 34;
        _showQrButton.Click += async (_, _) => await ShowQrCodeAsync();
        qrControls.Controls.Add(_showQrButton);
        _scanQrButton.Text = "Scan QR";
        _scanQrButton.AutoSize = true;
        _scanQrButton.Height = 34;
        _scanQrButton.Click += async (_, _) => await ScanQrCodeAsync();
        qrControls.Controls.Add(_scanQrButton);
        qrPanel.Controls.Add(qrControls, 0, 1);
        qrPanel.Controls.Add(new Label { Text = "QR value to display", AutoSize = true }, 0, 2);
        _qrValueTextBox.Multiline = true;
        _qrValueTextBox.ScrollBars = ScrollBars.Vertical;
        _qrValueTextBox.Dock = DockStyle.Fill;
        _qrValueTextBox.MaxLength = 2_048;
        _qrValueTextBox.PlaceholderText = "Text or URL encoded into the QR displayed by the PINPAD";
        qrPanel.Controls.Add(_qrValueTextBox, 0, 3);
        qrPanel.Controls.Add(new Label
        {
            Text = "Scanned value",
            AutoSize = true,
            Padding = new Padding(0, 8, 0, 0),
        }, 0, 4);
        _qrResultTextBox.Multiline = true;
        _qrResultTextBox.ScrollBars = ScrollBars.Vertical;
        _qrResultTextBox.Dock = DockStyle.Fill;
        _qrResultTextBox.ReadOnly = true;
        qrPanel.Controls.Add(_qrResultTextBox, 0, 5);
        split.Panel2.Controls.Add(qrPanel);

        _operationControls.AddRange(
            [
                _photoTimeout, _photoFacing, _photoQuality, _capturePhotoButton, _savePhotoButton,
                _qrTimeout, _qrFacing, _qrSampleCombo, _qrValueTextBox, _showQrButton, _scanQrButton,
            ]);
        return split;
    }

    private sealed record QrSample(string Name, string Value)
    {
        public override string ToString() => Name;
    }

    private static Label FormLabel(string text) => new()
    {
        Text = text,
        AutoSize = true,
        Margin = new Padding(4, 9, 4, 0),
    };

    private static void ConfigureTimeout(NumericUpDown control)
    {
        control.Minimum = 5;
        control.Maximum = 300;
        control.Value = 60;
        control.Width = 72;
        control.Margin = new Padding(3, 5, 10, 0);
    }

    private static void ConfigureFacing(ComboBox control)
    {
        control.DropDownStyle = ComboBoxStyle.DropDownList;
        control.Items.AddRange(Enum.GetValues<CameraFacing>().Cast<object>().ToArray());
        control.SelectedItem = CameraFacing.Front;
        control.Width = 82;
        control.Margin = new Padding(3, 5, 10, 0);
    }

    private Control BuildSpeechBar()
    {
        var panel = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            AutoSize = true,
            WrapContents = true,
            Padding = new Padding(0, 6, 0, 0),
        };
        panel.Controls.Add(new Label
        {
            Text = "Text to speech",
            AutoSize = true,
            Margin = new Padding(4, 9, 6, 0),
        });
        _speechLanguageCombo.DropDownStyle = ComboBoxStyle.DropDownList;
        _speechLanguageCombo.Width = 64;
        _speechLanguageCombo.Margin = new Padding(3, 5, 3, 0);
        _speechLanguageCombo.Items.AddRange(["es", "en"]);
        _speechLanguageCombo.SelectedItem = "es";
        panel.Controls.Add(_speechLanguageCombo);
        _speechTextBox.Width = 520;
        _speechTextBox.MaxLength = 1_000;
        _speechTextBox.PlaceholderText = "Text for the terminal to speak";
        _speechTextBox.Margin = new Padding(3, 5, 3, 0);
        panel.Controls.Add(_speechTextBox);
        _speakTextButton.Text = "Speak";
        _speakTextButton.AutoSize = true;
        _speakTextButton.Height = 34;
        _speakTextButton.Click += async (_, _) => await SpeakTextAsync();
        panel.Controls.Add(_speakTextButton);
        _operationControls.AddRange([_speechLanguageCombo, _speechTextBox, _speakTextButton]);
        return panel;
    }

    private Control BuildActivityPanel()
    {
        var panel = new TableLayoutPanel { Dock = DockStyle.Fill, RowCount = 2, ColumnCount = 3 };
        panel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        panel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 180));
        panel.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        panel.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

        _statusLabel.Text = "Ready.";
        _statusLabel.AutoSize = true;
        _statusLabel.Anchor = AnchorStyles.Left;
        panel.Controls.Add(_statusLabel, 0, 0);

        _progressBar.Dock = DockStyle.Fill;
        _progressBar.Style = ProgressBarStyle.Continuous;
        panel.Controls.Add(_progressBar, 1, 0);

        _cancelButton.Text = "Cancel";
        _cancelButton.Enabled = false;
        _cancelButton.AutoSize = true;
        _cancelButton.Click += (_, _) => _operationCancellation?.Cancel();
        panel.Controls.Add(_cancelButton, 2, 0);

        _logTextBox.Dock = DockStyle.Fill;
        _logTextBox.ReadOnly = true;
        _logTextBox.BackColor = Color.FromArgb(25, 29, 35);
        _logTextBox.ForeColor = Color.Gainsboro;
        _logTextBox.Font = new Font("Cascadia Mono", 8.5F);
        _logTextBox.WordWrap = false;
        panel.SetColumnSpan(_logTextBox, 3);
        panel.Controls.Add(_logTextBox, 0, 1);
        return panel;
    }

    private FlowLayoutPanel BuildButtonBar(params (string Text, Func<Task> Action)[] definitions)
    {
        var bar = new FlowLayoutPanel
        {
            AutoSize = true,
            Dock = DockStyle.Top,
            WrapContents = true,
            Padding = new Padding(0, 0, 0, 6),
        };
        foreach (var definition in definitions)
        {
            var button = new Button { Text = definition.Text, AutoSize = true, Height = 34 };
            button.Click += async (_, _) => await definition.Action();
            bar.Controls.Add(button);
            _operationControls.Add(button);
        }

        return bar;
    }

    private void ConfigureGrids()
    {
        _jpegGrid.Columns.Add(new DataGridViewTextBoxColumn
        {
            DataPropertyName = nameof(JpegEntry.Name),
            HeaderText = "Terminal name",
            AutoSizeMode = DataGridViewAutoSizeColumnMode.Fill,
        });
        _jpegGrid.Columns.Add(new DataGridViewCheckBoxColumn
        {
            DataPropertyName = nameof(JpegEntry.Selected),
            HeaderText = "In display list",
            Width = 120,
        });

        _mediaGrid.Columns.Add(new DataGridViewTextBoxColumn
        {
            DataPropertyName = nameof(MediaEntry.Name),
            HeaderText = "Terminal name",
            AutoSizeMode = DataGridViewAutoSizeColumnMode.Fill,
        });
        _mediaGrid.Columns.Add(new DataGridViewTextBoxColumn
        {
            DataPropertyName = nameof(MediaEntry.Type),
            HeaderText = "Type",
            Width = 90,
        });
        _mediaGrid.Columns.Add(new DataGridViewTextBoxColumn
        {
            DataPropertyName = nameof(MediaEntry.SizeBytes),
            HeaderText = "Size",
            Width = 130,
            DefaultCellStyle = new DataGridViewCellStyle { Format = "N0" },
        });
    }

    private async void ConnectButtonOnClick(object? sender, EventArgs eventArgs)
    {
        if (_client.IsConnected)
        {
            if (_operationInProgress)
            {
                return;
            }

            _client.Disconnect();
            SetConnectedState(false);
            return;
        }

        if (IsIpMode)
        {
            var host = _hostTextBox.Text.Trim();
            var tcpPort = decimal.ToInt32(_tcpPort.Value);
            if (string.IsNullOrWhiteSpace(host))
            {
                MessageBox.Show(
                    this,
                    "Enter the pinpad IP address or use Discover.",
                    "Connection",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Information);
                return;
            }

            await RunOperationAsync(_ =>
            {
                _client.ConnectTcp(host, tcpPort);
                SaveConnectionPreferences();
                SetConnectedState(true);
                return Task.CompletedTask;
            }, $"Connecting to {host}:{tcpPort}…", requiresConnection: false);
            return;
        }

        if (_portCombo.SelectedItem is not string portName || _baudCombo.SelectedItem is not int baudRate)
        {
            MessageBox.Show(this, "Select a serial port and baud rate.", "Connection", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        await RunOperationAsync(_ =>
        {
            _client.Connect(portName, baudRate);
            SaveConnectionPreferences();
            SetConnectedState(true);
            return Task.CompletedTask;
        }, $"Connecting to {portName}…", requiresConnection: false);
    }

    private async Task ApplyBaudAsync()
    {
        if (_client.IsTcpConnection)
        {
            return;
        }
        if (_baudCombo.SelectedItem is not int baudRate)
        {
            return;
        }

        await RunOperationAsync(
            token => _client.ChangeBaudRateAsync(baudRate, token),
            $"Changing terminal and local port to {baudRate:N0} bps…");
        SetConnectedState(_client.IsConnected);
    }

    private bool IsIpMode =>
        string.Equals(_connectionTypeCombo.SelectedItem as string, "IP", StringComparison.Ordinal);

    private void UpdateConnectionModeControls()
    {
        var ipMode = IsIpMode;
        _serialPortLabel.Visible = !ipMode;
        _portCombo.Visible = !ipMode;
        _refreshPortsButton.Visible = !ipMode;
        _baudLabel.Visible = !ipMode;
        _baudCombo.Visible = !ipMode;
        _applyBaudButton.Visible = !ipMode;
        _hostLabel.Visible = ipMode;
        _hostTextBox.Visible = ipMode;
        _tcpPortLabel.Visible = ipMode;
        _tcpPort.Visible = ipMode;
        _discoverButton.Visible = ipMode;
        SetConnectedState(_client.IsConnected);
    }

    private async Task DiscoverPinpadsAsync()
    {
        IReadOnlyList<DiscoveredPinpad> devices = [];
        var completed = await RunOperationAsync(async token =>
        {
            devices = await PinpadLanDiscovery.DiscoverAsync(TimeSpan.FromSeconds(2), token);
        }, "Discovering pinpads on the local network…", requiresConnection: false);
        if (!completed)
        {
            return;
        }

        if (devices.Count == 0)
        {
            MessageBox.Show(
                this,
                "No IP pinpads answered on this LAN. Confirm that IP mode is enabled and the PC is on the same Wi-Fi/Ethernet network.",
                "Pinpad discovery",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
            _statusLabel.Text = "No IP pinpads discovered.";
            return;
        }

        var selected = devices.Count == 1
            ? devices[0]
            : PinpadDiscoveryDialog.SelectDevice(this, devices);
        if (selected is null)
        {
            return;
        }

        _hostTextBox.Text = selected.Address;
        _tcpPort.Value = selected.TcpPort;
        _statusLabel.Text = $"Selected {selected.SerialNumber} ({selected.Model}) at {selected.Address}:{selected.TcpPort}.";
    }

    private async Task RefreshJpegsAsync() =>
        await RunOperationAsync(async token =>
        {
            var entries = await _client.GetJpegTableAsync(token);
            _jpegGrid.DataSource = new BindingList<JpegEntry>(entries.ToList());
            _statusLabel.Text = $"{entries.Count:N0} JPEG file(s) loaded.";
        }, "Reading JPEG table…");

    private async Task UploadJpegAsync()
    {
        using var dialog = new OpenFileDialog
        {
            Title = "Select JPEG image",
            Filter = "JPEG image (*.jpg;*.jpeg)|*.jpg;*.jpeg|All files (*.*)|*.*",
            CheckFileExists = true,
        };
        if (dialog.ShowDialog(this) != DialogResult.OK)
        {
            return;
        }

        var suggested = Path.GetFileNameWithoutExtension(dialog.FileName);
        if (suggested.Length > 15)
        {
            suggested = suggested[..15];
        }

        var name = TextPromptDialog.ShowPrompt(this, "Terminal JPEG name", "Name (maximum 15 characters)", suggested, 15);
        if (string.IsNullOrWhiteSpace(name))
        {
            return;
        }

        var uploaded = await RunOperationAsync(
            token => _client.UploadJpegAsync(dialog.FileName, name, overwrite: true, CreateProgress(), token),
            $"Uploading JPEG {name}…");
        if (!uploaded)
        {
            return;
        }

        PreviewImage(await File.ReadAllBytesAsync(dialog.FileName), $"{name} — local upload");
        await RefreshJpegsAsync();
    }

    private async Task DownloadJpegAsync()
    {
        var entry = SelectedJpeg();
        if (entry is null)
        {
            return;
        }

        byte[]? content = null;
        await RunOperationAsync(async token =>
        {
            content = await _client.DownloadJpegAsync(entry.Name, CreateProgress(), token);
        }, $"Downloading JPEG {entry.Name}…");
        if (content is null)
        {
            return;
        }

        PreviewImage(content, $"{entry.Name} — {content.LongLength:N0} bytes");
        using var dialog = new SaveFileDialog
        {
            Title = "Save JPEG",
            Filter = "JPEG image (*.jpg)|*.jpg|All files (*.*)|*.*",
            FileName = $"{entry.Name}.jpg",
        };
        if (dialog.ShowDialog(this) == DialogResult.OK)
        {
            await File.WriteAllBytesAsync(dialog.FileName, content);
            _statusLabel.Text = $"Saved {dialog.FileName}.";
        }
    }

    private async Task ShowJpegAsync()
    {
        var entry = SelectedJpeg();
        if (entry is null)
        {
            return;
        }

        await RunOperationAsync(token => _client.ShowJpegAsync(entry.Name, token), $"Showing {entry.Name} on terminal…");
    }

    private async Task SetJpegSelectionAsync(bool selected)
    {
        var entries = SelectedJpegs();
        if (entries.Count == 0)
        {
            return;
        }

        var updated = await RunOperationAsync(
            token => _client.SelectJpegsAsync(entries.Select(value => value.Name), selected, token),
            $"{(selected ? "Selecting" : "Unselecting")} {entries.Count:N0} JPEG(s)…");
        if (updated)
        {
            await RefreshJpegsAsync();
        }
    }

    private async Task PlaySelectedJpegsAsync() =>
        await RunOperationAsync(token => _client.PlaySelectedJpegsAsync(token), "Starting selected JPEG display list…");

    private async Task DeleteJpegsAsync()
    {
        var entries = SelectedJpegs();
        if (entries.Count == 0)
        {
            return;
        }

        if (MessageBox.Show(
                this,
                $"Delete {entries.Count:N0} selected JPEG file(s) from the terminal?",
                "Delete JPEGs",
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Warning) != DialogResult.Yes)
        {
            return;
        }

        var deleted = await RunOperationAsync(
            token => _client.DeleteJpegsAsync(entries.Select(value => value.Name), token),
            $"Deleting {entries.Count:N0} JPEG(s)…");
        if (deleted)
        {
            await RefreshJpegsAsync();
        }
    }

    private async Task InitializeJpegsAsync()
    {
        if (MessageBox.Show(
                this,
                "Initialize the JPEG table? This permanently deletes every terminal JPEG.",
                "Initialize JPEG table",
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Warning) != DialogResult.Yes)
        {
            return;
        }

        if (await RunOperationAsync(token => _client.InitializeJpegTableAsync(token), "Initializing JPEG table…"))
        {
            await RefreshJpegsAsync();
        }
    }

    private async Task RefreshMediaAsync() =>
        await RunOperationAsync(async token =>
        {
            var entries = await _client.GetMediaTableAsync(token);
            _mediaGrid.DataSource = new BindingList<MediaEntry>(entries.ToList());
            _statusLabel.Text = $"{entries.Count:N0} media file(s) loaded.";
        }, "Reading media table…");

    private async Task UploadMediaAsync()
    {
        using var dialog = new OpenFileDialog
        {
            Title = "Select MP3 or MP4",
            Filter = "Supported media (*.mp3;*.mp4)|*.mp3;*.mp4|MP3 audio (*.mp3)|*.mp3|MP4 video (*.mp4)|*.mp4",
            CheckFileExists = true,
        };
        if (dialog.ShowDialog(this) != DialogResult.OK)
        {
            return;
        }

        var suggested = Path.GetFileName(dialog.FileName);
        var name = TextPromptDialog.ShowPrompt(this, "Terminal media name", "Name with .mp3 or .mp4 extension (maximum 64 characters)", suggested, 64);
        if (string.IsNullOrWhiteSpace(name))
        {
            return;
        }

        var uploaded = await RunOperationAsync(
            token => _client.UploadMediaAsync(dialog.FileName, name, overwrite: true, CreateProgress(), token),
            $"Uploading media {name}…");
        if (uploaded)
        {
            await RefreshMediaAsync();
        }
    }

    private async Task DownloadMediaAsync()
    {
        var entry = SelectedMedia();
        if (entry is null)
        {
            return;
        }

        byte[]? content = null;
        await RunOperationAsync(async token =>
        {
            content = await _client.DownloadMediaAsync(entry.Name, CreateProgress(), token);
        }, $"Downloading media {entry.Name}…");
        if (content is null)
        {
            return;
        }

        using var dialog = new SaveFileDialog
        {
            Title = "Save media",
            Filter = entry.Type == MediaType.Mp3 ? "MP3 audio (*.mp3)|*.mp3" : "MP4 video (*.mp4)|*.mp4",
            FileName = entry.Name,
        };
        if (dialog.ShowDialog(this) == DialogResult.OK)
        {
            await File.WriteAllBytesAsync(dialog.FileName, content);
            _statusLabel.Text = $"Saved {dialog.FileName}.";
        }
    }

    private async Task PlayMediaAsync()
    {
        var entry = SelectedMedia();
        if (entry is null)
        {
            return;
        }

        await RunOperationAsync(token => _client.PlayMediaAsync(entry.Name, token), $"Playing {entry.Name} once…");
    }

    private async Task DeleteMediaAsync()
    {
        var entries = SelectedMediaEntries();
        if (entries.Count == 0)
        {
            MessageBox.Show(this, "Select one or more media rows first.", "Media selection", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        if (MessageBox.Show(
                this,
                $"Delete {entries.Count:N0} selected media file(s) from the terminal?",
                "Delete media",
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Warning) != DialogResult.Yes)
        {
            return;
        }

        var deleted = await RunOperationAsync(
            token => _client.DeleteMediaAsync(entries.Select(value => value.Name), token),
            $"Deleting {entries.Count:N0} media file(s)…");
        if (deleted)
        {
            await RefreshMediaAsync();
        }
    }

    private async Task SetMediaVolumeAsync()
    {
        if (_mediaVolumeCombo.SelectedItem is not string selected ||
            !int.TryParse(selected, out var volume))
        {
            return;
        }

        await RunOperationAsync(
            token => _client.SetMediaVolumeAsync(volume, token),
            $"Setting media volume to {volume:D2}…");
    }

    private async Task SpeakTextAsync()
    {
        if (_speechLanguageCombo.SelectedItem is not string language ||
            string.IsNullOrWhiteSpace(_speechTextBox.Text))
        {
            MessageBox.Show(
                this,
                "Select a language and enter text to speak.",
                "Text to speech",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
            return;
        }

        await RunOperationAsync(
            token => _client.SpeakTextAsync(language, _speechTextBox.Text, token),
            $"Speaking text in {language}…");
    }

    private async Task CaptureSignatureAsync()
    {
        if (_signatureOrientation.SelectedItem is not SignatureOrientation orientation ||
            _signatureFormat.SelectedItem is not SignatureImageFormat imageFormat)
        {
            return;
        }

        SignatureCaptureResult? result = null;
        await RunOperationAsync(async token =>
        {
            result = await _client.CaptureSignatureAsync(
                decimal.ToInt32(_signatureTimeout.Value),
                orientation,
                imageFormat,
                CreateProgress(),
                token);
        }, "Waiting for signature capture…");
        if (result is null)
        {
            return;
        }

        if (result.Status != SignatureCaptureStatus.Captured)
        {
            ClearCapturedSignature();
            _statusLabel.Text = $"Signature capture result: {result.Status}.";
            _signaturePreviewLabel.Text = $"No signature image returned. Result: {result.Status}.";
            return;
        }

        _capturedSignature = result.ImageBytes;
        _capturedSignatureFormat = result.ImageFormat;
        PreviewImage(
            result.ImageBytes,
            $"Captured {result.ImageFormat} signature — {result.ImageBytes.LongLength:N0} bytes",
            _signaturePreview,
            _signaturePreviewLabel);
        _saveSignatureButton.Enabled = true;
        _statusLabel.Text = "Signature captured. Use Save signature to store it.";
    }

    private async Task SaveCapturedSignatureAsync()
    {
        var imageBytes = _capturedSignature;
        if (imageBytes is null)
        {
            return;
        }

        var extension = _capturedSignatureFormat == SignatureImageFormat.Png ? "png" : "jpg";
        using var dialog = new SaveFileDialog
        {
            Title = "Save captured signature",
            Filter = _capturedSignatureFormat == SignatureImageFormat.Png
                ? "PNG image (*.png)|*.png|All files (*.*)|*.*"
                : "JPEG image (*.jpg)|*.jpg|All files (*.*)|*.*",
            FileName = $"signature-{DateTime.Now:yyyyMMdd-HHmmss}.{extension}",
        };
        if (dialog.ShowDialog(this) == DialogResult.OK)
        {
            await File.WriteAllBytesAsync(dialog.FileName, imageBytes);
            _statusLabel.Text = $"Saved {dialog.FileName}.";
        }
    }

    private void ClearCapturedSignature()
    {
        _capturedSignature = null;
        _saveSignatureButton.Enabled = false;
        var previous = _signaturePreview.Image;
        _signaturePreview.Image = null;
        previous?.Dispose();
    }

    private async Task CapturePhotoAsync()
    {
        if (_photoFacing.SelectedItem is not CameraFacing facing)
        {
            return;
        }

        PhotoCaptureResult? result = null;
        await RunOperationAsync(async token =>
        {
            result = await _client.CapturePhotoAsync(
                decimal.ToInt32(_photoTimeout.Value),
                facing,
                decimal.ToInt32(_photoQuality.Value),
                CreateProgress(),
                token);
        }, "Waiting for photo capture…");
        if (result is null)
        {
            return;
        }
        if (result.Status != PhotoCaptureStatus.Captured)
        {
            ClearCapturedPhoto();
            _statusLabel.Text = $"Photo capture result: {result.Status}.";
            _photoPreviewLabel.Text = $"No photo returned. Result: {result.Status}.";
            return;
        }

        _capturedPhoto = result.JpegBytes;
        PreviewImage(
            result.JpegBytes,
            $"Captured JPEG photo — {result.JpegBytes.LongLength:N0} bytes",
            _photoPreview,
            _photoPreviewLabel);
        _savePhotoButton.Enabled = true;
        _statusLabel.Text = "Photo captured. Use Save photo to store it.";
    }

    private async Task SaveCapturedPhotoAsync()
    {
        var photo = _capturedPhoto;
        if (photo is null)
        {
            return;
        }
        using var dialog = new SaveFileDialog
        {
            Title = "Save captured photo",
            Filter = "JPEG image (*.jpg)|*.jpg|All files (*.*)|*.*",
            FileName = $"photo-{DateTime.Now:yyyyMMdd-HHmmss}.jpg",
        };
        if (dialog.ShowDialog(this) == DialogResult.OK)
        {
            await File.WriteAllBytesAsync(dialog.FileName, photo);
            _statusLabel.Text = $"Saved {dialog.FileName}.";
        }
    }

    private void ClearCapturedPhoto()
    {
        _capturedPhoto = null;
        _savePhotoButton.Enabled = false;
        var previous = _photoPreview.Image;
        _photoPreview.Image = null;
        previous?.Dispose();
    }

    private async Task ShowQrCodeAsync()
    {
        if (string.IsNullOrWhiteSpace(_qrValueTextBox.Text))
        {
            MessageBox.Show(
                this,
                "Enter the text or URL to encode.",
                "Show QR",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
            return;
        }

        QrOperationStatus? result = null;
        await RunOperationAsync(async token =>
        {
            result = await _client.ShowQrCodeAsync(
                _qrValueTextBox.Text,
                decimal.ToInt32(_qrTimeout.Value),
                token);
        }, "Displaying QR code on the PINPAD…");
        if (result is not null)
        {
            _statusLabel.Text = $"QR display result: {result}.";
        }
    }

    private async Task ScanQrCodeAsync()
    {
        if (_qrFacing.SelectedItem is not CameraFacing facing)
        {
            return;
        }

        QrScanResult? result = null;
        await RunOperationAsync(async token =>
        {
            result = await _client.ScanQrCodeAsync(
                decimal.ToInt32(_qrTimeout.Value),
                facing,
                token);
        }, "Waiting for the PINPAD to scan a QR code…");
        if (result is null)
        {
            return;
        }
        _qrResultTextBox.Text = result.Value ?? "";
        _statusLabel.Text = result.Status == QrOperationStatus.Completed
            ? "QR code read successfully."
            : $"QR scan result: {result.Status}.";
    }

    private async Task InitializeMediaAsync()
    {
        if (MessageBox.Show(
                this,
                "Initialize the media table? This permanently deletes every MP3 and MP4 file on the terminal.",
                "Initialize media table",
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Warning) != DialogResult.Yes)
        {
            return;
        }

        if (await RunOperationAsync(token => _client.InitializeMediaTableAsync(token), "Initializing media table…"))
        {
            await RefreshMediaAsync();
        }
    }

    private async Task<bool> RunOperationAsync(
        Func<CancellationToken, Task> operation,
        string status,
        bool requiresConnection = true)
    {
        if (_operationInProgress)
        {
            return false;
        }

        if (requiresConnection && !_client.IsConnected)
        {
            MessageBox.Show(this, "Connect to a pinpad first.", "Not connected", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return false;
        }

        _operationInProgress = true;
        _operationCancellation = new CancellationTokenSource();
        SetOperationControls(false);
        _cancelButton.Enabled = true;
        _progressBar.Style = ProgressBarStyle.Marquee;
        _statusLabel.Text = status;
        try
        {
            await operation(_operationCancellation.Token);
            if (!_statusLabel.Text.EndsWith(".", StringComparison.Ordinal))
            {
                _statusLabel.Text = "Completed.";
            }

            return true;
        }
        catch (OperationCanceledException)
        {
            _statusLabel.Text = "Canceled.";
            AppendLog($"{DateTime.Now:HH:mm:ss.fff}  Operation canceled.");
            return false;
        }
        catch (Exception error)
        {
            _statusLabel.Text = "Failed.";
            AppendLog($"{DateTime.Now:HH:mm:ss.fff}  ERROR {error.Message}");
            MessageBox.Show(this, error.Message, "Pinpad operation failed", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return false;
        }
        finally
        {
            _operationCancellation.Dispose();
            _operationCancellation = null;
            _operationInProgress = false;
            _cancelButton.Enabled = false;
            _progressBar.Style = ProgressBarStyle.Continuous;
            _progressBar.Value = 0;
            SetOperationControls(true);
            SetConnectedState(_client.IsConnected);
        }
    }

    private IProgress<TransferProgress> CreateProgress() =>
        new Progress<TransferProgress>(progress =>
        {
            _progressBar.Style = progress.Total > 0 ? ProgressBarStyle.Continuous : ProgressBarStyle.Marquee;
            if (progress.Total > 0)
            {
                _progressBar.Value = progress.Percentage;
            }

            _statusLabel.Text = progress.Total > 0
                ? $"{progress.Operation}: {progress.Completed:N0}/{progress.Total:N0} packets ({progress.Percentage:N0}%)"
                : $"{progress.Operation}: {progress.Completed:N0} packets";
        });

    private void RefreshPorts(string? preferredPort = null)
    {
        var selected = string.IsNullOrWhiteSpace(preferredPort)
            ? _portCombo.SelectedItem as string
            : preferredPort;
        var ports = SerialPinpadTransport.GetPortNames();
        _portCombo.DataSource = ports;
        var availableSelection = selected is null
            ? null
            : ports.FirstOrDefault(port => string.Equals(port, selected, StringComparison.OrdinalIgnoreCase));
        if (availableSelection is not null)
        {
            _portCombo.SelectedItem = availableSelection;
        }

        if (ports.Length == 0)
        {
            _statusLabel.Text = "No serial ports detected.";
        }
    }

    private void RestoreConnectionPreferences(ConnectionPreferences preferences)
    {
        if (_connectionTypeCombo.Items.Contains(preferences.ConnectionType))
        {
            _connectionTypeCombo.SelectedItem = preferences.ConnectionType;
        }
        if (SupportedBaudRates.Contains(preferences.BaudRate))
        {
            _baudCombo.SelectedItem = preferences.BaudRate;
        }
        _hostTextBox.Text = preferences.Host;
        if (preferences.TcpPort is >= 1 and <= 65_535)
        {
            _tcpPort.Value = preferences.TcpPort;
        }
        UpdateConnectionModeControls();
    }

    private void SaveConnectionPreferences()
    {
        ConnectionPreferencesStore.Save(new ConnectionPreferences(
            _connectionTypeCombo.SelectedItem as string ?? "Serial",
            _portCombo.SelectedItem as string ?? "",
            _baudCombo.SelectedItem is int baudRate ? baudRate : DefaultBaudRate,
            _hostTextBox.Text.Trim(),
            decimal.ToInt32(_tcpPort.Value)));
    }

    private void SetConnectedState(bool connected)
    {
        _connectButton.Text = connected ? "Disconnect" : "Connect";
        _connectionLabel.Text = connected
            ? _client.IsTcpConnection
                ? $"Connected: {_client.PortName}"
                : $"Connected: {_client.PortName} @ {_client.BaudRate:N0}"
            : "Disconnected";
        _connectionLabel.ForeColor = connected ? Color.ForestGreen : Color.Firebrick;
        _connectionTypeCombo.Enabled = !connected && !_operationInProgress;
        _portCombo.Enabled = !connected && !_operationInProgress && !IsIpMode;
        _refreshPortsButton.Enabled = !connected && !_operationInProgress && !IsIpMode;
        _baudCombo.Enabled = !connected && !_operationInProgress && !IsIpMode;
        _hostTextBox.Enabled = !connected && !_operationInProgress && IsIpMode;
        _tcpPort.Enabled = !connected && !_operationInProgress && IsIpMode;
        _discoverButton.Enabled = !connected && !_operationInProgress && IsIpMode;
        _applyBaudButton.Enabled = connected && !_operationInProgress && !_client.IsTcpConnection;
    }

    private void SetOperationControls(bool enabled)
    {
        foreach (var control in _operationControls)
        {
            control.Enabled = enabled;
        }
        _saveSignatureButton.Enabled = enabled && _capturedSignature is not null;
        _savePhotoButton.Enabled = enabled && _capturedPhoto is not null;
    }

    private JpegEntry? SelectedJpeg()
    {
        var values = SelectedJpegs();
        if (values.Count == 0)
        {
            MessageBox.Show(this, "Select a JPEG row first.", "JPEG selection", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return null;
        }

        return values[0];
    }

    private List<JpegEntry> SelectedJpegs() =>
        _jpegGrid.SelectedRows.Cast<DataGridViewRow>()
            .Select(row => row.DataBoundItem)
            .OfType<JpegEntry>()
            .ToList();

    private MediaEntry? SelectedMedia()
    {
        var entry = SelectedMediaEntries().FirstOrDefault();
        if (entry is null)
        {
            MessageBox.Show(this, "Select a media row first.", "Media selection", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }

        return entry;
    }

    private List<MediaEntry> SelectedMediaEntries() =>
        _mediaGrid.SelectedRows.Cast<DataGridViewRow>()
            .Select(row => row.DataBoundItem)
            .OfType<MediaEntry>()
            .ToList();

    private void PreviewImage(byte[] content, string description) =>
        PreviewImage(content, description, _imagePreview, _previewLabel);

    private static void PreviewImage(
        byte[] content,
        string description,
        PictureBox preview,
        Label previewLabel)
    {
        try
        {
            using var stream = new MemoryStream(content);
            using var source = Image.FromStream(stream);
            var copy = new Bitmap(source);
            var previous = preview.Image;
            preview.Image = copy;
            previous?.Dispose();
            previewLabel.Text = $"{description}{Environment.NewLine}{copy.Width:N0} × {copy.Height:N0}";
        }
        catch (Exception error)
        {
            previewLabel.Text = $"Preview unavailable: {error.Message}";
        }
    }

    private void AppendLog(string value)
    {
        if (InvokeRequired)
        {
            BeginInvoke(() => AppendLog(value));
            return;
        }

        _logTextBox.AppendText(value + Environment.NewLine);
        _logTextBox.SelectionStart = _logTextBox.TextLength;
        _logTextBox.ScrollToCaret();
    }

    private void OnFormClosing(object? sender, FormClosingEventArgs eventArgs)
    {
        if (_operationInProgress)
        {
            var answer = MessageBox.Show(
                this,
                "A transfer is active. Cancel it and close the application?",
                "Close Pinpad Demo",
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Warning);
            if (answer != DialogResult.Yes)
            {
                eventArgs.Cancel = true;
                return;
            }

            _operationCancellation?.Cancel();
        }

        _imagePreview.Image?.Dispose();
        _signaturePreview.Image?.Dispose();
        SaveConnectionPreferences();
        _client.Dispose();
    }

    private static DataGridView CreateGrid() =>
        new()
        {
            Dock = DockStyle.Fill,
            AutoGenerateColumns = false,
            AllowUserToAddRows = false,
            AllowUserToDeleteRows = false,
            AllowUserToResizeRows = false,
            ReadOnly = true,
            MultiSelect = true,
            SelectionMode = DataGridViewSelectionMode.FullRowSelect,
            RowHeadersVisible = false,
            BackgroundColor = SystemColors.Window,
            BorderStyle = BorderStyle.Fixed3D,
        };
}
