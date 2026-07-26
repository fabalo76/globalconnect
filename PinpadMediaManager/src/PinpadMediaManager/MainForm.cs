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

    private readonly PinpadClient _client;
    private readonly ComboBox _portCombo = new();
    private readonly ComboBox _baudCombo = new();
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
    private readonly ProgressBar _progressBar = new();
    private readonly Label _statusLabel = new();
    private readonly Button _cancelButton = new();
    private readonly RichTextBox _logTextBox = new();
    private readonly List<Control> _operationControls = [];
    private CancellationTokenSource? _operationCancellation;
    private bool _operationInProgress;

    public MainForm()
    {
        _client = new PinpadClient(new SerialPinpadTransport());
        _client.Trace += AppendLog;

        Text = "Global Connect ONE — Pinpad Media Manager";
        MinimumSize = new Size(1040, 720);
        ClientSize = new Size(1260, 820);
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        Font = new Font("Segoe UI", 9F);

        BuildLayout();
        ConfigureGrids();
        RefreshPorts();
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
        var panel = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 9,
            Padding = new Padding(8),
            BackColor = Color.FromArgb(245, 247, 250),
            Margin = new Padding(0, 0, 0, 10),
        };

        panel.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        panel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 135));
        panel.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        panel.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        panel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 130));
        panel.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        panel.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        panel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        panel.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));

        panel.Controls.Add(new Label { Text = "Serial port", AutoSize = true, Anchor = AnchorStyles.Left }, 0, 0);
        _portCombo.DropDownStyle = ComboBoxStyle.DropDownList;
        _portCombo.Dock = DockStyle.Fill;
        panel.Controls.Add(_portCombo, 1, 0);

        _refreshPortsButton.Text = "Refresh";
        _refreshPortsButton.AutoSize = true;
        _refreshPortsButton.Click += (_, _) => RefreshPorts();
        panel.Controls.Add(_refreshPortsButton, 2, 0);

        panel.Controls.Add(new Label { Text = "Baud", AutoSize = true, Anchor = AnchorStyles.Left }, 3, 0);
        _baudCombo.DropDownStyle = ComboBoxStyle.DropDownList;
        _baudCombo.Dock = DockStyle.Fill;
        _baudCombo.Items.AddRange(SupportedBaudRates.Cast<object>().ToArray());
        _baudCombo.Format += (_, eventArgs) =>
        {
            if (eventArgs.ListItem is int value)
            {
                eventArgs.Value = value.ToString("N0");
            }
        };
        _baudCombo.SelectedItem = DefaultBaudRate;
        panel.Controls.Add(_baudCombo, 4, 0);

        _connectButton.Text = "Connect";
        _connectButton.AutoSize = true;
        _connectButton.Click += ConnectButtonOnClick;
        panel.Controls.Add(_connectButton, 5, 0);

        _applyBaudButton.Text = "Apply baud to terminal";
        _applyBaudButton.AutoSize = true;
        _applyBaudButton.Click += async (_, _) => await ApplyBaudAsync();
        panel.Controls.Add(_applyBaudButton, 6, 0);

        _connectionLabel.AutoSize = true;
        _connectionLabel.Anchor = AnchorStyles.Right;
        _connectionLabel.Font = new Font(Font, FontStyle.Bold);
        panel.Controls.Add(_connectionLabel, 8, 0);

        _operationControls.AddRange(
            [_portCombo, _baudCombo, _refreshPortsButton, _connectButton, _applyBaudButton]);
        return panel;
    }

    private Control BuildWorkspace()
    {
        var tabs = new TabControl { Dock = DockStyle.Fill };
        var jpegPage = new TabPage("Images — J commands") { Padding = new Padding(8) };
        var mediaPage = new TabPage("Media — M commands") { Padding = new Padding(8) };
        jpegPage.Controls.Add(BuildJpegPage());
        mediaPage.Controls.Add(BuildMediaPage());
        tabs.TabPages.Add(jpegPage);
        tabs.TabPages.Add(mediaPage);
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

        if (_portCombo.SelectedItem is not string portName || _baudCombo.SelectedItem is not int baudRate)
        {
            MessageBox.Show(this, "Select a serial port and baud rate.", "Connection", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        await RunOperationAsync(_ =>
        {
            _client.Connect(portName, baudRate);
            SetConnectedState(true);
            return Task.CompletedTask;
        }, $"Connecting to {portName}…", requiresConnection: false);
    }

    private async Task ApplyBaudAsync()
    {
        if (_baudCombo.SelectedItem is not int baudRate)
        {
            return;
        }

        await RunOperationAsync(
            token => _client.ChangeBaudRateAsync(baudRate, token),
            $"Changing terminal and local port to {baudRate:N0} bps…");
        SetConnectedState(_client.IsConnected);
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

    private void RefreshPorts()
    {
        var selected = _portCombo.SelectedItem as string;
        var ports = SerialPinpadTransport.GetPortNames();
        _portCombo.DataSource = ports;
        if (selected is not null && ports.Contains(selected, StringComparer.OrdinalIgnoreCase))
        {
            _portCombo.SelectedItem = selected;
        }

        if (ports.Length == 0)
        {
            _statusLabel.Text = "No serial ports detected.";
        }
    }

    private void SetConnectedState(bool connected)
    {
        _connectButton.Text = connected ? "Disconnect" : "Connect";
        _connectionLabel.Text = connected
            ? $"Connected: {_client.PortName} @ {_client.BaudRate:N0}"
            : "Disconnected";
        _connectionLabel.ForeColor = connected ? Color.ForestGreen : Color.Firebrick;
        _portCombo.Enabled = !connected && !_operationInProgress;
        _refreshPortsButton.Enabled = !connected && !_operationInProgress;
        _baudCombo.Enabled = !_operationInProgress;
        _applyBaudButton.Enabled = connected && !_operationInProgress;
    }

    private void SetOperationControls(bool enabled)
    {
        foreach (var control in _operationControls)
        {
            control.Enabled = enabled;
        }
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

    private void PreviewImage(byte[] content, string description)
    {
        try
        {
            using var stream = new MemoryStream(content);
            using var source = Image.FromStream(stream);
            var copy = new Bitmap(source);
            var previous = _imagePreview.Image;
            _imagePreview.Image = copy;
            previous?.Dispose();
            _previewLabel.Text = $"{description}{Environment.NewLine}{copy.Width:N0} × {copy.Height:N0}";
        }
        catch (Exception error)
        {
            _previewLabel.Text = $"Preview unavailable: {error.Message}";
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
                "Close Pinpad Media Manager",
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
