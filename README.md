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
`Deflater`. On raw compressible buffers it's ~2–2.5× faster at the same level, at the
cost of somewhat larger output (libdeflate's level numbering isn't ratio-equivalent to
zlib's; expect roughly 8–10% bigger on purely-compressible data at the same `level`) —
but at the whole-archive level on real projects (see [Benchmarks](#benchmarks)), that
size difference all but disappears, typically landing within ±0.3% of the JDK codec,
since most bytes in a jar-heavy archive are already-compressed content that neither
codec can shrink further. It only covers the in-memory fast path (libdeflate has no
streaming API), so large/spilled entries always use the JDK `Deflater`. Every other
platform/arch, or any failure loading the native build, falls back to the pure-Java
path automatically with no configuration needed.

## Benchmarks

Measured across the official binary distributions of ten popular open-source Java
projects: extract the distribution, then archive the identical directory tree four ways
— Gradle `Zip` (DEFLATE, baseline), `parallel-zip` DEFLATE on the JDK codec, `parallel-zip`
DEFLATE with the native libdeflate accelerator, and `parallel-zip` STORE. Same machine
throughout: 12 logical cores, JDK 21, warm file cache. (Baseline timings carry ~2–5%
run-to-run noise, consistent with what we saw testing heap size separately — read the
×-speedups as the meaningful signal, not the absolute seconds.)

| Project | Files | Raw size | Gradle `Zip` | parallel-zip DEFLATE (JDK) | parallel-zip DEFLATE (libdeflate) | parallel-zip STORE | STORE size Δ |
|---|--:|--:|--:|--:|--:|--:|--:|
| ZooKeeper 3.9.3 | 1,632 | 50.6 MiB | 0.93 s | 0.32 s (**2.9×**) | 0.25 s (**3.7×**) | 0.24 s (**3.9×**) | +107.4% |
| Cassandra 4.1.7 | 200 | 57.5 MiB | 1.33 s | 0.48 s (**2.8×**) | 0.38 s (**3.5×**) | 0.13 s (**10.0×**) | +18.6% |
| Kafka 3.8.1 | 235 | 121.1 MiB | 2.62 s | 1.36 s (**1.9×**) | 1.32 s (**2.0×**) | 0.28 s (**9.4×**) | +4.1% |
| Groovy 4.0.24 | 9,756 | 271.6 MiB | 4.07 s | 4.77 s (0.85×, slower) | 4.50 s (0.90×, slower) | 4.32 s (0.94×, slower) | +273.6% |
| Solr 9.7.0 | 2,091 | 308.5 MiB | 6.68 s | 2.28 s (**2.9×**) | 1.94 s (**3.4×**) | 0.74 s (**9.0×**) | +12.4% |
| HBase 2.6.1 | 2,588 | 404.8 MiB | 8.33 s | 2.52 s (**3.3×**) | 2.41 s (**3.5×**) | 1.02 s (**8.2×**) | +22.4% |
| Spark 3.5.3 | 1,825 | 427.9 MiB | 9.22 s | 4.44 s (**2.1×**) | 4.24 s (**2.2×**) | 1.03 s (**9.0×**) | +10.4% |
| Gradle 8.14.3 | 22,427 | 500.2 MiB | 9.86 s | 4.74 s (**2.1×**) | 4.26 s (**2.3×**) | 3.30 s (**3.0×**) | +108.6% |
| Flink 1.20.0 | 167 | 502.8 MiB | 10.76 s | 5.05 s (**2.1×**) | 5.03 s (**2.1×**) | 1.05 s (**10.2×**) | +8.9% |
| SonarQube distribution | 610 | 927.9 MiB | 19.82 s | 5.84 s (**3.4×**) | 5.64 s (**3.5×**) | 1.88 s (**10.6×**) | +7.4% |

The DEFLATE columns never produce a larger archive than the baseline (the per-entry
STORE fallback + comparable codec ratios guarantee that in practice), so either is a
safe default. STORE trades size for speed, and how much size depends entirely on how
compressible the content already is:

- **Jar-heavy binary distributions** (most of the table above) are mostly pre-compressed
  bytes already: DEFLATE gets **1.9–3.4×** faster on the JDK codec, **2.0–3.7×** with
  libdeflate, and STORE gets **3.0–10.6×** faster for a **4–22%** size increase (STORE's
  win varies more than DEFLATE's here — it's I/O-bound, so how much it gains depends on
  file count and average entry size, not just how compressible the content is). This is
  the sweet spot the plugin was built for.
- **libdeflate helps almost everywhere, but not uniformly.** It's consistently faster
  than the JDK codec, sometimes by a lot (Cassandra, Solr, HBase go from ~2.8–3.3× to
  ~3.4–3.5×), sometimes marginally (Flink barely moves, 2.1× either way) — when a corpus
  has a few huge files dominating wall time rather than many mid-sized compressible
  entries, the codec matters less than I/O.
- **Many small, highly-compressible files can flip DEFLATE negative.** Groovy's tree of
  `.class`/javadoc output is the one corpus here where DEFLATE is *slower* than the
  single-threaded baseline on both codecs (0.85× JDK, 0.90× libdeflate) — the parallel
  pipeline's per-entry overhead (queueing, semaphore handoff) outweighs the compression
  saved when each entry is already cheap to compress. libdeflate's raw speed narrows the
  gap without closing it. Gradle's own distribution has similarly many files (22k+) but
  doesn't show this regression (still 2.1–2.3× faster) — it's specifically Groovy's mix
  of many *small* entries that triggers it, not file count alone.
- **STORE mode's size cost tracks how compressible the content is, not file count.**
  Groovy and Gradle both skip DEFLATE on highly redundant, easily-compressed content, so
  STORE more than doubles the archive there (+273.6% and +108.6%) even though Gradle's
  DEFLATE performance is otherwise unremarkable.
- **Recommendation:** default to `store = false` (DEFLATE) — it's a safe, usually-faster,
  never-bigger-by-much drop-in, and the native accelerator (when bundled for your
  platform) improves on that further for free. Only opt into `store = true` for archives
  you already know are jar/binary-heavy, where the size cost is small and the speedup
  is large.

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
