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

| Project | Task | Stock `Zip` | `ParallelZip` | Speedup |
|---|---|--:|--:|--:|
| Micronaut Starter (Launch) CLI | `distZip` | 0.757 s | 0.070 s | **10.89×** |
| JBake | `jbake-dist:distZip` | 2.915 s | 0.290 s | **10.05×** |
| Gradle Profiler | `distZip` | 0.815 s | 0.169 s | **4.82×** |
| Gradle (the build tool) | `distributions-full:binDistributionZip` | 3.690 s | 0.815 s | **4.53×** |
| Groovy 4.0.24 | `groovy-binary:distBin` | 0.979 s | 0.241 s | **4.06×** |
| JBang | `distZip` | 0.404 s | 0.126 s | **3.21×** |
| SonarQube 26.6 | `sonar-application:zip` | 24.991 s | 8.520 s | **2.93×** |
| Spring Boot CLI | `cli:spring-boot-cli:zip` | 0.273 s | 0.096 s | **2.84×** |
| Grails CLI | `grails-cli:distZip` | 1.372 s | 0.662 s | **2.07×** |

Geometric-mean speedup across these nine real production Zip tasks: **~4.3×**. Archive
sizes matched within ~1–5% between the stock and parallel runs in every case (see
`benchmarks/results/gradle-inbuild.tsv` for the raw byte counts) — the deltas are
expected DEFLATE-codec variance (native libdeflate vs. the JDK `Deflater`), not missing
content.

Measurement confidence varies by row — most `ParallelZip` readings above are the median
of 2–3 isolated runs (Spring Boot CLI, JBang, Grails, Gradle-tool all confirmed via
repeats; the last showed real run-to-run variance of up to 2× even with the *same* jar,
apparently from Gradle's own task-graph overhead on such a large multi-project build,
not the archiving step). JBake, Groovy, Micronaut, and every `Zip` (stock) row are single
readings — treated as reliable where the gap is large (an order of magnitude, like
JBake/Micronaut, isn't noise) but worth another pass before quoting to two decimal
places. Two more things worth knowing before reading too much into any single row:

- **Micronaut and JBake show the *smallest* additional speedup from the native/mmap/CRC-fusion
  work** (10.89× and 10.05× vs. stock, but only ~1.0–1.6× faster than the earlier codec
  path) — most of their win was already banked by the earlier batching and
  incompressibility sniff. Their distributions bundle many small, already-compressed jars,
  exactly the shape those two features target.
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
  `System.nanoTime()` hooks instead.
- **JBake**'s `--profile` report failed to write for an unrelated environment reason
  (`Unable to create directory 'reports\profile'`) on this machine; also timed with
  `doFirst`/`doLast` hooks.
- **Grails CLI** and **Micronaut Starter CLI** required specific JDKs (17 and 25
  respectively) to satisfy their own build's toolchain requirements — unrelated to
  `parallel-zip`.

## Fixed-corpus benchmarks (static directory tree, four codecs)

An earlier, complementary methodology: eleven popular open-source Java projects'
official binary distributions, archived four ways — Gradle `Zip` (baseline),
`parallel-zip` DEFLATE (JDK codec), DEFLATE (libdeflate), and STORE — using a fixed
staging directory so only the archiving algorithm varies, not each project's own
compile/download chain. Same machine: 12 logical cores, JDK 21, warm cache.

| Project | Files | Raw size | Gradle `Zip` | parallel-zip DEFLATE (JDK) | parallel-zip DEFLATE (libdeflate) | parallel-zip STORE | STORE size Δ |
|---|--:|--:|--:|--:|--:|--:|--:|
| ZooKeeper 3.9.3 | 1,632 | 50.6 MiB | 0.75 s | 0.15 s (**4.97×**) | 0.08 s (**9.16×**) | 0.03 s (**25.03×**) | +107.0% |
| Cassandra 4.1.7 | 200 | 57.5 MiB | 1.22 s | 0.35 s (**3.49×**) | 0.27 s (**4.55×**) | 0.03 s (**39.23×**) | +18.9% |
| Kafka 3.8.1 | 235 | 121.1 MiB | 2.38 s | 0.35 s (**6.86×**) | 0.25 s (**9.67×**) | 0.06 s (**40.34×**) | +3.5% |
| Groovy 4.0.24 | 9,757 | 271.6 MiB | 3.25 s | 1.22 s (**2.66×**) | 0.85 s (**3.82×**) | 0.19 s (**17.46×**) | +271.4% |
| Solr 9.7.0 | 2,091 | 308.5 MiB | 5.97 s | 1.71 s (**3.49×**) | 1.34 s (**4.46×**) | 0.17 s (**34.32×**) | +12.1% |
| HBase 2.6.1 | 2,588 | 404.8 MiB | 7.32 s | 2.12 s (**3.45×**) | 1.95 s (**3.75×**) | 0.20 s (**37.34×**) | +22.1% |
| Spark 3.5.3 | 1,825 | 427.9 MiB | 8.25 s | 2.90 s (**2.84×**) | 2.65 s (**3.12×**) | 0.22 s (**37.17×**) | +9.8% |
| Flink 1.20.0 | 167 | 502.8 MiB | 9.71 s | 2.93 s (**3.31×**) | 3.03 s (**3.20×**) | 0.24 s (**40.28×**) | +8.5% |
| Gradle 8.14.3 | 22,432 | 500.3 MiB | 7.82 s | 2.37 s (**3.29×**) | 1.76 s (**4.44×**) | 0.37 s (**21.24×**) | +107.3% |
| SonarQube Community Build 26.7-SNAPSHOT² | 610 | 927.9 MiB | 17.67 s | 4.32 s (**4.09×**) | 4.13 s (**4.27×**) | 0.45 s (**39.45×**) | +6.5% |
| Hadoop 3.4.0¹ | 20,220 | 1,733.6 MiB | 29.06 s | 18.04 s (**1.61×**) | 17.57 s (**1.65×**) | 0.97 s (**29.84×**) | +77.2% |

¹ Hadoop needed `-Xmx4g` to enumerate and hold its 20k-entry, 1.7 GiB corpus (every row
in this table ran with `-Xmx4g` for consistency). It's also the corpus where both
DEFLATE codecs gain the least: a handful of individual files (500+ MiB) dominate its
wall time and always stream through the JDK codec regardless of which fast-path codec
handles the smaller entries (see [How it works](ARCHITECTURE.md)).

² A rolling snapshot build, not a fixed release — file count and size will drift between
re-benchmarks as it moves forward, unlike the other ten rows.

DEFLATE now trades a small amount of archive size (up to ~0.8% here, on SonarQube) for a
large speed win: an incompressibility sniff skips fully compressing large entries that
wouldn't shrink much anyway, so it's still the safe default — every corpus above is a
clear win on both codecs, size cost included. STORE trades size for speed more
aggressively, and how much size depends entirely on how compressible the content already
is: use `store = true` only for archives you already know are jar/binary-heavy, where the
size cost is small and the speedup large.

See also: [How it works](ARCHITECTURE.md) · [Compatibility](COMPATIBILITY.md) ·
[Reproducibility](REPRODUCIBILITY.md) · [Development](DEVELOPMENT.md)
