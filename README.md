# Parallel Zip — a multithreaded, reproducible Gradle archive task

[![CI](https://github.com/Kukis13/parallel-zip-gradle-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/Kukis13/parallel-zip-gradle-plugin/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Gradle 8 | 9](https://img.shields.io/badge/Gradle-8%20%7C%209-02303A?logo=gradle&logoColor=white)](#requirements)

`parallel-zip` is a small, dependency-free custom task that compresses entries across
**all your cores** — or skips compression entirely (`STORE`) — and produces a
**byte-for-byte reproducible** archive. It extends `AbstractArchiveTask`, so it's a
**drop-in for `Zip`** — the full `CopySpec` DSL works unchanged.

## Why

Gradle's built-in `Zip` task is **single-threaded** and re-DEFLATEs everything, even
content that is already compressed (jars, `.gz`, images). For large, jar-heavy
distributions that makes archiving one of the slowest tasks in the build.

Gradle has had an open request to build archives in parallel since 2017
([gradle/gradle#2774](https://github.com/gradle/gradle/issues/2774)) — a spike showed
~40% gains using Apache Commons Compress + the worker pool, but it was never shipped,
partly because parallel writing breaks reproducible entry order. This plugin keeps a
**fixed write order** so parallelism never affects the output bytes, which sidesteps
that blocker.

## Requirements

Gradle 8 and Gradle 9, JDK 17+. Verified directly against our own CI's Gradle 8.14.3 and
against a fresh Gradle 9.6.1 project (including `--configuration-cache`); not exhaustively
tested across every minor release, but the plugin only uses stable, non-deprecated
`AbstractArchiveTask`/`CopySpec` APIs, so other 8.x/9.x versions are expected to work too.

## Usage

`ParallelZip` extends `AbstractArchiveTask`, so it accepts the same `from` / `into` /
`include` / `exclude` / `rename` / `filter` / `duplicatesStrategy` DSL and archive-naming
conventions as `Zip`. Swapping `type: Zip` for `type: ParallelZip` needs no other changes:

```groovy
plugins {
    id 'com.ljarocki.parallel-zip' version '1.1.0'
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

## Options

Everything on `Zip`/`AbstractArchiveTask` applies (`from`, `into`, `include`, `exclude`,
`rename`, `filter`, `archiveFileName`, `destinationDirectory`, `archiveBaseName`,
`preserveFileTimestamps`, `reproducibleFileOrder`, …). This plugin adds:

| Property | Type | Default | Description |
|---|---|---|---|
| `store` | `boolean` | `false` | STORE all entries (no DEFLATE). Fastest; ~7% larger. |
| `level` | `int` | `-1` (zlib default 6) | DEFLATE level `0..9`. |
| `threads` | `int` | available processors | Compression worker threads. Does not affect output bytes. |

Per-entry safety net: if DEFLATE would make an entry *larger* (already-compressed data),
that entry is automatically STORED instead, so output is never bigger than necessary.
Archives beyond the standard ZIP limits — over 4 GiB, over 65,535 entries, or per-entry
offsets/sizes beyond 4 GiB — automatically get ZIP64 extra fields and end-of-central-directory
records, validated against both `java.util.zip` and a non-Java (.NET) reader.

On `linux-x64`, `linux-arm64`, `windows-x64`, `windows-arm64`, `macos-arm64` and
`macos-x64`, small-entry DEFLATE runs through a bundled native
[libdeflate](https://github.com/ebiggers/libdeflate) build instead of the JDK's
`Deflater` — faster, and within ~0.5% of the same archive size in practice (see
[Benchmarks](#benchmarks)). It only covers the in-memory fast path (no streaming API),
so large/spilled entries still use the JDK `Deflater`. Every other platform/arch, or
any failure loading the native build, falls back to the pure-Java path automatically.

Two more optimizations for archives with lots of small entries, both always on with no
configuration needed:

- **Small entries are batched** into fewer compression tasks, amortizing per-task
  scheduling overhead across many small files.
- **Unfiltered entries are read lazily**, on a worker thread at compress time, instead
  of eagerly on Gradle's single-threaded copy walk — this is the bigger win of the two.
  Gradle's own `FileCopyDetails` exposes the real source file only when no content
  filter is configured (`getFile()` and `open()` share the identical guard), so this
  never touches filtered entries; a filtered file is still read on the walk, since
  Gradle's filter chain has to run through its own API.

Together, on many-small-file archives, these turned what used to be the one case where
DEFLATE was *slower* than single-threaded `Zip` into one of the largest speedups
measured (see [Benchmarks](#benchmarks)).

## Benchmarks

The most direct evidence: clone a real project, add a `ParallelZip` twin of its actual
production `Zip` task, and diff the two tasks' own execution time with everything else
in the build warm/cached. Full methodology, all rows, and the fixed-corpus (static
directory tree, four codecs) benchmarks in **[BENCHMARKS.md](BENCHMARKS.md)**:

| Project | Task | Stock `Zip` | `ParallelZip` | Speedup |
|---|---|--:|--:|--:|
| Micronaut Starter (Launch) CLI | `distZip` | 0.757 s | 0.070 s | **10.89×** |
| JBake | `jbake-dist:distZip` | 2.915 s | 0.290 s | **10.05×** |
| Gradle (the build tool) | `distributions-full:binDistributionZip` | 3.690 s | 0.815 s | **4.53×** |
| SonarQube 26.6 | `sonar-application:zip` | 24.991 s | 8.520 s | **2.93×** |
| JBang | `distZip` | 0.404 s | 0.126 s | **3.21×** |

Geometric-mean speedup across nine real production Zip tasks measured this way: **~4.3×**.
Archive sizes matched within ~1–5% of the stock task in every case — DEFLATE trades a
small amount of archive size for a large speed win, so it's still the safe default. Use
`store = true` only for archives you already know are jar/binary-heavy, where the size
cost is small and the speedup large.

## Reproducibility

With `preserveFileTimestamps = false`, the archive is **byte-for-byte identical** across
runs and across thread counts (verified: SHA-256 is stable for 1, 8 and 12 threads).
Compression runs in parallel but entries are always written in a fixed order (Gradle's
resolved copy order, honouring `reproducibleFileOrder`), so scheduling never affects the
bytes. For fully reproducible builds also ensure identical file **contents** and the same
**JDK** (the DEFLATE codec must match); `store = true` sidesteps the codec dependency.
The native libdeflate accelerator is an additional codec dependency on top of the JDK:
builds on the six platforms listed above use it, builds elsewhere fall back to the JDK
`Deflater`, so byte-identical output across *different platforms* also requires both
sides to have (or both lack) the native accelerator — same-platform reproducibility is
unaffected either way.

## Roadmap

- Zero-copy (mmap) reads for large entries, feeding the native accelerator directly
  instead of copying through a JVM byte array.
- Fuse CRC32 computation into the same buffer pass as compression instead of two scans.

## Contributing

PRs welcome — bug fixes, new platform support, benchmarks against other projects,
anything. Just lead with a strong **why**: what's slow or broken today, and evidence
it's better after.

## Building

```bash
./gradlew build        # compile + run the functional tests
./gradlew publishToMavenLocal
```

## License

Apache License 2.0 — see [LICENSE](LICENSE). Third-party notices (the bundled
libdeflate accelerator) are in [NOTICE.md](NOTICE.md).
