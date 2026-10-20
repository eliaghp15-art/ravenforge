"""Audit that every ravenforge.* member RavenB4-ns.jar links against actually
exists in ravenbridge.jar, after applying the SRG->named mapping the agent applies
at load time (RuntimeRemapper with mappings/{methods,fields}.csv).

Usage: python audit.py <mod.jar> <shim.jar> <agent.jar>
"""
import io
import csv
import struct
import sys
import zipfile
from collections import defaultdict

POOL = {3, 4, 5, 6}


def parse(d):
    cnt = struct.unpack('>H', d[8:10])[0]
    i = 10
    pool = [None] * cnt
    idx = 1
    while idx < cnt:
        t = d[i]
        i += 1
        if t == 1:
            ln = struct.unpack('>H', d[i:i + 2])[0]
            pool[idx] = ('u', d[i + 2:i + 2 + ln].decode('utf-8', 'replace'))
            i += 2 + ln
        elif t in (3, 4):
            i += 4
        elif t in (5, 6):
            i += 8
            idx += 1
        elif t == 7:
            pool[idx] = ('c', struct.unpack('>H', d[i:i + 2])[0])
            i += 2
        elif t == 8:
            i += 2
        elif t in (9, 10, 11):
            pool[idx] = ('r', t, *struct.unpack('>HH', d[i:i + 4]))
            i += 4
        elif t == 12:
            pool[idx] = ('nt', *struct.unpack('>HH', d[i:i + 4]))
            i += 4
        elif t == 15:
            i += 3
        elif t in (16, 19, 20):
            i += 2
        elif t in (17, 18):
            i += 4
        else:
            raise RuntimeError('constant pool tag %d at index %d' % (t, idx))
        idx += 1

    def utf(j):
        return pool[j][1]

    def clsname(j):
        return utf(pool[j][1])

    i += 2
    this = clsname(struct.unpack('>H', d[i:i + 2])[0])
    i += 2
    sup_i = struct.unpack('>H', d[i:i + 2])[0]
    i += 2
    sup = clsname(sup_i) if sup_i else None
    ifc = struct.unpack('>H', d[i:i + 2])[0]
    i += 2 + 2 * ifc

    def attrs(i):
        n = struct.unpack('>H', d[i:i + 2])[0]
        i += 2
        for _ in range(n):
            ln = struct.unpack('>I', d[i + 2:i + 6])[0]
            i += 6 + ln
        return i

    methods, fields = {}, {}
    nf = struct.unpack('>H', d[i:i + 2])[0]
    i += 2
    for _ in range(nf):
        i += 2
        n = utf(struct.unpack('>H', d[i:i + 2])[0])
        desc = utf(struct.unpack('>H', d[i + 2:i + 4])[0])
        i += 4
        fields[n + desc] = True
        i = attrs(i)
    nm = struct.unpack('>H', d[i:i + 2])[0]
    i += 2
    for _ in range(nm):
        i += 2
        n = utf(struct.unpack('>H', d[i:i + 2])[0])
        desc = utf(struct.unpack('>H', d[i + 2:i + 4])[0])
        i += 4
        methods[n + desc] = True
        i = attrs(i)

    refs = set()
    for e in pool:
        if e and e[0] == 'r':
            _, tag, ci, nti = e
            nt = pool[nti]
            refs.add((tag == 9, clsname(ci), utf(nt[1]), utf(nt[2])))
    return this, sup, methods, fields, refs


def load(path):
    out = {}
    z = zipfile.ZipFile(path)
    for n in z.namelist():
        if n.endswith('.class'):
            this, sup, m, f, refs = parse(z.read(n))
            out[this] = (sup, m, f, refs)
    return out


def readmap(agent_jar, entry):
    z = zipfile.ZipFile(agent_jar)
    m = {}
    for row in csv.reader(io.StringIO(z.read(entry).decode('utf-8-sig'))):
        if len(row) < 2:
            continue
        s, n = row[0].strip(), row[1].strip()
        if s and n and s != 'searge':
            m[s] = n
    return m


def main(mod_jar, shim_jar, agent_jar):
    method_map = readmap(agent_jar, 'mappings/methods.csv')
    field_map = readmap(agent_jar, 'mappings/fields.csv')
    mod = load(mod_jar)
    shim = load(shim_jar)

    def has_member(owner, key, is_field):
        # A ravenforge class that does not exist at all is a hard gap. Once the
        # superclass chain leaves the shim it enters game/MCP classes we cannot
        # see from here; treat that as resolved rather than guessing.
        if owner not in shim:
            return False
        seen = set()
        while owner and owner not in seen:
            seen.add(owner)
            if owner not in shim:
                return True
            sup, methods, fields, _ = shim[owner]
            if key in (fields if is_field else methods):
                return True
            owner = sup
        return False

    missing = defaultdict(set)
    total = 0
    remapped = 0
    for this, (_sup, _m, _f, refs) in mod.items():
        for is_field, owner, name, desc in refs:
            if not owner.startswith('ravenforge/'):
                continue
            total += 1
            mapped = field_map.get(name) if is_field else method_map.get(name)
            if mapped and mapped != name:
                name = mapped
                remapped += 1
            if not has_member(owner, name + desc, is_field):
                missing[(is_field, owner, name, desc)].add(this)

    print('ravenforge refs checked: %d (srg-remapped: %d)' % (total, remapped))
    for is_field in (False, True):
        items = [(k, v) for k, v in missing.items() if k[0] == is_field]
        print('%s: %d' % ('MISSING FIELDS' if is_field else 'MISSING METHODS', len(items)))
        for (_, owner, name, desc), users in sorted(items, key=lambda x: (-len(x[1]), x[0][2])):
            print('  %s.%s%s  <- %d user(s): %s'
                  % (owner, name, desc, len(users), ', '.join(sorted(users))[:90]))
    return 1 if missing else 0


if __name__ == '__main__':
    sys.exit(main(*sys.argv[1:4]))
