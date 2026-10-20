"""Refuse to build mixins that redirect a call site Lunar also injects at.

An @Inject adds code; an @Redirect *replaces* the instruction. If Lunar has an
injector anchored on that same instruction, its injection finds nothing and Lunar
aborts the whole bake with "Scanned 0 target(s)" - the game then never boots. That is
exactly what happened on 2026-10-20 21:13 with a redirect on GuiScreen.handleMouseInput.

So: for every @Redirect target in our mixin sources, check Lunar's jars for a *mixin*
class whose constant pool contains that owner/name/descriptor. A plain (non-mixin)
class using the same call is harmless; only mixins matter.

Usage:
  python tools/check-redirects.py [--lunar-dir DIR] [--mixin-dir DIR]

Exit 1 if any redirect is unsafe, so it can gate a build.
"""
import argparse
import glob
import os
import re
import sys
import zipfile

MIXIN_ANNOTATION = b'Lorg/spongepowered/asm/mixin/Mixin;'
REDIRECT_TARGET = re.compile(r'target\s*=\s*"(L[^;]+;[^(]+\([^"]*)"')
DEFAULT_LUNAR = os.path.expanduser('~/.lunarclient/offline/multiver')


def redirected_targets(mixin_dir):
    """Every @At target declared inside an @Redirect annotation, as raw descriptors."""
    targets = []
    for path in sorted(glob.glob(os.path.join(mixin_dir, '*.java'))):
        src = open(path, encoding='utf-8').read()
        # Slice from the annotation to the handler declaration rather than matching
        # parentheses: a target descriptor contains its own `(` and `)`.
        for match in re.finditer(r'@Redirect\s*\(', src):
            block = src[match.start():match.start() + 1200]
            cut = block.find('private')
            if cut > 0:
                block = block[:cut]
            for target in REDIRECT_TARGET.findall(block):
                targets.append((target, os.path.basename(path)))
    return targets


def lunar_mixins(lunar_dir):
    """[(string, mixin class)] for every constant-pool string in a Lunar mixin class.

    Constant-pool UTF8 entries are stored with a 2-byte length prefix, which can itself
    be printable (a 55-byte descriptor is preceded by '7'), so matching has to be
    substring-based on the raw strings rather than equality against the whole entry.
    """
    index = []
    for jar in glob.glob(os.path.join(lunar_dir, '*.jar')):
        try:
            archive = zipfile.ZipFile(jar)
        except Exception:
            continue
        for name in archive.namelist():
            if not name.endswith('.class'):
                continue
            try:
                data = archive.read(name)
            except Exception:
                continue
            if MIXIN_ANNOTATION not in data:
                continue
            for value in re.findall(rb'[\x20-\x7e]{8,}', data):
                index.append((value.decode('ascii', 'ignore'),
                              '%s!%s' % (os.path.basename(jar), name)))
    return index


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--lunar-dir', default=DEFAULT_LUNAR)
    parser.add_argument('--mixin-dir', default='src/main/java/ravenbridge')
    args = parser.parse_args()

    targets = redirected_targets(args.mixin_dir)
    if not targets:
        print('redirect targets: 0 (nothing to check)')
        return 0
    index = lunar_mixins(args.lunar_dir)
    conflicts = []
    for target, source in targets:
        hits = sorted({where for text, where in index if target in text})
        print(('  CONFLICT ' if hits else '  OK       ') + '%-12s %s' % (source, target))
        for hit in hits[:5]:
            print('             Lunar mixin uses it: ' + hit)
        if hits:
            conflicts.append((target, source, hits))
    print('redirect targets checked: %d, conflicts: %d' % (len(targets), len(conflicts)))
    if conflicts:
        print()
        print('A @Redirect deletes the instruction; Lunar then fails its own injection and the')
        print('bake aborts. Hook the enclosing method with @Inject instead.')
        print('See "How it works" in README.md for why the bridge refuses this at build time.')
    return 1 if conflicts else 0


if __name__ == '__main__':
    sys.exit(main())
