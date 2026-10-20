"""Verify the build actually made Raven's script compiler work under Lunar.

Raven's ScriptManager reads
    public static final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
which returns null on Lunar's JRE (its module list has no jdk.compiler), and reload()
bails at its first guard without logging anything. Script.compile also touches
net.minecraft.launchwrapper, which Lunar does not ship. This checks the shipped jars for
the substitutions:

  1. no ToolProvider reference survives anywhere in the mod jar;
  2. ScriptManager.<clinit> really invokes ravenforge/script/ScriptCompiler.get (an
     invokestatic to that exact Methodref, not just a name in the constant pool);
  3. no launchwrapper reference survives, Script's Launch accesses point at
     ravenforge/script/LaunchShim, and the field descriptors match the shim's declarations
     (a mismatch would be a NoSuchFieldError at runtime, not a compile error);
  4. the agent jar carries the classes those references resolve to, plus the bake lookup.

Usage: python tools/verify-scripts.py <RavenB4-ns.jar> [ravenbridge.jar]
"""
import struct
import sys
import zipfile

TOOL_PROVIDER = b'javax/tools/ToolProvider'
GET_COMPILER = b'getSystemJavaCompiler'
SCRIPT_COMPILER = b'ravenforge/script/ScriptCompiler'
LAUNCH_CLASS = b'net/minecraft/launchwrapper/Launch'
LAUNCH_CLASSLOADER = b'net/minecraft/launchwrapper/LaunchClassLoader'
LAUNCH_DOTTED = b'net.minecraft.launchwrapper.Launch'
LAUNCH_SHIM = b'ravenforge/script/LaunchShim'
SCRIPT_MANAGER = b'xyz/daikirai/script/ScriptManager'

FIELDREF = 9
METHODREF = 10
NAME_AND_TYPE = 12
CLASS = 7
UTF8 = 1

CP_SIZES = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4,
            15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}


class Pool(object):
    """Minimal constant-pool reader: enough to resolve Fieldref/Methodref descriptors."""

    def __init__(self, data):
        self.data = data
        count = struct.unpack('>H', data[8:10])[0]
        self.entries = {}
        self.utf8 = {}
        pos = 10
        index = 1
        while index < count:
            tag = data[pos]
            pos += 1
            if tag == UTF8:
                length = struct.unpack('>H', data[pos:pos + 2])[0]
                self.utf8[index] = data[pos + 2:pos + 2 + length]
                pos += 2 + length
            elif tag in (CLASS, 7):
                self.entries[index] = (tag, struct.unpack('>H', data[pos:pos + 2])[0])
                pos += 2
            elif tag in (FIELDREF, METHODREF, 11, NAME_AND_TYPE):
                self.entries[index] = (tag, struct.unpack('>HH', data[pos:pos + 4]))
                pos += 4
            else:
                pos += CP_SIZES.get(tag, 0)
            index += 2 if tag in (5, 6) else 1

    def _ref(self, tag, owner, name):
        for entry in self.entries.values():
            if entry[0] != tag:
                continue
            class_index, nat_index = entry[1]
            owner_entry = self.entries.get(class_index)
            nat_entry = self.entries.get(nat_index)
            if not owner_entry or not nat_entry:
                continue
            if owner_entry[0] != CLASS or nat_entry[0] != NAME_AND_TYPE:
                continue
            if self.utf8.get(owner_entry[1]) != owner:
                continue
            if self.utf8.get(nat_entry[1][0]) != name:
                continue
            return nat_entry[1]
        return None

    def field_descriptor(self, owner, name):
        nat = self._ref(FIELDREF, owner, name)
        return None if nat is None else self.utf8.get(nat[1])

    def methodref_index(self, owner, name):
        """Pool index of the Methodref, for spotting the invokestatic that targets it."""
        for index, entry in self.entries.items():
            if entry[0] != METHODREF:
                continue
            class_index, nat_index = entry[1]
            owner_entry = self.entries.get(class_index)
            nat_entry = self.entries.get(nat_index)
            if not owner_entry or not nat_entry:
                continue
            if owner_entry[0] != CLASS or nat_entry[0] != NAME_AND_TYPE:
                continue
            if self.utf8.get(owner_entry[1]) != owner and owner is not None:
                continue
            if self.utf8.get(nat_entry[1][0]) != name:
                continue
            return index
        return None


