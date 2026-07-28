# Documentation

Technical documentation for [Epson Reset](../README.md). The README covers what the app is and how
to run it; everything below is the detail behind it.

## Using it

| Page | What's in it |
|---|---|
| [USB connections](usb-connection.md) | libusb, claiming the interface away from the print subsystem, per-platform remedies |
| [Network printers](network-printers.md) | SNMP identity, the command passthrough, the write gate, refusals, discovery, `netProbe` |
| [Backup and recovery](backup-and-restore.md) | Snapshots, the Snapshots tab, comparison, restoring, where a restore is allowed to land |
| [Preferences](preferences.md) | `preferences.json`, window placement, the update check |

## The data

| Page | What's in it |
|---|---|
| [Counter database](counter-database.md) | `database.json` and `counters.json`, why there are two, model capabilities, overlays, resyncing |
| [Measuring a maximum](calibration.md) | Where a percentage comes from, the measurement form, submitting one, merging one |
| [Printers not in the database](inspect.md) | The read-only Inspect tab: key discovery, EEPROM sweep, candidate ranking, export |

## Working on it

| Page | What's in it |
|---|---|
| [Field notes](field-notes.md) | **What worked and what didn't against real hardware** — including the wrong turn that damaged a printer, and what is still unproven |
| [Architecture](architecture.md) | Source layout, the `Transport` seam |
| [Implementation notes](implementation-notes.md) | The rules and traps the code depends on — protocol framing, the status block, SNMP, DNS-SD, the inspector's read-only guarantee |
| [Tests](testing.md) | What the suite covers, and the harder cases worth knowing about |
| [Formatting](formatting.md) | ktlint, the git hooks and how to install them, the two names the formatter may not change |
| [Builds and releases](releases.md) | CI, packaging per platform, cutting a release, signing |
