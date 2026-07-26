# Pinpad Media Manager

.NET 9 Windows Forms utility for managing the files stored by the Global Connect ONE PinpadApp over a serial/USB CDC connection.

## Features

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
- JPEG preview, transfer progress, cancel support, protocol trace, file-header validation, and confirmation before destructive table initialization/deletion.

The protocol implementation uses the terminal's `STX/ETX/LRC` transaction exchange (`ACK → response frame → ACK`) and `SI/SO` administration exchange (`ACK → response frame → ACK → EOT`). PC-to-terminal JPEG and media uploads use 8,192-character Base64 data packets. The four-digit length field permits at most 9,999 characters; 8,192 is the recommended maximum and should be used at 115,200 bps for large files.

## Build and run

Open `PinpadMediaManager.sln` in Visual Studio 2022 17.12 or later, or use:

```powershell
dotnet build PinpadMediaManager.sln -c Release
dotnet test PinpadMediaManager.sln -c Release
dotnet run --project src/PinpadMediaManager/PinpadMediaManager.csproj
```

On the terminal, configure PinpadApp for USB and enable USB CDC. The resulting Windows COM port will appear in the connection bar. Start at the terminal's current baud rate (normally 9,600), then select 115,200 and choose **Apply baud to terminal** before large transfers.

## Safety and limits

- Terminal image names are limited to 15 characters.
- Media names are limited to 64 characters and must retain `.mp3` or `.mp4`.
- PinpadApp accepts MP3 files up to 16 MiB and MP4 files up to 64 MiB.
- Initializing a table permanently deletes every file in that table.
