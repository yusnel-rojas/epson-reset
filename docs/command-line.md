# Command line

The app is the interface — everything a reset needs is in the window. The tasks below are headless
tools for the cases the window can't cover: a self-check to paste into a bug report, probes for
working out what an unfamiliar printer answers, and a restore that runs without a display.

All of them take their arguments through Gradle's `--args`, quoted as one string:

```bash
./gradlew diagnose --args="ET-2825 --live"
```

| Task | What it does |
|---|---|
| `run` | Starts the app. |
| `hotRun --auto` | Starts the app with Compose Hot Reload. Saving Kotlin code recompiles and reloads the affected UI while preserving compatible state. |
| `diagnose` | Self-check: environment, database, libusb, connected printers, then a dry run for the named model (`L3150` if you name none). `--live` reads the real printer instead of the simulated EEPROM. Never writes. |
| `restore` | Lists saved snapshots; a filename previews one, `--live` writes it back — [Backup and recovery](backup-and-restore.md#restoring). |
| `netProbe` | Staged network probe of one address: is SNMP answering, what does it say it is, does the command passthrough exist, and `--read` for whether the firmware allows an EEPROM read — [Network printers](network-printers.md#probing-one-from-the-command-line). |
| `readProbe` | Dumps every exchange of the USB D4 handshake and tries several read-command shapes, to find which one the firmware answers. Takes a model name, `ET-2825` by default. |
| `statusProbe` | Asks a USB printer for its own maintenance status, instead of computing a percentage against a maximum nobody publishes. |
| `maintenanceProbe` | Previews the nozzle-check and cleaning commands, and with `--live` runs one as ESC/P2 remote-mode print data — [Maintenance operations](maintenance.md). `--control` uses the 1284.4 channel instead, `--params=00,10` varies the parameter bytes, and `--no-precheck` skips the normal busy/error check. It never polls status after sending because doing so can interrupt the active print job. `align` runs head alignment in its three steps (`--choose=`, `--save`). The only probe that can spend ink, so it sends nothing without `--live`. |
| `test` | The suite — [Tests](testing.md). |
| `ktlintFormat` · `installGitHooks` | Formatting and the pre-commit hook — [Formatting](formatting.md). |
| `packageDistributionForCurrentOS` | Installer for the machine you're on: dmg, exe or deb — [Builds and releases](releases.md). |

`diagnose` is the one worth running first when something doesn't work. It prints the OS, the Java
version, whether libusb loaded, what's on the USB bus and what the database knows about it, and
needs no printer to be reachable. The app's own **Copy** button carries the same environment header
above the log, so `diagnose` is for the cases where there's no window to copy from, or where a full
scan is the question — [Troubleshooting](troubleshooting.md#reporting-it).

The probes are experiments kept in the tree because their answers were worth recording, not polished
tools; what they found is in [Field notes](field-notes.md).
