# Benchmarks

Every table on this page compares **three points**, all on the same **v2.0.0** build so only
the `skipAlreadyCompressed` flag varies, not plugin version: stock Gradle `Zip`, `parallel-zip`
2.0.0 with `skipAlreadyCompressed = true` (the default), and `skipAlreadyCompressed = false`. The
flag matters enough for both speed *and* size that any single number without saying which
mode it's from is misleading — so every size or speed claim on this page, and in the README,
names its mode.

## In-build benchmarks (real projects, real `Zip` tasks — zero-config 2.0.0)

With v2.0.0's zero-config `ParallelZipPlugin` (`accelerateAllZipTasks = true`), no task types or
build scripts were modified — each project's real `Zip` task was intercepted directly via an
`--init-script` and timed via `doFirst`/`doLast` `System.nanoTime()` hooks (median of 3 warm
readings, first warmup discarded). Everything else in each build (compilation, resource
processing, dependency resolution) was warm/cached. Same machine: 12 logical cores, JDK 17/21/25
(whichever each project's build required), each project measured in isolation.

| Project | Task | Stock `Zip` | `skipAlreadyCompressed=true` | `skipAlreadyCompressed=false` | Speedup (true) | Speedup (false) | Size Δ (true) | Size Δ (false) |
|---|---|--:|--:|--:|--:|--:|--:|--:|
| JBake¹ | `jbake-dist:distZip` | 2.965 s | 0.059 s | 0.250 s | **49.9×** | **11.8×** | +7.45% | +0.24% |
| Gradle Profiler | `distZip` | 0.879 s | 0.029 s | 0.142 s | **30.3×** | **6.19×** | +8.28% | −0.22% |
| Gradle (the build tool) | `distributions-full:binDistributionZip` | 3.245 s | 0.124 s | 0.675 s | **26.2×** | **4.81×** | +10.73% | +0.86% |
| Spring Boot CLI | `cli:spring-boot-cli:zip` | 0.185 s | 0.010 s | 0.074 s | **18.5×** | **2.50×** | +16.88% | −0.29% |
| JBang | `distZip` | 0.329 s | 0.020 s | 0.158 s | **16.5×** | **2.08×** | +10.65% | −0.29% |
| Grails CLI | `grails-shell-cli:distZip` | 1.265 s | 0.043 s | 0.154 s | **29.4×** | **8.21×** | +11.17% | −0.24% |
| Groovy 4.0.24 | `groovy-binary:distBin` | 0.693 s | 0.042 s | 0.110 s | **16.5×** | **6.30×** | +10.92% | +0.02% |
| Micronaut Starter (Launch) CLI | `micronaut-cli:distZip` | 0.793 s | 0.032 s | 0.083 s | **24.8×** | **9.55×** | +12.93% | +4.72% |
| SonarQube (Community Build) | `sonar-application:zip` | 22.67 s | 3.82 s | 5.77 s | **5.93×** | **3.93×** | +6.44% | +0.74% |
| dj-session-export (Kotlin/Native) | `serato-convert:zipMingwX64` | 0.218 s | 0.050 s | 0.060 s | **4.36×** | **3.63×** | −1.70% | −1.70% |

¹ Carried over from 1.5.0: JBake uses Gradle 7.3.3, whose `jars-9` bytecode instrumentation (ASM 9.1) cannot parse JDK 22 classfiles inside v2.0.0's Multi-Release JAR (`META-INF/versions/22`). All 9 Gradle 8.x/9.x projects above — including Gradle 9.4 (`dj-session-export`), Gradle 9.6 (`Micronaut Starter`), and Gradle Isolated Projects (`Gradle`) — were freshly measured on v2.0.0.

Geometric-mean speedup over stock `Zip` across the nine freshly measured v2.0.0 tasks: **16.2×**
with `skipAlreadyCompressed=true`, **4.69×** with `skipAlreadyCompressed=false`.

**Size, both modes**: `skipAlreadyCompressed=true` ranges from **−1.70% to +16.88%** across all
ten projects — larger than stock everywhere there is already-compressed content to STORE instead
of DEFLATE, smaller than stock on the Kotlin/Native project that has none. `skipAlreadyCompressed=false`
stays within **±1%** of stock in eight of the ten projects (−0.29% to +0.86%), with Micronaut
Starter CLI at +4.72% and `dj-session-export` at −1.70%.

## Fixed-corpus benchmarks (static directory tree — v2.0.0 `ParallelZipFastPath`)

Eleven popular open-source projects' official binary distributions archived three ways — stock
Gradle `Zip`, `ParallelZip` 2.0.0 (`skipAlreadyCompressed=true`), and `ParallelZip` 2.0.0
(`skipAlreadyCompressed=false`) — using a fixed staging directory so only the archiving engine
varies. Unfiltered directory `CopySpec`s take v2.0.0's `ParallelZipFastPath` (bypassing Gradle's
single-threaded `CopyActionProcessingStream`), native `compressFilesBatch` on small-file batches,
and parallel `pwrite` on large entries. Same machine, JDK 21, `-Xmx4g` daemon, median of 3 warm
runs (first warmup discarded).

