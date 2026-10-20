#!/usr/bin/env bash
# Drives ravenforge.script.JavacProcessCompiler through the same calls Script.compile makes.
# Needs a JDK on PATH only for building/running the harness itself; the compiler under test
# shells out to whichever JDK it finds, exactly as it does inside the game.
set -euo pipefail
cd "$(dirname "$0")/.."

JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"
SEP=";"
case "$(uname -s)" in
  Linux|Darwin) SEP=":" ;;
esac

rm -rf build/testclasses
mkdir -p build/testclasses

"$JAVAC" -proc:none --release 8 -nowarn \
  -cp build/classes \
  -d build/testclasses \
  test/ravenforge/script/stub/ScriptBase.java \
  test/ravenforge/script/ScriptCompileTest.java

# The stub base is the -classpath entry: it must be the only place the script's superclass
# can come from, which is what proves the classpath reaches the external javac.
"$JAVA" -cp "build/classes${SEP}build/testclasses" ravenforge.script.ScriptCompileTest \
  build/testclasses

# Same compiler, but with the classes a script actually extends: the shipped mod jar plus
# Lunar's bake. Skips when either is absent, since that needs a local Lunar install.
# build/RavenB4-ns.jar is the artifact that ships; REAL_MOD_JAR overrides it deliberately.
MOD_JAR="${REAL_MOD_JAR:-build/RavenB4-ns.jar}"
BAKE="${BAKE:-$(ls -t "$HOME"/.lunarclient/offline/multiver/cache/*/*/bake.zip 2>/dev/null | head -1 || true)}"
if [ -f "$MOD_JAR" ] && [ -n "$BAKE" ] && [ -f "$BAKE" ]; then
  to_native() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi
  }
  MOD_JAR="$(cd "$(dirname "$MOD_JAR")" && pwd)/$(basename "$MOD_JAR")"
  echo "real-jar check against $(basename "$MOD_JAR") and $(basename "$BAKE")"
  "$JAVA" "-Dravenforge.bake=$(to_native "$BAKE")" \
    -cp "build/classes${SEP}build/testclasses" \
    ravenforge.script.ScriptCompileTest real "$(to_native "$MOD_JAR")$(echo "$SEP")$(to_native "$PWD/ravenbridge.jar")"
else
  echo "skip real-jar check (need $MOD_JAR and a bake under ~/.lunarclient)"
fi
