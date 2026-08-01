# Troubleshooting

Ordered by where things usually stop: getting the app open, seeing the printer, reading it,
resetting it.

Run `./gradlew diagnose` first if you're not sure which of those you're in — it prints the OS, the
Java version, whether libusb loaded, what's on the USB bus and what the database knows about it,
and needs no printer to be reachable. See [Command line](command-line.md).

## The app won't open

**macOS says the app is damaged or from an unidentified developer.** Release builds are only signed
and notarized when the signing secrets are present; without them the `.dmg` is unsigned and needs
right-click → Open on first launch. See [Builds and releases](releases.md).

## No printer shows up

**Over USB, nothing is listed at all.**

- **Windows** — the app reads the printer through its own Windows driver, so the fix is to make sure
  Windows has that driver: plug the printer in, let Windows finish installing it (it appears under
  Settings → Bluetooth & devices → Printers), then rescan. No libusb and no Zadig are involved on
  this path. See [USB connections](usb-connection.md#windows--the-printers-own-driver-driverless-from-the-users-side).
- **macOS / Linux** — libusb isn't loading. The app says so rather than failing silently, and
  everything else — network printers, the database browser, dry runs — still works. Install it:
  `brew install libusb` (macOS) or `sudo apt install libusb-1.0-0` (Debian/Ubuntu). Details in
  [USB connections](usb-connection.md).

**Over the network, nothing is found.** Discovery is mDNS, which many networks drop across subnets
or with multicast filtered. Open the printer menu in the top bar and choose **Add printer by IP
address…** to type it in directly. See [Network printers](network-printers.md).

## The printer is listed but won't open

The OS print subsystem owns the printer's USB interface and has to release it first. This is the
most common wall, and it differs per platform:

**macOS — remove the printer from the system.** The macOS printer driver claims the interface and
holds it. Deleting the queue is what makes it let go:

1. System Settings → Printers & Scanners
2. Select the printer → Remove Printer
3. Back in the app, rescan

Stopping the print queue or turning the printer off and on is not enough — the driver reattaches.
Add the printer back afterwards; nothing about the removal touches the printer itself. The app
shows this remedy in the window when the claim fails ("macOS's printer driver owns this device").

**Linux — usually automatic.** The kernel driver is detached on open. If it still fails:

```bash
sudo systemctl stop cups
```

`Permission denied` instead means udev, not CUPS — run with `sudo`, or add a udev rule for vendor
`04B8`.

**Windows — usually nothing to do.** The app shares the printer's own driver through the print
spooler, so there is no interface to claim and printing keeps working. If a listed printer won't
open, it is normally offline or paused (Settings → Bluetooth & devices → Printers), or the reset job
is stuck behind others in the queue — clear the queue and rescan. Only the *advanced libusb
fallback* needs [Zadig](https://zadig.akeo.ie/) to bind the printer to WinUSB, which takes it away
from the Windows print subsystem until you reinstall the vendor driver; the default spooler path
needs none of that.

**"Another process is holding the printer"** on any platform means something else got there first —
a scanning utility, a vendor status monitor, a second copy of this app. Close it and rescan.

None of this applies over the network, where there is no interface to claim.

## It connects but won't read

**`:41:NA;` over the network.** The firmware refuses the whole class of factory commands. There is
nothing client-side to be done about it — identity, serial and ink levels still work, but counters
and resets need USB. An ET-2825 on firmware 05.24 does this; see
[the write gate](network-printers.md#the-write-gate).

**The model isn't in the database.** 1588 models are known, and yours may not be one of them. The
Inspect tab probes an unknown printer read-only to work out its layout —
[Printers not in the database](inspect.md).

## It reads but the numbers look wrong

**No percentage, just a count.** Normal. Upstream defines a maximum for 6 of 626 counters, so most
models have nothing to compute a percentage against, and the app says "no limit" rather than
inventing one. You can supply one via [an overlay](counter-database.md#overlays) or
[measure it](calibration.md).

**Raw bytes instead of a value, or "layout uncertain".** Groups wider than 4 bytes aren't decoded as
one integer — the ET-2820's 6-address entry mixes a real counter with limit bytes that always read
`0x5E`, and decoding it whole gives a meaningless 14-digit number. Entries upstream marks `(?)` are
flagged rather than trusted.

## The reset didn't work

**The app won't run live until you pick a model, though it already found one.** Your printer named
its family rather than itself — "L310 Series" covers L310 and L3100…L3109, and those do not share a
read key. Open the target in the top bar and pick the printed model from its series shortlist; the
choice is kept in `model-choices.txt` against the printer's serial and isn't asked again. The target
has no effective model until this is settled, even for a dry run. Most families never raise this,
because their members write identical bytes.

**`:42:NG;` — the write was rejected.** The key doesn't match the model. The run aborts there
rather than hammering the EEPROM. Check the model matched in the app is actually your printer;
a near-neighbour in the same family is not the same key.

**It said OK but the printer still complains.** A successful reset needs a power cycle to take
effect.

**The waste box light is still on.** Many models keep only the platen counter in EEPROM, so a reset
leaves the waste box counter alone and the box still needs servicing. The app says which counters it
can touch before you write.

**You want the old values back.** Every live run saves the bytes it's about to overwrite, before the
first write — [Backup and recovery](backup-and-restore.md).

## Reporting it

The log panel at the bottom of the window is the bug report. **Copy** puts the whole log on the
clipboard, packet trace included — the trace is copied whether or not **Show packet trace** is
turned on, so you don't have to reproduce the failure with it open. Above the log it writes a
header, so a pasted report says what it came from without anyone having to ask:

```
# Epson Reset 1.0.0
# Mac OS X 26.5 (aarch64), Java 21.0.11
# libusb: loaded
# database: 1588 models, bundled
# layouts: 1475 models
# printer: EPSON ET-2820 Series on USB (bus 1.4)
# model: ET-2820 (printer reports ET-2825)
# mode: dry run
```

That is usually enough on its own. `./gradlew diagnose` adds the parts that need no printer to be
selected — a full USB and network scan, and a dry run against the database — which is what to send
when the problem is that nothing shows up at all, or the app won't open far enough to copy
anything. See [Command line](command-line.md).

What's already known to work and not work is in [Field notes](field-notes.md).
