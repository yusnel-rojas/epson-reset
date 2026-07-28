# Implementation notes

Rules and traps that live in the code but cannot be read off it — the things that were expensive to
work out, and that are easy to undo by accident. Everything here is a maintainer's note; the
user-facing behaviour it produces is described on the other pages.

## The protocol

**Reads are unprivileged.** `SequenceGenerator.readPacket` carries a read key and no write key, and
has no value byte. That single fact is what makes sampling counters safe against a mismatched
model, what lets the inspector explore a printer nobody has identified, and what lets a reset take
its backup before deciding anything. Anything that adds a key to the read path removes all three.

**`||` is a command name, not a prefix.** It is the name of the *factory* commands that carry a
read key. Plain commands such as `st` (status) use their own two letters and no key, so they cannot
be built by prepending `||` to something.

**The endianness split in a write packet is deliberate.** The 1284.4 header length is big-endian;
the Epson payload length that follows is little-endian. It reads like a bug and is not one.

**One write path.** `generate` is "these pad groups at their reset values" and a restore is "these
addresses at the bytes that were there before" — the same body, same framing, same `:42:OK;`
verification. The golden-packet tests therefore pin the framing a restore emits too. Adding a
second write path is how that guarantee gets lost.

**Replies lag behind commands.** One drain can carry several replies, or a previous read's reply,
so readings are matched by the address the printer echoes back rather than by position. See
[Field notes](field-notes.md#the-reply-rides-a-later-credit-exchange) for how that was found.

## The status block

`@BDC ST2` is the literal header, then a little-endian 16-bit payload length, then type/length/value
records.

- **Skip exactly one line terminator, never a run of them.** The length that follows is binary and
  its low byte is `0x0A` often enough to matter — a 10-byte payload, a 2570-byte one. A greedy skip
  eats the length, and every field after it decodes from the wrong offset.
- **Ink levels lead with the entry width, not an entry count.** Then one `[slot][colour][level %]`
  per colour. An earlier reading of this took the first byte for a count and was wrong.
- **The write blocker is an allow-list.** Only a printer that reports itself idle is written to.
  Listing the *unsafe* states instead would make every state code this doesn't know about read as
  permission, and a write landing mid-job is the one thing a backup taken moments later cannot undo
  cleanly. A block with no state field gates on nothing, which is the honest answer: nothing was
  reported.
- **State names are wording only.** Only the idle state is confirmed against hardware; the rest come
  from the common documentation. A code missing or mislabelled there can produce a clumsy message,
  never permission to write.
- **Fields `0x28`, `0x36`, `0x37` and `0x54` are deliberately unnamed.** `0x36` in particular is a
  tempting 32-byte block decoding to plausible small integers, but nothing establishes which — if
  any — is a waste-pad figure, and a mislabelled maintenance number is worse than an unlabelled
  one. A calibration report carries the block raw for exactly this reason
  ([Measuring a maximum](calibration.md#what-the-submission-is)).

## SNMP

**What opens the write gate is a parsed `EE:` reading — nothing else.** Not a non-empty reply, and
not the passthrough's leading status byte, which is `0x00` on refusals too. The gate itself is
described in [Network printers](network-printers.md#the-write-gate); this is the part that decides
whether it is real.

**Skip BER fields by their own length, structurally.** Lengths are self-describing, so nothing may
assume the offsets one agent's encoding happens to produce.

**Not `position += readLength()`.** The left operand is read before the call, so the bytes
`readLength()` consumes for the length are thrown away, every skip lands short, and the cursor walks
into the middle of the community string — reporting the `b` of `public` as the PDU tag. The codec
tests pin this.

**Multi-byte sub-identifiers are not an edge case.** Command payloads are arbitrary bytes, so any
value above 127 in an ESC/P command hits the multi-byte encoding. Getting it wrong silently
addresses a different OID.

## DNS-SD

Hand-rolled: one question, four record types, a frozen wire format, and no interest in publishing
anything — a dependency here would ship a whole responder.

- **The TXT record is the reason it's worth doing.** Epson's `_pdl-datastream._tcp` advertisement
  carries `usb_MFG` and `usb_MDL` — the USB descriptor strings, republished — so a discovered
  printer arrives with the identity the USB path would have given it and resolves the same way.
- **Multicast first, unicast reply as the fallback.** Binding 5353 alongside the OS resolver is fine
  with `SO_REUSEADDR`, and a refused bind is the cue to fall back to a QU-bit query
  (RFC 6762 §5.4). The fallback is not the primary path because responders may ignore the bit and
  multicast the answer, where an ephemeral socket would miss it.
- **Send on every interface.** On a laptop with a VPN up, the default route is not the one the
  printer is on.
- **Instances come from SRV names, not PTR answers**, and anything that doesn't say Epson somewhere
  is dropped rather than listed unmatched — offering to write EEPROM keys to another make is not a
  mistake worth leaving available.

## The inspector

**Nothing in it emits opcode `0x42`.** That is the safety property the whole feature rests on, and
it is asserted per packet rather than left to review: a bug that let a write out here would write to
a guessed address on a printer we have by definition failed to identify — the one irreversible thing
in the app.

- **Keys are ordered by how many models share them.** The read key is a family trait, so the popular
  ones clear most consumer printers in the first few probes. Key `0` is dropped: it is the parser's
  default for entries that never declared one, not a key.
- **Probe the addresses that key's family uses.** An address no model populates can legitimately
  read back nothing on a working key and look like a failure.
- **One handshake for the whole sweep**, and an early stop once enough keys have answered — the
  point is a usable key, not an enumeration of every key the firmware tolerates.
- **It is not established that a wrong key yields silence** rather than a junk reply. If a printer
  answers every key, that means the firmware doesn't validate keys on reads and the ranking collapses
  to "they all work" — still usable, since then the sweep is what matters. So hit counts are reported
  and the caller judges, rather than a winner being announced.

## What the window enforces

The view model owns the decisions that gate a live write, which is why its transport, discovery and
backup directory are constructor parameters — see [Tests](testing.md).

- **Identity is not selection.** The model a printer named *itself* is recorded only from evidence
  the printer supplied: an exact scan match, or a model string a connection test returned. A
  merely-likely match is excluded, because it already asks the user to confirm and pinning a guess
  would make the guess unarguable. A mismatch between the two is measured against that, never
  against a remembered preference — which is why a remembered model yields to anything the printer
  says.
- **A live write is blocked on evidence, not policy.** A connection test that came back status-only
  means this printer has already refused, so a run that cannot work is not offered. With no test
  run, the attempt is allowed: the reset reads before it writes, refuses without a complete backup,
  and the transport will not carry a write until a read has succeeded on that connection.
- **A dry run may never produce a file.** Snapshots, comparisons and calibrations are all refused
  against simulated values, because the fake transport invents a byte for every address and the
  result would be indistinguishable from a real capture — one a restore would happily write into an
  EEPROM, or a maximum every later percentage would be computed from.
- **The read-back is the proof.** A printer can acknowledge every write and commit none, so a run
  counts as successful only when the values come back changed.
