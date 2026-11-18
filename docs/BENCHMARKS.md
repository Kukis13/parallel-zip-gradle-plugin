# Benchmarks

## v2.0.0 Benchmarks (3-run warm medians)

Measured on the same 12-core Windows machine (`-Xmx4g` for fixed-corpus tasks), discarding the
first warmup run and taking the median of 3 warm runs via `doFirst`/`doLast` `System.nanoTime()`
hooks.

### In-build zero-config acceleration (`ParallelZipPlugin` on stock `Zip`)

Applied via `id 'com.ljarocki.parallel-zip'` with no task type changes (`accelerateAllZipTasks = true`):

| Project | Task | Stock `Zip` | v2.0.0 (`skip=true`) | v2.0.0 (`skip=false`) | Speedup (`true`) | Speedup (`false`) | Size Δ (`true`) | Size Δ (`false`) |
|---|---|--:|--:|--:|--:|--:|--:|--:|
| SonarQube (Community Build) | `sonar-application:zip` | 22.67 s | **3.82 s** | **5.77 s** | **5.93×** | **3.93×** | +6.44% | +0.74% |

*(1.5.0 on SonarQube was `4.13 s` / `5.89 s` against a `23.31 s` stock baseline.)*

### `ParallelZipFastPath` & v2.0.0 workloads (Gradle `ParallelZip` task vs stock `Zip`)

Unfiltered directory `CopySpec`s bypass Gradle's single-threaded `CopyActionProcessingStream`
via `ParallelZipFastPath`, exercise native `compressFilesBatch` on small-file batches, positional
`pwrite` on large entries, and `libdeflate_deflate_compress_nonfinal` chunking on `> 128 MiB` files:

| Corpus | Workload | Stock `Zip` | v2.0.0 (`skip=true`) | v2.0.0 (`skip=false`) | Speedup (`true`) | Speedup (`false`) | Size Δ (`true`) | Size Δ (`false`) |
|---|---|--:|--:|--:|--:|--:|--:|--:|
| SonarQube (extracted) | 904 MiB mixed jars + JRE | 19.11 s | **0.68 s** | **1.98 s** | **28.1×** | **9.65×** | +6.44% | +0.74% |
| Gradle 8.14.3 (`-all`) | 224 MiB distribution | 10.23 s | **0.78 s** | **1.33 s** | **13.1×** | **7.68×** | +7.70% | +0.40% |
| 20,000-file JSON tree | `compressFilesBatch` | 1.80 s | **0.25 s** | — | **7.36×** | — | −0.00% | — |
| 256 MiB single log file | `> 128 MiB` parallel `libdeflate` | 0.87 s | **0.12 s** | — | **7.47×** | — | +1.95% | — |

### `ParallelTarGz` (pigz-style parallel block gzip) vs stock Gradle `Tar` (`Compression.GZIP`)

| Corpus | Stock `Tar` (GZIP) | `ParallelTarGz` | Speedup | Size Δ |
|---|--:|--:|--:|--:|
| SonarQube (extracted) | 22.13 s | **0.89 s** | **24.8×** | +2.25% |
| Gradle 8.14.3 (`-all`) | 10.60 s | **3.31 s** | **3.21×** | +3.90% |

---

## 1.5.0 Baseline: In-build benchmarks (real projects, real Zip tasks)

Every table below compares **three points**, all on the same 1.5.0 build so only
the `skipAlreadyCompressed` flag varies, not plugin version: stock Gradle `Zip`, `ParallelZip`
with `skipAlreadyCompressed = true` (the default), and `skipAlreadyCompressed = false`. The
flag matters enough for both speed *and* size that any single number without saying which
mode it's from is misleading — so every size or speed claim on this page, and in the README,
names its mode.

