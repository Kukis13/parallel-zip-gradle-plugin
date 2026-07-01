# Benchmarks

Eleven popular open-source Java projects' official binary distributions, archived four
ways — Gradle `Zip` (baseline), `parallel-zip` DEFLATE (JDK codec), DEFLATE
(libdeflate), and STORE. Same machine: 12 logical cores, JDK 21, warm cache.

| Project | Files | Raw size | Gradle `Zip` | parallel-zip DEFLATE (JDK) | parallel-zip DEFLATE (libdeflate) | parallel-zip STORE | STORE size Δ |
|---|--:|--:|--:|--:|--:|--:|--:|
| ZooKeeper 3.9.3 | 1,632 | 50.6 MiB | 0.93 s | 0.32 s (**2.9×**) | 0.25 s (**3.7×**) | 0.24 s (**3.9×**) | +107.4% |
| Cassandra 4.1.7 | 200 | 57.5 MiB | 1.33 s | 0.48 s (**2.8×**) | 0.38 s (**3.5×**) | 0.13 s (**10.0×**) | +18.6% |
| Kafka 3.8.1 | 235 | 121.1 MiB | 2.62 s | 1.36 s (**1.9×**) | 1.32 s (**2.0×**) | 0.28 s (**9.4×**) | +4.1% |
| Groovy 4.0.24 | 9,756 | 271.6 MiB | 4.07 s | 4.77 s (0.85×, slower) | 4.50 s (0.90×, slower) | 4.32 s (0.94×, slower) | +273.6% |
| Solr 9.7.0 | 2,091 | 308.5 MiB | 6.68 s | 2.28 s (**2.9×**) | 1.94 s (**3.4×**) | 0.74 s (**9.0×**) | +12.4% |
| HBase 2.6.1 | 2,588 | 404.8 MiB | 8.33 s | 2.52 s (**3.3×**) | 2.41 s (**3.5×**) | 1.02 s (**8.2×**) | +22.4% |
| Spark 3.5.3 | 1,825 | 427.9 MiB | 9.22 s | 4.44 s (**2.1×**) | 4.24 s (**2.2×**) | 1.03 s (**9.0×**) | +10.4% |
| Gradle 8.14.3 | 22,427 | 500.2 MiB | 9.86 s | 4.74 s (**2.1×**) | 4.26 s (**2.3×**) | 3.30 s (**3.0×**) | +108.6% |
| Flink 1.20.0 | 167 | 502.8 MiB | 10.76 s | 5.05 s (**2.1×**) | 5.03 s (**2.1×**) | 1.05 s (**10.2×**) | +8.9% |
| SonarQube Community Build 26.6 | 749 | 966.6 MiB | 20.93 s | 6.56 s (**3.2×**) | 6.25 s (**3.4×**) | 2.00 s (**10.5×**) | +9.2% |
| Hadoop 3.4.0¹ | 20,220 | 1,733.6 MiB | 33.09 s | 21.19 s (**1.6×**) | 21.90 s (1.5×) | 6.02 s (**5.5×**) | +77.5% |

¹ Hadoop's raw size and file count needed `-Xmx4g`; every other row here runs on the
default 1g heap used for this benchmark. It's also the clearest case where libdeflate
doesn't help: a few individual files (500+ MiB) dominate its wall time and always stream
through the JDK codec (see the [README](README.md#options)), so the libdeflate column is a wash.

DEFLATE never produces a bigger archive than the baseline in practice, so it's the safe
default. The one exception is Groovy: many small, already-cheap-to-compress files, where
per-entry pipeline overhead outweighs the compression saved — the only corpus here where
DEFLATE is slower than single-threaded `Zip`. Use `store = true` only for archives you
already know are jar/binary-heavy, where STORE's size cost is small and the speedup large.
