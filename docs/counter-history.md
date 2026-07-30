# Counter history and projection

A counter read is more useful beside an older read from the same printer. Epson Reset therefore
keeps successful live reads in a local append-only journal and shows the current fill trend beneath
the decoded counters on Reset.

Recording is on by default under **Settings → Counter history → Keep counter history on this computer**.
Turning it off stops new entries and leaves existing history readable. **Delete counter history…**
is a separate confirmed action because pausing collection should never destroy measurements.

The file is plain newline-delimited JSON:

```
~/Library/Application Support/EpsonReset/counter-history.jsonl     macOS
%APPDATA%/EpsonReset/counter-history.jsonl                         Windows
$XDG_DATA_HOME/epson-reset/counter-history.jsonl                   Linux
```

Each line contains one timestamp, a canonical printer serial, its defensible alias spellings, the
model name and `CounterReader.Report`'s address readings. The aliases matter for printers whose USB
descriptor encodes only part of the serial: a later SNMP or status answer can join the samples
without trusting the descriptor's ambiguous spelling. A damaged line is skipped independently; it
cannot make the records before or after it unreadable. Nothing is uploaded.

## What is recorded

A report is eligible when it came from a real printer, answered at least one address and did not
end in a report-level error. This includes:

- **Read counters** on Reset;
- the fresh printer read behind **Read & save** and live snapshot comparison;
- the pre-write read of a live reset and its successful verification read.

Dry-run values are invented by `FakeTransport` and are never recorded. Opening a snapshot file is
also not a printer read and never records anything. A printer that supplies no serial is skipped:
model and connection address are not stable enough to join measurements safely.

USB and SNMP often spell the same serial differently. The journal retains `Serials.readings` and
matches them with `Serials.same`, so changing connection does not split one printer's history even
when the USB descriptor is only partly encoded.

## Rate and projected maximum

`CounterProjection` works independently for every decoded `CounterSpec`. It uses
`SnapshotComparison` to compare dated samples, which keeps byte assembly and counter deltas identical
to snapshot comparison.

A counter drop starts a new segment. That is normally a reset; allowing older, higher values into
the slope would produce a negative fill rate and a meaningless date. Within the current segment,
the rate is the oldest-to-newest increase divided by elapsed days.

A projected date is shown only when all of these are true:

- at least two complete samples exist in the current segment;
- they are at least 24 hours apart;
- the counter increased;
- its layout declares a measured maximum.

Without a maximum the fill rate is still useful, but there is no honest destination date. The date
is an extrapolation of the observed average, not a printer guarantee: cleaning cycles, print volume
and maintenance habits can change the rate abruptly.
