#!/usr/bin/env python3
"""Convert reinkpy's epson.toml into the two data files this app bundles.

    counters.json  read layouts — which addresses group into one little-endian counter
    database.json  reset data   — rkey / write keys / reset values, in pad groups

Both files come from the same source, so a model reaching reinkpy reaches the app in one step.

Usage:
    curl -sL https://codeberg.org/atufi/reinkpy/raw/branch/main/reinkpy/epson.toml -o epson.toml
    python3 tools/convert_reinkpy.py epson.toml \\
        src/main/resources/counters.json src/main/resources/database.json

Written against a Python without tomllib; the file's shape is uniform enough to parse directly.
"""
import json
import re
import sys


# reinkpy writes non-printable key bytes as \uXXXX. They must be decoded here: the write key goes
# out as Latin-1, one byte per character, so a literal backslash-u-zero-zero-zero-zero would be six
# wrong bytes and the printer answers :42:NG;.
_ESCAPES = {'n': '\n', 't': '\t', 'r': '\r', '"': '"', '\\': '\\', 'b': '\b', 'f': '\f'}


def unescape(text):
    """Decode TOML basic-string escapes."""
    out = []
    i = 0
    while i < len(text):
        c = text[i]
        if c != '\\' or i + 1 >= len(text):
            out.append(c)
            i += 1
            continue
        nxt = text[i + 1]
        if nxt == 'u' and i + 6 <= len(text):
            out.append(chr(int(text[i + 2:i + 6], 16)))
            i += 6
        elif nxt == 'U' and i + 10 <= len(text):
            out.append(chr(int(text[i + 2:i + 10], 16)))
            i += 10
        elif nxt in _ESCAPES:
            out.append(_ESCAPES[nxt])
            i += 2
        else:
            out.append(c)
            i += 1
    return ''.join(out)


def parse_int_list(text):
    return [int(x, 0) for x in re.findall(r'0x[0-9a-fA-F]+|\d+', text)]


def parse_mem_entry(entry):
    """One `{ addr = [...], desc = "...", reset = [...], max = N }` record."""
    out = {}

    addr = re.search(r'addr\s*=\s*\[([^\]]*)\]', entry)
    if not addr:
        return None
    out['addr'] = parse_int_list(addr.group(1))

    desc = re.search(r'desc\s*=\s*"([^"]*)"', entry)
    out['desc'] = unescape(desc.group(1)) if desc else ''

    for key in ('reset', 'min', 'max'):
        m = re.search(key + r'\s*=\s*\[([^\]]*)\]', entry)
        if m:
            out[key] = parse_int_list(m.group(1))
            continue
        m = re.search(key + r'\s*=\s*(0x[0-9a-fA-F]+|\d+)', entry)
        if m:
            out[key] = int(m.group(1), 0)

    return out


def parse_block(block):
    out = {}

    models = re.search(r'models\s*=\s*\[(.*?)\]', block, re.S)
    if not models:
        return None
    out['models'] = [unescape(m) for m in re.findall(r'"([^"]*)"', models.group(1))]
    if not out['models']:
        return None

    for key in ('rkey', 'rlen', 'wlen', 'mem_low', 'mem_high'):
        m = re.search(r'^\s*' + key + r'\s*=\s*(0x[0-9a-fA-F]+|\d+)', block, re.M)
        if m:
            out[key] = int(m.group(1), 0)

    for key in ('wkey', 'wkey1'):
        m = re.search(r'^\s*' + key + r'\s*=\s*"([^"]*)"', block, re.M)
        if m:
            out[key] = unescape(m.group(1))

    mem = re.search(r'mem\s*=\s*\[(.*?)\n\]', block, re.S)
    counters = []
    if mem:
        for entry in re.findall(r'\{[^}]*\}', mem.group(1)):
            parsed = parse_mem_entry(entry)
            if parsed:
                counters.append(parsed)
    out['counters'] = counters

    return out


def parse(text):
    """Every [[EPSON]] block, in file order."""
    blocks = []
    for block in text.split('[[EPSON]]'):
        if not block.strip():
            continue
        parsed = parse_block(block)
        if parsed:
            blocks.append(parsed)
    return blocks


