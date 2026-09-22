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
