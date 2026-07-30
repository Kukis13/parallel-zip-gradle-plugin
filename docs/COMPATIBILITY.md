# Compatibility

Gradle 8 and Gradle 9, JDK 17+. Verified directly against our own CI's Gradle 8.14.3 and
against a fresh Gradle 9.6.1 project (including `--configuration-cache`); not exhaustively
tested across every minor release, but the plugin only uses stable, non-deprecated
`AbstractArchiveTask`/`CopySpec` APIs, so other 8.x/9.x versions are expected to work too.

`ParallelZip` is `@CacheableTask` (as of 1.4.1) and works with `org.gradle.caching=true` /
`--build-cache` — an unchanged rebuild restores the archive from the build cache instead
of re-executing the task at all. Verified with a real functional test (populate the
cache, wipe local build state, rerun, assert the outcome is `FROM_CACHE`), not just the
annotation's presence. See [How it works](ARCHITECTURE.md#build-cache).

See also: [How it works](ARCHITECTURE.md) · [Benchmarks](BENCHMARKS.md) ·
[Reproducibility](REPRODUCIBILITY.md) · [Development](DEVELOPMENT.md)
