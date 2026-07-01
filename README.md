# Parallel Zip — a multithreaded, reproducible Gradle archive task

Gradle's built-in `Zip` task is **single-threaded** and re-DEFLATEs everything, even
content that is already compressed (jars, `.gz`, images). For large, jar-heavy
distributions that makes archiving one of the slowest tasks in the build.

`parallel-zip` is a small, dependency-free custom task that compresses entries across
**all your cores** — or skips compression entirely (`STORE`) — and produces a
**byte-for-byte reproducible** archive.

> Status: early (`0.3.0`). The core engine is tested and produces valid, verifiable
> archives, including ZIP64 (archives > 4 GiB and > 65,535 entries) and streamed
> entries larger than 2 GiB. Output is cross-validated with a non-Java reader.
> See [Limitations](#limitations).

## Why

Gradle has had an open request to build archives in parallel since 2017
([gradle/gradle#2774](https://github.com/gradle/gradle/issues/2774)) — a spike showed
~40% gains using Apache Commons Compress + the worker pool, but it was never shipped,
partly because parallel writing breaks reproducible entry order. This plugin keeps a
**fixed, name-sorted write order** so parallelism never affects the output bytes, which
sidesteps that blocker.

## Usage

```groovy
plugins {
    id 'io.github.kukis13.parallel-zip' version '0.3.0'
}

tasks.register('dist', io.github.kukis13.parallelzip.ParallelZip) {
    from = layout.buildDirectory.dir('staging')      // directory to archive
    into = 'myapp-1.0'                                // optional path prefix (like Gradle's `into`)
    archiveFile = layout.buildDirectory.file('dist.zip')

    store = false              // true = STORE everything (fastest, ~7% larger)
    level = 6                  // DEFLATE level 0..9 (ignored when store = true)
    threads = 12               // default: number of available processors
    preserveTimestamps = false // false = reproducible, byte-stable archive
}
```

## Options

| Property | Type | Default | Description |
|---|---|---|---|
| `from` | `Directory` | — (required) | Directory whose contents are archived. |
| `into` | `String` | `""` | Path prefix prepended to every entry. |
| `archiveFile` | `RegularFile` | — (required) | Output archive. |
| `store` | `boolean` | `false` | STORE all entries (no DEFLATE). Fastest; ~7% larger. |
| `level` | `int` | `-1` (zlib default 6) | DEFLATE level `0..9`. |
| `threads` | `int` | available processors | Compression worker threads. |
| `preserveTimestamps` | `boolean` | `true` | `false` writes a fixed timestamp for reproducible output. |

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

With `preserveTimestamps = false`, the archive is **byte-for-byte identical** across
runs and across thread counts (verified: SHA-256 is stable for 1, 4, 8 and 12 threads).
Entries are always written in name-sorted order. For fully reproducible builds also
ensure identical file **contents** and the same **JDK** (the DEFLATE codec must match);
`store = true` sidesteps the codec dependency entirely.

## Large archives (ZIP64)

ZIP64 is applied automatically when needed — archives larger than 4 GiB, more than
65,535 entries, or per-entry offsets/sizes beyond 4 GiB. The correct ZIP64 extra fields
and end-of-central-directory records are emitted, and the output is validated against
both `java.util.zip` and a non-Java (.NET) reader. Deterministic order and reproducibility
are preserved in ZIP64 mode.

## Memory and large entries

Small entries are compressed in memory (the fast path). Entries larger than 128 MiB are
streamed through the deflater to a temporary file — or read straight from the source when
stored — so heap use stays bounded and a **single entry may be arbitrarily large** (tested
past 2 GiB). The streamed path produces byte-for-byte identical output to the in-memory
path, so it never affects reproducibility.

## Limitations

- **Single source directory** per task (plus an `into` prefix). Full `CopySpec`
  (`from`/`include`/`exclude`/`rename`/`filter`) is on the roadmap.
- No symlink or POSIX-permission preservation yet.

## Roadmap

- Full `CopySpec` support so it can extend `AbstractArchiveTask` as a true drop-in `Zip`.
- Optional `AbstractArchiveTask` integration (`archiveBaseName`/`destinationDirectory`).
- Pluggable codecs (e.g. libdeflate / zstd) for the compressible slice.

## Building

```bash
./gradlew build        # compile + run the functional tests
./gradlew publishToMavenLocal
```

## License

Apache License 2.0 — see [LICENSE](LICENSE).