def pad_kind(desc):
    """Which pad a counter belongs to.

    reinkpy names the platen counters outright, and marks the ones whose grouping it is unsure of
    with `(?)` — which in practice are the platen entries too. Everything else is the main pad.
    The split drives the platen-only flag — the UI warning that the waste box still needs
    servicing.
    """
    d = desc.lower()
    return 'platen' if ('platen' in d or '(?)' in d) else 'main'


def pad_groups(counters):
    """Merge consecutive runs of same-pad counters into one group.

    Runs, not a global partition: a model whose counters go main, platen, main keeps three groups
    in that order. Reset values are padded with 0x00 so every address has a partner.
    """
    groups = []
    for c in counters:
        kind = pad_kind(c['desc'])
        reset = list(c.get('reset', []))
        reset += [0] * (len(c['addr']) - len(reset))

        if groups and groups[-1]['kind'] == kind:
            groups[-1]['addresses'] += c['addr']
            groups[-1]['reset'] += reset
        else:
            # No 'desc': it is a pure function of 'kind' (only two pairings ever occur), and 2827
            # copies of two fixed strings is 14% of the file. The loader derives the label.
            groups.append({
                'kind': kind,
                'addresses': list(c['addr']),
                'reset': reset,
            })
    return groups


def build_database(blocks):
    """Reshape into the per-model map the app's PrinterDatabase reads.

    Models whose block carries no mem entries are still emitted, with empty groups — they resolve
    by name and the UI reports them unsupported, which beats "model not found".

    A model listed in more than one block keeps the last, which is upstream's own convention. It
    matters for exactly one model today: ME-300 appears both in a keyless block of its own and in
    the ME/XP family block, and only the latter carries the write key that a reset actually needs.
    """
    db = {}
    for b in blocks:
        for name in b['models']:
            # No model-level 'addresses'/'reset' either: they are the pad groups concatenated, so
            # for anything generated here they are 20% of the file saying it twice.
            db[name] = {
                'rkey': b.get('rkey', 0),
                'wkey': b.get('wkey', ''),
                'wkey1': b.get('wkey1', ''),
                'rlen': b.get('rlen', 2),
                'wlen': b.get('wlen', 2),
                'mem_high': b.get('mem_high', 0x7FF),
                'pad_groups': pad_groups(b['counters']),
            }
    return dict(sorted(db.items()))


def build_counters(blocks):
    groups = [b for b in blocks if b['counters']]
    return {
        'source': 'reinkpy epson.toml (codeberg.org/atufi/reinkpy)',
        'note': 'Addresses are grouped per counter: one entry = one little-endian value.',
        'groups': groups,
    }


def write_json(path, doc):
    with open(path, 'w') as f:
        json.dump(doc, f, separators=(',', ':'))


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else 'epson.toml'
    counters_dst = sys.argv[2] if len(sys.argv) > 2 else 'counters.json'
    database_dst = sys.argv[3] if len(sys.argv) > 3 else None

    blocks = parse(open(src).read())

    counters_doc = build_counters(blocks)
    write_json(counters_dst, counters_doc)

    groups = counters_doc['groups']
    models = sum(len(g['models']) for g in groups)
    counters = sum(len(g['counters']) for g in groups)
    with_max = sum(1 for g in groups for c in g['counters'] if 'max' in c)
    summary = (f'{len(groups)} groups, {models} models, {counters} counters, '
               f'{with_max} with a max → {counters_dst}')

    if database_dst:
        db = build_database(blocks)
        write_json(database_dst, db)
        resettable = sum(1 for m in db.values() if any(g['addresses'] for g in m['pad_groups']))
        platen_only = sum(
            1 for m in db.values()
            if m['pad_groups'] and all(g['kind'] == 'platen' for g in m['pad_groups'])
        )
        summary += (f'; {len(db)} models, {resettable} resettable, '
                    f'{platen_only} platen-only → {database_dst}')

    print(summary)


if __name__ == '__main__':
    main()
