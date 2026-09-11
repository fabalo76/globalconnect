# Continental Payment Technologies — Outlook signature

This package uses a local companion PNG. Classic Outlook attaches that image to outgoing messages as an inline image, so recipients do not need Google Drive or another external image host.

## Classic Outlook for Windows

1. Close Outlook.
2. Copy `Continental Payment Technologies.htm`, `Continental Payment Technologies.txt`, and the complete `Continental Payment Systems_files` folder into `%APPDATA%\Microsoft\Signatures`.
3. Reopen Outlook and go to **File → Options → Mail → Signatures**.
4. Select **Continental Payment Technologies** for new messages and replies/forwards.
5. Send a test message to an external address and confirm the logo appears.

Keep the `.htm` file and its `_files` folder together. Renaming one without updating the other will break the image reference.

## New Outlook or Outlook on the web

The cloud-based signature editor does not load the classic Windows Signatures folder. Open the HTML file in a browser, copy the rendered signature into **Settings → Accounts → Signatures**, then use the editor's image insertion control if the logo does not carry across. The classic Outlook package is the dependable option when the logo must be embedded in each outgoing message.
