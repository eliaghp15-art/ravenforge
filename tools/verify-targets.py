"""Verify that every class/method name our mixins target exists in Lunar's own
named mapping (lunar/lunar_named_b5_1.8.9.kin inside lunar-platform-mappings-v1_8.jar).

Names in the .kin are stored length-prefixed, so this matches by suffix. It proves the
identifier exists in the named space Mixin resolves against; it cannot prove ownership
(that `displayGuiScreen` belongs to Minecraft), so a miss here is a hard error while a
pass here still leaves the live baked class as the final authority.

Usage: python tools/verify-targets.py <lunar-platform-mappings-v1_8.jar> [mixin dir]
"""
import glob
import os
import re
import sys
import zipfile


def named_strings(mappings_jar):
    with zipfile.ZipFile(mappings_jar) as z:
        data = z.read('lunar/lunar_named_b5_1.8.9.kin')
    return [m.group().decode() for m in re.finditer(rb'[\x20-\x7e]{3,}', data)]


def declared_targets(mixin_dir):
    """(kind, name, source file) for every target our mixin sources declare."""
    out = []
    for path in sorted(glob.glob(os.path.join(mixin_dir, '*.java'))):
        src = open(path, encoding='utf-8').read()
        for m in re.finditer(r'targets\s*=\s*"([^"]+)"', src):
            out.append(('class', m.group(1), path))
        for m in re.finditer(r'method\s*=\s*"([^"]+)"', src):
            # A method may be declared with an explicit descriptor, e.g.
            # "doRender(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDFF)V";
            # the mapping only knows the bare name.
            name = m.group(1).split('(', 1)[0]
            out.append(('method', name, path))
        # @At(value="INVOKE", target="Lowner;name(desc)ret")
        for m in re.finditer(r'target\s*=\s*"(L[^;]+;[^(\s]+)\(', src):
            owner, name = m.group(1).split(';')
            owner = owner.lstrip('L')
            if owner.startswith('net/minecraft'):
                out.append(('class', owner.replace('/', '.'), path))
                out.append(('method', name, path))
            else:
                # LWJGL and other runtime libraries are supplied by the game, not mapped here.
                out.append(('external class', owner.replace('/', '.'), path))
                out.append(('external method', name, path))
    return out


def main(mappings_jar, mixin_dir='src/main/java/ravenbridge'):
    strings = named_strings(mappings_jar)
    targets = declared_targets(mixin_dir)
    missing = []
    for kind, name, path in targets:
        if kind.startswith('external'):
            print('  SKIP  %-16s %s  (runtime library, not in Lunar mappings)' % (kind, name))
            continue
        # Class entries are stored slash-separated, method entries bare.
        probes = {name, name.replace('/', '.'), name.replace('.', '/')}
        is_last = any(x.endswith(p) for x in strings for p in probes)
        print(('  OK    ' if is_last else '  MISS  ') + '%-6s %s' % (kind, name))
        if not is_last:
            missing.append((kind, name, os.path.basename(path)))
    print('declared targets: %d, missing: %d' % (len(targets), len(missing)))
    for kind, name, path in missing:
        print('  MISSING %s %s  (declared in %s)' % (kind, name, path))
    return 1 if missing else 0


if __name__ == '__main__':
    sys.exit(main(*sys.argv[1:]))
