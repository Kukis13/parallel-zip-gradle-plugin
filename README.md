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
in the build warm/cached.

| Project | Task | Stock `Zip` | `ParallelZip` | Speedup |
|---|---|--:|--:|--:|
| Micronaut Starter (Launch) CLI | `distZip` | 0.757 s | 0.070 s | **10.89×** |
| JBake | `jbake-dist:distZip` | 2.915 s | 0.290 s | **10.05×** |
| Gradle (the build tool) | `distributions-full:binDistributionZip` | 3.690 s | 0.815 s | **4.53×** |
| SonarQube 26.6 | `sonar-application:zip` | 24.991 s | 8.520 s | **2.93×** |
| JBang | `distZip` | 0.404 s | 0.126 s | **3.21×** |

Geometric-mean speedup across nine real production Zip tasks measured this way: **~4.3×**.
Archive sizes matched within ~1–5% of the stock task in every case.

Full methodology, all rows, and the fixed-corpus (static directory tree, four codecs)
benchmarks → **[docs/BENCHMARKS.md](docs/BENCHMARKS.md)**.

## Usage

`ParallelZip` extends `AbstractArchiveTask`, so it accepts the same `from` / `into` /
`include` / `exclude` / `rename` / `filter` / `duplicatesStrategy` DSL and archive-naming
conventions as `Zip`. Swapping `type: Zip` for `type: ParallelZip` needs no other changes:

```groovy
plugins {
    id 'com.ljarocki.parallel-zip' version '1.3.0'
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

    store = false                    // true = STORE everything (fastest, ~7% larger)
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
| `store` | `boolean` | `false` | STORE all entries (no DEFLATE). Fastest; ~7% larger. |
| `level` | `int` | `-1` (zlib default 6) | DEFLATE level `0..9`. |
| `threads` | `int` | available processors | Compression worker threads. Does not affect output bytes. |

An entry that wouldn't shrink is automatically STORED instead of DEFLATEd, and archives
past the standard ZIP limits (4 GiB, 65,535 entries) automatically get ZIP64 handling —
no configuration needed either way. On six common platform/arch combinations, DEFLATE
also runs through a bundled native accelerator instead of the JDK's `Deflater` — see
[How it works](docs/ARCHITECTURE.md) for the internals of both.

## Learn more

- **[How it works](docs/ARCHITECTURE.md)** — why this exists, the native accelerator,
  small-entry optimizations, safety nets.
- **[Benchmarks](docs/BENCHMARKS.md)** — full in-build results and fixed-corpus
  (four-codec) benchmarks, methodology.
- **[Compatibility](docs/COMPATIBILITY.md)** — Gradle 8/9, JDK, configuration cache.
- **[Reproducibility](docs/REPRODUCIBILITY.md)** — byte-for-byte output guarantees.
- **[Development](docs/DEVELOPMENT.md)** — building, testing, contributing.

## License

Apache License 2.0 — see [LICENSE](LICENSE). Third-party notices (the bundled
libdeflate accelerator) are in [NOTICE.md](NOTICE.md).