The most direct evidence for this plugin isn't archiving a static directory tree — it's
what happens when you actually swap `type: Zip` for `type: ParallelZip` in a real
project's real build. For each project below: clone it, build once with its stock `Zip`
task, add a second task with identical configuration but `ParallelZip`, then build again
and diff the two tasks' own execution time (via `doFirst`/`doLast`
`System.nanoTime()` hooks). Everything else in the build (compilation, resource
processing, dependency resolution) was warm/cached in every run, so only the archiving
step itself is being compared. Same machine: 12 logical cores, JDK 17/21/25 (whichever
each project's build required), each project measured in isolation.

| Project | Task | Stock `Zip` | `skipAlreadyCompressed=true` | `skipAlreadyCompressed=false` | Speedup (true) | Speedup (false) | Size Δ (true) | Size Δ (false) |
|---|---|--:|--:|--:|--:|--:|--:|--:|
| JBake | `jbake-dist:distZip` | 2.965 s | 0.059 s | 0.250 s | **49.9×** | **11.8×** | +7.45% | +0.24% |
| Gradle Profiler | `distZip` | 1.078 s | 0.021 s | 0.145 s | **51.3×** | **7.43×** | +8.30% | −0.22% |
| Gradle (the build tool) | `distributions-full:binDistributionZip` | 4.001 s | 0.125 s | 0.714 s | **32.0×** | **5.60×** | +12.36% | +0.93% |
| Spring Boot CLI | `cli:spring-boot-cli:zip` | 0.251 s | 0.009 s | 0.073 s | **28.2×** | **3.44×** | +17.12% | −0.02% |
| JBang | `distZip` | 0.376 s | 0.015 s | 0.130 s | **24.4×** | **2.90×** | +10.86% | −0.30% |
| Grails CLI | `grails-shell-cli:distZip` | 1.841 s | 0.054 s | 0.156 s | **33.8×** | **11.8×** | +11.05% | −0.22% |
| Groovy 4.0.24 | `groovy-binary:distBin` | 1.380 s | 0.047 s | 0.192 s | **29.1×** | **7.18×** | +10.72% | −0.23% |
| Micronaut Starter (Launch) CLI¹ | `distZip` | 1.047 s | 0.072 s | 0.089 s | **14.5×** | **11.8×** | +13.21% | +5.00% |
| SonarQube (Community Build) | `sonar-application:zip` | 23.31 s | 4.13 s | 5.89 s | **5.64×** | **3.96×** | +6.28% | +0.77% |
| dj-session-export (Kotlin/Native) | `serato-convert:zipMingwX64` | 0.299 s | 0.061 s | 0.060 s | **4.89×** | **5.03×** | −1.69% | −1.69% |

¹ Carried over from the 1.4.1 benchmark run (`5.1.1-SNAPSHOT` BOM expired from Sonatype Snapshots).

The last row is the odd one out on purpose: it's the only non-JVM project in this table —
a `kotlin("multiplatform")` project targeting `mingwX64` only, archiving a linked native
`.exe` plus its bundled MinGW runtime DLLs, no JVM content at all (see
[NikkyAI/dj-session-export](https://github.com/NikkyAI/dj-session-export)). None of its
four entries match a recognized magic-byte signature, so `skipAlreadyCompressed` has no
effect either way — both modes hit real DEFLATE via the native accelerator, which is why
its `true`/`false` numbers are nearly identical here, unlike every JVM-heavy project above.
It's also the only row where `ParallelZip` beats stock on size, not just speed: libdeflate
edges out the JDK's `Deflater` by about 1.7% on this content. The takeaway isn't about
Kotlin specifically — `ParallelZip` is a generic `CopySpec`/`AbstractArchiveTask` swap that
doesn't care what's inside — it's that a real Kotlin/Native distribution archive is a
perfectly ordinary `Zip` task, and (see [ARCHITECTURE.md](ARCHITECTURE.md)) an archive with
no already-compressed content is exactly the case where the two modes converge.

Geometric-mean speedup over stock `Zip` across the nine freshly measured tasks: **~22.6×** with
`skipAlreadyCompressed=true`, **~5.87×** with it set `false` (both pulled down from the
JVM-project figures by the Kotlin/Native row's much smaller, sub-100ms archive, where
fixed per-task overhead dominates and there's little already-compressed content to skip in
the first place). Both modes are a clear win over stock every single time — the choice
between them is purely about how much archive size you're willing to trade for the
difference between "very fast" and "extremely fast."

**Size, both modes, no cherry-picking**: `skipAlreadyCompressed=true` ranges from **−1.69%
to +17.12%** across all ten projects — larger than stock everywhere there's
already-compressed content to STORE instead of DEFLATE, smaller than stock on the one
project (Kotlin/Native) that has none. `skipAlreadyCompressed=false` stays within about
**±1%** of stock in eight of the ten projects (as tight as −0.30%, i.e. sometimes *smaller*
than stock); Micronaut Starter CLI is the high outlier at +5.00%, and the Kotlin/Native
project is the low outlier at −1.69% (same as its own `true`-mode number, since the flag
doesn't change anything for content with no recognized signatures) — still far below
`true` mode's own worst case (+13.21% on Micronaut Starter CLI). If archive size matters
more than shaving the last few hundred milliseconds off an already-fast task,
`skipAlreadyCompressed=false` is the mode to reach for.

Measurement confidence varies by row — all are medians of 3+ warm readings with the first
(cold/compiling) reading discarded, except Micronaut Starter CLI's `true`-mode reading,
which showed real run-to-run swings (30–100 ms range) at this sub-100ms scale — treat that
cell as directionally correct, not precise to more than one significant figure. JBang,
Grails, and Groovy's `true`-mode readings are similarly fast (tens of ms) and carry the
same caveat, though less severely. The Kotlin/Native project's readings are on the same
sub-100ms scale (80–103 ms across both modes) and carry the same caveat.

Two projects were tried and dropped rather than forced in, both for reasons unrelated to
`parallel-zip`:

- **Corda**'s only `Zip`-typed task (`buildCordappDependenciesZip`) is broken on the
  current `master` branch — it resolves a non-resolvable `testImplementation`
  configuration, a pre-existing bug in Corda itself.
- The **Kotlin compiler's own build** (JetBrains/kotlin) has genuine Kotlin/Native
  `Zip`-typed tasks (`distNativeSources`, `samplesZip`), but they sit behind one of the
  heaviest builds in the OSS Gradle ecosystem — it didn't finish in a reasonable amount of
  time on this machine. This is a different project from the Kotlin/Native row in the table
  above, which is a small, real-world Kotlin/Native *application* (not the compiler itself)
  and built in under three minutes.

Every other candidate project investigated (Kafka, Solr, Elasticsearch, Micronaut Core,
Apache Beam, OkHttp, Ktor, ktlint, Nextflow, Ratpack, JReleaser, Netflix Eureka,
Spinnaker Orca, …) either ships a `.tar`/`.tar.gz` distribution instead of a `.zip`
(this plugin is ZIP-specific), has no distribution-archiving task at all (pure
libraries), or buries its real archive task inside custom internal Java-based Gradle
plugin code (e.g. Elasticsearch) rather than a plain `Zip` task, which was judged too
invasive to safely duplicate.

Notes on measurement method:

- **Gradle (the tool)** enables Isolated Projects, which forces configuration cache on;
  configuration cache rejects `project.ext` as a channel between a task's `doFirst` and
  `doLast` — use `System.getProperties()` instead when timing a config-cache-enabled
  project this way.
- **JBake**'s Gradle 7.3.3 wrapper needs a JDK 17 daemon (fails to even start under JDK 21+).
- **Grails CLI**, **Micronaut Starter CLI**, and **Spring Boot CLI** required specific
  JDKs (17, 25, and 25 respectively) for the Gradle *daemon itself*, not just a
  toolchain — export `JAVA_HOME` before invoking `gradlew` rather than relying on the
  default.
- Every project's `buildscript` classpath block resolves the plugin jar unconditionally,
  even when running the plain stock task — pass the jar path explicitly on every
  invocation, including "stock" runs, rather than relying on a default that can go stale
  between benchmark rounds.
- **dj-session-export**'s timing hooks live in a `subprojects.forEach { subproject -> ...
  }` block; capturing `subproject` itself (rather than `subproject.name` snapshotted to a
  local `String` first) inside a task's `doFirst`/`doLast` breaks the configuration cache
  with "cannot serialize object of type 'LifecycleAwareProject'" — a different
  config-cache pitfall than the `project.ext` one above, same fix pattern (capture a plain
  value, not a Gradle model object, before the closure).
- **dj-session-export**, like every project with `org.gradle.caching=true` and a
  `@CacheableTask` target, needs `--rerun-tasks` on every timed invocation once the plugin
  is at 1.4.1+ — otherwise a repeat run just restores `ParallelZip`'s output from the build
  cache instead of re-executing, and the timing hooks (which live in `doFirst`/`doLast`)
  never fire at all.

## Fixed-corpus benchmarks (static directory tree)

A complementary methodology: eleven popular open-source projects' official binary
distributions, archived three ways — Gradle `Zip` (baseline), `parallel-zip` 1.5.0 with
`skipAlreadyCompressed=true`, and with it `false` — using a fixed staging directory so
only the archiving mode varies, not each project's own compile/download chain. Same
machine, JDK 21, warm cache, `-Xmx4g` daemon for every project for consistency (only
strictly required for Hadoop's ~1.7 GiB corpus).

| Project | Files | Raw size | Gradle `Zip` | `skipAlreadyCompressed=true` | `skipAlreadyCompressed=false` | Speedup (true) | Speedup (false) | Size Δ (true) | Size Δ (false) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| SonarQube Community Build | 645 | 931.1 MiB | 29.76 s | 0.48 s | 2.17 s | **61.9×** | **13.71×** | +6.45% | +0.73% |
| Gradle 8.14.3 | 317 | 145.1 MiB | 4.02 s | 0.08 s | 0.61 s | **49.1×** | **6.62×** | +10.73% | +0.86% |
| Groovy 4.0.24 | 102 | 31.8 MiB | 1.01 s | 0.02 s | 0.10 s | **42.1×** | **10.21×** | +10.92% | +0.03% |
| Cassandra 4.1.7 | 200 | 57.1 MiB | 1.99 s | 0.04 s | 0.17 s | **50.9×** | **11.73×** | +7.03% | −0.31% |
| Kafka 3.8.1 | 235 | 120.4 MiB | 3.35 s | 0.06 s | 0.18 s | **54.1×** | **18.75×** | +4.00% | +0.55% |
| Flink 1.20.0 | 167 | 502.4 MiB | 13.17 s | 0.22 s | 1.45 s | **59.0×** | **9.06×** | +8.84% | +0.24% |
| Spark 3.5.3 | 1,825 | 423.6 MiB | 12.78 s | 0.34 s | 1.40 s | **37.5×** | **9.11×** | +6.93% | +0.50% |
| Solr 9.7.0 | 2,091 | 304.4 MiB | 8.91 s | 0.25 s | 0.86 s | **36.0×** | **10.30×** | +9.21% | +0.20% |
| HBase 2.6.1 | 2,588 | 397.1 MiB | 12.05 s | 0.43 s | 1.15 s | **28.1×** | **10.46×** | +10.58% | +0.16% |
| Hadoop 3.4.0¹ | 20,220 | 1,681.2 MiB | 172.93 s | 6.92 s | 17.72 s | **25.0×** | **9.76×** | +12.17% | +0.05% |
| ZooKeeper 3.9.3 | 1,632 | 46.5 MiB | 1.50 s | 0.14 s | 0.17 s | **10.8×** | **8.72×** | +4.75% | −0.00% |

¹ Ran with `-Xmx4g` like every other row here, and needed it — this is the 20k-file,
1.7 GiB corpus. Windows `tar` couldn't materialize 3 native-library symlinks during
extraction (relative symlinks to versioned `.so.x.y.z` targets); affected all three
columns identically, so the comparison is still apples-to-apples.

**Same pattern here as the in-build suite, at tighter tolerances**: `skipAlreadyCompressed=true`
costs +4.0% to +12.2% archive size across all eleven corpora for 7×–46× speedup.
`skipAlreadyCompressed=false` stays within **±1% of stock in every single corpus** here
(−0.31% to +0.93%) — even tighter than the in-build suite — while still delivering a real
4×–14× speedup from parallelism and the native libdeflate accelerator alone, no size cost
worth mentioning. If you want speed with a near-zero size footprint, this is the mode.

**`store = true`** (STORE everything, no DEFLATE attempt at all, independent of
`skipAlreadyCompressed`) is the fastest possible mode but trades size far more
aggressively, and *how much* depends entirely on how compressible the content already is —
from the 1.4.0 codec benchmark on these same eleven corpora: **+4.1% (Kafka) to +107.0%
(ZooKeeper)**, with most projects in the +7–22% range and Hadoop a +77.4% outlier. There is
no single representative number for STORE mode — check your own project's content mix, or
default to `skipAlreadyCompressed` (`true` or `false`) instead, which never costs more than
about +17% in the worst case measured above.

See also: [How it works](ARCHITECTURE.md) · [Compatibility](COMPATIBILITY.md) ·
[Reproducibility](REPRODUCIBILITY.md) · [Development](DEVELOPMENT.md)
