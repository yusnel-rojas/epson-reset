#!/usr/bin/env python3
"""Merge a submitted counter calibration into calibrations.json.

A calibration is a measured maximum for one model's waste counter — the thing that turns a raw
count into a percentage. They arrive as the JSON entry Epson Reset generates (see
.github/ISSUE_TEMPLATE/calibration.yml), and this checks one before it lands:

    * the models exist in counters.json
    * every `addr` is a counter that file already declares for them — a calibration adds a maximum,
      never a layout, so upstream stays the only source of truth for which addresses form a counter
    * `max` lies inside the `range` the submitter's own observation implies
    * nothing already on file contradicts it

Usage:
    python3 tools/apply_calibration.py entry.json [--dry-run]
    pbpaste | python3 tools/apply_calibration.py - --dry-run

The merge is a textual splice rather than a re-serialisation. calibrations.json is hand-formatted,
and `"percent": 60.90` means something `json.dump` would destroy: the trailing zero is what says the
reference tool was read to two decimals, and the range beside it cannot be re-derived without it.
"""
import argparse
import json
import sys

COUNTERS = 'data/reinkpy/counters.json'
CALIBRATIONS = 'data/curated/calibrations.json'


def fail(message):
    print(f'refused: {message}', file=sys.stderr)
    sys.exit(1)


def layouts(path):
    """model (lowercased) -> the counters counters.json declares for it."""
    doc = json.load(open(path))
    out = {}
    for group in doc.get('groups', []):
        for model in group.get('models', []):
            out[model.lower()] = group.get('counters', [])
    return out


def existing_maxima(doc):
    """(model, addr) -> max, for everything already calibrated."""
    out = {}
    for entry in doc.get('calibrations', []):
        for model in entry.get('models', []):
            for maximum in entry.get('maxima', []):
                out[(model.lower(), tuple(maximum['addr']))] = maximum['max']
    return out


def read_entry(source):
    """The submitted entry, however it was pasted: bare, wrapped, or as a list."""
    text = sys.stdin.read() if source == '-' else open(source).read()
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError as e:
        fail(f'that is not valid JSON ({e})')

    if isinstance(parsed, dict) and 'calibrations' in parsed:
        entries = parsed['calibrations']
    elif isinstance(parsed, list):
        entries = parsed
    else:
        entries = [parsed]

    if len(entries) != 1:
        fail(f'expected one calibration entry, got {len(entries)} — merge them one at a time')
    return entries[0], text


def validate(entry, declared, already):
    models = entry.get('models')
    if not models or not isinstance(models, list):
        fail('the entry names no models')

    maxima = entry.get('maxima')
    if not maxima or not isinstance(maxima, list):
        fail('the entry carries no maxima')

    for model in models:
        if model.lower() not in declared:
            fail(f'{model} has no counter layout in {COUNTERS}, so there is nothing to calibrate')

    for maximum in maxima:
        addr = maximum.get('addr')
        value = maximum.get('max')

        if not isinstance(addr, list) or not addr:
            fail(f'a maximum has no addresses: {maximum}')
        if not isinstance(value, int) or value <= 0:
            fail(f'addr {addr} has no usable max: {value!r}')
        if 'observed' not in maximum:
            fail(f'addr {addr} records no observation, so nobody can re-check it')

        # A maximum outside the window its own observation implies is arithmetic that did not
        # happen. The app emits both, so a mismatch means one of them was edited by hand.
        window = maximum.get('range')
        if isinstance(window, list) and len(window) == 2 and not window[0] <= value <= window[1]:
            fail(f'addr {addr}: max {value} is outside its own stated range {window}')

        for model in models:
            counters = declared[model.lower()]
            if not any(counter['addr'] == addr for counter in counters):
                shown = ', '.join(str(c['addr']) for c in counters)
                fail(
                    f'{model} declares no counter at {addr}. Its counters are: {shown}. '
                    'A calibration adds a maximum, never a layout — fix the layout upstream '
                    'at reinkpy first.'
                )

            previous = already.get((model.lower(), tuple(addr)))
            if previous is not None and previous != value:
                fail(
                    f'{model} addr {addr} is already calibrated at {previous}, and this says '
                    f'{value}. Two measurements disagreeing is a finding, not a merge — resolve '
                    'it by hand.'
                )


def splice(text, entry_text):
    """Insert one entry at the end of the "calibrations" array, leaving the rest byte for byte."""
    key = text.index('"calibrations"')
    start = text.index('[', key)

    depth, i, in_string, escaped = 0, start, False, False
    while i < len(text):
        c = text[i]
        if in_string:
            if escaped:
                escaped = False
            elif c == '\\':
                escaped = True
            elif c == '"':
                in_string = False
        elif c == '"':
            in_string = True
        elif c in '[{':
            depth += 1
        elif c in ']}':
            depth -= 1
            if depth == 0:
                break
        i += 1
    else:
        fail(f'{CALIBRATIONS} has no closing bracket for its calibrations array')

    body = text[start + 1:i]
    indented = '\n'.join('    ' + line if line else line for line in entry_text.splitlines())
    separator = ',\n' if body.strip() else '\n'
    return text[:i].rstrip() + separator + indented + '\n  ' + text[i:]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('entry', help='file holding the calibration entry, or - for stdin')
    parser.add_argument('--dry-run', action='store_true', help='validate and report, write nothing')
    parser.add_argument('--counters', default=COUNTERS)
    parser.add_argument('--calibrations', default=CALIBRATIONS)
    args = parser.parse_args()

    declared = layouts(args.counters)
    current_text = open(args.calibrations).read()
    current = json.loads(current_text)

    entry, entry_text = read_entry(args.entry)
    already = existing_maxima(current)

    validate(entry, declared, already)

    models = entry['models']
    maxima = entry['maxima']
    pairs = {(m.lower(), tuple(x['addr'])) for m in models for x in maxima}
    if pairs <= set(already):
        print('already on file, unchanged — every maximum here matches what is recorded.')
        return

    # Re-emit from the parsed entry only when the submission was wrapped; a bare entry is inserted
    # as it was written, so the app's own formatting (and its trailing zeros) survive.
    if entry_text.lstrip().startswith('{') and '"calibrations"' not in entry_text:
        insert = entry_text.strip()
    else:
        insert = json.dumps(entry, indent=2)

    merged = splice(current_text, insert)

    # Parsing it back is the only check that the splice produced a file, not a mess.
    json.loads(merged)

    if not args.dry_run:
        open(args.calibrations, 'w').write(merged)

    counters = len(maxima)
    print(
        f'{"would add" if args.dry_run else "added"} {counters} maximum(s) for '
        f'{len(models)} model(s): {", ".join(models)}'
    )
    print()
    print('Now run ./gradlew test. CapabilityTest pins how many models can show a percentage and')
    print('docs/counter-database.md repeats the figure, so the suite fails until both are updated —')
    print('deliberately:')
    print('that number should only ever move because someone measured something. The assertion')
    print('failure reports the new count.')


if __name__ == '__main__':
    main()
