# Parallel Zip — a multithreaded, reproducible Gradle archive task

[![CI](https://github.com/Kukis13/parallel-zip-gradle-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/Kukis13/parallel-zip-gradle-plugin/actions/workflows/ci.yml)

Gradle's built-in `Zip` task is **single-threaded** and re-DEFLATEs everything, even
content that is already compressed (jars, `.gz`, images). For large, jar-heavy
distributions that makes archiving one of the slowest tasks in the build.

`parallel-zip` is a small, dependency-free custom task that compresses entries across
**all your cores** — or skips compression entirely (`STORE`) — and produces a
**byte-for-byte reproducible** archive.

> Status: early (`0.4.0`). It extends `AbstractArchiveTask`, so it's a **drop-in for
> `Zip`** — the full `CopySpec` DSL works unchanged. Tested for validity, reproducibility,
> ZIP64 (> 4 GiB, > 65,535 entries), and streamed entries > 2 GiB, and cross-validated
> against a non-Java reader on a real ~927 MiB distribution. See [Limitations](#limitations).

## Why

Gradle has had an open request to build archives in parallel since 2017
([gradle/gradle#2774](https://github.com/gradle/gradle/issues/2774)) — a spike showed
~40% gains using Apache Commons Compress + the worker pool, but it was never shipped,
partly because parallel writing breaks reproducible entry order. This plugin keeps a
**fixed write order** so parallelism never affects the output bytes, which sidesteps
that blocker.

## Usage

`ParallelZip` extends `AbstractArchiveTask`, so it accepts the same `from` / `into` /
`include` / `exclude` / `rename` / `filter` / `duplicatesStrategy` DSL and archive-naming
conventions as `Zip`. Swapping `type: Zip` for `type: ParallelZip` needs no other changes:

```groovy
plugins {
    id 'io.github.kukis13.parallel-zip' version '0.4.0'
}

tasks.register('dist', io.github.kukis13.parallelzip.ParallelZip) {
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

## Benchmarks

Measured on the **SonarQube** distribution archive (`:sonar-application:zip`): 610 files,
~927 MiB raw, ~99% of bytes already compressed (jars + JRE archives). Machine: 12 logical
cores, JDK 21, warm file cache.

| Approach | Time | Archive size | Notes |
|---|---|---|---|
| Gradle `Zip` (DEFLATE, baseline) | **~23 s** | 863 MiB | single-threaded |
| Gradle `Zip` (`entryCompression = STORED`) | ~2.9 s | 927 MiB | single-threaded |
| **parallel-zip, DEFLATE, 12 threads** | **~4.9 s** | 863 MiB | **same size as baseline**, ~5× faster |
| **parallel-zip, STORE, 12 threads** | **~0.7 s** | 927 MiB | ~30× faster, +7.4% size |

DEFLATE scaling (CPU-bound, so it parallelizes well): 1 thread ≈ 26 s → 4 threads ≈ 8.7 s
→ 12 threads ≈ 4.9 s.

STORE is I/O-bound on a warm cache, so a single thread already saturates the write path;
its win comes from skipping compression, not from threads.

## Reproducibility

With `preserveFileTimestamps = false`, the archive is **byte-for-byte identical** across
runs and across thread counts (verified: SHA-256 is stable for 1, 8 and 12 threads).
Compression runs in parallel but entries are always written in a fixed order (Gradle's
resolved copy order, honouring `reproducibleFileOrder`), so scheduling never affects the
bytes. For fully reproducible builds also ensure identical file **contents** and the same
**JDK** (the DEFLATE codec must match); `store = true` sidesteps the codec dependency.

## Large archives (ZIP64)

ZIP64 is applied automatically when needed — archives larger than 4 GiB, more than
65,535 entries, or per-entry offsets/sizes beyond 4 GiB. The correct ZIP64 extra fields
and end-of-central-directory records are emitted, and the output is validated against
both `java.util.zip` and a non-Java (.NET) reader.

## Memory

Small entries are compressed in memory (the fast path); entries larger than ~8 MiB are
streamed through the deflater to a temp file, so a **single entry may be arbitrarily
large** (tested past 2 GiB). In-flight memory is bounded to a fraction of the heap, so the
task runs within a default (512 MiB) Gradle daemon even on a ~1 GiB archive — though, like
any parallel work, more heap (`org.gradle.jvmargs=-Xmx…`) lets more entries compress at
once. The streamed path is byte-for-byte identical to the in-memory path.

## Limitations

- No symlink preservation yet (POSIX file permissions **are** carried through).
- `duplicatesStrategy`, `reproducibleFileOrder`, and content filters are honoured via
  Gradle's standard copy pipeline.

## Roadmap

- Pluggable codecs (e.g. libdeflate / zstd) for the compressible slice.
- Optional per-entry parallel *reads* for filter-free specs (skip materialization).

## Building

```bash
./gradlew build        # compile + run the functional tests
./gradlew publishToMavenLocal
```

## License

Apache License 2.0 — see [LICENSE](LICENSE).
