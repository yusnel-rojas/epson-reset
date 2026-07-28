# Epson Reset
[![Build & package](https://github.com/yusnel-rojas/epson-reset/actions/workflows/build.yml/badge.svg)](https://github.com/yusnel-rojas/epson-reset/actions/workflows/build.yml)
[![Platforms](https://img.shields.io/badge/platforms-macOS%20%7C%20Windows%20%7C%20Linux-lightgrey)](#requirements)

A desktop app (macOS, Windows, Linux) for reading and resetting the waste ink pad counters on Epson printers.

<img src="docs/images/screenshot-01.png" alt="The Reset tab: a connected ET-2820 matched to its database entry, its waste counters decoded, and the Live write confirmation" width="900" />

- Finds printers on the USB bus **and on the network** (over SNMP, which names the exact model),
  matching both against a database of 1588 models
- Reads counters, decoding grouped addresses into real values (`[48,49]` → `3865`)
- Resets counters, verifying each write's `:42:OK;` acknowledgement
- Dry-runs the whole sequence against a simulated EEPROM without touching hardware
- Inspects unknown printers read-only, to work out their counter layout
- Shows a hex trace of every packet, for bug reports

## Before you use this

A waste ink counter is the printer's own record of how much ink its absorbent pad has soaked up.
Resetting that counter does not empty the pad. The ink is still in there, and a pad at the end of
its life still needs replacing — otherwise the ink eventually ends up somewhere you did not choose.

**Using this is entirely your decision and entirely your risk.** Whatever follows from it — an
overflowing pad, ink on the desk, a printer that stops working, a warranty the manufacturer
declines to honour — is yours to carry. The software comes with no warranty of any kind and no
promise that it does the right thing on your printer, and nobody who wrote it is accountable for
what happens to your hardware. Sections 15 and 16 of the [licence](LICENSE) say the same in legal
terms.

If that is not a trade you want to make, stay in dry run: it writes nothing.

## Running

```bash
./gradlew run
```

Headless self-check — environment, database, USB scan, dry run:

```bash
./gradlew diagnose --args="ET-2825"
```

Installer for the machine you're on (dmg / exe / deb):

```bash
./gradlew packageDistributionForCurrentOS
```

## Requirements

Nothing, for a network printer — no driver, no library, no wrestling with the OS for the interface.
What a network printer will *let* you do varies; see [Network printers](docs/network-printers.md).

For USB, libusb-1.0:

```bash
brew install libusb            # macOS
sudo apt install libusb-1.0-0  # Debian/Ubuntu
```

Without it the app still runs — network printers, database browser and dry runs all work — but USB
detection is off. On Windows the printer must be bound to a libusb-compatible driver (Zadig). The
OS print subsystem also has to release the interface first, which differs per platform:
[USB connections](docs/usb-connection.md).

## Safety

Dry run is the default on the reset path; switching to Live takes a second confirmation naming the
target printer. Reads carry no write key, so sampling counters is safe even against a mismatched
model — and the Inspect tab can't write at all.

A rejected write (`:42:NG;`) means the key doesn't match the model, and aborts the run rather than
hammering the EEPROM. Many models keep only the platen counter in EEPROM, so a reset leaves the
waste box counter alone and the box still needs servicing; the UI says so before you write. A
successful reset needs a power cycle to take effect.

Before the first write of any live run, the app saves the bytes that run is about to overwrite —
[Backup and recovery](docs/backup-and-restore.md).

Whether a reset can happen over the network is the printer's decision, not a setting — see
[the write gate](docs/network-printers.md#the-write-gate). Where a printer refuses, the app says
which refusal it was rather than failing obscurely.

**The reset (write) path has not been run against a real printer here** — only the read path has.
That and every other standing limit is in [Field notes](docs/field-notes.md#standing-limits).

## Documentation

[**docs/**](docs/README.md) — the technical detail behind all of the above.

| | |
|---|---|
| [Field notes](docs/field-notes.md) | What worked and what didn't against real hardware, including the wrong turn that damaged a printer |
| [Network printers](docs/network-printers.md) | SNMP identity, the command passthrough, the write gate, refusals, `netProbe` |
| [USB connections](docs/usb-connection.md) | libusb, claiming the interface, per-platform remedies |
| [Backup and recovery](docs/backup-and-restore.md) | Snapshots, comparison, restoring, where a restore may land |
| [Counter database](docs/counter-database.md) | The two data files, model capabilities, overlays, resyncing |
| [Measuring a maximum](docs/calibration.md) | Where a percentage comes from, and how to contribute one |
| [Printers not in the database](docs/inspect.md) | The read-only Inspect tab |
| [Preferences](docs/preferences.md) | `preferences.json`, window placement, the update check |
| [Architecture](docs/architecture.md) · [Implementation notes](docs/implementation-notes.md) · [Tests](docs/testing.md) · [Formatting](docs/formatting.md) · [Builds and releases](docs/releases.md) | Working on the app itself |

## Credit

Protocol ported from [RxNaison/Epson-Waste-Reset](https://github.com/RxNaison/Epson-Waste-Reset) (C++,
Apache-2.0); printer waste reset database comes from [reinkpy](https://codeberg.org/atufi/reinkpy) (AGPL-3.0).
