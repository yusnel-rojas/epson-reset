# Architecture

```
AppPaths    Every file the app keeps between runs, and the one place that decides where
            they live — the data directory and the names in it
db/         PrinterModel, PrinterDatabase (reset data) + CounterSpec, CounterSpecs (read layouts)
            ModelCapability — what the two data files together say the app can do per model
            ModelClass — turning a family name back into the models it could mean
protocol/   EpsonD4 constants, SequenceGenerator, Executor, CounterReader, DeviceId
            EscpRemote — the command inside a 1284.4 packet, for links that aren't USB
            FactoryReply — telling a refusal apart from a rejection and from an answer
backup/     EepromBackup — the snapshot (taken before a write, or on request), the gate that
            blocks a run without one, and the read-back that makes a saved one viewable
            SnapshotComparison — two samples and what moved between them, at the counter
            level and the byte level
device/     DetectedPrinter + Link (USB or network), DeviceMatcher, PrinterDiscovery,
            PrinterTransports — the one place that knows a printer can be attached two ways —
            ConnectionTest, and ModelChoices (which unit a printer that names only its family is)
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

## Families, and why a match is not always an identification

Printers name themselves the way the sticker on the box does: `MDL:L3110 Series;`. That is a family,
not a unit, and the database holds units. Stripping the word and matching what's left is right often
enough to be dangerous — the eight L311x entries agree on every byte a reset writes, so picking any
of them is picking all of them, but L310 and L3100 are one family name apart and read with different
keys.

`ModelClass` draws the family from the names alone, since nothing in the database records one: the
entries that extend a name (`ET-1810` also starting `ET-18100`, two unrelated printers) and the ones
differing only in the last digit (`ET-2820` for any of ET-2820…ET-2828, which is how Epson actually
numbers the units inside one advertised series). If everything in that set writes the same bytes the
report is acted on as an exact match, which is the usual outcome. If they disagree, `DeviceMatcher`
answers `CLASS_ONLY`: the sidebar asks which one it is, listing that set and the read keys that
separate them, and a live run is blocked until it's answered. A unit that names itself outright is
taken at its word regardless.

The answer is kept in `model-choices.txt`, against the printer's serial — not in the database, which
the [sync workflow](releases.md) replaces wholesale, and not against the address or USB port, which
a DHCP lease or a different socket changes. What the printer reported is stored beside it, so a
different printer answering to the same serial or port inherits nothing.

## The other link usually knows

A printer plugged in over USB is often the same printer already on the network, and the network one
was asked a better question. SNMP reads `EpsonMib.MODEL`, which gives the unit — `ET-2825`. The USB
descriptor gives `EPSON ET-2820 Series`, which is eight units in a trenchcoat. Where both are
present the unit wins, and the family never has to be put to the user at all.

What proves they are one printer is the serial, and that takes a step of its own: the descriptor
reports it hex-encoded (`58414441…`) where SNMP reports the same characters plainly (`XADA0202…`).
`Serials.canonical` decodes the first into the second, under a deliberately narrow test — an even
run of hex digits *and* a decoded form that still looks like a serial — because guessing wrong here
renames a printer. The raw value stays on the device card underneath, since it is what the device
actually said. Canonical serials also mean a family answer confirmed over one link is found again
over the other, which is otherwise the same printer being asked twice.

`PrinterDiscovery.crossChecked` does the join, off entries the browse has already queried, so it
costs no extra traffic; `Settings → Identification` turns it off. Where the two links resolve to
entries that **disagree on the recipe**, nothing is borrowed: that is not a refinement but a
contradiction, and the app says so and leaves the picker open rather than writing a key on a guess
about which link is lying.
