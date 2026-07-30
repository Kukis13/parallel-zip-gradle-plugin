# Benchmarks

Every table on this page compares **three points**, all on the same 1.4.1 build so only
the `skipAlreadyCompressed` flag varies, not plugin version: stock Gradle `Zip`, `ParallelZip`
with `skipAlreadyCompressed = true` (the default), and `skipAlreadyCompressed = false`. The
flag matters enough for both speed *and* size that any single number without saying which
mode it's from is misleading — so every size or speed claim on this page, and in the README,
names its mode.

## In-build benchmarks (real projects, real Zip tasks)

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
| JBake | `jbake-dist:distZip` | 2.965 s | 0.069 s | 0.368 s | **43.2×** | **8.06×** | +7.45% | +0.24% |
| Gradle Profiler | `distZip` | 1.078 s | 0.037 s | 0.184 s | **29.1×** | **5.86×** | +8.30% | −0.23% |
| Gradle (the build tool) | `distributions-full:binDistributionZip` | 4.001 s | 0.138 s | 0.846 s | **29.0×** | **4.73×** | +12.36% | +0.93% |
| Spring Boot CLI | `cli:spring-boot-cli:zip` | 0.251 s | 0.010 s | 0.098 s | **25.7×** | **2.57×** | +16.84% | −0.29% |
| JBang | `distZip` | 0.376 s | 0.017 s | 0.163 s | **22.2×** | **2.31×** | +10.86% | −0.30% |
| Grails CLI | `grails-shell-cli:distZip` | 1.841 s | 0.083 s | 0.178 s | **22.1×** | **10.35×** | +11.05% | −0.22% |
| Groovy 4.0.24 | `groovy-binary:distBin` | 1.380 s | 0.067 s | 0.254 s | **20.5×** | **5.43×** | +10.72% | −0.23% |
| Micronaut Starter (Launch) CLI | `distZip` | 1.047 s | 0.072 s | 0.089 s | **14.5×** | **11.8×** | +13.21% | +5.00% |
| SonarQube (Community Build) | `sonar-application:zip` | 23.31 s | 3.96 s | 5.56 s | **5.89×** | **4.19×** | +6.26% | +0.77% |

Geometric-mean speedup over stock `Zip` across these nine tasks: **~21.0×** with
`skipAlreadyCompressed=true`, **~5.37×** with it set `false`. Both modes are a clear win
over stock every single time — the choice between them is purely about how much archive
size you're willing to trade for the difference between "very fast" and "extremely fast."

**Size, both modes, no cherry-picking**: `skipAlreadyCompressed=true` runs **6.3–16.8%**
larger than stock across all nine projects — the direct, consistent cost of STOREing
recognized-already-compressed content instead of attempting DEFLATE on it.
`skipAlreadyCompressed=false` stays within about **±1%** of stock in eight of the nine
projects (as tight as −0.30%, i.e. sometimes *smaller* than stock); Micronaut Starter CLI
is the one outlier at +5.00%, still far below `true` mode's cost on the same project
(+13.21%). If archive size matters more than shaving the last few hundred milliseconds off
an already-fast task, `skipAlreadyCompressed=false` is the mode to reach for.

Measurement confidence varies by row — all are medians of 3+ warm readings with the first
(cold/compiling) reading discarded, except Micronaut Starter CLI's `true`-mode reading,
which showed real run-to-run swings (30–100 ms range) at this sub-100ms scale — treat that
cell as directionally correct, not precise to more than one significant figure. JBang,
Grails, and Groovy's `true`-mode readings are similarly fast (tens of ms) and carry the
same caveat, though less severely.

Three projects from the original candidate list were dropped rather than forced in:

- **Corda**'s only `Zip`-typed task (`buildCordappDependenciesZip`) is broken on the
  current `master` branch independent of this plugin — it resolves a
  non-resolvable `testImplementation` configuration, a pre-existing bug unrelated to
  `parallel-zip`.
- **Kotlin/Native**'s candidate tasks (`distNativeSources`, `samplesZip`) sit behind the
  Kotlin/Native compiler's own build, one of the heaviest in the OSS Gradle ecosystem —
  it didn't finish in a reasonable amount of time on this machine.
- Every other candidate project investigated (Kafka, Solr, Elasticsearch, Micronaut Core,
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

## Fixed-corpus benchmarks (static directory tree)

A complementary methodology: eleven popular open-source projects' official binary
distributions, archived three ways — Gradle `Zip` (baseline), `parallel-zip` 1.4.1 with
`skipAlreadyCompressed=true`, and with it `false` — using a fixed staging directory so
only the archiving mode varies, not each project's own compile/download chain. Same
machine, JDK 21, warm cache, `-Xmx4g` daemon for every project for consistency (only
strictly required for Hadoop's ~1.7 GiB corpus).

| Project | Files | Raw size | Gradle `Zip` | `skipAlreadyCompressed=true` | `skipAlreadyCompressed=false` | Speedup (true) | Speedup (false) | Size Δ (true) | Size Δ (false) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| SonarQube Community Build | 645 | 931.1 MiB | 29.76 s | 0.65 s | 3.60 s | **46.1×** | **8.28×** | +6.45% | +0.73% |
| Gradle 8.14.3 | 317 | 145.1 MiB | 4.02 s | 0.09 s | 0.92 s | **45.3×** | **4.37×** | +10.73% | +0.86% |
| Groovy 4.0.24 | 102 | 31.8 MiB | 1.01 s | 0.03 s | 0.13 s | **38.4×** | **7.94×** | +10.92% | +0.03% |
| Cassandra 4.1.7 | 200 | 57.1 MiB | 1.99 s | 0.05 s | 0.23 s | **39.3×** | **8.79×** | +7.03% | −0.31% |
| Kafka 3.8.1 | 235 | 120.4 MiB | 3.35 s | 0.09 s | 0.23 s | **36.6×** | **14.47×** | +4.00% | +0.55% |
| Flink 1.20.0 | 167 | 502.4 MiB | 13.17 s | 0.39 s | 2.04 s | **33.9×** | **6.46×** | +8.84% | +0.24% |
| Spark 3.5.3 | 1,825 | 423.6 MiB | 12.78 s | 0.43 s | 1.90 s | **30.0×** | **6.73×** | +6.93% | +0.50% |
| Solr 9.7.0 | 2,091 | 304.4 MiB | 8.91 s | 0.33 s | 1.12 s | **26.9×** | **7.95×** | +9.21% | +0.20% |
| HBase 2.6.1 | 2,588 | 397.1 MiB | 12.05 s | 0.54 s | 1.40 s | **22.5×** | **8.59×** | +10.58% | +0.16% |
| Hadoop 3.4.0¹ | 20,220 | 1,681.2 MiB | 172.93 s | 8.61 s | 22.23 s | **20.1×** | **7.78×** | +12.17% | +0.05% |
| ZooKeeper 3.9.3 | 1,632 | 46.5 MiB | 1.50 s | 0.21 s | 0.26 s | **7.20×** | **5.67×** | +4.75% | −0.00% |

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
