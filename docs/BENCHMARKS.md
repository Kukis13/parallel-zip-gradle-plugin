# Benchmarks

## In-build benchmarks (real projects, real Zip tasks)

The most direct evidence for this plugin isn't archiving a static directory tree — it's
what happens when you actually swap `type: Zip` for `type: ParallelZip` in a real
project's real build. For each project below: clone it, build once with its stock `Zip`
task, add a second task with identical configuration but `ParallelZip`, then build again
and diff the two tasks' own execution time (via `doFirst`/`doLast`
`System.nanoTime()` hooks, or Gradle's `--profile` report where hooks weren't added —
see notes). Everything else in the build (compilation, resource processing, dependency
resolution) was warm/cached in every run, so only the archiving step itself is being
compared. Same machine: 12 logical cores, JDK 17/21/25 (whichever each project's build
required), each project measured in isolation (no other Gradle daemons running).

As of the 1.4.0 refresh, every row compares three points: stock Gradle `Zip`, the real
published **v1.3.0** jar (all six platforms' native accelerators, exactly as released),
and the new **v1.4.0** jar (adds magic-byte already-compressed detection, a Windows
mmap file-lock fix, and an OOM guard for small Gradle daemon heaps — see
[the 1.4.0 release notes](https://github.com/Kukis13/parallel-zip-gradle-plugin/releases/tag/v1.4.0)).

| Project | Task | Stock `Zip` | ParallelZip 1.3.0 | ParallelZip 1.4.0 | Speedup 1.3.0 | Speedup 1.4.0 |
|---|---|--:|--:|--:|--:|--:|
| JBake | `jbake-dist:distZip` | 2.338 s | 0.325 s | 0.046 s | **7.20×** | **50.53×** |
| Groovy 4.0.24 | `groovy-binary:distBin` | 1.824 s | 0.186 s | 0.041 s | **9.83×** | **44.16×**³ |
| Micronaut Starter (Launch) CLI | `distZip` | 0.778 s | 0.072 s | 0.020 s | **10.86×** | **39.32×** |
| Gradle Profiler | `distZip` | 0.803 s | 0.137 s | 0.024 s | **5.86×** | **33.46×** |
| Gradle (the build tool) | `distributions-full:binDistributionZip` | 4.026 s | 0.820 s | 0.142 s | **4.91×** | **28.35×** |
| JBang | `distZip` | 0.296 s | 0.143 s | 0.012 s | **2.07×** | **25.47×** |
| Spring Boot CLI | `cli:spring-boot-cli:zip` | 0.175 s | 0.073 s | 0.007 s | **2.40×** | **24.54×** |
| Grails CLI | `grails-shell-cli:distZip` | 1.288 s | 0.161 s | 0.061 s | **8.02×** | **21.2×** |
| SonarQube (Community Build) | `sonar-application:zip` | 23.31 s | 5.56 s | 3.96 s | **4.19×** | **5.89×** |

Geometric-mean speedup across these nine real production Zip tasks: **~5.4×** for
v1.3.0, **~26.5×** for v1.4.0 — most of that jump is the new magic-byte skip paying off
hardest on exactly the kind of distribution these projects ship: a pile of already-
compressed jars and nested archives that v1.3.0 was still faithfully (and pointlessly)
re-deflating. See `benchmarks/results/gradle-inbuild.tsv` for the raw numbers (including
byte counts) behind every cell above.

**Archive size, honestly**: v1.3.0's output tracked stock `Zip` within ~0.3% on every
project above — essentially free. **v1.4.0 does not preserve that**: it's consistently
**6–17% larger** than both stock and v1.3.0 across all nine projects (worst case: Spring
Boot CLI, +16.8%; best case: SonarQube, +6.3%). This is the direct, expected cost of the
magic-byte skip — it STOREs content it recognizes as already-compressed (jars, gzip,
images, etc.) rather than paying to re-deflate it, even in cases where DEFLATE would
still have squeezed out a percent or two more than the plugin now leaves on the table.
The trade is real and by design, not a defect: see
[How it works](ARCHITECTURE.md#already-compressed-detection) for why skipping that work
is usually worth more in CPU-seconds than it costs in bytes. If it costs you more than
it's worth on a particular archive, set `skipAlreadyCompressed = false` on the task to
fall back to always attempting DEFLATE (closer to 1.3.x's size, at the cost of most of
this speedup) — or `store = true` if you want the opposite: STORE everything, no
DEFLATE attempt at all, maximum speed, maximum size.

Measurement confidence varies by row — most are now medians of 3–5 warm readings with
the first (cold/compiling) reading explicitly discarded, following the methodology below.
Two rows still carry a caveat:

- **Groovy**³ is a sub-2-second task on this machine and showed real run-to-run swings
  of ±40% or more even among "warm" readings — the 44.16× figure is directional, not
  precise to more than one significant figure.
- **JBang and Micronaut's ParallelZip readings are sub-30ms**, where JVM/JIT warm-up
  noise is a large fraction of the signal — the relative ordering (huge speedup) is
  solid, the exact multiple less so.

- **Micronaut and JBake show the *smallest* additional 1.3.0→1.4.0 jump on the codec/
  batching side** relative to the earlier 1.1.0→1.3.0 native/mmap/CRC-fusion work (see
  the historical `benchmarks/results/gradle-inbuild.tsv` if comparing further back) —
  most of their earlier win was already banked. What v1.4.0 adds on top is almost
  entirely the magic-byte skip, and it still roughly doubles their already-large speedup
  because their distributions are dense with small already-compressed jars.
- **`--profile`'s per-task number includes Gradle's own bookkeeping** (up-to-date
  checks, snapshotting), not just the task's own execution — for fast tasks this can be
  a large fraction of the reported time. Prefer `doFirst`/`doLast` hooks for anything
  under ~1s.

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

Notes on measurement method per row:

- **Gradle (the tool)** enables Isolated Projects, which forces configuration cache on
  and configuration cache doesn't support `--profile`; timed with `doFirst`/`doLast`
  `System.nanoTime()` hooks instead. Configuration cache also rejects `project.ext` as a
  channel between a task's `doFirst` and `doLast` (fails with an "invalid reference"
  error at execution time) — use `System.getProperties()` instead when timing a
  config-cache-enabled project this way.
- **JBake**'s `--profile` report failed to write for an unrelated environment reason
  (`Unable to create directory 'reports\profile'`) on this machine; also timed with
  `doFirst`/`doLast` hooks. Its Gradle 7.3.3 wrapper needs a JDK 17 daemon (fails to
  even start under JDK 21+).
- **Grails CLI**, **Micronaut Starter CLI**, and **Spring Boot CLI** required specific
  JDKs (17, 25, and 25 respectively) for the Gradle *daemon itself*, not just a
  toolchain — export `JAVA_HOME` before invoking `gradlew` rather than relying on the
  default.

## Fixed-corpus benchmarks (static directory tree, four codecs)

An earlier, complementary methodology: eleven popular open-source Java projects'
official binary distributions, archived four ways — Gradle `Zip` (baseline),
`parallel-zip` DEFLATE (JDK codec), DEFLATE (libdeflate), and STORE — using a fixed
staging directory so only the archiving algorithm varies, not each project's own
compile/download chain. All four `parallel-zip` columns run the same v1.4.0 build (the
JDK-codec column uses a jar built without the native accelerator bundled, forcing the
pure-Java fallback; the libdeflate and STORE columns use the normal native-accelerated
jar). Same machine: 12 logical cores, JDK 21, warm cache, `-Xmx4g` daemon for every
project (including the small ones, for consistency).

| Project | Files | Raw size | Gradle `Zip` | parallel-zip DEFLATE (JDK) | parallel-zip DEFLATE (libdeflate) | parallel-zip STORE | STORE size Δ |
|---|--:|--:|--:|--:|--:|--:|--:|
| Groovy 4.0.24 | 102 | 31.8 MiB | 0.69 s | 0.03 s (**27.29×**) | 0.06 s (**12.32×**) | 0.02 s (**32.75×**) | +11.3% |
| ZooKeeper 3.9.3 | 1,632 | 46.5 MiB | 0.98 s | 0.18 s (**5.40×**) | 0.19 s (**5.04×**) | 0.15 s (**6.67×**) | +107.0% |
| Cassandra 4.1.7 | 200 | 57.1 MiB | 1.32 s | 0.07 s (**19.33×**) | 0.07 s (**19.53×**) | 0.03 s (**41.58×**) | +18.6% |
| Kafka 3.8.1 | 235 | 120.4 MiB | 2.67 s | 0.08 s (**35.57×**) | 0.09 s (**29.65×**) | 0.06 s (**43.28×**) | +4.1% |
| Gradle 8.14.3 | 317 | 145.1 MiB | 3.02 s | 0.07 s (**44.76×**) | 0.10 s (**31.80×**) | 0.06 s (**48.57×**) | +10.7% |
| Solr 9.7.0 | 2,091 | 304.4 MiB | 7.39 s | 0.29 s (**25.32×**) | 0.28 s (**26.42×**) | 0.29 s (**25.81×**) | +12.4% |
| HBase 2.6.1 | 2,588 | 397.1 MiB | 10.81 s | 0.56 s (**19.40×**) | 0.64 s (**16.78×**) | 0.65 s (**16.73×**) | +22.4% |
| Spark 3.5.3 | 1,825 | 423.6 MiB | 9.55 s | 0.50 s (**19.20×**) | 0.41 s (**23.18×**) | 0.32 s (**29.88×**) | +10.4% |
| Flink 1.20.0 | 167 | 502.4 MiB | 12.03 s | 0.32 s (**37.63×**) | 0.34 s (**35.58×**) | 0.30 s (**39.86×**) | +8.9% |
| SonarQube Community Build 26.7.0.124771² | 645 | 931.0 MiB | 18.90 s | 0.53 s (**35.78×**) | 0.44 s (**42.71×**) | 0.41 s (**46.28×**) | +7.6% |
| Hadoop 3.4.0¹ | 20,220 | 1.64 GiB | 38.81 s | 8.35 s (**4.65×**) | 7.99 s (**4.86×**) | 3.84 s (**10.11×**) | +77.4% |

¹ Hadoop ran with `-Xmx4g` like every other row here, and needed it — this is the
20k-file, 1.7 GiB corpus. It's also the one corpus where both DEFLATE codecs gain the
least (4.6–4.9× vs. 12–49× everywhere else): at this file count and size the workload
shifts from CPU-bound (compression-dominated) to I/O-bound (reading 20k small-to-medium
files off disk dominates wall time), which compresses the gap between codecs — not a
regression, just a different bottleneck. Windows `tar` also couldn't materialize 3 of
Hadoop's native-library symlinks (`lib/native/lib{hdfs,hadoop,hdfspp}.so`, relative
symlinks to versioned `.so.x.y.z` targets) during extraction; this affected all four
columns identically (20,220 files landed in every archive, not 20,223), so the
four-way comparison is still apples-to-apples, just slightly short of Hadoop's full
official file count.

² A rolling snapshot build, not a fixed release — file count and size will drift between
re-benchmarks as it moves forward, unlike the other ten rows. This run used build
26.7.0.124771, the latest available at benchmark time.

DEFLATE now trades a small amount of archive size (up to ~12% here, on HBase; Hadoop's
+77.4% is an outlier explained by its unusually high proportion of small, individually
incompressible or tiny files) for a large speed win: a magic-byte and incompressibility
sniff skips fully compressing entries that are already compressed or wouldn't shrink
much anyway, so it's still the safe default — every corpus above is a clear win on both
codecs, size cost included. STORE trades size for speed more aggressively, and how much
size depends entirely on how compressible the content already is: use `store = true`
only for archives you already know are jar/binary-heavy, where the size cost is small
and the speedup large.

See also: [How it works](ARCHITECTURE.md) · [Compatibility](COMPATIBILITY.md) ·
[Reproducibility](REPRODUCIBILITY.md) · [Development](DEVELOPMENT.md)
