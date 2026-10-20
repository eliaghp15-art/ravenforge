"""Verify that every method our mixins name - with an explicit descriptor, or as an
@At INVOKE target - exists in the baked Minecraft classes with that exact signature.

verify-targets.py proves a *name* exists somewhere in Lunar's named mapping. This
proves the *shape* too, which is what Mixin actually matches on: a wrong descriptor
makes an injector or redirector silently find zero call sites, and the hook just
never fires.

Usage:
  python tools/verify-descriptors.py <bake.zip> [mixin dir]

The bake is Lunar's named jar (net.minecraft.client.gui.GuiScreen, no .class suffix).
"""
import glob
import os
import re
import subprocess
import sys
import tempfile
import zipfile

METHOD_SPEC = re.compile(r'method\s*=\s*"([^"]+)"')
AT_TARGET = re.compile(r'target\s*=\s*"(L[^;]+;[^(]+)(\([^"]*)"')
MIXIN_TARGET = re.compile(r'targets\s*=\s*"([^"]+)"')


def method_exists(class_file, name, descriptor):
    out = subprocess.run(['javap', '-p', '-s', class_file], capture_output=True, text=True).stdout
    lines = out.splitlines()
    for i, line in enumerate(lines):
        if 'descriptor:' not in line:
            continue
        desc = line.split('descriptor:', 1)[1].strip()
        if desc != descriptor:
            continue
        # javap prints the method name on the preceding declaration line.
        decl = lines[i - 1]
        match = re.search(r'([\w$<>]+)\(', decl)
        if match and match.group(1) == name:
            return True
    return False


def main(bake_zip, mixin_dir='src/main/java/ravenbridge'):
    checks = []  # (target class, method, descriptor, note, source file)
    for path in sorted(glob.glob(os.path.join(mixin_dir, '*.java'))):
        src = open(path, encoding='utf-8').read()
        targets = MIXIN_TARGET.findall(src)
        if len(targets) != 1:
            print('  SKIP  %s (no single @Mixin target)' % os.path.basename(path))
            continue
        owner = targets[0]
        for spec in METHOD_SPEC.findall(src):
            if '(' not in spec:
                continue
            name, descriptor = spec.split('(', 1)
            checks.append((owner, name, '(' + descriptor, 'handler', path))
        for target in AT_TARGET.findall(src):
            ref, descriptor = target
            ref_owner, name = ref.split(';')
            # Any owner that lives in the bake is checked, not just net.minecraft:
            # OptiFine's ShadersRender.renderHand1 is a hand-render anchor too, and a
            # miss there silently drops RenderWorldLast whenever shaders are enabled.
            checks.append((ref_owner[1:].replace('/', '.'), name, descriptor, 'at-target', path))

    zf = zipfile.ZipFile(bake_zip)
    available = set(zf.namelist())
    cache = {}
    missing = []
    for owner, name, descriptor, note, path in checks:
        if owner not in available:
            # LWJGL and other runtime-provided libraries are not part of the bake.
            if owner.startswith(('org.lwjgl', 'java.', 'sun.', 'com.google', 'com.mojang')):
                print('  SKIP  %-12s %s  (runtime library, not in the bake)' % (name, descriptor))
                continue
            print('  MISS  class %s  (declared in %s)' % (owner, os.path.basename(path)))
            missing.append((owner, name, descriptor, path))
            continue
        if owner not in cache:
            handle, temp = tempfile.mkstemp(suffix='.class')
            os.close(handle)
            with open(temp, 'wb') as out:
                out.write(zf.read(owner))
            cache[owner] = temp
        ok = method_exists(cache[owner], name, descriptor)
        print(('  OK    ' if ok else '  MISS  ') + '%-48s %s%s  (%s)' % (name, descriptor, '', note))
        if not ok:
            missing.append((owner, name, descriptor, path))
    for temp in cache.values():
        os.unlink(temp)
    print('descriptor checks: %d, missing: %d' % (len(checks), len(missing)))
    for owner, name, descriptor, path in missing:
        print('  MISSING %s.%s%s  (declared in %s)' % (owner, name, descriptor, os.path.basename(path)))
    return 1 if missing else 0


if __name__ == '__main__':
    sys.exit(main(*sys.argv[1:]))
