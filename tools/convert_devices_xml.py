#!/usr/bin/env python3
"""Read waste-counter limits out of a service devices.xml, as a counters overlay.

Nothing this produces is shipped. The app bundles no data from this source and no part of it
depends on this script — it exists for whoever has such a file and wants percentages for a model
nobody has measured by hand.

Why it is not shipped: the file circulating as devices.xml is the device database of the
commercial PrintHelp / WIC Reset utility (printhelp.info), which its own embedded FAQ names
outright. Where it came from before that, and on what terms, is not established. Facts about a
printer are not the vendor's to own, but "probably fine" is not a provenance, so the project
carries none of it and this script takes the file as an argument rather than fetching it.

What comes out is a counters-overlay.json: the layouts already in counters.json, with a maximum
and a floor filled in where this source states one. Drop it beside the database cache
(~/Library/Application Support/EpsonReset/ on macOS) and those counters start showing a percentage.
See docs/counter-database.md#overlays.

Nothing here invents a layout. A limit is only emitted for an address group counters.json already
declares, so the overlay can never move what the app reads or writes — only how full it says a
counter is. Read `desc` and the addresses straight from counters.json; only the numbers are new.

It also reports, on stderr and to nobody's file: where this source disagrees with a measurement in
calibrations.json, and where it disagrees with our reset keys. The second is worth running even if
you throw the overlay away — rkey and wkey drive every EEPROM write and reach us from one source.

Usage:
    python3 tools/convert_devices_xml.py devices.xml \\
        data/reinkpy/counters.json data/reinkpy/database.json counters-overlay.json
"""
import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict


SOURCE = 'PrintHelp / WIC Reset devices.xml (printhelp.info), via github.com/CiRIP/ez-reset'


def normalise(name):
    """A model name reduced to what two sources can be expected to agree on.

    The same printer is 'ET-2820 Series' in the XML and 'ET-2820' in counters.json, and the XML
    prefixes some entries with the brand. Case, spacing and punctuation carry no information here.
    """
    name = name.upper().replace(' SERIES', '').replace('EPSON', '')
    return re.sub(r'[^A-Z0-9]', '', name)


def siblings(title):
    """The SKUs a title spells out: 'Epson ET-2820/2821/2823/2825' -> the four of them.

    The XML files a whole family under one series name — its <model> is 'ET-2820 Series', which
    matches exactly one of the eight models counters.json lists separately. The title is where the
    vendor says which SKUs that series covers, so expanding it is reading the file, not inferring
    from a shared layout.

    Continuations are usually bare numbers ('2821') and inherit the first SKU's prefix; where they
    are written out ('DX3850') they are taken as they stand, under any leading words.
    """
    parts = re.sub(r'^\s*EPSON\s+', '', title, flags=re.I).split('/')
    if len(parts) < 2:
        return []

    head = parts[0].strip()
    words, sku = head.rsplit(' ', 1) if ' ' in head else ('', head)
    prefix = re.match(r'^([A-Za-z]+[- ]?)', sku)

    out = []
    for part in parts[1:]:
        part = part.strip()
        if not part:
            continue
        if part[0].isdigit() and prefix:
            part = prefix.group(1) + part
        out.append(f'{words} {part}'.strip())
    return out


def read_spec(spec):
    """Everything one <spec> says, in the shapes the output files want."""
    out = {'counters': [], 'rkey': None, 'wkey': None}

    for counter in spec.findall('.//waste/query/counter'):
        maximum = counter.find('.//max')
        if maximum is None:
            continue
        # The addresses sit either in an <entry> child or directly in the counter's text.
        entry = counter.find('.//entry')
        raw = entry.text if entry is not None else counter.text
        if not raw:
            continue
        minimum = counter.find('.//min')
        out['counters'].append((
            [int(a, 0) for a in raw.split()],
            int(maximum.text),
            int(minimum.text, 0) if minimum is not None else None,
        ))

    factory = spec.find('.//service/factory')
    if factory is not None:
        raw = [int(b, 0) for b in factory.text.split()]
        out['rkey'] = raw[0] | (raw[1] << 8) if len(raw) > 1 else raw[0]

    keyword = spec.find('.//service/keyword')
    if keyword is not None:
        out['wkey'] = ''.join(chr(int(b, 0)) for b in keyword.text.split())

    return out


