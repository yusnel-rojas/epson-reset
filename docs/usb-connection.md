# USB connections

How USB is reached depends on the platform.

## Windows — the printer's own driver, driverless from the user's side

On Windows the app talks to the printer through the **Windows print spooler**, over the driver
Windows already installed when the printer was plugged in. There is nothing to install, nothing to
place, and nothing to unbind: the printer stays a normal Windows printer and keeps printing.

`WindowsPrinterScanner` enumerates installed queues with `EnumPrinters` and keeps the ones that look
like an Epson on a USB port (`USB…`/`ESDPRT…`, not a network `WSD…`/IP port — those the SNMP path
owns). `WinspoolTransport` opens a RAW job on that queue (`OpenPrinter` → `StartDocPrinter` →
`WritePrinter`/`ReadPrinter`) and passes the D4 packet stream through **unchanged**. That works
because `usbprint.sys` forwards a RAW job to the same USB bulk endpoints libusb would drive and
returns the back-channel through `ReadPrinter` — so this is the libusb path's byte pipe by another
route, not the SNMP passthrough (which has to unwrap each packet to a bare ESC/P command).

This replaces **Zadig**, whose driver rebind used to be required and which took the printer away from
the Windows print subsystem entirely. libusb remains as an advanced fallback (below), but a normal
Windows user needs none of it.

> The spooler back-channel needs a real Windows printer to fully confirm — the hardware testing in
> [field notes](field-notes.md) was done on macOS. The framing is deliberately identical to the
> proven libusb path to keep that risk small.

## macOS and Linux — libusb

USB needs libusb-1.0, looked up in `/opt/homebrew/lib`, `/usr/local/lib`, `/opt/local/lib` and
`/usr/lib`. Without it the app still runs — network printers, database browser and dry runs all
work — but USB detection is off.

```bash
brew install libusb            # macOS
sudo apt install libusb-1.0-0  # Debian/Ubuntu
```

libusb is bound directly through JNA rather than via `usb4java`, whose newest release (1.3.0, 2018)
ships no `darwin-aarch64` native and can't load on Apple Silicon.

## Advanced: libusb on Windows (fallback)

The libusb path still exists on Windows for anyone who wants it, and is used automatically only when
the spooler scan turns up no Epson queue. It needs `usb-1.0.dll` — the installer bundles it next to
the app, and JNA looks the library up by its name `usb-1.0` — and the printer bound to a
libusb-compatible driver with [Zadig](https://zadig.akeo.ie/). Binding to WinUSB removes the printer
from the Windows print subsystem until the vendor driver is reinstalled, which is exactly why it is
no longer the default.

## Claiming the interface

The OS print subsystem owns the printer's USB interface and has to release it first — **on macOS and
Linux**:

- **Linux** — automatic (kernel driver detach), or stop CUPS / run with `sudo`
- **macOS** — remove the printer under System Settings → Printers & Scanners, then rescan

Windows needs none of this on the spooler path: it shares the driver rather than claiming the
interface. Failures surface in the UI with the remedy for your platform; the step-by-step versions
are in [Troubleshooting](troubleshooting.md#the-printer-is-listed-but-wont-open). None of this
applies over the network, where there is no interface to claim — see
[Network printers](network-printers.md).
