# Benchmarks

Eleven popular open-source Java projects' official binary distributions, archived four
ways — Gradle `Zip` (baseline), `parallel-zip` DEFLATE (JDK codec), DEFLATE
(libdeflate), and STORE. Same machine: 12 logical cores, JDK 21, warm cache.

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
handles the smaller entries (see [Options](README.md#options)).

² A rolling snapshot build, not a fixed release — file count and size will drift between
re-benchmarks as it moves forward, unlike the other ten rows.

DEFLATE now trades a small amount of archive size (up to ~0.8% here, on SonarQube) for a
large speed win: an incompressibility sniff skips fully compressing large entries that
wouldn't shrink much anyway, so it's still the safe default — every corpus above is a
clear win on both codecs, size cost included. STORE trades size for speed more
aggressively, and how much size depends entirely on how compressible the content already
is: use `store = true` only for archives you already know are jar/binary-heavy, where the
size cost is small and the speedup large.
