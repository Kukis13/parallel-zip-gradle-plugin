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
`macos-x64`, DEFLATE runs through a bundled native
[libdeflate](https://github.com/ebiggers/libdeflate) build instead of the JDK's
`Deflater` — faster, and within ~0.5% of the same archive size in practice (see
[Benchmarks](#benchmarks)). Small entries are compressed straight from a JVM byte array;
large entries (above 8 MiB, up to 128 MiB) are compressed straight from a memory-mapped
view of the source file instead, with no `Files.readAllBytes` copy in between. Either
way, CRC-32 is computed natively in the same pass as compression, not as a separate scan.
libdeflate has no streaming API, so only entries past 128 MiB still stream through the
JDK `Deflater`. Every other platform/arch, or any failure loading the native build or
memory-mapping the file, falls back to the pure-Java path automatically.

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

A few highlights from real open-source projects — full 11-project table and methodology
in **[BENCHMARKS.md](BENCHMARKS.md)**:

| Project | Files | Raw size | Gradle `Zip` | parallel-zip DEFLATE (JDK) | parallel-zip DEFLATE (libdeflate) | parallel-zip STORE | STORE size Δ |
|---|--:|--:|--:|--:|--:|--:|--:|
| Cassandra 4.1.7 | 200 | 57.5 MiB | 1.22 s | 0.35 s (**3.49×**) | 0.27 s (**4.55×**) | 0.03 s (**39.23×**) | +18.9% |
| Solr 9.7.0 | 2,091 | 308.5 MiB | 5.97 s | 1.71 s (**3.49×**) | 1.34 s (**4.46×**) | 0.17 s (**34.32×**) | +12.1% |
| SonarQube Community Build 26.7-SNAPSHOT | 610 | 927.9 MiB | 17.67 s | 4.32 s (**4.09×**) | 4.13 s (**4.27×**) | 0.45 s (**39.45×**) | +6.5% |

DEFLATE trades a small amount of archive size (up to ~0.8%) for a large speed win, so
it's still the safe default. Use `store = true` only for archives you already know are
jar/binary-heavy, where the size cost is small and the speedup large.

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
