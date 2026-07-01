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
`Deflater` — faster, and within ~0.3% of the same archive size in practice (see
[Benchmarks](#benchmarks)). It only covers the in-memory fast path (no streaming API),
so large/spilled entries still use the JDK `Deflater`. Every other platform/arch, or any
failure loading the native build, falls back to the pure-Java path automatically.

## Benchmarks

Eleven popular open-source Java projects' official binary distributions, archived four
ways — Gradle `Zip` (baseline), `parallel-zip` DEFLATE (JDK codec), DEFLATE
(libdeflate), and STORE. Same machine: 12 logical cores, JDK 21, warm cache.

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
| SonarQube Community Build 26.6 | 749 | 966.6 MiB | 20.93 s | 6.56 s (**3.2×**) | 6.25 s (**3.4×**) | 2.00 s (**10.5×**) | +9.2% |
| Hadoop 3.4.0¹ | 20,220 | 1,733.6 MiB | 33.09 s | 21.19 s (**1.6×**) | 21.90 s (1.5×) | 6.02 s (**5.5×**) | +77.5% |

¹ Hadoop's raw size and file count needed `-Xmx4g`; every other row here runs on the
default 1g heap used for this benchmark. It's also the clearest case where libdeflate
doesn't help: a few individual files (500+ MiB) dominate its wall time and always stream
through the JDK codec (see [Options](#options)), so the libdeflate column is a wash.

DEFLATE never produces a bigger archive than the baseline in practice, so it's the safe
default. The one exception is Groovy: many small, already-cheap-to-compress files, where
per-entry pipeline overhead outweighs the compression saved — the only corpus here where
DEFLATE is slower than single-threaded `Zip`. Use `store = true` only for archives you
already know are jar/binary-heavy, where STORE's size cost is small and the speedup large.

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
