# Reproducibility

With `preserveFileTimestamps = false`, the archive is **byte-for-byte identical** across
runs and across thread counts (verified: SHA-256 is stable for 1, 8 and 12 threads).
Compression runs in parallel but entries are always written in a fixed order (Gradle's
resolved copy order, honouring `reproducibleFileOrder`), so scheduling never affects the
bytes. For fully reproducible builds also ensure identical file **contents** and the same
**JDK** (the DEFLATE codec must match); `store = true` sidesteps the codec dependency.
The native libdeflate accelerator is an additional codec dependency on top of the JDK:
builds on the six platforms listed in [How it works](ARCHITECTURE.md) use it, builds
elsewhere fall back to the JDK `Deflater`, so byte-identical output across *different
platforms* also requires both sides to have (or both lack) the native accelerator —
same-platform reproducibility is unaffected either way.

See also: [How it works](ARCHITECTURE.md) · [Benchmarks](BENCHMARKS.md) ·
[Compatibility](COMPATIBILITY.md) · [Development](DEVELOPMENT.md)
