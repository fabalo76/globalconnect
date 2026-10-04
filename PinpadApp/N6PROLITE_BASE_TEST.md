# N6Pro / N6ProLite USB base — release 1.3.124 (154)

USB port 0 has been confirmed by the device operator. USB mode now always uses
SDK serial port 0 on N6Pro and N6ProLite, including normalized spelling variants.
Previously saved USB port selections are overridden when settings are loaded
and again when the transport is created. Configuration displays port 0 without
port-selection controls. RS232 configuration remains independent.

The PL2303GC base uses UART without enabling CDC. The PC COM number is separate
from the terminal SDK port number. Match baud rate, data bits, parity, and stop
bits on both ends. Use Export connection log in settings to save connection
results and byte counts through Android's file picker.

Sign the release APK before installing on the production device. Keep
xTMSAgent 2.1.2.75 installed for the previously delivered navigation correction.

## Serial disconnection recovery validation

The photographed `USB_BASE_PL2303GC port=0 receive failed: -4008` means the SDK
reported a disconnected serial driver. After installing a build containing the
recovery change on a test terminal:

1. Select USB serial and verify a host communication test succeeds on port 0.
2. While idle, disconnect and reconnect the base/cable. If this produces a fatal
   SDK receive error, verify that the banner reports automatic reconnection and
   the exported connection log records `serial recovery`, disconnect results,
   and `serial reopened`. Simply closing the PC COM port may not reproduce an
   internal driver failure.
3. Reopen the host COM port and send a fresh communication test. Confirm traffic
   resumes without restarting the app or terminal; reopening alone confirms
   only that SDK connect succeeded, not that the physical link works.
4. Leave the base unavailable for at least a minute and confirm retries slow to
   at most one attempt per 30 seconds and the administration UI stays usable.
5. Change transport settings while retries are pending. Confirm the old serial
   connection stops retrying and only the selected transport receives data.

Do these checks while idle, without a payment in progress. If the driver remains
unavailable after reconnecting the base, export the connection log before
rebooting. A lower-level PL2303/firmware fault may still require a device reboot;
the bundled Nexgo SDK exposes no base reset operation. This physical validation
has not yet been performed for the recovery change.

## Idle media validation

1. Upload a JPEG and a short MP4 through the desktop media manager. In pinpad Settings → Idle screen, choose each file. Verify the top date, connection, and network indicators remain visible, with no overlap.
2. Verify the image preserves its proportions and the video loops silently through several loops. Check Settings remains accessible with ENTER + 1 and the five-second idle hold.
3. Start and cancel a contactless/ICC transaction while each idle media type is active. Verify card/PIN prompts replace the media immediately, and media resumes only after returning to idle. Repeat with an approved test transaction.
4. Restart the app and reboot the terminal; confirm the chosen media returns. Background/foreground the app while video is active and check playback resumes without sound.
5. Delete the selected media or initialize its table from the host. Confirm idle text returns. Select an MP4 the device cannot decode and verify text fallback without a crash.
6. Send J7 with a stored image name, then J8 with 1; confirm the image displays with the status bar. Send J8 with 0 and verify idle text. Check missing image and invalid-operation responses report failure.

These physical media checks have not yet been performed on the N6 Pro Lite.
