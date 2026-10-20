# ravenforge

Run a Forge **RavenB4** build inside **Lunar Client 1.8.9** - including Raven's runtime
`scripts/` compiler, which Lunar's JRE cannot run on its own.

Lunar is not Forge. It has no `net.minecraftforge.*`, no LaunchWrapper, and it ships a JRE
with no compiler module. This project is the small amount of code needed to make a Forge mod
that expects all three work anyway:

```
-javaagent:.../lunar-agent.jar=.../raven-agent.json
   +-- ravenbridge.jar   ravenforge/* shim, event bus, mixins, script compiler
   +-- RavenB4-ns.jar    the mod you own, with every Forge reference rewritten
```

It contains **no** Minecraft, Lunar or RavenB4 code. You supply the two jars you already have;
`build.sh` produces the bridge and a namespaced copy of the mod.

## How it works

* **Namespacing at build time.** `tools/ravenbridge/tools/RemapMod.java` rewrites
  `net/minecraftforge/*` to `ravenforge/*` inside the mod jar, forces `remap=false` on its mixin
  annotations, drops the vendored Mixin/ASM trees, and repoints the SRG names Raven passes to
  reflection as string literals. Nothing is patched at runtime, so a partial failure cannot
  leave the mod half-rewritten.
* **A shim, not Forge.** `ravenforge/*` is a hand-written subset: event bus with
  `@SubscribeEvent` priorities, cancelable events, `FMLInitializationEvent`,
  `ClientCommandHandler`, `ForgeHooksClient`, and the event classes the mod posts.
* **Event surface from mixins.** `ravenbridge/*` mixins inject at the points Forge would have
  dispatched from (`EntityRenderer.renderWorldPass` before the hand, both hand paths including
  OptiFine's, `RenderTickEvent` straddling `updateCameraAndRender`, `GuiScreen` input,
  `Minecraft.startGame`, ...).
* **Scripts.** `ravenforge/script/JavacProcessCompiler` is a `JavaCompiler` backed by an
  external `javac`, because `ToolProvider.getSystemJavaCompiler()` returns `null` on Lunar's
  JRE and `ScriptManager.reload()` silently returns on a null compiler. It compiles against a
  repacked copy of Lunar's `bake.zip` - the bake stores classes under dot-separated entry
  names, which `javac` ignores entirely, so it has to be rewritten into a path-shaped jar
  first.

## Requirements

* Lunar Client with 1.8.9 installed (the `offline/multiver` directory).
* A JDK - `build.sh` needs `javac`, and scripts are compiled at runtime by a JDK found on
  `JAVA_HOME`, PATH or in `Program Files`.
* Python 3 on PATH as `python` (or `python3`) - the redirect, script-compiler and target
  checks are Python.
* `lunar-agent.jar` and the Forge `RavenB4.jar`, which you already need to run the mod.

## Build

```bash
RAVEN_JAR=~/Downloads/RavenB4.jar \
AGENT_JAR=~/Downloads/Meowtils-Lunar/lunar-agent.jar \
./build.sh
```

Outputs:

| File | What it is |
|---|---|
| `ravenbridge.jar` | the bridge: shim, mixins, registrar agent, script compiler |
| `build/RavenB4-ns.jar` | your mod jar with Forge references rewritten |

The build is not trusted to be correct because it exits 0. It runs, and fails on:

* `tools/check-redirects.py` - an `@Redirect` deletes the instruction it replaces; if Lunar's
  mixins inject at that same call site, Lunar aborts the bake and the game never boots.
* `tools/verify-scripts.py` - the shipped jar must still route `ScriptManager` at
  `ScriptCompiler.get()`/`classpath()`, must not reference `ToolProvider` or launchwrapper, and
  the `LaunchShim` field descriptors must match what the rewritten call sites ask for.
* `tools/test-scripts.sh` - compiles through Raven's exact interface (valid script, missing
  superclass, error position, javac-internal flags, class-file version) and, when a Lunar bake
  is present, compiles a script extending the real `ScriptDefaults` and one naming
  `net.minecraft.client.Minecraft`.

## Install

Copy both jars somewhere permanent, point the agent at them, and add the agent to Lunar's JVM
arguments.

`raven-agent.json`:

```json
{
  "mods": [
    {
      "jar": "C:\\path\\to\\ravenbridge.jar",
      "mixin": "mixins.ravenbridge.json",
      "property": "ravenforge.shim"
    },
    {
      "jar": "C:\\path\\to\\RavenB4-ns.jar",
      "mixin": "clickgui.mixins.json",
      "property": "raven.agent.injected"
    }
  ]
}
```

JVM argument:

```
-javaagent:C:\path\to\lunar-agent.jar=C:\path\to\raven-agent.json
```

A healthy log starts like this, in order:

```text
[RavenBridge] premain self=... mod=...
[RavenBridge] mixin configs registered
[RavenForge] startGame hook reached
[RavenForge] ClickGuiMod.init completed
[RavenForge] bake prepared for javac: bake-...jar (10218 classes)
[RavenForge] script compiler: using ...javac.exe (javac 17, targeting Java 17)
[RavenForge] script classpath: [RavenB4-ns.jar, ravenbridge.jar, ...bake-...jar]
```

Each line localises a fault: no `premain` means the JVM argument did not reach the game, no
mixin config means the agent could not find Lunar's Mixin runtime, no `ClickGuiMod.init` means
the mod threw during init (the stack trace follows).

## Scripts

Scripts live in `<game dir>/keystrokes/scripts/*.java` and are compiled on the first world tick
and by the `Manager` module's *Load scripts* button. A script is a class body:

```java
Chat.print("&ahello from a script");

public void onEnable() {
    Chat.print("&7enabled");
}

public void onDisable() {
}
```

Compile errors are reported in chat, one line per error, with the script line number.

Known limitation: the wrapper imports the same 19 `java.*` types Raven's own
`ScriptManager.IMPORTS` lists. Scripts written for other clients in the same family (for
example the `keystrokesmod` builds, which expose `Simulation`, `registerKey` and different
`registerSlider` signatures) need porting; they fail on API, not on the compiler.

## Status

This is a reverse-engineered bridge, not an official port. What is verified is listed in the
build output above, plus offline checks over the shipped jars (`tools/audit.py`,
`tools/verify-descriptors.py`, `tools/verify-targets.py`) covering every target, descriptor and
member the mixins name. What is *not* verified automatically is how the client looks and feels:
HUD placement, ESP geometry with shaders on, and click behaviour are only observable in a live
client.

## License

[PolyForm Noncommercial 1.0.0](LICENSE) - source-available, not open source.

You may read, build, run, modify and share this for any noncommercial purpose: personal use,
study, hobby projects, research, and use by noncommercial organizations. You may **not** sell
it, sell builds or derivatives of it, bundle it into a paid product or service, or charge for
support, hosting or distribution of it. Commercial use needs written permission from the
copyright holder.

This covers only the code in this repository. The jars it is built against - Lunar Client, the
Forge `RavenB4` build, and the `lunar-agent.jar` that bootstraps the JVM agent - are not
distributed here and remain under their own terms. No third-party client, game or launcher code
is included or distributed here.