def invokes_method(data, pool, owner, name):
    """True when some `invokestatic <ref>` instruction targets this exact Methodref."""
    ref = pool.methodref_index(owner, name)
    if ref is None:
        return False
    return struct.pack('>BH', 0xB8, ref) in data


def main(mod_jar, agent_jar=None):
    failures = []
    with zipfile.ZipFile(mod_jar) as jar:
        names = jar.namelist()
        leaked = [n for n in names if TOOL_PROVIDER in jar.read(n)]
        if leaked:
            failures.append('ToolProvider still referenced by: %s' % ', '.join(sorted(leaked)[:5]))
        for needle, label in ((LAUNCH_CLASS, 'Launch'), (LAUNCH_CLASSLOADER, 'LaunchClassLoader'),
                              (LAUNCH_DOTTED, 'Launch (dotted)')):
            launch = [n for n in names if needle in jar.read(n)]
            if launch:
                failures.append('%s still referenced by: %s' % (label, ', '.join(sorted(launch)[:5])))

        manager_name = 'xyz/daikirai/script/ScriptManager.class'
        if manager_name not in names:
            failures.append('ScriptManager.class missing from the mod jar')
        else:
            data = jar.read(manager_name)
            pool = Pool(data)
            if not invokes_method(data, pool, SCRIPT_COMPILER, b'get'):
                failures.append('ScriptManager does not invoke ravenforge/script/ScriptCompiler.get')
            if GET_COMPILER in data:
                failures.append('ScriptManager still names getSystemJavaCompiler')
            if not invokes_method(data, pool, SCRIPT_COMPILER, b'classpath'):
                failures.append('ScriptManager does not invoke ravenforge/script/ScriptCompiler.classpath')
            # The method definition still exists; what must be gone is the call site.
            if pool.methodref_index(SCRIPT_MANAGER, b'ownJar') is not None:
                failures.append('ScriptManager still calls its own ownJar()')

        script_name = 'xyz/daikirai/script/Script.class'
        if script_name not in names:
            failures.append('Script.class missing from the mod jar')
        else:
            pool = Pool(jar.read(script_name))
            for field, expected in ((b'blackboard', b'Ljava/util/Map;'),
                                    (b'classLoader', b'Ljava/lang/ClassLoader;')):
                actual = pool.field_descriptor(LAUNCH_SHIM, field)
                print('  %s  LaunchShim.%s -> %s' % ('OK  ' if actual == expected else 'FAIL',
                                                     field.decode(),
                                                     None if actual is None else actual.decode()))
                if actual != expected:
                    failures.append('LaunchShim.%s descriptor is %s, expected %s'
                                    % (field.decode(), actual, expected.decode()))

    if agent_jar:
        with zipfile.ZipFile(agent_jar) as agent:
            names = agent.namelist()
            for required in ('ravenforge/script/ScriptCompiler.class',
                             'ravenforge/script/JavacProcessCompiler.class',
                             'ravenforge/script/LaunchShim.class'):
                print(('  OK    ' if required in names else '  MISS  ') + required)
                if required not in names:
                    failures.append('%s missing from %s' % (required, agent_jar))
            if 'ravenforge/script/ScriptCompiler.class' in names:
                compiler = agent.read('ravenforge/script/ScriptCompiler.class')
                for probe in (b'bake.zip', b'ravenforge.bake'):
                    if probe not in compiler:
                        failures.append('ScriptCompiler lost its %s lookup' % probe.decode())

    for failure in failures:
        print('  FAIL  ' + failure)
    print('script compiler checks: %d failed' % len(failures))
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main(*sys.argv[1:]))
