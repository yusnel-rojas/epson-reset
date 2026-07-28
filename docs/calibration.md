# Measuring a maximum

A percentage needs a maximum, and Epson publishes none. Upstream declares one for 6 counters out of
626; the rest of what this app knows was measured off a printer by hand. That is the entire reason
**Percentage possible** reads 14 out of 1588 — not a missing feature, a missing measurement. And the
only place a maximum can come from is a printer that has one to reveal.

So the counters card carries the action that fills it: **Measure a maximum…**, top right, which
opens a window of its own. A form belongs off to the side — it is an errand run once, and the
counters are what the tab is for. Two observations count.

| Observation | Gives |
|---|---|
| **The printer is saying service required now** | The counter has reached its limit, so the value on screen *is* the limit. Exact, and it needs no second tool — the printer is the reference. Worth recording the moment the message appears: the count goes on rising afterwards, and a late reading overstates the maximum. |
| **Another tool reports a percentage** | `max = value / (percent/100)`, which is how all three ET-282x figures were derived. |

A borrowed percentage is only as precise as its printed decimals, so the derivation is a range
rather than a number: `60.90` on a reading of 3865 means the true figure was in `[60.895, 60.905)`,
which puts the maximum at 6346 and nowhere else. `60.9` — one decimal — leaves it anywhere between
6342 and 6351, and the form says so rather than presenting the midpoint as an answer. The range is
recorded beside the maximum so the figure stays re-checkable, and the smaller end is taken as the
maximum: it reports the pad as fuller than it might be, and a warning that comes early is the one
worth having.

Alongside the percentage there is a box for **the count that tool shows**, which is optional and
worth filling in. A tool reading a different number is reading a different counter, and its
percentage would then calibrate the wrong thing — so a mismatch is refused, and reported as the
layout finding it is rather than merged as a maximum.

## Which model, exactly

The first field on the form, and the one that decides what the submission is worth. A maximum
belongs to a **unit**, not a family: over USB the descriptor only ever says `ET-2820 Series`, which
is its own database entry covering eight SKUs, and **127 models share that layout** in
`counters.json`. A measurement filed under the family is attributed to all of them and to none.

It matters most for the case that hasn't happened yet. If a second printer measures the same
counter and gets a different maximum, that is the evidence for splitting the group — and it only
works if both submissions name the unit they came from. Two measurements against `ET-2820 Series`
say nothing at all.

So the field starts from the strongest identification available, and says which one that was. They
are not equal. SNMP reads the model out of the Epson MIB and gives the unit — including
[borrowed onto a USB printer](architecture.md#the-other-link-usually-knows) from its own network
entry, which is the best case, since the printer settles it and nobody has to be asked; a USB
descriptor on its own almost always gives the family, which is how `ET-2820 Series` ends up
prefilling the box as `ET-2820`; and where the family's members disagree, the name is one
[you confirmed by hand](architecture.md#families-and-why-a-match-is-not-always-an-identification)
rather than anything the printer said. The form names the channel and quotes what it answered, so
"the printer says so" is never claimed on behalf of a source that said something weaker.

That changes what the warnings mean. Correcting a name derived from a family is not an override —
it is the field doing its job, and the form says so rather than defending the prefilled value. The
prefilled value is queried too, because leaving `ET-2820` in place is exactly how a unit's
measurement gets filed under eight of them.

The picker lists the models sharing this layout, its own series first, and captions each with why
it is there. The report carries the channel, the verbatim answer and the filed name, so a maintainer
can see how it was arrived at rather than taking it on trust.

## Applying one, and taking it back off

A maximum is the divisor behind every percentage for that counter, so a wrong one makes every
reading of it wrong — which makes how to undo it part of the feature rather than a footnote. There
are two ways to apply one, and they differ in exactly the way that matters afterwards:

| | Lasts | Undone by |
|---|---|---|
| **Use this maximum now**, on the form | This session | **Undo**, next to it — or quitting the app |
| **counters-overlay.json**, saved into the data directory by hand | Until the file is deleted | **Settings → Counter maxima → Delete the overlay file** |

Settings states which of the two is in force, if either, so "my percentages look wrong" has an
answer that does not involve knowing that a restart would have cleared half of it. Nothing in the
app writes the overlay file: it is copied out of the form and saved deliberately, and it is the only
one of the two that survives a restart.

## What the submission is

Every counter says what the app holds for it today, because that decides what the submission *is*:

| | |
|---|---|
| **New** | No maximum on file. This is the gap, and the only thing that closes it. |
| **Confirms** | There is one, and this agrees. Still worth filing: every figure in `calibrations.json` rests on exactly one printer, and a second unit agreeing is the only evidence that a pad capacity is a model constant rather than that printer's number. |
| **Disagrees** | There is one, and this contradicts it. A finding, not a correction — `apply_calibration.py` refuses to merge over it, and the report says so at the top. |

The form is refused in a dry run, for the reason **Save snapshot**
[is](backup-and-restore.md#what-a-snapshot-covers): a dry read's values are invented by
`FakeTransport`, and a maximum derived from them would be indistinguishable from a measured one —
except that every percentage anyone computed from it afterwards, on every printer of that model,
would be wrong.

Four things come out of it:

- **Show it now** layers the maximum onto this session's layouts, through `applyCalibrations` —
  the same code path the bundled file takes, so there is no second way a percentage can appear. The
  count in the window's corner ticks up as you watch.
- **Copy overlay** keeps it after a restart, as `counters-overlay.json`.
- **Copy entry** is the `calibrations.json` entry itself.
- **Open an issue** fills in a form in your browser and stops there. Nothing is sent; you read it
  and submit it.

The report carries the model, firmware and connection; each counter's value, what the app currently
holds for it and what the measurement makes of that; the ink levels, off the same status block, as
context for a pad that fills as ink is used; and the raw status block undecoded, because
`protocol/Status` refuses to name the fields that *look* like maintenance figures and a submission
that carries them beside a known maximum is how that eventually gets settled. It does not carry the
serial number, which would identify your printer in a public issue while adding nothing: a pad
capacity is a model constant rather than a per-unit one. The window lists all of this before you
send anything.

The generated entry names **only the model measured**, never its siblings. 119 models share the
ET-2820's EEPROM layout, and a shared layout does not prove identical pad capacity; widening the
list is a judgement to make with the evidence in front of you.

## Merging one

```bash
python3 tools/apply_calibration.py entry.json --dry-run
```

It checks the models exist, that every address is a counter `counters.json` already declares (a
calibration adds a maximum, never a layout), that the maximum sits inside its own stated range, and
that nothing on file contradicts it. Then it splices the entry in textually, leaving the rest of the
file byte for byte — `"percent": 60.90` has to keep its trailing zero, because that zero is what
says the tool was read to two decimals.

Then `./gradlew test`, which **fails**: `CapabilityTest` pins how many models can show a percentage
and the [capability table](counter-database.md#model-capabilities) repeats it. That is deliberate.
The number should only ever move because somebody measured something, so moving it is an edit, and
the assertion reports what to change it to (`expected: <14> but was: <15>`).
