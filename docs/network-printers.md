# Network printers

A printer on Wi-Fi or Ethernet is reached over **SNMP**, not over the print port — see
[Field notes](field-notes.md#what-went-wrong) for how that was arrived at, and what it cost. What
the app can do there depends on the printer, and it asks rather than assumes.

Nothing needs installing for a network printer: no driver, no library, no wrestling with the OS for
an interface.

## What always works

On any Epson answering SNMP:

| | |
|---|---|
| **Exact model** | `…1.2.2.1.1.1.2.1` → `ET-2825`. The only source anywhere that names the unit rather than the family — DNS-SD and the USB descriptor both say `ET-2820 Series`, which is a *different* database entry with different addresses. Discovery pays an SNMP round trip for this because matching on the family name silently builds a reset from the wrong sibling's data. |
| **Serial** | `…1.1.1.5.1` → `XADA020273`. What binds a backup to one physical printer. |
| **Status** | `…1.1.1.4.1` → a complete `@BDC ST2` block, byte-identical to the one the USB path reads: ink levels and all. |
| **Firmware** | `…2.1.1.2.1.3` → `05.24`. Worth reporting, because the refusal below is firmware-specific. |

## What depends on the printer

Counters and resets depend on the **command passthrough**,
`1.3.6.1.4.1.1248.1.2.2.44.1.1.2.1.<command bytes as sub-identifiers>`, which carries an ESC/P
command and returns its reply. Whether a firmware honours a *factory* command (`||`, carrying a
read key) through it is the printer's decision, and printers differ. **Test connection** asks, in
one read, and reports the answer.

## The write gate

Not a ban on network writes, and not a switch either. `SnmpTransport` refuses to carry a write
**until that same connection has already completed a read** — until an `EE:` reply has come back
through it.

That makes the printer the decider. A firmware that answers reads has demonstrated it accepts
factory commands from here, and a reset run reads its counters before writing anything (the backup
depends on it), so on such a printer the gate opens by itself in the normal course of the run. A
firmware that refuses never produces the evidence, so a write is structurally impossible rather
than merely disallowed.

## When a printer says no

An ET-2825 on firmware 05.24 answers a factory read with `||:41:NA;`. The same reply comes back for
a deliberately **wrong** read key and for a different address — byte-identical — which means the key
was never examined. It is declining the command class, not objecting to the model.

`FactoryReply` tells that apart from the two answers it could be confused with, because the three
need different things said:

| Reply | Means | What to do |
|---|---|---|
| `EE:AAAAVV;` | a reading | — |
| `:42:NG;` | the write key doesn't match this model | pick the right model |
| `:41:NA;` / `:42:NA;` | the command class is refused here | use USB; nothing on this side changes it |

## Finding printers

| | |
|---|---|
| **Discovered** | A DNS-SD browse for `_pdl-datastream._tcp`, then SNMP for the identity. Hand-rolled in `net/MdnsDiscovery`: one question, four record types, no dependency. |
| **Added by address** | Type an IP (or paste the printer's web-page URL). It identifies itself over SNMP immediately, and is saved to `network-printers.txt` next to the database cache. |

Discovery is a convenience, not a requirement — a guest network, a VPN, or a sandbox that won't
pass multicast each break it without stopping SNMP working. So the address box is always there.

## Probing one from the command line

`netProbe` is the staged version, for finding out what a printer actually does. Each stage answers
one question and stops if it can't:

```bash
./gradlew netProbe --args="192.168.2.39"
```

Stages 1-3: is an agent answering, what does it say it is, and does the command passthrough exist
(tested with a plain `st`). Then the one that decides everything:

```bash
./gradlew netProbe --args="192.168.2.39 --read"
```

Stage 4 sends a single EEPROM read and reports whether the firmware allowed it. All of it is SNMP
GETs, so nothing can be written and nothing can be printed.