def read_devices(path):
    """normalised model name -> everything its specs say, merged in the order it names them.

    A printer names one or more specs and the interesting blocks live on the specs, so several
    hundred models resolve to the same few dozen entries. A name a printer states outright always
    beats one derived from a sibling list.
    """
    root = ET.parse(path).getroot()
    devices, records = root.find('devices'), root.find('records')
    if devices is None or records is None:
        raise SystemExit(f'{path}: not a devices.xml (no <devices>/<records>)')

    cache = {}
    out, aliases = {}, {}
    for printer in records.findall('.//printer'):
        model = printer.attrib.get('model')
        if not model:
            continue

        merged = {'counters': [], 'rkey': None, 'wkey': None}
        for name in printer.attrib.get('specs', '').split(','):
            spec = devices.find(name)
            if spec is None:
                continue
            if name not in cache:
                cache[name] = read_spec(spec)
            for key, value in cache[name].items():
                if isinstance(value, list):
                    merged[key] += value
                elif value is not None and merged[key] is None:
                    merged[key] = value

        if any(merged.values()):
            out[normalise(model)] = merged
            for sibling in siblings(printer.attrib.get('title', '')):
                aliases.setdefault(normalise(sibling), merged)

    for name, merged in aliases.items():
        out.setdefault(name, merged)
    return out


def read_counters(path):
    """model name -> its declared counters, from counters.json.

    Whole entries, not just addresses: an overlay replaces a model's layout outright, so it has to
    carry the layout back unchanged and add only the numbers.
    """
    out = {}
    for group in json.load(open(path))['groups']:
        for model in group['models']:
            out[model] = group['counters']
    return out


def match(ours, theirs):
    """Our address groups paired with their limits.

    Matched as sets, not sequences: a dozen of the older models (PX-V600, ME Office 510, PM-3500C)
    list the pair high byte first. The pairing is what is being read across, not the byte order —
    our own order is what gets emitted, since that is what decodes the value.

    A model whose specs give one address group two different maxima is dropped rather than guessed
    at; the caller reports it.
    """
    by_addresses = {}
    for addresses, maximum, minimum in theirs:
        key = frozenset(addresses)
        if by_addresses.get(key, (maximum, minimum))[0] != maximum:
            return None
        by_addresses[key] = (maximum, minimum)

    return [
        (addresses, *by_addresses[frozenset(addresses)])
        for addresses in ours
        if frozenset(addresses) in by_addresses
    ]


def grouped(by_model, payload):
    """Models sharing identical data, collapsed into one entry each.

    Several hundred models share a few dozen sets of figures, and one entry per model would be a
    file of mostly repetition — the same reason counters.json groups by layout.
    """
    groups = defaultdict(list)
    for model, value in by_model.items():
        groups[value].append(model)

    return [
        {'models': sorted(models), **payload(value)}
        for value, models in sorted(groups.items(), key=lambda kv: sorted(kv[1])[0].lower())
    ]


def build_overlay(devices, counters):
    """A counters-overlay.json: our layouts, with this source's limits filled in.

    A model whose layout gains nothing is left out entirely. An overlay entry replaces the bundled
    layout, so writing one that only repeats it is a way to go stale for no benefit.
    """
    matched, limits, conflicted = {}, {}, []
    for model, declared in counters.items():
        theirs = devices.get(normalise(model))
        if not theirs or not theirs['counters']:
            continue

        rows = match([c['addr'] for c in declared], theirs['counters'])
        if rows is None:
            conflicted.append(model)
            continue
        if not rows:
            continue

        found = {tuple(a): (mx, mn) for a, mx, mn in rows}
        limits[model] = tuple((a, mx) for a, (mx, _) in sorted(found.items()))

        layout = []
        for counter in declared:
            entry = dict(counter)
            maximum, minimum = found.get(tuple(counter['addr']), (None, None))
            if maximum is not None:
                entry['max'] = maximum
            if minimum is not None:
                entry['min'] = minimum
            layout.append(entry)
        matched[model] = tuple(json.dumps(c, sort_keys=True) for c in layout)

    doc = {
        'source': SOURCE,
        'note': 'Counter layouts from counters.json with waste-counter limits filled in. Drop this '
                'beside the database cache as counters-overlay.json. A limit is never emitted for '
                'an address group counters.json does not already declare.',
        'groups': grouped(matched, lambda layout: {'counters': [json.loads(c) for c in layout]}),
    }
    return doc, limits, conflicted


