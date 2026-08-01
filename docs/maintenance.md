# Maintenance operations

A nozzle check and cleaning operations, sent as ESC/P2 remote-mode print data. The status query
still uses the separate IEEE-1284.4 control channel.

This is the one part of the app that deliberately **fills** the waste pad rather than reading or
clearing its counter, so it is worth being blunt about the trade before anything else: every
cleaning cycle flushes ink through the head and into the pad. A reset lowers the number; a cleaning
raises it. Running cleanings until a print looks right and then resetting the counter is the exact
loop that ends with ink on the desk.

That is also why the nozzle check comes first in the list. It costs a page and a little ink, and it
is the only way to find out whether a cleaning is warranted at all — a cleaning run on a head that
was never blocked is pad life spent for nothing.

## What the hardware said

The first live remote-mode attempt did not work, and the way it failed identified two missing pieces
in the stream: the printer-side parser was still in IEEE-1284.4 packet mode, and `NC` was sent with
an empty payload instead of its two-byte `00 00` payload. With both fixed, an ET-2820 printed the
complete BK/YMC nozzle report. That proves the `NC 00 00` action bytes on this model, but not yet the
whole job lifecycle.

It also exposed two end-of-job problems. A status sample 2.5 seconds later was rendered as the
literal text `st`, proving that a completed USB write is not a completed printer job. Removing that
poll stopped the stray text, but a second live run still printed the full report and retained the
sheet. The poll was an interruption, not the underlying failure to eject.

