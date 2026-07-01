# Parallel Zip — a multithreaded, reproducible Gradle archive task

[![CI](https://github.com/Kukis13/parallel-zip-gradle-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/Kukis13/parallel-zip-gradle-plugin/actions/workflows/ci.yml)

Gradle's built-in `Zip` task is **single-threaded** and re-DEFLATEs everything, even
content that is already compressed (jars, `.gz`, images). For large, jar-heavy
distributions that makes archiving one of the slowest tasks in the build.

`parallel-zip` is a small, dependency-free custom task that compresses entries across
**all your cores** — or skips compression entirely (`STORE`) — and produces a
**byte-for-byte reproducible** archive. It extends `AbstractArchiveTask`, so it's a
**drop-in for `Zip`** — the full `CopySpec` DSL works unchanged.

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
    id 'io.github.kukis13.parallel-zip' version '1.0.0'
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
Archives beyond the standard ZIP limits — over 4 GiB, over 65,535 entries, or per-entry
offsets/sizes beyond 4 GiB — automatically get ZIP64 extra fields and end-of-central-directory
records, validated against both `java.util.zip` and a non-Java (.NET) reader.

On `linux-x64` and `windows-x64`, small-entry DEFLATE runs through a bundled native
[libdeflate](https://github.com/ebiggers/libdeflate) build instead of the JDK's
`Deflater` — measured ~2–2.5× faster at the same level, at the cost of a somewhat
larger output (libdeflate's level numbering isn't ratio-equivalent to zlib's; expect
roughly 8–10% bigger than the JDK codec at the same `level`). It only covers the
in-memory fast path (libdeflate has no streaming API), so large/spilled entries always
use the JDK `Deflater`. Every other platform/arch, or any failure loading the native
build, falls back to the pure-Java path automatically with no configuration needed.

## Benchmarks

Measured across the official binary distributions of ten popular open-source Java
projects: extract the distribution, then archive the identical directory tree three ways
— Gradle `Zip` (DEFLATE, baseline), `parallel-zip` DEFLATE, and `parallel-zip` STORE.
Same machine throughout: 12 logical cores, JDK 21, warm file cache. Predates the native
libdeflate accelerator (both DEFLATE runs used the JDK codec, hence identical sizes);
see [Options](#options) for how the native path changes the size/speed tradeoff.

| Project | Files | Raw size | Gradle `Zip` | parallel-zip DEFLATE | parallel-zip STORE | STORE size Δ |
|---|--:|--:|--:|--:|--:|--:|
| ZooKeeper 3.9.3 | 1,632 | 50.6 MiB | 0.85 s | 0.36 s (**2.4×**) | 0.24 s (**3.6×**) | +107.4% |
| Cassandra 4.1.7 | 200 | 57.5 MiB | 1.28 s | 0.47 s (**2.7×**) | 0.14 s (**9.3×**) | +18.6% |
| Kafka 3.8.1 | 235 | 121.1 MiB | 2.55 s | 1.40 s (**1.8×**) | 0.29 s (**8.8×**) | +4.1% |
| Groovy 4.0.24 | 9,757 | 271.6 MiB | 4.01 s | 4.98 s (0.8×, **slower**) | 4.45 s (0.9×, slower) | +273.6% |
| Solr 9.7.0 | 2,091 | 308.5 MiB | 6.48 s | 2.42 s (**2.7×**) | 0.77 s (**8.5×**) | +12.4% |
| HBase 2.6.1 | 2,588 | 404.8 MiB | 8.10 s | 2.86 s (**2.8×**) | 1.08 s (**7.5×**) | +22.4% |
| Spark 3.5.3 | 1,825 | 427.9 MiB | 9.11 s | 4.63 s (**2.0×**) | 1.10 s (**8.3×**) | +10.4% |
| Gradle 8.14.3 | 22,432 | 500.2 MiB | 9.56 s | 5.19 s (**1.8×**) | 3.64 s (**2.6×**) | +108.6% |
| Flink 1.20.0 | 167 | 502.8 MiB | 10.66 s | 5.47 s (**1.9×**) | 1.06 s (**10.1×**) | +8.9% |
| SonarQube distribution | 610 | 927 MiB | 23 s | 4.9 s (**4.7×**) | 0.7 s (**32.9×**) | +7.4% |

The DEFLATE column never produces a larger archive than the baseline (the per-entry STORE
fallback + identical codec guarantee that), so it's a safe default. STORE trades size for
speed, and how much size depends entirely on how compressible the content already is:

- **Jar-heavy binary distributions** (most of the table above) are mostly pre-compressed
  bytes already: DEFLATE gets **1.8–4.7×** faster and STORE gets **7–33×** faster for a
  modest **4–22%** size increase. This is the sweet spot the plugin was built for.
- **Corpora dominated by many small, highly-compressible files** — Groovy's tree of
  `.class`/javadoc output, or Gradle's own distribution (22k+ files) — see much smaller
  DEFLATE gains, and on Groovy the parallel pipeline's per-entry overhead (queueing,
  semaphore handoff) actually *outweighs* the compression saved, making it slightly
  slower than the single-threaded baseline. STORE mode is a poor fit here too: skipping
  DEFLATE on already-cheap-to-compress, highly redundant content can more than **double**
  the archive size.
- **Recommendation:** default to `store = false` (DEFLATE) — it's a safe, usually-faster,
  never-bigger drop-in. Only opt into `store = true` for archives you already know are
  jar/binary-heavy, where the size cost is small and the speedup is large.

## Reproducibility

With `preserveFileTimestamps = false`, the archive is **byte-for-byte identical** across
runs and across thread counts (verified: SHA-256 is stable for 1, 8 and 12 threads).
Compression runs in parallel but entries are always written in a fixed order (Gradle's
resolved copy order, honouring `reproducibleFileOrder`), so scheduling never affects the
bytes. For fully reproducible builds also ensure identical file **contents** and the same
**JDK** (the DEFLATE codec must match); `store = true` sidesteps the codec dependency.
The native libdeflate accelerator is an additional codec dependency on top of the JDK:
builds on `linux-x64`/`windows-x64` use it, builds elsewhere fall back to the JDK
`Deflater`, so byte-identical output across *different platforms* also requires both
sides to have (or both lack) the native accelerator — same-platform reproducibility is
unaffected either way.

## Roadmap

- Optional per-entry parallel *reads* for filter-free specs (skip materialization).

## Building

```bash
./gradlew build        # compile + run the functional tests
./gradlew publishToMavenLocal
```

## License

Apache License 2.0 — see [LICENSE](LICENSE). Third-party notices (the bundled
libdeflate accelerator) are in [NOTICE.md](NOTICE.md).
