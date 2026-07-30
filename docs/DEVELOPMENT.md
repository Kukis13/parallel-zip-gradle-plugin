# Development

```bash
./gradlew build        # compile + run the functional tests
./gradlew publishToMavenLocal
```

`./gradlew build` also runs `compileNativeDeflate`, which needs a C toolchain (MSVC, or
MinGW + Ninja/CMake on Windows) on `PATH`. If none is found, it **silently skips** and
logs `parallel-zip: skipping native libdeflate build for <classifier> (...)`  — the
build still succeeds, just falling back to the pure-Java `Deflater` for that run. If
you're working on the native code itself, check for that message (or grep the test
report for `skipped="0"` in `LibdeflateNativeTest`) rather than assuming a green build
means the native path was actually exercised.

## Contributing

PRs welcome — bug fixes, new platform support, benchmarks against other projects,
anything. Just lead with a strong **why**: what's slow or broken today, and evidence
it's better after.

See also: [How it works](ARCHITECTURE.md) · [Benchmarks](BENCHMARKS.md) ·
[Compatibility](COMPATIBILITY.md) · [Reproducibility](REPRODUCIBILITY.md)
