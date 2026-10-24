# Distant Horizons 3.3.1 integration

The Tellus fork is now based on upstream **3.3.1**, with release version
`3.3.1-tellus.1` and API **7.1.0**. It supports Fabric on Minecraft 1.20.1, 1.21.1,
1.21.11, 26.2, and 26.3; Forge on 1.20.1; and NeoForge on the other four versions.
Use the updated Tellus 0.8.3 companion jar with the matching DH jar.

## Upstream improvements retained

The fork includes 3.3's generation scheduling, rendering/TAA, texture mipmaps,
and reduced database I/O, plus native 26.3 support. The upstream 3.3.1 change
pins Shadow to 9.0.0 to prevent differences between Fabric and NeoForge binaries
from separating translation resources during Forgix merging. These builds retain
that fix and ship separate loader jars. English translation resources are
identical across all ten jars.

Sources: [3.3.0 release](https://gitlab.com/distant-horizons-team/distant-horizons/-/releases/3.3.0),
[3.3.1 source](https://gitlab.com/distant-horizons-team/distant-horizons/-/tree/3.3.1).

## Tellus-specific behavior

- **Extended height remains supported.** Full-data and render-data packing retain
  the previous fork's 14-bit vertical fields, covering Tellus's `-640..10447`
  world range. Render color precision is unchanged from the previous Tellus fork.
  Existing stock-format data and the prior Tellus cache marker remain readable;
  new data uses upstream's V2 storage format with extended coordinates.
- **Managed downloads now inform the DH queue.** A default, optional
  `getGenerationAvailability` extension returns READY, SPLIT, or WAIT. Waiting
  regions stay queued without occupying generation workers or starving ready
  regions farther away. Partly downloaded regions split only when useful.
- **Completed LODs carry their correct generation stage.** Tellus explicitly
  marks completed columns as FEATURES through API 7.1. DH can reuse them instead
  of treating them as unfinished SURFACE output. Full generated chunks can still
  supersede direct LODs.
- **Iris atlas handling is preserved.** Initial allocation, resizing, and uploads
  preserve GL texture/pixel state; the API exposes the current atlas ID. Iris gets
  a neutral atlas even when textured LODs are disabled, and retains control of its
  texture units. The new mipmap filter remains enabled.
- **The fork cannot replace itself with stock DH.** The updater guard also applies
  when an existing configuration had enabled automatic updates. Stock DH's
  vertical packing still cannot represent the full Tellus extended-height range.

Tellus now compiles against API 7.1 and requires DH 3.3 or newer when installed.
The obsolete experimental N-sized-generation config override is no longer used.
Fabric 26.3's local runtime configuration and NeoForge's development XZ dependency
were also corrected, and 26.3 includes the chunk-update height guard.

## Loading improvements

Readiness checks inspect at most the smaller of the requested cell area and the
cached-cell population. This avoids scanning enormous empty regions; a regression
test covers a request spanning over a billion cells. Randomized tests compare
the optimized decisions with exhaustive checks, including negative coordinates.

Queue selection no longer allocates a distance wrapper for every candidate.
Failed or cancelled work releases queue slots and pooled results so retries can
continue. Already-generated regions at the required generation stage avoid
unnecessary child database lookups. Child upsampling preserves completed data and
its generation/compression metadata.

No terrain sampling resolution, tree/building detail, water behavior, noise,
or quality setting was reduced. These changes avoid repeated work and waiting;
they do not establish a measured FPS or terrain-throughput improvement.

## Validation

- All ten DH jars build, and all 80 API/core tests pass.
- The complete Tellus build covers 5,768 test cases: zero failures/errors and
  39 existing skips across the ten targets.
- Every DH jar passes ZIP integrity, loader/version/Java metadata checks,
  preservation of all 148 official API classes, identical core/API and language
  resources, and resolution of all 107 DH references used by Iris 1.11.4 and 1.11.6.
- A real Apple M4 OpenGL context passes atlas initialization, interface access,
  neutral pixels, state restoration, and resizing checks.
- Fabric and NeoForge 26.3 both pass direct generation at detail levels 0 and 4
  in copied extended-height worlds. Each request generates all 4,096 columns;
  both loaders produce 10,464 fine and 12,308 coarse data points using the same
  source cache. Every column retains FEATURES metadata and roundtrips exactly
  through uncompressed and LZ4 storage. Logs are in `output/dh-3.3/logs/`.

A full interactive shader-pack test on Windows/NVIDIA is still outside this local
validation. An initial NeoForge probe raced Tellus's server-start callback and
waited for its startup gate after the first request. A thread dump identified the
test event-ordering issue; the probe now starts after normal lifecycle listeners.
Both loaders use the same completed source cache for the managed-path comparison.

## Builds and sources

The versioned source patches and build/verification tools live in
[`compat/distant-horizons`](../compat/distant-horizons/README.md). The patches
apply to the exact upstream 3.3.1 revisions recorded there.

Install only the jar matching the Minecraft version and loader; replacing the old
jar avoids loading two versions of the same mod.
