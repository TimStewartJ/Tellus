# Tellus fork of Distant Horizons 3.3.1

This directory keeps the fork changes reviewable and reproducible even though
`third_party/` is excluded from the Tellus repository. The two patches contain the
loader/rendering changes and the separate core/API changes, including tests.
Their code is covered by Distant Horizons' LGPL-3.0 license.

Pinned upstream sources:

- [Distant Horizons 3.3.1](https://gitlab.com/distant-horizons-team/distant-horizons/-/tree/3.3.1), commit `f0cefb7a994a670ca653e3dd52ff345275018724`.
- [Distant Horizons core](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/tree/b0a5f3506364485eb9e4cd94b5343304adb6c88c), commit `b0a5f3506364485eb9e4cd94b5343304adb6c88c`.
- Release version `3.3.1-tellus.1`; official API `7.1.0` plus the optional Tellus generation-availability method.

Use a fresh upstream checkout at the pinned revision, initialize its submodules,
then apply `patches/loader.patch` at the loader repository root and
`patches/core.patch` inside `coreSubProjects`. Use absolute patch paths when
invoking `git apply` from those directories. The existing local fork already has
these changes; do not apply them twice.

From the Tellus root, with `JAVA_HOME` pointing to JDK 25:

```sh
python3 compat/distant-horizons/build.py
./gradlew --no-daemon --max-workers=2 build
python3 compat/distant-horizons/verify_jars.py output/dh-3.3 \
  --api /path/to/DistantHorizonsApi-7.1.0.jar \
  --iris /path/to/iris-fabric-1.11.4+mc26.2.jar \
  --iris /path/to/iris-1.11.6+26.3-fabric.jar
```

The build script runs all five Minecraft targets sequentially, stages ten
loader-specific jars, and preserves the core/API test reports. Gradle also uses
JDK 17 and 21 toolchains for older Minecraft versions. Add `--offline` when the
required dependencies are already cached. Validation emits `manifest.json` and
`SHA256SUMS.txt`; it checks archive integrity, loader versions, Java versions,
official API member preservation, shared translations/core bytecode, the Tellus
availability extension, and compiled Iris references.

See [the integration notes](../../docs/distant-horizons-3.3.1.md) for behavior,
compatibility, and runtime validation. Runtime probe sources and logs from this
local build are retained under `output/dh-3.3/`.

## Forge 1.20.1 packaging fix for the 3.3.4 fork

`patches/multi-release.patch` applies to the 3.3.4 loader checkout and is already
applied to the local fork. It normalizes the final `shadeDowngradedApi` jar:
when JVMDowngrader has flattened `META-INF/versions`, the stale `Multi-Release`
manifest flag is removed. Real multi-release jars keep their flag and entries.
This prevents Forge 1.20.1's SecureJarHandler from failing before mod startup.
The release verifier also rejects this metadata mismatch.