The ending was then tried twice with `JE`, first alone and then ordered differently per operation.
The sheet stayed in the printer through both. (Those were not invented — see
[the second reference](#two-references-two-page-boundaries) — but neither ejected over raw USB.)

What settled it was a capture rather than an argument. `escputil` implements these commands and can
be pointed at a **file** instead of a printer, which yields its exact bytes for free:

```bash
touch /tmp/nc.bin && escputil --nozzle-check --raw-device /tmp/nc.bin -m escp2-et2750
```

There is no `JE` in it — not in the nozzle check, not in the cleaning. Both streams end with the same
`1B 00 00 00 1B 00 0C 1B 00 1B 00`, and the `0C` there is the form feed that ejects the page. The
nozzle check also turns out to be three commands inside one session, not one: `VI 02 00 00 00`, then
`NC 02 00 00 10`, then `NC 02 00 00 00`. Cleaning is `CH` alone — no prelude, and no `TI` clock
command.

Both operations are now byte-identical to that reference, asserted against the captured bytes.

## Two references, two page boundaries

Ircama's [`epson_escp2`](https://github.com/Ircama/epson_escp2) implements the same operations over
LPR, and agrees on the packet-mode exit, the double `ESC @`, `REMOTE1`, `NC 02 00 00 00` and `VI`.
It differs on the ending: it emits `JE 01 00 00` — a nozzle check leaves remote mode then sends it, a
cleaning leaves, re-enters, sends it and leaves again — and it emits **no form feed at all**, relying
on LPR closing the print job to release the page. It also syncs the clock with `TI` before `CH`.

Two coherent shapes, then: `JE` plus a job close, or a form feed. This app sends the second, because
that is the one proven on an ET-2820 over raw USB — where there is no spooler job to close, and the
page stays in the printer until something ejects it.

An ET-2825 over SNMP answers the nozzle-check command with `nc:;`: its own name, a colon, an empty
data field, a semicolon. Not a refusal — the same firmware says `:41:NA;` to a factory read on the
same connection, so it knows how to decline and chose not to. The command was **parsed and not acted
on**, and the printer's state never moved. That is what a query channel does with a command that is
not a query: `st`, `di`, `ia`, `pm` belong there, and an action does not.

So the envelope was right and the channel is wrong. The action form of these commands lives in the
print data stream in ESC/P2 remote mode, which is a different path — see
[the field notes](field-notes.md#the-control-channel-parses-nc-and-does-nothing-with-it) for both
runs in full.

The print-maintenance section exposes these operations as a guided USB-only sequence. It starts
with a nozzle check, asks whether its printed pattern has gaps, and enables cleaning only when the
answer is yes. After cleaning, it offers another nozzle check to confirm the result. The same tab
contains the separate counter-reset workflow, keeping reset beside the other routine
printer-maintenance actions.

Only the step you are on is drawn. `patternAssessment` already *is* that sequence, so the panel
reads it rather than stacking every stage at full size and greying out the ones you cannot reach —
a settled answer collapses to one line with a **Start over** beside it, and a pattern with no gaps
collapses the whole section, because there is nothing to do. Each cleaning's ink and pad cost is
stated on its confirmation dialog, at the moment of committing, rather than sitting permanently
under a button that could not be pressed.

The gate is the assertion, not the printout. **I have already seen the pattern — it has gaps**
records the same observation without spending a sheet, for someone who came here knowing what they
need. What it does not do is remove the assertion: no cleaning is ever sent that nobody claimed was
needed, which is the whole point of the gate.

The nozzle check is proven end to end on one ET-2820 — command, print, eject — and cross-checked
against `escputil` driving the same printer. Head cleaning ran first time on the same printer. Power
cleaning is untried; its confirmation says so where it will be read. Its `CH 00 10` is
corroborated by `epson_escp2`, which builds the parameter as `group | 0x10`.

## The printer will not ask

A host-started nozzle check prints the pattern and stops there. It does not raise the panel's
"is the pattern OK?" step, and no command turns that on — the panel prompts because the panel menu
started the job. A job that arrives down the data stream gets the operation and none of the
interaction.

This is confirmed rather than assumed. `escputil --nozzle-check` prints one line, `Running nozzle
check, please ensure paper is in the printer.`, and asks nothing — checked both from a file dump and
by driving it at a real ET-2820 through a CUPS queue, which produced the same page as this app's own
raw USB write.

That is not a gap to be closed in the protocol; it is where the responsibility sits. `escputil` does
the same thing and asks on its own stdout, and its head-alignment flow is entirely host-driven for
the same reason. So the asking belongs to this app: print the pattern, then put the question — gaps
or no gaps — to whoever is looking at the page, and offer a cleaning only if the answer is yes. The
probe does that in text and the Maintenance tab presents the same gate as a dialog.

## Head alignment

The operation that genuinely does have to ask. Its whole command set is captured, by driving
`escputil --align-head` through its own prompts against a FIFO — every pass answered, then saved,
with the bytes going to a file instead of a printer:

| Command | Bytes | Does |
|---|---|---|
| `DT` | `DT 03 00 · 00 <pass> 00` | print the pattern for one pass |
| `DA` | `DA 04 00 · 00 <pass> 00 <choice>` | submit the chosen pair for one pass |
| `SV` | `SV 00 00` | write the submitted choices into the printer |

The save turned out **not** to be `DA` — that is the per-pass choice. `SV` is the one that persists,
and it carries no parameters at all.

Three steps, three separate invocations:

```bash
./gradlew maintenanceProbe --args="align --live"                    # print the patterns
./gradlew maintenanceProbe --args="align --choose=8,8,8,8 --live"   # one pair number per pass
./gradlew maintenanceProbe --args="align --save --live"             # make it permanent
```

They are separate on purpose. Step two needs a human to have looked at a sheet of paper, and a
prompt read through Gradle's stdin would be the least reliable part of the feature. Step three is
the one that cannot be taken back.

**Everything up to the save is reversible by switching the printer off and on** — the choices are
volatile until `SV` writes them. After `SV` there is no undo and no backup: the previous alignment
cannot be read back, so nothing here can restore it. A half-answered set is refused rather than
defaulted, because a pair number nobody read off the paper is worse than leaving the alignment
alone.

None of the three has been run against hardware. The bytes match the reference exactly, which is
where the nozzle check started too — and that took four live runs to finish.

## Silence is a refusal condition

The other thing the hardware taught, more expensively. Asked over USB on a channel that had not come
up, an ET-2820 **printed the commands as text** — the literal string `ststncst`, the ASCII of every
command in the run — and then stalled waiting for a job that never arrived.

Two ASCII letters need no parser to do damage. If the 1284.4 channel is not up, the printer is not
ignoring a control command; it is rendering it. The only warning is a status read that comes back
empty, which is easy to read as "no news".

It is not. A run now refuses to send anything until a status block has come back, on the same
principle as `SnmpTransport.readProven` — a channel is not usable until it has demonstrably
answered. Over USB an empty status usually means the print subsystem still holds the device;
[USB connections](usb-connection.md) is the fix.

## Asking a printer

```bash
./gradlew maintenanceProbe
```

With no arguments it prints the three operations, both forms of each sequence, and — if a printer is
reachable — its current state and ink levels. It sends nothing.

Naming an operation still sends nothing:

```bash
./gradlew maintenanceProbe --args="nozzle_check"
```

Only `--live` does, and only for the operation named:

```bash
./gradlew maintenanceProbe --args="nozzle_check --live"
```

Start there. A printed test pattern is unambiguous proof the command landed, and it is the cheapest
of the three by a wide margin.

Remote mode is USB-only: it is print data, and the SNMP transport carries control commands. Three
flags cover the rest:

| Flag | What it does |
|---|---|
| `--control` | Sends the control-channel form instead, which reproduces the `nc:;` finding. Works over the network. |
| `--params=00,10` | Overrides the parameter bytes of the operative command, hex, comma-separated. The captured preludes are untouched, so only the byte pair under test changes. |
| `--no-precheck` | Skips the normal busy/error status check. Protocol experiments only; the default check is safe because the outgoing stream explicitly exits packet mode. |

The defaults are the reference's own: `VI 00 00` + `NC 00 10` + `NC 00 00` for the nozzle check, and
`CH 00 00` for an ordinary all-head cleaning. Power cleaning's `CH 00 10` stays marked inferred
because no printer here has run it, but the byte has two sources: `epson_escp2` builds a cleaning
parameter as `group | 0x10`, low bits selecting the nozzle group and `0x10` the power flag. That also
settles `CH 00 02` — nozzle group 2, not a deep clean.

## Exit packet mode before remote mode

A USB handle is host state; the active protocol parser is printer state. Closing and reopening USB
therefore does not get the printer out of IEEE-1284.4 packet mode.

The obvious order was tried first — check the printer is idle, send, check what moved — and it is
why nothing happened. Reading the status negotiates 1284.4, and **the printer stays in that mode
after the USB handle is closed**: closing a handle is a host-side event that says nothing to the
firmware. The remote sequence that followed was read by the D4 parser, found invalid, and dropped.
The ET-2820 did nothing at all.

The stream starts with Epson's fixed Exit Packet Mode EJL sequence, initialises twice, enters
`REMOTE1`, sends the operation's commands, leaves remote mode, and closes with the captured
`1B 00 0C 1B 00 1B 00` tail. Every operation ends the same way: the reference has no per-operation
ending, no `JE`, and no `TI` clock command.

That explicit exit makes the safe prefix possible again: status check, packet-mode exit, command.
There is deliberately no suffix. A status poll after USB reports the write complete can still arrive
while the printer is processing the print job, and an ET-2820 rendered that poll as `st` and retained
the paper. `MaintenanceTest` pins both the EJL preamble and the absence of post-command traffic.

## How acceptance is judged

There is no acknowledgement to check. Unlike an EEPROM write, which answers `:42:OK;` or `:42:NG;`,
a remote maintenance command produces nothing to parse, and polling afterward is unsafe. The run
therefore checks status only before sending, reports the stream as sent, and leaves acceptance to
the printed page or observed cleaning activity.

The remote path has one positive outcome, **Sent**. The control-channel experiment still keeps its
observed outcomes apart:

| Outcome | Meaning |
|---|---|
| Sent | The remote stream was written. Nothing more is sent; verify at the printer. |
| Accepted | The control experiment observed a working state. |
| Refused | A `:NA;` came back on the control experiment, or the precheck could not establish a usable idle printer. |
| Inconclusive | The command went out, nothing refused it, and the state never moved. |

Inconclusive is the honest answer to an ambiguous control-channel result, not a failure. The remote
path cannot safely resolve that ambiguity in-band, so when it says **Sent**, watch the printer — that
is the tiebreaker.

## What it will not do

A run is refused outright when the printer's own status says it is busy, and especially when it says
it is already cleaning: asking again is at best ignored and at worst another cycle's worth of ink.
That is the same gate the reset path uses, `Status.busyReason`, which is phrased without naming the
operation because every caller's answer to a busy printer is the same one.

The nozzle-check and cleaning commands do not write EEPROM. That is enforced rather than promised —
`Maintenance.packetFor` asserts that what it built is not a write packet, and the test suite walks
every operation to check it, the same way the [inspector's read-only guarantee](inspect.md) is
checked. The Maintenance tab also houses the separate, explicitly gated counter-reset workflow;
that path keeps its confirmation, automatic backup and write verification, and asks for the write
at the button — **Save current, then reset**, in red, with **Simulate reset** behind the chevron —
rather than through a mode left switched on.

Both the confirmation and the completion say the thing the counter cannot: clearing it is what
unblocks the printer, and the waste ink pad — or the maintenance box, on the models that use one —
is a physical part still holding exactly what it held before. Only replacing or cleaning it changes
that.

Both terms, deliberately. Which of the two a printer has is not something this app can derive:
`isPlatenOnly` records that every counter *in EEPROM* is a platen one, which correlates with a
maintenance box handling the main waste but does not state it, and no field anywhere says whether
a box is user-replaceable. Naming one part would be wrong for the other half of the database, so
both are named and the sentence is true either way. Where there is a real signal — the platen-only
models — the reset card's own callout still says the sharper thing.

## Over the network

Still worth pursuing, and now for a better reason than a hunch. The ET-2825 on 05.24 refuses factory
commands over SNMP with `:41:NA;`, but it *parsed* the maintenance command on that same connection
and answered it. These are not EEPROM writes, so
[the write gate](network-printers.md#the-write-gate) does not hold them back either.

A printer that can never be reset over the network may well still be cleanable over it. What is
missing is the channel, not the permission. Until an LPR print-data transport exists, the
Maintenance tab refuses a network target with that explanation and asks for a USB connection.