def check_keys(devices, database):
    """Our reset keys against this source's, model by model.

    rkey and wkey are the bytes every EEPROM write depends on and they reach the project from one
    source alone. Nothing is written out — a second opinion is worth having even when the data
    behind it is not worth shipping, and a disagreement here is worth chasing before anything else
    in this file is believed.
    """
    compared, mismatches = 0, []
    for model, entry in sorted(database.items()):
        theirs = devices.get(normalise(model))
        if not theirs or (theirs['rkey'] is None and theirs['wkey'] is None):
            continue
        compared += 1

        if theirs['rkey'] is not None and theirs['rkey'] != entry.get('rkey'):
            mismatches.append(f"{model} rkey: ours 0x{entry.get('rkey', 0):04X}, "
                              f"theirs 0x{theirs['rkey']:04X}")
        if theirs['wkey'] is not None and theirs['wkey'] != entry.get('wkey'):
            mismatches.append(f"{model} wkey: ours {entry.get('wkey')!r}, theirs {theirs['wkey']!r}")

    return compared, mismatches


def check_calibrations(path, matched):
    """Curated maxima that the vendor figures disagree with.

    A calibration measured against a percentage reading can only bracket its maximum, and states
    the bracket in `range`. A vendor figure landing inside that bracket is the sharper number and
    the curated entry should give way — but that is a judgement to make by hand with the evidence
    in front of you, so this only reports it.
    """
    try:
        entries = json.load(open(path))['calibrations']
    except (OSError, KeyError, ValueError):
        return []

    out = []
    for entry in entries:
        for model in entry['models']:
            pairs = dict(matched.get(model, ()))
            for measured in entry.get('maxima', []):
                theirs = pairs.get(tuple(measured['addr']))
                if theirs is None or theirs == measured['max']:
                    continue
                low, high = (measured.get('range') or [measured['max']] * 2)[:2]
                out.append((model, measured['addr'], measured['max'], theirs, low <= theirs <= high))
    return out


def write(path, doc):
    with open(path, 'w') as f:
        json.dump(doc, f, separators=(',', ':'))
        f.write('\n')


def main():
    if len(sys.argv) != 5:
        raise SystemExit(' '.join(__doc__.strip().splitlines()[-2:]))

    xml_path, counters_path, database_path, out_path = sys.argv[1:]
    devices = read_devices(xml_path)
    counters = read_counters(counters_path)
    database = json.load(open(database_path))

    overlay, limits, conflicted = build_overlay(devices, counters)
    write(out_path, overlay)
    print(f'{len(limits)} models given a limit, in {len(overlay["groups"])} distinct layouts '
          f'-> {out_path}')

    compared, mismatches = check_keys(devices, database)
    print(f'reset keys: {compared} models checked, {len(mismatches)} disagree', file=sys.stderr)
    for line in mismatches:
        print(f'  {line}', file=sys.stderr)

    if conflicted:
        print(f'skipped {len(conflicted)}, whose specs give one address group two maxima: '
              + ', '.join(sorted(conflicted)), file=sys.stderr)

    unmatched = sorted(set(devices) - {normalise(m) for m in counters})
    if unmatched:
        print(f'{len(unmatched)} models carry data but no layout in counters.json, so get no '
              f'limits: ' + ', '.join(unmatched[:12]) + (' …' if len(unmatched) > 12 else ''),
              file=sys.stderr)

    for model, addr, ours, theirs, inside in check_calibrations(
        'data/curated/calibrations.json', limits,
    ):
        where = 'inside its stated range' if inside else 'OUTSIDE its stated range'
        print(f'calibrations.json {model} {addr}: measured {ours}, this source says {theirs} '
              f'({where})', file=sys.stderr)


if __name__ == '__main__':
    main()
