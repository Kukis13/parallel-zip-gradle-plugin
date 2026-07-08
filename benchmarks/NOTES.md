# In-build benchmark notes

Raw results: `results/gradle-inbuild.tsv`, measured against jars built from
`perf/mmap-and-crc32-fusion`. Jars aren't committed here (they'd go stale against source)
— rebuild before a rerun with, from the plugin root: `./gradlew jar` for the JDK-only
variant (no C toolchain on `PATH`), and again with a MinGW `gcc`/`cmake` on `PATH` for
the native-libdeflate variant. Point each project's injected `buildscript { dependencies
{ classpath files('...') } }` block at whichever jar you're benchmarking.

Lessons learned doing this by hand for 9 projects, worth encoding if this gets automated:

- **Only Gradle projects with a real `Zip`-typed task qualify.** Most large distributable
  Java server projects are Maven/Ant/SBT. Even among Gradle projects, many ship `.tar`/
  `.tar.gz` instead (Kafka, Solr's primary release), which this plugin can't touch (it's
  ZIP-specific). Check with `grep -rE "type\s*:\s*Zip|<Zip>\(|distZip\b"` across
  `*.gradle`/`*.gradle.kts` before committing to a project.
- **Multi-module project paths rarely match directory paths.** `include 'foo:bar'` maps
  to `foo/bar` by convention, but plenty of projects remap names (Micronaut Starter:
  `starter-cli` directory but `project(':starter-cli').name = "micronaut-cli"` — the real
  task path is `:micronaut-cli:distZip`) or use custom directory-mapping DSLs. Always
  confirm with `./gradlew projects` before assuming a path.
- **`--profile` isn't always available.** Isolated Projects forces configuration cache
  on, and configuration cache doesn't support `--profile` (Gradle build). Fall back to
  `doFirst`/`doLast` `System.nanoTime()` hooks added directly to both tasks — works
  everywhere, and lets both tasks' timing print in one `println` grep pass instead of
  parsing an HTML report.
- **Pin the JDK per project.** Several projects require a specific JDK the daemon runs
  on (Grails CLI: 17; Spring Boot CLI / Micronaut Starter: 25 for the daemon itself, not
  just a toolchain) — export `JAVA_HOME` before invoking `gradlew`, don't rely on the
  default.
- **Windows long-path limits break shallow clones of large repos** (Kafka, Elasticsearch,
  Spring Boot, Gradle, Beam, Micronaut Core all hit `Filename too long`). Clone with
  `git -c core.longpaths=true clone --depth 1 ...`.
- **Where to inject the plugin jar**: a per-project `buildscript { dependencies {
  classpath files('...') } }` block at the top of whichever `build.gradle`(`.kts`) owns
  the real Zip task (or its `buildSrc`/precompiled-script-plugin module if the task is
  defined there) is the least invasive approach — no need to touch `settings.gradle` or
  fight the project's own plugin resolution / version catalogs.
- **Duplicate the task, don't replace it.** Register a second task (`parZip`,
  `parDistZip`, …) with identical `CopySpec` configuration (`with someSharedSpec`
  where the project already factors one out; otherwise copy the whole task body) rather
  than mutating the original — keeps both numbers comparable from the same build/cache
  state and is trivially revertible (these are all throwaway clones under scratchpad,
  never committed upstream).
- **Some real tasks are just broken on current HEAD** (Corda's `buildCordappDependenciesZip`
  resolves a non-resolvable configuration, unrelated to this plugin). Don't burn time
  patching bit-rotted tasks in unrelated projects — note it and move on.
- **Some builds are simply too heavy to be worth it** (Kotlin/Native's compiler build).
  Time-box the investigation; if a project needs many minutes just to reach the target
  task, it's a bad fit for a benchmark suite meant to be re-run regularly.
- **Single-shot task timing is not trustworthy — always take 3+ readings and use the
  median.** Confirmed this the hard way comparing v1.1.0 against a later branch: fast
  tasks (Micronaut Starter CLI, ~100ms) swung by ±40% between consecutive runs of the
  *same* jar, enough to flip which version looked faster. Large multi-project builds are
  just as bad for a different reason — gradle/gradle's own `binDistributionZip` varied
  from 806ms to 1896ms across three isolated runs of the same jar (>2x), apparently from
  variance elsewhere in its huge task graph rather than the archiving step itself. A
  single reading can show either version "winning" by a wide margin; only the median of
  several is meaningful. Concurrent Gradle daemons from other benchmark runs make this
  much worse (one run read 11.1s that a clean rerun later showed was really ~8.5s) — run
  one project at a time, `--stop` daemons between projects if unsure.
- **`--profile`'s per-task number includes Gradle's own bookkeeping** (up-to-date
  checks, snapshotting, etc.), not just the task's own `Action` execution — for a fast
  task this fixed overhead can be a big fraction of the reported time (seen: `--profile`
  reported 127ms for a task whose `doFirst`/`doLast` hooks measured 67ms of actual work).
  Prefer the hooks for anything under ~1s; for slower tasks the discrepancy is
  proportionally small enough not to matter.

If/when this becomes a real one-command framework, the automatable parts are: cloning at
a pinned tag, building the two plugin jars, running `./gradlew projects` to resolve the
real task path, running both tasks with timing hooks, and aggregating results. The part
that resists automation is identifying *which* task is the real one and writing its
`ParallelZip` twin — that needs a human to read each project's build once.
