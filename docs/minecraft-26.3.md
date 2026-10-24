# Minecraft Java Edition 26.3 compatibility

Tellus 0.8.3 adds Minecraft 26.3 targets for Fabric and NeoForge. Both require Java 25. Fabric uses Loader 0.19.5 and Fabric API 0.160.5+26.3; NeoForge uses 26.3.0.3-beta. Distant Horizons support was added after the initial port; see [the 3.3.1 integration notes](distant-horizons-3.3.1.md).

## Build and source reuse

```sh
./gradlew -PtellusTargets=mc263,mc263-neoforge build
```

The installable outputs are `mc263/build/libs/Tellus-fabric-26.3-0.8.3.jar` and `mc263-neoforge/build/libs/Tellus-neoforge-26.3-0.8.3.jar`. Choose the JAR matching your loader.

The version projects contain build configuration, loader metadata, and small loader-specific adapters. They reuse the existing loader bootstraps and a single set of native adapters:

| Source layer | Responsibility |
| --- | --- |
| `src` | Common geography, underground projection, placement policies, and regression tests |
| `shared/mc262` | Existing generator, preview, screens, and compatible Minecraft APIs |
| `shared/mc26` | APIs available in both 26.2 and 26.3, kept out of the 1.21.11 build |
| `shared/mc263` | Small 26.3 adapters and fixtures, shared by Fabric and NeoForge |
| `gradle/tellus-263-sources.gradle` | One source-override list for both new loader targets |

The small `ChunkSerializationCompat` constructor adapter is Fabric-specific; NeoForge retains its existing accessor-based section repair. The NeoForge 26.3 target uses ModDevGradle's [documented binary pipeline](https://github.com/neoforged/ModDevGradle#disabling-decompilation-and-recompilation), because the initial 26.3 beta's source transformation fails on `HolderSet` method visibility. Older targets retain their original build mode.

The initial port's approximately 27,000 lines of version-local Java were reduced to approximately 1,050 lines of shared 26.3 adapters and tests. The large terrain generator, biome source, preview, settings screen, and search widget each retain a shared implementation. Older dependencies remain pinned; extracted older adapters preserve the existing native calls and arguments.

## Required adaptations

- Adapt the combined terrain-generation stage, biome resolver signatures, structure lookup, feature registry, and native mob-spawning entry points.
- Use 26.3 bulk density sampling and material rules for the temporary vanilla cave field, including ore veins. Tellus's surface-relative projection, flood protection, underground depth, and structure exclusions stay shared.
- Apply the new carver masks through Tellus's existing terrain protections, and adapt underground feature placement to its new callback API.
- Adapt high-section-Y chunk serialization while retaining the common repair algorithm.
- Isolate RenderPearl pipeline types and bind groups in a small preview adapter, and isolate SDL keyboard input and platform URL calls from shared screens.
- Update clock access and Tellus dimension attributes, including normal Overworld straw-bed behavior.
- Resolve biome providers through the game classloader on NeoForge 26.3; other targets retain their existing service-discovery behavior.

## Validation

The final `./gradlew build --offline --max-workers=2 --console=plain` passed for all ten loader/version targets. The regression suites reported 5,748 tests: **5,709 passed, 39 skipped, zero failures or errors**. The two new targets each ran 599 tests with one skip. The duplicate-source check also passed.

Runtime checks used isolated local development worlds with caves, ores, and geological patches enabled:

- Fabric and NeoForge both generated Tellus terrain with the extended Y range **−640 through 10,447** (693 sections).
- Both loaders saved and reloaded marker blocks at **Y=10,000 and Y=−630** successfully, and shut down cleanly.
- Fabric's client initialized its graphics, audio, and texture atlases. Interactive screens and gameplay have not received a full manual playtest; NeoForge client startup was not separately exercised.
- Both installable JARs passed ZIP integrity and metadata checks. Distant Horizons was absent from these initial port checks; subsequent integration checks are documented in the 3.3.1 notes linked above.

Local build/test reports, runtime logs, and artifact checksums are retained under `output/minecraft-26.3/`. The existing eight release JARs in the builds folder are preserved.

## Possible follow-ups

These are opportunities for a separate feature update, not additions to Tellus's geographic mapping in this compatibility release:

1. Map poplar foliage and the Dappled Forest biome to suitable real-world climate and ecoregion profiles.
2. Use concrete stairs/slabs for generated roofs, steps, curbs, and building details; wool variants and cushions could improve interiors.
3. Add an explicit Abandoned Camps setting with Tellus terrain placement rules.
4. Benchmark the new density/material-rule pipeline and terrain rendering on representative Tellus worlds before selecting further performance work.

The new content and engine changes are described in the [official 26.3 release notes](https://feedback.minecraft.net/hc/en-us/articles/48913133328013-Minecraft-Java-Edition-26-3). Loader versions are published by [Fabric](https://meta.fabricmc.net/v2/versions/loader/26.3) and [NeoForge](https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml).