| Project | Files | Raw size | Gradle `Zip` | `skipAlreadyCompressed=true` | `skipAlreadyCompressed=false` | Speedup (true) | Speedup (false) | Size Δ (true) | Size Δ (false) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| SonarQube Community Build | 645 | 904.0 MiB | 19.11 s | **0.68 s** | **1.98 s** | **28.1×** | **9.65×** | +6.44% | +0.74% |
| Gradle 8.14.3 (`-all`) | 317 | 223.8 MiB | 10.23 s | **0.78 s** | **1.33 s** | **13.1×** | **7.68×** | +7.70% | +0.40% |
| Groovy 4.0.24 | 102 | 31.8 MiB | 0.64 s | **0.03 s** | **0.09 s** | **22.2×** | **7.22×** | +10.92% | +0.03% |
| Cassandra 4.1.7 | 200 | 57.1 MiB | 1.23 s | **0.04 s** | **0.16 s** | **28.5×** | **7.81×** | +7.02% | −0.31% |
| Kafka 3.8.1 | 235 | 120.4 MiB | 2.40 s | **0.08 s** | **0.16 s** | **28.5×** | **14.88×** | +4.00% | +0.55% |
| Flink 1.20.0 | 167 | 502.4 MiB | 9.79 s | **0.34 s** | **1.49 s** | **29.0×** | **6.59×** | +8.84% | +0.24% |
| Spark 3.5.3 | 1,825 | 423.6 MiB | 8.94 s | **0.32 s** | **1.36 s** | **27.8×** | **6.59×** | +6.93% | +0.50% |
| Solr 9.7.0 | 2,091 | 304.4 MiB | 6.43 s | **0.26 s** | **0.80 s** | **24.5×** | **8.07×** | +9.21% | +0.20% |
| HBase 2.6.1 | 2,588 | 397.1 MiB | 7.97 s | **0.34 s** | **1.02 s** | **23.3×** | **7.83×** | +10.58% | +0.16% |
| Hadoop 3.4.0¹ | 20,219 | 1,675.2 MiB | 33.77 s | **2.30 s** | **3.98 s** | **14.7×** | **8.48×** | +12.19% | −0.08% |
| ZooKeeper 3.9.3 | 1,632 | 46.5 MiB | 0.92 s | **0.08 s** | **0.12 s** | **11.7×** | **7.94×** | +4.75% | −0.00% |

¹ Compared to v1.5.0 on Hadoop 3.4.0 (20,219 files, 1.64 GiB), v2.0.0's `ParallelZipFastPath` +
native `compressFilesBatch` + parallel `pwrite` cut `skipAlreadyCompressed=true` from **6.92 s →
2.30 s (3.0× faster)** and `skipAlreadyCompressed=false` from **17.72 s → 3.98 s (4.45× faster)**.

## v2.0.0 new workloads (`ParallelTarGz`, `> 128 MiB` chunked `libdeflate`, 20k-file batch)

### `ParallelTarGz` (pigz-style parallel block gzip) vs stock Gradle `Tar` (`Compression.GZIP`)

| Corpus | Stock `Tar` (GZIP) | `ParallelTarGz` | Speedup | Size Δ |
|---|--:|--:|--:|--:|
| SonarQube (extracted, 904 MiB) | 22.13 s | **0.89 s** | **24.8×** | +2.25% |
| Gradle 8.14.3 (`-all`, 224 MiB) | 10.60 s | **3.31 s** | **3.21×** | +3.90% |

### Targeted v2.0.0 engine workloads (`ParallelZip` vs stock `Zip`)

| Workload | Stock `Zip` | v2.0.0 `ParallelZip` | Speedup | Size Δ |
|---|--:|--:|--:|--:|
| 20,000-file JSON tree (`compressFilesBatch` + fast-path) | 1.80 s | **0.25 s** | **7.36×** | −0.00% |
| 256 MiB single compressible log (`libdeflate_deflate_compress_nonfinal`) | 0.87 s | **0.12 s** | **7.47×** | +1.95% |

## Notes on measurement method

- **Gradle (the tool)** enables Isolated Projects, which forces configuration cache on;
  configuration cache rejects `project.ext` as a channel between a task's `doFirst` and
  `doLast` — use `System.getProperties()` instead when timing a config-cache-enabled
  project this way.
- **JBake**'s Gradle 7.3.3 wrapper needs a JDK 17 daemon and cannot instrument JDK 22 classes in
  Multi-Release JARs; its row is carried over from 1.5.0.
- **Gradle**, **Micronaut Starter CLI**, and **Spring Boot CLI** required JDK 25 for the Gradle
  daemon itself; **Gradle Profiler**, **JBang**, **Grails CLI**, **Groovy 4.0.24**, and
  **dj-session-export** ran on JDK 21.
- **`store = true`** (STORE everything, no DEFLATE attempt at all) is the fastest possible mode
  but trades size aggressively (+4.1% on Kafka to +107.0% on ZooKeeper); default to
  `skipAlreadyCompressed` (`true` or `false`) instead.

See also: [How it works](ARCHITECTURE.md) · [Compatibility](COMPATIBILITY.md) ·
[Reproducibility](REPRODUCIBILITY.md) · [Development](DEVELOPMENT.md)
