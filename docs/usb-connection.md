# USB connections

USB needs libusb-1.0, looked up in `/opt/homebrew/lib`, `/usr/local/lib`, `/opt/local/lib` and
`/usr/lib`. Without it the app still runs — network printers, database browser and dry runs all
work — but USB detection is off.

```bash
brew install libusb            # macOS
sudo apt install libusb-1.0-0  # Debian/Ubuntu
```

On Windows the printer must be bound to a libusb-compatible driver (Zadig).

libusb is bound directly through JNA rather than via `usb4java`, whose newest release (1.3.0, 2018)
ships no `darwin-aarch64` native and can't load on Apple Silicon.

## Claiming the interface

The OS print subsystem owns the printer's USB interface and has to release it first:

- **Linux** — automatic (kernel driver detach), or stop CUPS / run with `sudo`
- **macOS** — remove the printer under System Settings → Printers & Scanners, then rescan
- **Windows** — bind the device to WinUSB with Zadig

Failures here surface in the UI with the remedy for your platform. None of this applies over the
network, where there is no interface to claim — see [Network printers](network-printers.md).
