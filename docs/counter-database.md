# Counter database

## Model capabilities

The **Models** tab lists the whole database against what the app can do with each entry. Every
column is derived from the bundled data at load time. Clicking a row selects that model.

<img src="images/screenshot-02.png" alt="The Models tab: 1588 models with their reset, read, decode and limit capabilities" width="900" />

| | |
|---|---|
| Resettable | 1475 of 1588 — the other 113 carry no EEPROM addresses |
| Platen-only | 313, where a reset clears the platen pad only |
| Values decoded | 123 fully, 1352 in part — upstream marks some layouts a guess, or the group is too wide to be one number |
| Percentage possible | 14 — a maximum is declared upstream for 6, measured here for the ET-282x family. [Your printer can add to that](calibration.md) |

Reset and read are separate columns even though the bundled data answers them identically today: an
OTA refresh or user overlay can move one without the other, and reading shouldn't inherit a "no"
from the write path. `CapabilityTest` pins every number above.

## The two files

Both are generated from reinkpy's `epson.toml`:

| File | Gives |
|---|---|
| `database.json` | rkey / write keys / reset values in pad groups — drives the **reset** |
| `counters.json` | which addresses group into one counter — drives the **read** |

They differ because a pad group is not a counter. A group is everything the reset writes to one
pad, so `[48,49]` is two loose bytes to it, while reinkpy's grouping keeps it as one 2-byte
little-endian value — the difference between showing `0x19 0x0F` and showing `3865`.

Regenerate both:

```bash
curl -sL https://codeberg.org/atufi/reinkpy/raw/branch/main/reinkpy/epson.toml -o epson.toml
python3 tools/convert_reinkpy.py epson.toml src/main/resources/counters.json src/main/resources/database.json
```

## Resyncing

**Sync printer data** in the Actions tab does the same on a runner, monthly or on demand. It runs
the tests and dry run against the result, then opens a PR listing which models were added, removed
or changed — always a PR, never a direct commit, since a group changing shape upstream changes what
the app reads together and what it writes. Measured maxima live in `calibrations.json` and are
layered on at load time, so a resync never discards them.

## Overlays

To add or correct a model without rebuilding, drop a `counters-overlay.json` next to the database
cache (`~/Library/Application Support/EpsonReset/` on macOS). A model in the overlay replaces the
bundled entry outright:

```json
{
  "groups": [
    {
      "models": ["ET-2825"],
      "counters": [
        { "addr": [48, 49], "desc": "Waste counter", "max": 8450 },
        { "addr": [50, 51], "desc": "Waste counter (platen)" }
      ]
    }
  ]
}
```

Add a `max` and that counter starts showing a percentage. Where that number comes from is
[Measuring a maximum](calibration.md).
