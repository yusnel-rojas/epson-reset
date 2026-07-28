# Printers not in the database

The **Inspect** tab is for when your printer isn't in the list. It's read-only end to end, so it
can't modify a printer we've by definition failed to identify — `DeviceInspector.assertReadOnly`
enforces that per packet, and two tests cover it.

1. **Find a read key.** A model reports nothing until it gets its 16-bit read key. Rather than
   brute-forcing 65536, this tries the 159 distinct keys already in the database, most widely used
   first — they run in families, so a consumer printer usually falls out in the first few.
2. **Sweep the EEPROM.** Reads every address in range (256 / 512 / 2048) as a hex dump, with `--`
   where nothing answered.
3. **Rank candidates.** `family` means a model sharing your read key uses exactly these addresses —
   close to a real layout, since the key is a family trait and families share their EEPROM map.
   `likely` is an adjacent little-endian pair bordered by a `0x5E` limit byte, how Epson marks a
   group's edge. `weak` is an adjacent pair holding a plausible count and nothing else. Blank
   (`0x00`), saturated (`0xFF`) and pure limit-byte regions are excluded — that's unprogrammed
   EEPROM, not data.
4. **Export.** An [overlay](counter-database.md#overlays) so the app works for you now, and a report
   to file with [reinkpy](https://codeberg.org/atufi/reinkpy) — that's where both bundled files are
   generated from, so a model added there reaches this app on the next sync, and every other tool on
   the same data too.

Key discovery has not been proven against hardware; see
[Field notes](field-notes.md#what-has-touched-a-printer-and-what-hasnt).
