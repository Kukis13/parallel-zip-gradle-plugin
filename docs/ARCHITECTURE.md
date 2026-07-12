# How it works

## Why

Gradle's built-in `Zip` task is **single-threaded** and re-DEFLATEs everything, even
content that is already compressed (jars, `.gz`, images). For large, jar-heavy
distributions that makes archiving one of the slowest tasks in the build.

Gradle has had an open request to build archives in parallel since 2017
([gradle/gradle#2774](https://github.com/gradle/gradle/issues/2774)) — a spike showed
~40% gains using Apache Commons Compress + the worker pool, but it was never shipped,
partly because parallel writing breaks reproducible entry order. This plugin keeps a
**fixed write order** so parallelism never affects the output bytes, which sidesteps
that blocker (see [Reproducibility](REPRODUCIBILITY.md)).

## Native accelerator

On `linux-x64`, `linux-arm64`, `windows-x64`, `windows-arm64`, `macos-arm64` and
`macos-x64`, DEFLATE runs through a bundled native
[libdeflate](https://github.com/ebiggers/libdeflate) build instead of the JDK's
`Deflater` — faster, and within ~0.5% of the same archive size in practice (see
[Benchmarks](BENCHMARKS.md)). Small entries are compressed straight from a JVM byte array;
large entries (above 8 MiB, up to 128 MiB) are compressed straight from a memory-mapped
view of the source file instead, with no `Files.readAllBytes` copy in between. Either
way, CRC-32 is computed natively in the same pass as compression, not as a separate scan.
libdeflate has no streaming API, so only entries past 128 MiB still stream through the
JDK `Deflater`. Every other platform/arch, or any failure loading the native build or
memory-mapping the file, falls back to the pure-Java path automatically.

## Small-entry optimizations

Two more optimizations for archives with lots of small entries, both always on with no
configuration needed:

- **Small entries are batched** into fewer compression tasks, amortizing per-task
  scheduling overhead across many small files.
- **Unfiltered entries are read lazily**, on a worker thread at compress time, instead
  of eagerly on Gradle's single-threaded copy walk — this is the bigger win of the two.
  Gradle's own `FileCopyDetails` exposes the real source file only when no content
  filter is configured (`getFile()` and `open()` share the identical guard), so this
  never touches filtered entries; a filtered file is still read on the walk, since
  Gradle's filter chain has to run through its own API.

Together, on many-small-file archives, these turned what used to be the one case where
DEFLATE was *slower* than single-threaded `Zip` into one of the largest speedups
measured (see [Benchmarks](BENCHMARKS.md)).

## Safety nets

Per-entry: if DEFLATE would make an entry *larger* (already-compressed data), that
entry is automatically STORED instead, so output is never bigger than necessary.

Archive-level: archives beyond the standard ZIP limits — over 4 GiB, over 65,535
entries, or per-entry offsets/sizes beyond 4 GiB — automatically get ZIP64 extra fields
and end-of-central-directory records, validated against both `java.util.zip` and a
non-Java (.NET) reader.

See also: [Benchmarks](BENCHMARKS.md) · [Compatibility](COMPATIBILITY.md) ·
[Reproducibility](REPRODUCIBILITY.md) · [Development](DEVELOPMENT.md)
