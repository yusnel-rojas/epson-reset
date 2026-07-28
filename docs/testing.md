# Tests

```bash
./gradlew test
```

338 of them, no hardware and no network required. What they cover is the software; what a printer
has actually been observed doing is in [Field notes](field-notes.md).

Worth knowing what the harder ones cover:

- **The write gates.** `ResetViewModelTest` drives the view model against a scripted printer that
  can be told to misbehave: an address that answers no read (the run must stop before the first
  write), a backup directory it cannot write to (likewise), and a printer that acknowledges every
  write and commits none (the read-back must catch it). `ResetViewModel` takes its transport,
  discovery and backup directory as constructor parameters so these can be reached at all.
- **The network path**, against a loopback SNMP agent — including
  [the write gate](network-printers.md#the-write-gate) from both sides: a write refused before any
  read has succeeded, and allowed after one has.
- **The SNMP codec**, against responses generated to the encoding a real agent produced. The bug
  those pin walked the BER cursor one byte short per field, because `position += readLength()`
  reads the left operand before the call that advances it.
- **The wire formats**, against messages built byte by byte: DNS-SD responses with compression
  pointers, a pointer loop that must be refused rather than followed, and 1284 device IDs.
- **The golden packets**, pinning the reset framing byte-for-byte against the reference C++. With
  the write path unexercised on hardware, this is what stands in for it.
- **The read-only inspector**, two tests on `DeviceInspector.assertReadOnly`.
- **The capability numbers**, `CapabilityTest`, which pins every figure in the
  [capability table](counter-database.md#model-capabilities) — including the count of models that
  can show a percentage, so that number can only move as a deliberate edit. See
  [merging a calibration](calibration.md#merging-one).
- **The update check**, without reaching GitHub — `UpdateCheck.check` takes the fetch as a
  parameter, so the cases that matter (a repository with no releases, a build ahead of the latest
  tag, a version string that can't be compared) are ordinary tests rather than a live call.

A headless self-check runs the whole environment, database, USB scan and dry run in one go:

```bash
./gradlew diagnose --args="ET-2825"
```

The suite also runs on every `git push`, from the `pre-push` hook — see
[Formatting](formatting.md#the-hooks) for what is installed and how to skip it.
