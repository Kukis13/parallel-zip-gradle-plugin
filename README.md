# Parallel Zip — a multithreaded, reproducible Gradle archive task

[![CI](https://github.com/Kukis13/parallel-zip-gradle-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/Kukis13/parallel-zip-gradle-plugin/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Gradle 8 | 9](https://img.shields.io/badge/Gradle-8%20%7C%209-02303A?logo=gradle&logoColor=white)](docs/COMPATIBILITY.md)

`parallel-zip` is a small, dependency-free custom task that compresses entries across
**all your cores** — or skips compression entirely (`STORE`) — and produces a
**byte-for-byte reproducible** archive. It extends `AbstractArchiveTask`, so it's a
**drop-in for `Zip`** — the full `CopySpec` DSL works unchanged. Gradle's own `Zip` task
is single-threaded and re-DEFLATEs everything, even content that's already compressed
(jars, `.gz`, images); this plugin keeps a fixed write order so parallel compression
never affects the output bytes (see [How it works](docs/ARCHITECTURE.md)).

## Results

The most direct evidence: clone a real project, add a `ParallelZip` twin of its actual
production `Zip` task, and diff the two tasks' own execution time with everything else
in the build warm/cached. `skipAlreadyCompressed` (default `true`) trades archive size
for speed, so every row below shows **both modes** — the size difference between them is
too large to summarize with one number.

| Project | Task | Stock `Zip` | `skipAlreadyCompressed=true` | `skipAlreadyCompressed=false` |
|---|---|--:|--:|--:|
| JBake | `jbake-dist:distZip` | 2.965 s | 0.059 s (**49.9×**, +7.5% size) | 0.250 s (**11.8×**, +0.2% size) |
| Gradle (the build tool) | `distributions-full:binDistributionZip` | 4.001 s | 0.125 s (**32.0×**, +12.4% size) | 0.714 s (**5.60×**, +0.9% size) |
| JBang | `distZip` | 0.376 s | 0.015 s (**24.4×**, +10.9% size) | 0.130 s (**2.90×**, −0.3% size) |
| Grails CLI | `grails-shell-cli:distZip` | 1.841 s | 0.054 s (**33.8×**, +11.1% size) | 0.156 s (**11.8×**, −0.2% size) |
| SonarQube (Community Build) | `sonar-application:zip` | 23.31 s | 4.13 s (**5.64×**, +6.3% size) | 5.89 s (**3.96×**, +0.8% size) |
| dj-session-export (Kotlin/Native) | `serato-convert:zipMingwX64` | 0.299 s | 0.061 s (**4.89×**, −1.7% size) | 0.060 s (**5.03×**, −1.7% size) |

Not just JVM projects: the last row is a `kotlin("multiplatform")` project targeting
`mingwX64` only, archiving a linked native `.exe` and its runtime DLLs — `ParallelZip` is a
generic `CopySpec`/`AbstractArchiveTask` swap, so it doesn't care what's inside. It's also
the only project here where the archive comes out *smaller* than stock, since none of its
content matches an already-compressed signature.

Geometric-mean speedup over stock across the nine freshly measured production Zip tasks: **~22.6×** with
`skipAlreadyCompressed=true`, **~5.87×** with it `false`. The size cost follows the same
split: `true` mode ranges from **−1.7% to +17.1%** across all ten projects (negative only
on the Kotlin/Native project, which has no already-compressed content to STORE instead of
DEFLATE); `false` mode stays within about **±1.7%** of stock in nine of ten (one outlier at
+5.0%) while still beating stock by 2.9×–11.8×. Pick `true` for the fastest builds, `false`
when archive size matters more than shaving the last bit of time off an already-fast task.
Full breakdown, all ten projects, and the eleven-project fixed-corpus benchmarks →
**[docs/BENCHMARKS.md](docs/BENCHMARKS.md)**.

## Usage

`ParallelZip` extends `AbstractArchiveTask`, so it accepts the same `from` / `into` /
`include` / `exclude` / `rename` / `filter` / `duplicatesStrategy` DSL and archive-naming
conventions as `Zip`. Swapping `type: Zip` for `type: ParallelZip` needs no other changes:

```groovy
plugins {
    id 'com.ljarocki.parallel-zip' version '1.5.0'
}

tasks.register('dist', com.ljarocki.parallelzip.ParallelZip) {
    into('myapp-1.0') {
        from 'build/staging'
        from(configurations.runtimeClasspath) { into 'lib' }
        exclude '**/*.tmp'
        rename 'app.properties', 'application.properties'
    }
    archiveFileName = 'dist.zip'
    destinationDirectory = layout.buildDirectory

    store = false                    // true = STORE everything (fastest; size cost varies
                                      // widely by content, +4% to +107% measured — see docs)
    skipAlreadyCompressed = true     // false = always attempt DEFLATE, near-stock size
    level = 6                        // DEFLATE level 0..9 (ignored when store = true)
    threads = 12                     // default: available processors
    preserveFileTimestamps = false   // inherited from AbstractArchiveTask; false = reproducible
    reproducibleFileOrder = true     // inherited; deterministic entry order
}
```

Everything on `Zip`/`AbstractArchiveTask` applies (`from`, `into`, `include`, `exclude`,
`rename`, `filter`, `archiveFileName`, `destinationDirectory`, `archiveBaseName`,
`preserveFileTimestamps`, `reproducibleFileOrder`, …). This plugin adds:

| Property | Type | Default | Description |
|---|---|---|---|
| `store` | `boolean` | `false` | STORE all entries (no DEFLATE). Fastest; size cost depends entirely on content — measured +4% to +107% across 11 real projects, see [Benchmarks](docs/BENCHMARKS.md). |
| `skipAlreadyCompressed` | `boolean` | `true` | STORE entries recognized by file signature as already compressed (jars, gzip, images, …) instead of attempting DEFLATE. `true`: −2% to +17% vs. stock size (negative only on content with nothing already-compressed to STORE), 3–46× faster. `false`: within ~2% of stock size (one outlier at +5%), still 2–14× faster. |
| `level` | `int` | `-1` (zlib default 6) | DEFLATE level `0..9`. |
| `threads` | `int` | available processors | Compression worker threads. Does not affect output bytes. |

An entry that wouldn't shrink is automatically STORED instead of DEFLATEd, and archives
past the standard ZIP limits (4 GiB, 65,535 entries) automatically get ZIP64 handling —
no configuration needed either way. On six common platform/arch combinations, DEFLATE
also runs through a bundled native accelerator instead of the JDK's `Deflater` — see
[How it works](docs/ARCHITECTURE.md) for the internals of both.

File permissions (including the executable bit) are carried into the archive as Unix mode
bits and honoured by extractors on Linux/macOS, matching Gradle's own `Zip`. Gradle's
`filePermissions` / `dirPermissions` / `eachFile` overrides apply as usual.
(Fixed in 1.4.2 — 1.4.1 and earlier marked archives MS-DOS/FAT, so `unzip` on Linux
dropped the executable bit. The fix changes the archive bytes, so a 1.4.2 archive is not
byte-identical to a 1.4.1 one of the same inputs.)

`ParallelZip` is also `@CacheableTask` — unlike Gradle's own `Zip`, which opts out of the
build cache upstream. With `org.gradle.caching=true` (or `--build-cache`), an unchanged
rebuild restores the archive from cache instead of re-running at all.

## Learn more

- **[How it works](docs/ARCHITECTURE.md)** — why this exists, the native accelerator,
  small-entry optimizations, safety nets.
- **[Benchmarks](docs/BENCHMARKS.md)** — full in-build results and fixed-corpus
  benchmarks (both `skipAlreadyCompressed` modes), methodology.
- **[Compatibility](docs/COMPATIBILITY.md)** — Gradle 8/9, JDK, configuration cache.
- **[Reproducibility](docs/REPRODUCIBILITY.md)** — byte-for-byte output guarantees.
- **[Development](docs/DEVELOPMENT.md)** — building, testing, contributing.

## License

Apache License 2.0 — see [LICENSE](LICENSE). Third-party notices (the bundled
libdeflate accelerator) are in [NOTICE.md](NOTICE.md).
