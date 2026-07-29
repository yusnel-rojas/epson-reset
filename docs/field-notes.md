# Field notes

What has actually been tried against real printers, what came back, and what is still guesswork.
Everything here is a hardware finding rather than a design decision — the two are kept apart on
purpose, because a design can be argued about and a printer's answer can only be recorded.

The hardware is an ET-2820 (USB) and an ET-2825 on firmware 05.24 (USB and network).

## What has touched a printer, and what hasn't

| Path | Status |
|---|---|
| Read over USB | Exercised. Counters come back and decode; the [credit flow](#the-reply-rides-a-later-credit-exchange) was diagnosed here. |
| **Reset (write) over USB** | Exercised on an ET-2825. A live run reported 14 of 14 writes verified over 45 acknowledged packets; a re-read returned every counter at zero; restoring the pre-reset snapshot brought the original values back. The write, the read-back and the recovery path have all now answered on hardware. |
| Identity, serial, status, firmware over the network | Works on the ET-2825, over SNMP. |
| Counters and resets over the network | Refused by that firmware — [`:41:NA;`](#the-refusal-is-of-the-command-class-not-the-key). Untested elsewhere; the app asks and reports. |
| The inspector's read-key discovery | Unproven against hardware. The 159 keys and the ranking are reasoning about the database, not a result. |
| **Nozzle check over USB** | Works on an ET-2820: prints the full BK/YMC report and ejects the sheet. The stream is `escputil`'s byte for byte, and `escputil` driven against the same printer produces the same page — [below](#the-stream-that-works-is-the-reference-stream). |
| **Head cleaning over USB** | Works on an ET-2820: the printer reports it is cleaning and runs the cycle. Bytes match `escputil --clean-head`, and `epson_escp2` independently agrees on the command. |
| The maintenance commands on the control channel | Answered: that channel **parses them and does not act on them** — [below](#the-control-channel-parses-nc-and-does-nothing-with-it). Not refused, either. The action needed the print data stream. |
| Percentages | Measured by hand on the ET-282x family. Epson publishes no maxima; see [Measuring a maximum](calibration.md). |

## What went wrong

Three wrong turns, all worth recording, because the first one damaged a printer and the later ones
each looked like success from the host's side.

### Port 9100, packets unchanged — a forced power-off

**The packets were sent to port 9100 unchanged**, on the reasoning that `ESC SOH @EJL 1284.4`
negotiates a 1284.4 channel over any bidirectional byte stream. It does not. Port 9100 is the print
data stream and a 1284.4 packet is not valid ESC/P2, so an ET-2820 rendered the packets as pages
and then sat waiting for the rest of a job that never arrived. It took a forced power-off.

### Remote envelope, but an incomplete job stream

**Then the command was wrapped** in ESC/P2 remote mode — `ESC @`, `ESC ( R … REMOTE1`, the command,
`ESC NUL NUL NUL`. That stopped the damage: the printer took it in silence and nothing printed. It
also answered nothing. The later USB result showed that this was still not a complete maintenance
job stream: it lacked Exit Packet Mode, the documented `NC` payload, and job termination.

### The same rendering, over USB, from a channel that never came up

**A maintenance command was sent over USB after the status read returned nothing**, and the ET-2820
printed the literal text `ststncst` — the ASCII of every command in the run, in order: the two `st`
status queries, the `nc` nozzle check, the `st` check afterwards. Then it sat waiting for the rest
of a job that was never coming, exactly as the port-9100 attempt did.

The 1284.4 channel had not come up. Nothing in the trace said so outright; what it said was that the
status read came back **0 bytes**, twice. That is the whole warning, and it is easy to read as "no
news" — the run that caused this treated an unanswered status as "not blocked" and carried on.

Two bytes of ASCII do not need a parser to do damage. If the channel is not up, the printer is not
ignoring a control command; it is printing it. So silence on the control channel is now a refusal
condition in its own right: `Maintenance.run` will not send anything until a status block has come
back, on the same principle as `SnmpTransport.readProven`. `MaintenanceTest` pins it.

Over USB this state usually means the print subsystem still holds the device — the same thing
[USB connections](usb-connection.md) is about.

### Why SNMP was a reasonable place to try next

Worth being explicit about why SNMP is a reasonable place to experiment when 9100 was not: an SNMP
GET for an OID a printer doesn't implement comes back `noSuchName`. There is no parser to confuse
and no path by which a wrong guess becomes something the printer prints. A mistake costs an error
string.

That asymmetry is why the network path exists at all, and why `netProbe` is staged — see
[Network printers](network-printers.md).

## What went right

### The reply rides a later credit exchange

Worth knowing if you touch the read code. Under IEEE 1284.4 the printer may not transmit until it
has been granted credit, so the answer to a read doesn't arrive on the drain immediately after the
command — it rides along with a later credit exchange, and draining once returns nothing even when
the framing is correct. `CounterReader` sends a second credit pair to flush the reply, and matches
readings by the address the printer echoes back rather than by position, since one drain can carry
several. Diagnosed on a real ET-2820 with:

```bash
./gradlew readProbe --args="ET-2825"
```

### The refusal is of the command class, not the key

An ET-2825 on firmware 05.24 answers a factory read with `||:41:NA;`. The same reply comes back for
a deliberately **wrong** read key and for a different address — byte-identical — which means the key
was never examined. It is declining the command class, not objecting to the model.

That is what made it safe to conclude nothing client-side would change it, and what
`FactoryReply` exists to tell apart from a rejection. See
[When a printer says no](network-printers.md#when-a-printer-says-no).

### SNMP names the unit, and nothing else does

`…1.2.2.1.1.1.2.1` returns `ET-2825`. Both other sources — DNS-SD and the USB descriptor — say
`ET-2820 Series`, which is a *different* database entry with different addresses. Discovery pays an
SNMP round trip for this because matching on the family name silently builds a reset from the wrong
sibling's data, and this is the only place the unit's own name can be had.

### The control channel parses `nc` and does nothing with it

An ET-2825 on 05.24, over SNMP, answered the nozzle-check command with five bytes:

```
6e 63 3a 3b 0c    | nc:;.
```

That is `nc:;` — the command's own name, a colon, **an empty data field**, a semicolon. It is the
ordinary Epson reply grammar with nothing in it, and it separates three outcomes that would
otherwise all look like failure: no echo means the command reached no parser; an echo with data
means it was understood and answered; an echo with nothing in it means it was parsed and had
nothing to return.

Nothing else moved. The status block before and after was byte-identical, the state stayed idle
(`0x04`), and the printer did not react. So the command was understood and declined to act on —
which is what a *query* channel does with a command that is not a query. `st`, `di`, `ia`, `pm` all
belong there; a nozzle check does not. The action lives in the print data stream in ESC/P2 remote
mode, which is a different path entirely.

Two things worth keeping from this. The first is that **this was not a refusal**: the same firmware
answers a factory read with `:41:NA;` over the same connection, so it clearly knows how to say no,
and it did not say it here. The second is that the reply grammar is a usable signal — `Maintenance`
now reads the echo and reports "parsed but not acted on" separately from silence, because the two
mean opposite things about what to try next.

### Reading status exposed the missing packet-mode exit

The remote-mode sequence was sent to an ET-2820 over USB in the obvious order — check the printer is
idle, send, check what moved. The framing was taken in silence, nothing printed, and **the printer
did nothing at all**: no paper, no carriage movement, no sound, and a status block byte-identical
either side of the command.

Complete silence is the informative part. Reading the status negotiates 1284.4, and the printer
**stays in that mode after the USB handle is closed** — closing a handle is a host-side event and
says nothing to the firmware. The stream started immediately with `ESC @`, so it went to a D4 parser,
which found it invalid and dropped it.

The missing instruction was Epson's fixed Exit Packet Mode EJL sequence. A newly opened USB handle
is not enough; the print-data stream must actively return the printer-side parser from 1284.4 to
ESC/P2 before entering `REMOTE1`.

The failed stream also carried `NC` with a zero-byte payload. The documented form is `NC 02 00 00
00`: a two-byte payload of `00 00`. The corrected probe now sends Exit Packet Mode, two `ESC @`
initializations, the `REMOTE1` session and two-byte `NC`, then closes the maintenance job. Because
the outgoing stream exits packet mode itself, `runInRemoteMode` can safely check status first again.

The corrected `NC 00 00` stream was then run live on the ET-2820. It printed the complete BK/YMC
nozzle report, including the utility-style explanatory text. That is a successful host-utility
nozzle check, not the printer-panel workflow; the panel owns its own prompts and follow-up UI.

The automatic status sample afterward was not safe. Although the USB write had completed and the
report content was on paper, the printer had not returned to its control parser 2.5 seconds later.
It printed the literal status command `st`, retained the sheet, and returned no status bytes. A
subsequent run with all post-command traffic removed printed no stray characters and again produced
the complete report — but still retained the sheet. So the status poll caused `st`; it did not cause
the missing eject.

### The stream that works is the reference stream

The ending was then reasoned about twice, and both answers were wrong. First `JE` on its own, on the
grounds that a job needs closing. Then a `JE` ordering that differed between nozzle check and
cleaning, on the grounds that the asymmetry explained the retained sheet. The sheet stayed put
through both.

What settled it was not more reasoning but a capture. `escputil` from gutenprint 5.3.3 implements
these commands, and it can be pointed at a **file** instead of a printer, which yields its exact
bytes at no cost and with no hardware involved:

```bash
touch /tmp/nc.bin && escputil --nozzle-check --raw-device /tmp/nc.bin -m escp2-et2750
```

Three things came out of that dump, none of them guessable:

- **There is no `JE`.** Neither the nozzle-check nor the clean-head stream contains one.
- **Both streams end identically**, `1B 00 00 00 1B 00 0C 1B 00 1B 00`. There is no per-operation
  ending, and the `0C` form feed in it is what ejects the sheet — the missing byte all along.
- **The nozzle check is three commands, not one**: `VI 02 00 00 00`, then `NC 02 00 00 10`, then
  `NC 02 00 00 00`, all inside a single remote session. Cleaning is `CH` alone, with no prelude and
  no `TI` clock command.

Both operations are now byte-identical to that reference, asserted against the captured bytes in
`MaintenanceTest`. The lesson is cheaper than the four live runs that preceded it: when a reference
implementation exists, dump it to a file first.

Cross-checked afterwards by driving `escputil --nozzle-check` at the same ET-2820 through a CUPS
queue. It produced the same page this app's raw USB write produces — same bytes over a different
transport, same result. `libusb` also kept working with the queue present; macOS does not hold the
interface exclusively until something actually prints.

That cross-check was briefly written up here as retiring the spooler-boundary theory. It does not:
escputil's stream carries its own `0C`, so it ejects on any transport, and a test where both sides
self-eject says nothing about whether a job close would have done it instead. The theory is
untested, not disproven — and [a second reference](#a-second-reference-disagrees-with-the-first)
turns out to depend on it.

### A second reference disagrees with the first

`escputil` is not the only implementation of these commands. Ircama's
[`epson_escp2`](https://github.com/Ircama/epson_escp2) (EUPL-1.2) builds the same operations for a
different transport — LPR rather than raw USB — and where the two disagree, the disagreement is
informative rather than a sign that one is wrong.

Agreed, byte for byte: the `@EJL 1284.4` packet-mode exit, the double `ESC @`, `ESC ( R 08 00 00
REMOTE1`, `NC 02 00 00 00`, and `VI 02 00 00 00`.

Disagreed, and this is the useful part:

- **`JE` is real.** `JOB_END = remote_cmd("JE", b'\x00')` — `JE 01 00 00` — and the per-operation
  ordering is exactly the asymmetry that was tried here and then withdrawn: a nozzle check leaves
  remote mode and emits `JE`, a cleaning leaves, re-enters, emits `JE` and leaves again. It was
  recorded above as invented. It was not; it just is not what `escputil` does.
- **`TI` before `CH` is real**, from the same source, attributed to the XP-205/207 driver sequence.
  It was removed here for having no reference. It had one.
- **There is no form feed anywhere in that implementation.** It ends on `JE` and relies on LPR
  closing the print job to release the page. Which is precisely the spooler-boundary reasoning, and
  why the theory above cannot be called dead.

So there are two coherent shapes: `JE` plus a job close, or a form feed. This app sends the second,
because that is the one proven on an ET-2820 over raw USB, where there is no job to close. Nothing
here is invented — the two references simply solve the page boundary differently, and the transport
decides which one applies.

- **Power cleaning is `group | 0x10`.** The cleaning parameter's low bits select the nozzle group
  (0–5) and `0x10` is the power flag, cited to x900-otsakupuhastajat. `CH 00 10` was inferred here
  and is now corroborated — and `CH 00 02`, briefly labelled power cleaning, is nozzle group 2.

### Cleaning ran first time

`CH 02 00 00 00` over USB, in the same envelope the nozzle check uses, put an ET-2820 into a cleaning
cycle on the first live attempt — no retained sheet, no stray text, no protocol archaeology.

Worth recording because of the contrast. The nozzle check took four live runs and two wrong endings
to get there; cleaning took none. What changed in between was not skill but method: by the time
cleaning was tried, the stream had been dumped from a reference implementation rather than reasoned
out, and a second reference had been checked against it. The bytes were right before the printer
ever saw them.

The cycle also does what the feature exists to warn about — it flushes ink into the pad, and the
counter this app otherwise lowers goes up. Nothing measured that here: no counter reading was taken
before the run, so what one cycle costs is still unknown. Taking a reading either side of the next
one is [how that number gets made](calibration.md).

### A host-started maintenance job is not asked about

`escputil --nozzle-check` prints exactly one line, `Running nozzle check, please ensure paper is in
the printer.`, and asks nothing — confirmed both from a file dump and against real hardware. Nothing
appears on the page either.

That is worth recording because the absence looks like a defect. The panel's "is the pattern OK?"
step belongs to the panel menu that started the job; a job arriving down the data stream gets the
operation and none of the interaction, and no command switches it on. The only `escputil` operation
that asks anything is `--align-head`, which is interactive because a pattern number has to be chosen
and sent back.

So the asking belongs to whatever started the job. This app's probe now puts the gaps-or-no-gaps
question in text, which is one more than the reference does.

### The reset writes, and the restore puts it back

An ET-2825 over USB, live: 14 writes across 2 groups, all 14 verified, 45 packets sent and 45
acknowledged. Reading the counters again returned every address at zero — the platen group's
`08 96 15 5E 5E 5E` came back `00 00 00 5E 5E 5E`, the `5E` bytes being the limit bytes that never
move. Replaying the snapshot taken before the run restored the original values, which is the first
time the recovery path has been shown to work against hardware rather than against a simulated
EEPROM.

Two things this does not establish: that the same sequence is right for any other model — the keys
and addresses are per-model data, and only this one has been tried — and that a reset over the
network is possible, which the same firmware still refuses.

## Standing limits

- Percentages are almost never available. Upstream defines a `max` for 6 of 626 counters, and no
  model in the ET-2820 family has one. The UI says "no limit" rather than inventing a number;
  supply your own via [an overlay](counter-database.md#overlays) or
  [measure one](calibration.md).
- Some groupings are guesses. Entries upstream marks `(?)` are flagged "layout uncertain". Groups
  wider than 4 bytes are shown as raw bytes — the ET-2820's 6-address entry mixes a real counter
  with limit bytes that always read `0x5E`, and decoding it as one integer gives a meaningless
  14-digit number.
- The inspector's key discovery is unproven against hardware, as above.
- **Maintenance commands do not work on the control channel**, and none of them are in the window
  yet. Over USB in remote mode, the nozzle check is proven end to end on one ET-2820 — command,
  print, eject, cross-checked against `escputil` driving the same printer — and head cleaning ran
  first time. Power cleaning is untried; its `CH 00 10` is corroborated by `epson_escp2` building the
  parameter as `group | 0x10`, but no printer here has run it.
- **A host-started maintenance job gets no printer prompt**, and that is correct rather than
  missing — [above](#a-host-started-maintenance-job-is-not-asked-about). The asking belongs to the
  app.
- **Head alignment is implemented but unproven.** All three commands are captured — `DT` prints a
  pass, `DA` submits its chosen pair, `SV` persists the set — by driving `escputil --align-head`
  through its prompts against a FIFO. The save is `SV`, not `DA` as first assumed. Nothing has been
  run against hardware, and `SV` is the only operation in this app that changes a printer setting
  with no way to read the old value back and restore it.
- **Counters over the network work only where the firmware allows factory commands.** An ET-2825 on
  05.24 refuses them (`:41:NA;`) and there is nothing client-side to be done about it — identity,
  serial and ink levels still work, resets need USB. Whether other models allow it is untested; the
  app asks and reports.
- Many models keep only the platen counter in EEPROM, so a reset leaves the waste box counter alone
  and the box still needs servicing. The UI says so before you write.

## Adding to this

A hex trace of every packet is in the log panel, and that is what a bug report wants. If your
printer answers something not listed here — particularly a network printer that *allows* factory
commands — that is a finding worth filing, since the table at the top is one household's hardware.
