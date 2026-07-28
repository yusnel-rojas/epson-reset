# Architecture

```
AppPaths    Every file the app keeps between runs, and the one place that decides where
            they live — the data directory and the names in it
db/         PrinterModel, PrinterDatabase (reset data) + CounterSpec, CounterSpecs (read layouts)
            ModelCapability — what the two data files together say the app can do per model
protocol/   EpsonD4 constants, SequenceGenerator, Executor, CounterReader, DeviceId
            EscpRemote — the command inside a 1284.4 packet, for links that aren't USB
            FactoryReply — telling a refusal apart from a rejection and from an answer
backup/     EepromBackup — the snapshot (taken before a write, or on request), the gate that
            blocks a run without one, and the read-back that makes a saved one viewable
            SnapshotComparison — two samples and what moved between them, at the counter
            level and the byte level
device/     DetectedPrinter + Link (USB or network), DeviceMatcher, PrinterDiscovery,
            PrinterTransports — the one place that knows a printer can be attached two ways —
            and ConnectionTest
usb/        LibUsb (JNA), UsbPrinterScanner, LibUsbTransport
net/        Snmp (a hand-rolled v1 GET), EpsonMib (the OIDs), SnmpTransport (the command
            passthrough, and the write gate), MdnsDiscovery, NetworkAddress, SavedPrinters
prefs/      Preferences + PreferencesStore (what survives a launch), ScreenFit — whether a
            remembered window position still lands on a screen that exists
update/     AppVersion (stamped into the jar at build time), UpdateCheck — is there a newer
            release than this one
ui/         Compose screens and the view model — Reset, Models, Inspect, Snapshots
tools/      convert_reinkpy.py — regenerates counters.json and database.json from upstream
installer/  windows/EpsonReset.iss — Inno Setup script the Windows CI job compiles
.github/    ci.yml (tests per push), build.yml (installers on tags), sync-printer-data.yml
```

`Transport` is the seam: `LibUsbTransport` over USB, `NetworkTransport` over TCP 9100, and
`FakeTransport` for dry runs and tests.

Two things about that seam are load-bearing elsewhere: `SnmpTransport` carries
[the write gate](network-printers.md#the-write-gate), and `FakeTransport`'s invented bytes are why
snapshots, comparisons and calibrations are all refused in a dry run — a fabricated reading is
indistinguishable from a measured one once it's in a file.
