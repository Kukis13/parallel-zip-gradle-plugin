# Benchmarks

Eleven popular open-source Java projects' official binary distributions, archived four
ways — Gradle `Zip` (baseline), `parallel-zip` DEFLATE (JDK codec), DEFLATE
(libdeflate), and STORE. Same machine: 12 logical cores, JDK 21, warm cache.

| Project | Files | Raw size | Gradle `Zip` | parallel-zip DEFLATE (JDK) | parallel-zip DEFLATE (libdeflate) | parallel-zip STORE | STORE size Δ |
|---|--:|--:|--:|--:|--:|--:|--:|
| ZooKeeper 3.9.3 | 1,632 | 50.6 MiB | 1.10 s | 0.25 s (**4.35×**) | 0.23 s (**4.78×**) | 0.22 s (**5.05×**) | +107.4% |
| Cassandra 4.1.7 | 200 | 57.5 MiB | 1.38 s | 0.42 s (**3.29×**) | 0.39 s (**3.52×**) | 0.12 s (**11.30×**) | +18.6% |
| Kafka 3.8.1 | 235 | 121.1 MiB | 2.69 s | 1.32 s (**2.04×**) | 1.34 s (**2.01×**) | 0.26 s (**10.28×**) | +4.1% |
| Groovy 4.0.24 | 9,756 | 271.6 MiB | 4.22 s | 1.71 s (**2.47×**) | 1.70 s (**2.48×**) | 1.29 s (**3.28×**) | +273.6% |
| Solr 9.7.0 | 2,091 | 308.5 MiB | 6.89 s | 1.95 s (**3.54×**) | 1.80 s (**3.82×**) | 0.63 s (**10.87×**) | +12.4% |
| HBase 2.6.1 | 2,588 | 404.8 MiB | 8.54 s | 2.32 s (**3.68×**) | 2.43 s (**3.51×**) | 0.90 s (**9.47×**) | +22.4% |
| Spark 3.5.3 | 1,825 | 427.9 MiB | 9.50 s | 4.15 s (**2.29×**) | 4.25 s (**2.24×**) | 0.88 s (**10.79×**) | +10.4% |
| Flink 1.20.0 | 167 | 502.8 MiB | 10.94 s | 3.31 s (**3.30×**) | 3.62 s (**3.02×**) | 0.91 s (**11.98×**) | +8.9% |
| Gradle 8.14.3 | 22,427 | 500.2 MiB | 10.30 s | 4.60 s (**2.24×**) | 4.19 s (**2.46×**) | 3.00 s (**3.44×**) | +108.6% |
| SonarQube Community Build 26.6 | 749 | 966.6 MiB | 21.11 s | 6.55 s (**3.22×**) | 6.33 s (**3.33×**) | 1.78 s (**11.88×**) | +9.2% |
| Hadoop 3.4.0¹ | 20,220 | 1,733.6 MiB | 33.02 s | 20.88 s (**1.58×**) | 20.13 s (**1.64×**) | 4.91 s (**6.73×**) | +77.5% |

¹ Hadoop's raw size and file count needed `-Xmx4g`; every other row here runs on the
default 1g heap used for this benchmark. It's also the corpus where both DEFLATE codecs
gain the least: a handful of individual files (500+ MiB) dominate its wall time and
always stream through the JDK codec regardless of which fast-path codec handles the
smaller entries (see [Options](README.md#options)).

DEFLATE never produces a bigger archive than the baseline in practice, so it's the safe
default — every corpus above is a clear win on both codecs, with no exceptions. STORE
trades size for speed, and how much size depends entirely on how compressible the
content already is: use `store = true` only for archives you already know are
jar/binary-heavy, where the size cost is small and the speedup large.
