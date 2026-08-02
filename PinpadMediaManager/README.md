# Pinpad Media Manager

## A10 Demo workspace

The **A10 Demo** tab brings the operational parts of the legacy A10P Demo AP
into the Global Connect tool without loading its old `UIC.*` assemblies:

- **Device & hardware** reads the serial number, the four legacy `19x` version
  values, hardware capabilities, a secure random block, and tests the keypad
  beeper and reader connection.
- **Display tests** exercises the legacy `Z2`, `Z3`, `Z7`, and `Z8` prompt and
  idle-display commands.
- **Key injection** provides the A10-style MK0-MK9, MK11-B-MK16-G, and
  DUKPT0/1 grids with bulk selection, active-key selection, KCV/result feedback,
  and configuration import/export. The A10 Demo AP test keys are populated on
  first run. Subsequent edits are saved atomically to
  `%LOCALAPPDATA%\Global Connect ONE\Pinpad Media Manager\key-injection-test-settings.json`
  and restored on startup. This file contains clear **test keys** and must be
  protected and excluded from production key-management workflows.
- **PIN entry** covers Master/Session and DUKPT PIN capture with standard,
  external, and custom prompts while redacting keys and PIN blocks from the
  shared protocol trace.
- **EMV data setup** accepts the original text formats for Data Formats,
  Terminal Configuration, contact/contactless CA keys, and
  contact/contactless applications. The Android PINPAD parses the file and
  applies it to the NEXGO EMV kernel.
- **ICC transaction** reproduces the legacy `T11` / `T15` / `T27` / `T17`
  flow and includes an approve, decline, or no-response host simulator.
- **Contactless transaction** reproduces the `T61` / `T65` / `T71` flow.
- **ICC / SAM card** provides card-presence, cold-reset, deactivate, and raw
  APDU operations.
- **Command console** exposes remaining legacy administration and transaction
  diagnostics without bypassing the PINPAD security policy. Payloads accept
  the readable `<FS>`, `<SUB>`, and `<RS>` separator tokens; clear-key commands
  are still rejected unless the terminal is in its authenticated key-injection
  mode.

Both transaction pages read the standard receipt tags after completion. The
existing Images, Media, Signature, and Camera & QR tabs remain unchanged.

The Camera & QR tab supports the PINPAD visual-operation commands:

- `PH1`/`PH2`: capture, preview, transfer, and explicitly save a JPEG photo.
- `QR1`/`QR2`: generate and display a QR value on the PINPAD.
- `QR3`/`QR4`: read a QR code with the PINPAD camera and show the decoded value.

Photo transfers reuse the ACK-paced 1,024-character Base64 packet protocol used by signature capture. Camera selection defaults to Front because customer-facing PINPAD models commonly expose only that camera.
The QR panel includes editable predefined samples for plain text, URL, Wi-Fi, email, phone, SMS, payment-reference JSON, and Spanish UTF-8 testing.

.NET 9 Windows Forms utility for managing the files stored by the Global Connect ONE PinpadApp over serial/USB CDC or a raw LAN TCP connection.

## Features

- Connect over raw TCP/IP on Wi-Fi/Ethernet with the same PINPAD framing used
  by serial.
- Discover IP-mode PINPADs on the local LAN with UDP broadcast port `39100`.
- Connect to any Windows COM port at 1,200–115,200 bps.
- Change both the terminal and local COM baud rate with administration command `13`.
- Manage JPEG images with the existing J command family:
  - `J0` initialize the JPEG table.
  - `J1` list images and display-list selection.
  - `J2` select or unselect images.
  - `J3` delete images.
  - `J4` upload a JPEG to the terminal.
  - `J5` download a JPEG from the terminal.
  - `J6` play the selected display list.
  - `J9` show one image.
- Manage media with the Global Connect M command family:
  - `M10` initialize the media table.
  - `M11` list MP3/MP4 files.
  - `M12` upload MP3/MP4.
  - `M13` download MP3/MP4.
  - `M14` play one file.
  - `M15` set media volume from `00` to `99`; the desktop dropdown uses increments of 5.
  - `M16` delete one or more selected files.
  - `M17` speak UTF-8 text using `en` or `es`; the terminal selects the appropriate installed offline voice.
- Capture signatures with the S command family:
  - `S1` starts horizontal or vertical on-screen capture with a 5–300 second timeout and PNG/JPEG selection.
  - `S2` returns captured, cancelled, timeout, or error and streams captured image data in ACK-paced 1,024-character Base64 packets.
- JPEG/signature preview, signature save, transfer progress, cancel support, protocol trace, file-header validation, and confirmation before destructive table initialization/deletion.

The protocol implementation uses the terminal's `STX/ETX/LRC` transaction exchange (`ACK → response frame → ACK`) and `SI/SO` administration exchange (`ACK → response frame → ACK → EOT`). PC-to-terminal JPEG and media uploads use 8,192-character Base64 data packets. The four-digit length field permits at most 9,999 characters; 8,192 is the recommended maximum and should be used at 115,200 bps for large files.

## Build and run

Open `PinpadMediaManager.sln` in Visual Studio 2022 17.12 or later, or use:

```powershell
dotnet build PinpadMediaManager.sln -c Release
dotnet test PinpadMediaManager.sln -c Release
dotnet run --project src/PinpadMediaManager/PinpadMediaManager.csproj
```

For serial, configure PinpadApp for USB and enable USB CDC. The resulting
Windows COM port will appear in the connection bar. Start at the terminal's
current baud rate (normally 9,600), then select 115,200 and choose
**Apply baud to terminal** before large transfers.

For LAN operation, configure PinpadApp for **IP**, keep TCP port `9100` (or set
another port), and place the PC and PINPAD on the same Wi-Fi/Ethernet network.
Select **IP** in the manager and either enter the address/port or choose
**Discover**. The initial implementation is intentionally unencrypted and
should only be used on a trusted LAN.

## Safety and limits

- Terminal image names are limited to 15 characters.
- Media names are limited to 64 characters and must retain `.mp3` or `.mp4`.
- PinpadApp accepts MP3 files up to 16 MiB and MP4 files up to 64 MiB.
- Initializing a table permanently deletes every file in that table.
