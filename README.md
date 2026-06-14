# Grappling Hook Mod — Minecraft Forge 1.7.10

A Minecraft **1.7.10** (Forge) mod. This repo is a clean boilerplate: a working
`@Mod` entry point, sided proxies, registration stubs, and a network channel —
wired up and ready to build and run, with no content yet.

## Build

The build uses [RetroFuturaGradle](https://github.com/GTNewHorizons/RetroFuturaGradle)
(the maintained 1.7.10 toolchain).

- **Java 8** is the compile/run toolchain (RFG can auto-provision it).
- Gradle runs via the wrapper (Gradle 8.8); use a JDK in the 8–21 range to launch it.

```sh
# set JAVA_HOME to a JDK that can run Gradle (8–21), then:
./gradlew build          # -> build/libs/grapplinghook-<version>.jar  (the loadable mod)
./gradlew runClient      # dev client
./gradlew runServer      # dev dedicated server
```

The reobfuscated `grapplinghook-<version>.jar` is the one to drop into
`.minecraft/mods/` of a **1.7.10 Forge** install. (The `-dev.jar` is deobfuscated
and only works inside the Gradle dev environment.)

## Layout

- `src/main/java/com/ajabad/grapplinghook/`
  - `ModGrapplingHook` — `@Mod` entry point; dispatches the FML lifecycle to the proxy
  - `Reference` — modid, name, version, proxy class names
  - `ModItems` / `ModEntities` — registration stubs (empty; add content here)
  - `network/ModNetwork` — `SimpleNetworkWrapper` channel (no messages yet)
  - `proxy/CommonProxy` / `proxy/ClientProxy` — sided init (server-safe vs client-only)
  - `util/Vector3` — small mutable float 3-vector helper
- `src/main/resources/`
  - `mcmod.info` — mod metadata (version / mcversion injected at build time)
  - `assets/grapplinghook/lang/en_US.lang` — localization

## Where to start

1. Set the modid / name / version in `Reference.java` (and `group` / `version` in `build.gradle.kts`).
2. Register items in `ModItems.register()` and entities in `ModEntities.register()`.
3. Add client renderers in `ClientProxy.registerRenderers()`.
4. Add network messages in `ModNetwork.init()`.
