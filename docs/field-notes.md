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
| Percentages | Measured by hand on the ET-282x family. Epson publishes no maxima; see [Measuring a maximum](calibration.md). |

## What went wrong

Two wrong turns on the way to network support, both worth recording, because the first one damaged a
printer.

### Port 9100, packets unchanged — a forced power-off

**The packets were sent to port 9100 unchanged**, on the reasoning that `ESC SOH @EJL 1284.4`
negotiates a 1284.4 channel over any bidirectional byte stream. It does not. Port 9100 is the print
data stream and a 1284.4 packet is not valid ESC/P2, so an ET-2820 rendered the packets as pages
and then sat waiting for the rest of a job that never arrived. It took a forced power-off.

### Correct framing, and still nothing back

**Then the framing was corrected** to ESC/P2 remote mode — `ESC @`, `ESC ( R … REMOTE1`, the
command, `ESC NUL NUL NUL`. That stopped the damage: the printer took it in silence and nothing
printed. It also answered nothing. Port 9100 is **one-way** on this firmware; it accepts a stream
and never replies, whatever you put on it.

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
