using System.ComponentModel;
using PinpadMediaManager.Core.Protocol;

namespace PinpadMediaManager;

public sealed partial class MainForm
{
    private readonly DataGridView _audioGrid = CreateGrid();
    private readonly Label _audioStatus = new() { AutoSize = true, Text = "Refresh to see recordings on the terminal." };

    private Control BuildAudioRecordingsPage()
    {
        var panel = new TableLayoutPanel { Dock = DockStyle.Fill, RowCount = 4, ColumnCount = 1 };
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        panel.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        var buttons = BuildButtonBar(
            ("Start recording", StartAudioAsync), ("Stop recording", StopAudioAsync),
            ("Refresh list", RefreshAudioAsync), ("Get recording…", GetAudioAsync),
            ("Delete selected", DeleteAudioAsync), ("Delete all recordings", ResetAudioAsync));
        panel.Controls.Add(buttons, 0, 0);
        panel.Controls.Add(_audioStatus, 0, 1);
        _audioGrid.MultiSelect = false;
        _audioGrid.Columns.Add(new DataGridViewTextBoxColumn { HeaderText = "File name", DataPropertyName = "Name", AutoSizeMode = DataGridViewAutoSizeColumnMode.Fill });
        _audioGrid.Columns.Add(new DataGridViewTextBoxColumn { HeaderText = "State", DataPropertyName = "State", Width = 110 });
        _audioGrid.Columns.Add(new DataGridViewTextBoxColumn { HeaderText = "Duration", DataPropertyName = "Duration", Width = 100 });
        _audioGrid.Columns.Add(new DataGridViewTextBoxColumn { HeaderText = "Bytes", DataPropertyName = "SizeBytes", Width = 120 });
        panel.Controls.Add(_audioGrid, 0, 2);
        panel.Controls.Add(new Label {
            AutoSize = true, Padding = new Padding(4, 10, 4, 4),
            Text = "N6 Pro only. Recordings stop after 30 minutes or a pinpad reset. The newest 10 recordings are kept; " +
                "starting another removes the oldest. Stop a recording before retrieving or deleting it. " +
                "Delete all also stops capture. Refresh after an automatic stop or pinpad reset."
        }, 0, 3);
        return panel;
    }

    private async Task RefreshAudioAsync() => await RunOperationAsync(async token => {
        var entries = await _client.ListAudioRecordingsAsync(token);
        _audioGrid.DataSource = new BindingList<AudioRecordingEntry>(entries.ToList());
        var active = entries.FirstOrDefault(entry => entry.State == "Recording");
        _audioStatus.Text = active is null ? $"{entries.Count} recording(s). No capture is running." : $"Recording: {active.Name}";
    }, "Reading audio recordings…");

    private async Task StartAudioAsync()
    {
        if (await RunOperationAsync(async token => {
            var name = await _client.StartAudioRecordingAsync(token);
            _audioStatus.Text = $"Recording started: {name}";
        }, "Starting microphone…")) await RefreshAudioAsync();
    }

    private async Task StopAudioAsync()
    {
        if (await RunOperationAsync(async token => {
            var name = await _client.StopAudioRecordingAsync(token);
            _audioStatus.Text = $"Recording saved: {name}";
        }, "Stopping and saving recording…")) await RefreshAudioAsync();
    }

    private AudioRecordingEntry? SelectedAudio()
    {
        var entry = _audioGrid.CurrentRow?.DataBoundItem as AudioRecordingEntry;
        if (entry is null) MessageBox.Show(this, "Select a recording first.", "Audio recordings");
        return entry;
    }

    private async Task GetAudioAsync()
    {
        var entry = SelectedAudio();
        if (entry is null) return;
        using var dialog = new SaveFileDialog { FileName = entry.Name, Filter = entry.Name.EndsWith(".aac") ? "AAC audio (*.aac)|*.aac" : "WAV audio (*.wav)|*.wav", DefaultExt = Path.GetExtension(entry.Name).TrimStart('.'), AddExtension = true };
        if (dialog.ShowDialog(this) != DialogResult.OK) return;
        await RunOperationAsync(async token => {
            var temporary = dialog.FileName + "." + Guid.NewGuid().ToString("N") + ".part";
            try {
                await using (var output = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None)) {
                    await _client.DownloadAudioRecordingAsync(entry.Name, output, CreateProgress(), token);
                    await output.FlushAsync(token);
                }
                token.ThrowIfCancellationRequested();
                File.Move(temporary, dialog.FileName, overwrite: true);
                _audioStatus.Text = $"Saved: {dialog.FileName}";
            } finally {
                if (File.Exists(temporary)) File.Delete(temporary);
            }
        }, $"Retrieving {entry.Name}…");
    }

    private async Task DeleteAudioAsync()
    {
        var entry = SelectedAudio();
        if (entry is null || MessageBox.Show(this, $"Delete {entry.Name} from the terminal?", "Delete recording",
            MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
        if (await RunOperationAsync(token => _client.DeleteAudioRecordingAsync(entry.Name, token), "Deleting recording…")) await RefreshAudioAsync();
    }

    private async Task ResetAudioAsync()
    {
        if (MessageBox.Show(this, "Stop capture and delete all audio recordings from the terminal?", "Delete all recordings",
            MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
        if (await RunOperationAsync(token => _client.ResetAudioRecordingsAsync(token), "Deleting all recordings…")) await RefreshAudioAsync();
    }
}
