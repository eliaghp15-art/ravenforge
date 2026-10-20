#!/usr/bin/env bash
# Builds ravenbridge.jar (shim + agent) and the namespaced RavenB4 jar.
#
#   RAVEN_JAR=/path/to/RavenB4.jar \
#   AGENT_JAR=/path/to/lunar-agent.jar \
#   ./build.sh
#
# Output:
#   ravenbridge.jar         agent jar: ravenforge/* shim, bridge mixins, registrar
#   build/RavenB4-ns.jar    RavenB4 with net/minecraftforge/* rewritten to ravenforge/*
#
# Nothing third-party is vendored here: RAVEN_JAR and AGENT_JAR are jars you already
# have, and the ASM used by the rewriter is extracted from a Lunar jar on this machine.
set -euo pipefail
cd "$(dirname "$0")"

SEP=";"
case "$(uname -s)" in
  Linux|Darwin) SEP=":" ;;
esac

if [ -z "${RAVEN_JAR:-}" ] || [ ! -f "${RAVEN_JAR:-}" ]; then
  cat >&2 <<'EOF'
error: RAVEN_JAR must point at the Forge RavenB4 jar you own, e.g.

  RAVEN_JAR=~/Downloads/RavenB4.jar \
  AGENT_JAR=~/Downloads/Meowtils-Lunar/lunar-agent.jar \
  ./build.sh
EOF
  exit 2
fi
MOD_IN="$RAVEN_JAR"

if [ -z "${AGENT_JAR:-}" ] || [ ! -f "${AGENT_JAR:-}" ]; then
  cat >&2 <<'EOF'
error: AGENT_JAR must point at lunar-agent.jar (it carries mappings/{fields,methods}.csv,
       which are used to rewrite the SRG names Raven passes to reflection as literals).
EOF
  exit 2
fi

LUNAR_DIR="${LUNAR_DIR:-$HOME/.lunarclient/offline/multiver}"

# An @Redirect deletes the instruction it replaces. If Lunar's own mixins inject at that same
# call, their injection finds nothing and Lunar aborts the bake ("Scanned 0 target(s)") - the
# game never boots. That is what happened on 2026-10-20 21:13 with GuiScreen.handleMouseInput.
if [ -d "$LUNAR_DIR" ]; then
  python tools/check-redirects.py --lunar-dir "$LUNAR_DIR"
else
  echo "skip redirect/Lunar conflict check (no Lunar install at $LUNAR_DIR)"
fi

rm -rf build/classes build/mixinstubs build/tools build/stubs
mkdir -p build/classes build/mixinstubs build/tools build/stubs

# Compile-time-only Minecraft/LWJGL shells. These carry no behaviour: they exist so the
# mixin sources can name game types without the real client jar, and are never packaged.
javac -proc:none --release 8 -nowarn \
  -d build/stubs \
  $(find stubs -name '*.java')

# Compile-time-only Mixin annotations (never packaged).
javac -proc:none --release 8 -nowarn \
  -cp "build/stubs" \
  -d build/mixinstubs \
  $(find compile-stubs -name '*.java')

javac -proc:none --release 8 -nowarn \
  -cp "build/mixinstubs${SEP}build/stubs" \
  -d build/classes \
  $(find src/main/java -name '*.java')

cp -r src/main/resources/* build/classes/

jar cfm ravenbridge.jar manifest.txt -C build/classes .
echo "built $(pwd)/ravenbridge.jar"

# RemapMod needs ASM. The game already ships it inside Lunar's own jars, so it is extracted
# on demand instead of being committed or downloaded.
ASM_CP="${ASM_CP:-build/asmcp}"
if [ ! -f "$ASM_CP/org/objectweb/asm/ClassVisitor.class" ]; then
  if [ ! -d "$LUNAR_DIR" ]; then
    echo "error: no Lunar install at $LUNAR_DIR and no ASM at $ASM_CP" >&2
    exit 2
  fi
  echo "extracting ASM into $ASM_CP"
  mkdir -p "$ASM_CP"
  python - "$LUNAR_DIR" "$ASM_CP" <<'PY'
import os
import sys
import zipfile

lunar, out = sys.argv[1], sys.argv[2]
probe = 'org/objectweb/asm/ClassVisitor.class'
for name in sorted(os.listdir(lunar)):
    if not name.endswith('.jar'):
        continue
    try:
        jar = zipfile.ZipFile(os.path.join(lunar, name))
    except zipfile.BadZipFile:
        continue
    names = jar.namelist()
    if probe not in names:
        continue
    for entry in names:
        if not entry.startswith('org/objectweb/'):
            continue
        target = os.path.join(out, entry)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, 'wb') as handle:
            handle.write(jar.read(entry))
    print('  from ' + name)
    break
else:
    raise SystemExit('no jar with ' + probe + ' in ' + lunar)
PY
fi

# Build-time namespacer.
javac -proc:none --release 8 -nowarn \
  -cp "$ASM_CP" \
  -d build/tools \
  tools/ravenbridge/tools/RemapMod.java

java -cp "$ASM_CP${SEP}build/tools" ravenbridge.tools.RemapMod "$MOD_IN" build/RavenB4-ns.jar "$AGENT_JAR"

# Scripts compile at runtime. Lunar's JRE has no jdk.compiler and no LaunchWrapper, so the
# rewrite above repoints ScriptManager at ScriptCompiler.get() and Script's Launch fields at
# LaunchShim; these two steps prove the shipped jars still carry that, and that the compiler
# can actually build a script against the mod jar plus Lunar's bake.
python tools/verify-scripts.py build/RavenB4-ns.jar ravenbridge.jar
bash tools/test-scripts.sh
