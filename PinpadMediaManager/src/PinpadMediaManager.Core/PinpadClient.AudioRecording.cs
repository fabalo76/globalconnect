using System.Globalization;
using PinpadMediaManager.Core.Models;
using PinpadMediaManager.Core.Protocol;

namespace PinpadMediaManager.Core;

public sealed partial class PinpadClient
{
    public async Task<string> StartAudioRecordingAsync(CancellationToken cancellationToken = default) =>
        AudioRecordingProtocol.StartedFile((await ExecuteAsync(PinpadFrameType.Transaction, "M20", "", cancellationToken)).PayloadAscii);

    public async Task<string> StopAudioRecordingAsync(CancellationToken cancellationToken = default) =>
        AudioRecordingProtocol.StartedFile((await ExecuteAsync(PinpadFrameType.Transaction, "M21", "", cancellationToken)).PayloadAscii);

    public async Task<IReadOnlyList<AudioRecordingEntry>> ListAudioRecordingsAsync(CancellationToken cancellationToken = default) =>
        AudioRecordingProtocol.ParseList((await ExecuteAsync(PinpadFrameType.Transaction, "M22", "", cancellationToken)).PayloadAscii);

    public async Task DeleteAudioRecordingAsync(string name, CancellationToken cancellationToken = default)
    {
        if (!AudioRecordingProtocol.ValidName(name)) throw new ArgumentException("Invalid recording filename.", nameof(name));
        AudioRecordingProtocol.Success((await ExecuteAsync(PinpadFrameType.Transaction, "M24", name, cancellationToken)).PayloadAscii);
    }

    public async Task ResetAudioRecordingsAsync(CancellationToken cancellationToken = default) =>
        AudioRecordingProtocol.Success((await ExecuteAsync(PinpadFrameType.Transaction, "M25", "", cancellationToken)).PayloadAscii);

    public async Task DownloadAudioRecordingAsync(string name, Stream destination,
        IProgress<TransferProgress>? progress = null, CancellationToken cancellationToken = default)
    {
        if (!AudioRecordingProtocol.ValidName(name)) throw new ArgumentException("Invalid recording filename.", nameof(name));
        if (!destination.CanWrite) throw new ArgumentException("Destination must be writable.", nameof(destination));
        await _operationGate.WaitAsync(cancellationToken);
        try
        {
            await Task.Run(() =>
            {
                long offset = 0;
                long? total = null;
                do
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    var response = Execute(PinpadFrameType.Transaction, "M23",
                        name + PinpadControl.Fs + offset.ToString(CultureInfo.InvariantCulture), true, cancellationToken);
                    var packet = AudioRecordingProtocol.ParsePacket(response.PayloadAscii, offset, total);
                    if (offset == 0 && !AudioRecordingProtocol.ValidHeader(name, packet.Data))
                        throw new PinpadProtocolException("The terminal returned an invalid audio recording header.");
                    total = packet.Total;
                    destination.Write(packet.Data);
                    offset += packet.Data.Length;
                    progress?.Report(new TransferProgress("Downloading audio", offset, total.Value));
                } while (offset < total);
            }, cancellationToken);
        }
        finally { _operationGate.Release(); }
    }
}
