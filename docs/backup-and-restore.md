# Backup and recovery

A reset is one-way — the printer offers no undo — so before the first write of any live run the app
saves the bytes that run is about to overwrite. That is not optional and not something to remember:
a live reset that cannot save its backup **stops before writing anything**, and a restore takes one
first for the same reason.

Which is why Maintenance offers no snapshot button. It would produce the same file the reset is
about to produce, in the same place, while implying the reset was unprotected until you pressed it.
Taking a recovery point *without* resetting is a real thing to want — a dated sample proves later
what the counters read today — and that is what **Read & save** on the Snapshots tab is for; it
always performs a fresh read first. Both paths land in:

```
~/Library/Application Support/EpsonReset/backups/     macOS
%APPDATA%/EpsonReset/backups/                         Windows
$XDG_DATA_HOME/epson-reset/backups/                   Linux
```

## What a snapshot covers

The address list comes from the generated packets, not from the model: `Executor.writePacketTarget`
reads address and value back off each write packet, so the backup covers what the run will touch by
construction. A snapshot taken on demand is captured against the sequence a reset *would* generate,
for the same reason — a file wider than that could not be written back, and a snapshot that cannot
be restored is a screenshot with extra steps. If any of those addresses didn't answer the read, the
run (or the save) is refused rather than proceeding with a gap — nothing has been written at that
point, and reads are unprivileged, so retrying costs nothing. A reset dry run needs no snapshot and
is never blocked by this rule.

A snapshot can only be taken from values that came off a printer. A dry read's values are invented
by `FakeTransport`, and the resulting file would be indistinguishable from a real capture — one a
restore would happily write into an EEPROM. So saving is refused for a dry reading; **Read & save**
is explicitly a fresh hardware read and never uses the simulated EEPROM.

## The Snapshots tab

It creates a snapshot directly from the printer-and-model target shown in the top bar. The action
reads only the reset/recovery addresses and writes nothing to the printer. The model is automatic
when the printer identifies itself exactly; an ambiguous family is settled from its constrained
shortlist in the same target menu.

The tab lists everything saved, newest first. Selecting one reads it back: the bytes are in the
file, so the counters, the percentages and the address table are rendered with no printer involved
and nothing that can fail — a dry read in the literal sense. It goes through
`CounterReader.Reading` rather than a renderer of its own, so saved bytes decode with exactly the
code that decodes live ones. Files that won't parse are listed as unreadable rather than hidden: a
recovery point that isn't one is precisely the thing worth knowing about.

## Comparing two samples

**Compare** pairs the open snapshot with a second sample — another file, or the printer as it is
now. It is never implicit: selecting a snapshot starts no comparison and reads no hardware, because
this tab's one guarantee is that it works with nothing plugged in.

The comparison is shown at two levels, and they answer different questions. `SnapshotComparison`
assembles the decoded counters first: a pair at `[48,49]` going `3865 → 3901` is `+36` there and,
one level down, a byte rolling `0xFF → 0x00` beside another rising by one — two changes that look
unrelated until they're added together. The byte table underneath is what verifies a restore put
back exactly what it took, and is the only level available for a model with no known layout.

Two uses follow from that. A snapshot taken before a reset still proves the reset landed, days later
and with the app restarted in between — the run's own read-back does the same check, but only in
memory, and `readCounters` clears it. And two snapshots taken either side of a known amount of
printing show which addresses actually move, which is what tells a real counter from the `0x5E`
limit bytes and padding around it. Anything that moved and belongs to no counter in the layout is
listed separately: that's [`counters.json`](counter-database.md) being incomplete, stated as an
observation, since two readings cannot say what such an address counts.

Comparing against a dry read is refused for the reason saving one is — `FakeTransport` invents a
byte for every address, so the differences would be fiction. Two different serials are flagged
rather than blocked, and an address only one side carries is shown as a dash, never counted as a
change.

## Restoring

Restoring is offered there for any snapshot, and in-window right after a live run stops with writes
landed. **Save current, then restore** is the primary action, because it is the one that leaves a
way back; **Restore without saving first** and **Simulate restore** sit behind it, each named for
what it does.

That choice belongs to this tab. It used to be inherited from a Dry run switch on Maintenance —
not visible from here, and silently deciding not only whether the write happened but whether the
safety net was taken at all. A mode that changes whether a write can be undone must not live on a
screen you are not looking at. Reset now works the same way: **Save current, then reset** in red,
with **Simulate reset** behind the chevron, and no switch left set for the next person to find. The same list is on the CLI — `./gradlew restore` prints what's saved, a filename previews
it, `--live` writes it:

```bash
./gradlew restore --args="ET-2820-20260727T004500Z.json --live"
```

A restore is the reset sequence with saved bytes in place of reset values — same generator,
executor and `:42:OK;` verification, so there's no second write path. `EepromBackup.parse` rejects
bytes outside `0..255`, so a corrupt file fails on load rather than at the printer.

## Where a restore is allowed to land

Decided by `UnitSelector`, separately from the tools that act on the result so the rule can be
tested directly. Both callers go through it — the `restore` CLI picking from everything connected,
and the window checking the one printer already selected. A wrong *model* is caught by the write key
— the printer answers `:42:NG;` and nothing lands — but a wrong unit of the *same* model would
accept the write, so the key is no help there:

| On the bus | Result |
|---|---|
| A unit whose serial matches the backup | writes to it |
| Nothing resolving to the backup's model | refused |
| Right model, serial says a different unit | refused |
| Two of the model, none matchable by serial | refused — disconnect the others |
| One of the model, no serial either side | writes, flagged as unconfirmed |

That last row is deliberate. Refusing it would strand every backup taken before the serial was
recorded, and with a single candidate there is nothing to confuse it with.

The serial that binds a backup to a unit is read during the run itself: a live run now asks for the
status block on the channel it has already opened, falling back to the USB descriptor and then to
whatever a previous read left behind. Reading it rather than hoping one is lying around is what
makes the check bite on an ordinary run — and it is the only source at all over the network, where
there is no descriptor to fall back on.

## What this isn't

This is not an EEPROM image. It won't help if the printer stops responding, since the restore needs
the same channel, and it puts the waste counters back up where they were — recovery from a
half-finished run, not an undo for a successful one.
