# USB connections

How USB is reached depends on the platform.

## Windows — the `usbprint.sys` device interface, driverless from the user's side

On Windows the app talks to the printer's **real USB endpoints** through `usbprint.sys` — the
driver Windows itself binds to every USB printer the moment it is plugged in. That driver registers
a device interface (`\\?\usb#vid_04b8&pid_…#…#{28d78fad-…}`) that opens with plain `CreateFile`,
and `ReadFile`/`WriteFile` on the handle are raw bulk transfers. There is nothing to install,
nothing to place, and nothing to unbind: the printer stays a normal Windows printer and keeps
printing. This is the same channel Epson's own Adjustment Program uses.

Because the bytes hit the wire as-is, `UsbPrintTransport` speaks the **full 1284.4 protocol** —
handshake, channel open, credit, D4-framed factory commands — exactly like the libusb path, and
reaches the control socket where the waste-counter service lives. Discovery is unchanged:
`WindowsPrinterScanner` enumerates installed queues with `EnumPrinters` and keeps the ones that
look like an Epson on a USB port (`USB…`/`ESDPRT…`, not a network `WSD…`/IP port — those the SNMP
path owns); the queue name is what the model matcher works from. All I/O is OVERLAPPED with
explicit deadlines, because a synchronous read on this handle blocks until the printer talks —
which for a wrong key is never. The handle is exclusive: a job mid-print or **EPSON Status
Monitor** holding the port surfaces as a sharing violation, with the remedy in the UI.

This replaces **Zadig**, whose driver rebind used to be required and which took the printer away from
the Windows print subsystem entirely. libusb remains as an advanced fallback (below), but a normal
Windows user needs none of it.

### The spooler fallback

`WinspoolTransport` — a RAW job over `OpenPrinter`/`WritePrinter`/`ReadPrinter` — is kept only for
the odd setup where no `usbprint` interface is registered at all. A RAW job reaches the printer's
**print-data** service, not the 1284.4 control socket, so it strips the D4 framing and sends bare
ESC/P factory commands, the SNMP-passthrough pattern. Real hardware (an ET-2820-family unit)
answered that service by **printing the commands as text**, which is why it was demoted from
default to last resort.

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
