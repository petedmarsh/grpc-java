# HPACK bucket-size benchmark results — 2026-09-25

[Suggested PR title and description](PR.md)

Changing the bucket count from 16 to 64 or 128 improved request-processing time
in both tested mixed-metadata workloads when the peer omitted the header-table
setting, without changing allocation or encoded size. With an explicit 8 KiB
advertisement, the larger usable tables also reduced allocation and encoded size.
128 had the lowest mean time, allocation, and encoded size of the three choices
in those 8 KiB cases.

These are synthetic transport-encoding measurements, not end-to-end RPC timings.
The production constructor arguments and grpc-java dependency versions were not
changed.

## Environment and method

- Apple M5 Max, 18 logical CPUs, 128 GiB RAM; macOS 26.7.
- Homebrew OpenJDK 21.0.12, arm64, G1, `-Xms512m -Xmx512m`; compressed references.
- Netty **4.2.18.Final** throughout the isolated runtime classpath; JMH **1.37**.
- One worker thread; three fresh JVM forks per case; each fork used three
  one-second warmup iterations and five one-second measurement iterations.
- 36 timed configurations: 27 explicit-setting encoding cases, six omitted-setting
  encoding cases, and three encoder-construction cases. 108 JVM forks and 540
  measurement iterations. Total JMH wall time: 14 minutes 48 seconds.
- Tables show JMH's mean and reported 99.9% confidence interval half-width.
  Allocation is `gc.alloc.rate.norm`; HPACK bytes are measured from the actual
  encoder output. A separate profiler combines thread-local byte counters outside
  the measured loop, without per-request atomic updates.

Each encode operation constructs fresh Netty headers and encodes one request on
an existing connection. Both mixed profiles have seven HTTP/2/gRPC fields, ten
low-cardinality fields, and five request-specific values. Short values are
36/32/55/36/43 bytes; large values are 128/256/512/128/256 bytes. All are eligible
for indexing. Fresh wrappers prevent request-specific values from benefiting
from cached hashes across requests. Source bytes are prepared outside timing.
Huffman encoding is disabled, matching grpc-java's constructor arguments.

Allocation includes the Netty header container, fresh value wrappers, and HPACK
entry churn. It excludes fixture generation and the reused output buffer.
grpc-java's own outbound header container differs, so these are not exact
production per-RPC allocation totals.

## Peer explicitly advertises 8 KiB

| Workload | Buckets | Effective table | Time (µs/request), 99.9% CI | Allocated B/request | HPACK B/request |
|---|---:|---:|---:|---:|---:|
| MIXED_SHORT | 16 | 1 KiB | 1.209 ± 0.165 | 2600.0 | 601.8 |
| MIXED_SHORT | 64 | 4 KiB | 0.990 ± 0.012 | 1703.7 | 276.6 |
| MIXED_SHORT | 128 | 8 KiB | 0.908 ± 0.015 | 1604.3 | 257.8 |
| MIXED_LARGE | 16 | 1 KiB | 1.197 ± 0.010 | 2600.0 | 1687.8 |
| MIXED_LARGE | 64 | 4 KiB | 1.239 ± 0.004 | 1944.3 | 1428.9 |
| MIXED_LARGE | 128 | 8 KiB | 1.096 ± 0.017 | 1759.2 | 1379.2 |

Compared with 16 buckets:

- **64, short:** time −18.1%, allocation −34.5%, HPACK bytes −54.0%.
- **128, short:** time −24.9%, allocation −38.3%, HPACK bytes −57.2%.
- **64, large:** time **+3.5%**, allocation −25.2%, HPACK bytes −15.3%.
- **128, large:** time −8.4%, allocation −32.3%, HPACK bytes −18.3%.

The CPU tradeoff is not monotonic: 64 improved compression and allocation for
large values but was slightly slower than 16 in this run. The short/16 timing
also varied substantially by fork (means approximately 0.999, 1.298, and 1.330 µs);
the percentage timing differences should be interpreted with that uncertainty.

## Peer omits SETTINGS_HEADER_TABLE_SIZE

Netty starts with a 4 KiB encoder table. Its new clamp is applied by
`setMaxHeaderTableSize()`, which the connection encoder calls only when the peer
actually supplies that setting. Omission therefore leaves **all three choices at
4 KiB**. This is different from explicitly advertising 4096.

| Workload | Buckets | Effective table | Time (µs/request), 99.9% CI | Allocated B/request | HPACK B/request |
|---|---:|---:|---:|---:|---:|
| MIXED_SHORT | 16 | 4 KiB | 1.280 ± 0.012 | 1703.7 | 276.6 |
| MIXED_SHORT | 64 | 4 KiB | 0.961 ± 0.005 | 1703.7 | 276.6 |
| MIXED_SHORT | 128 | 4 KiB | 0.922 ± 0.007 | 1703.7 | 276.6 |
| MIXED_LARGE | 16 | 4 KiB | 1.484 ± 0.017 | 1944.3 | 1428.9 |
| MIXED_LARGE | 64 | 4 KiB | 1.202 ± 0.011 | 1944.3 | 1428.9 |
| MIXED_LARGE | 128 | 4 KiB | 1.133 ± 0.007 | 1944.3 | 1428.9 |

Relative to 16, time decreased by 24.9%/19.0% for 64 and 28.0%/23.6% for 128
(short/large respectively). Allocation and encoded size stayed the same. This
isolates an encoder lookup-cost benefit at the same HPACK capacity.

## Peer explicitly advertises 4 KiB

| Workload | Buckets | Effective table | Time (µs/request), 99.9% CI | Allocated B/request | HPACK B/request |
|---|---:|---:|---:|---:|---:|
| MIXED_SHORT | 16 | 1 KiB | 1.111 ± 0.204 | 2600.0 | 601.8 |
| MIXED_SHORT | 64 | 4 KiB | 1.041 ± 0.008 | 1703.7 | 276.6 |
| MIXED_SHORT | 128 | 4 KiB | 0.884 ± 0.002 | 1703.7 | 276.6 |
| MIXED_LARGE | 16 | 1 KiB | 1.191 ± 0.008 | 2600.0 | 1687.8 |
| MIXED_LARGE | 64 | 4 KiB | 1.244 ± 0.005 | 1944.3 | 1428.9 |
| MIXED_LARGE | 128 | 4 KiB | 1.164 ± 0.004 | 1944.3 | 1428.9 |

64 and 128 have the same effective capacity, allocation, and encoded size here.
128 had lower time in both mixed profiles. 16 is clamped to 1 KiB because the
peer explicitly supplied the setting.

## Fixed 1 KiB control

| Workload | Buckets | Effective table | Time (µs/request), 99.9% CI | Allocated B/request | HPACK B/request |
|---|---:|---:|---:|---:|---:|
| MIXED_SHORT | 16 | 1 KiB | 1.225 ± 0.171 | 2600.0 | 601.8 |
| MIXED_SHORT | 64 | 1 KiB | 0.802 ± 0.004 | 2600.0 | 601.8 |
| MIXED_SHORT | 128 | 1 KiB | 0.942 ± 0.146 | 2600.0 | 601.8 |
| MIXED_LARGE | 16 | 1 KiB | 1.183 ± 0.007 | 2600.0 | 1687.8 |
| MIXED_LARGE | 64 | 1 KiB | 1.049 ± 0.013 | 2600.0 | 1687.8 |
| MIXED_LARGE | 128 | 1 KiB | 1.004 ± 0.009 | 2600.0 | 1687.8 |

All cases have the same effective capacity, allocation, and encoded size.
Changing the buckets can still change lookup/eviction cost. The short/128 and
short/64 timing intervals overlap, so this control does not establish that 128
always beats 64.

The separate STABLE workload has twelve repeated fields that fit in 1 KiB.
Across all nine explicit-setting cases it produced 12 HPACK bytes and allocated
760 bytes/request, with mean times between 0.177 and 0.185 µs/request.

## Encoder construction

| Buckets | Time (ns/encoder), 99.9% CI | Allocated B/encoder | Extra allocation versus 16 |
|---:|---:|---:|---:|
| 16 | 29.53 ± 3.05 | 376 | +0 B |
| 64 | 36.19 ± 1.51 | 760 | +384 B |
| 128 | 102.41 ± 3.12 | 1272 | +896 B |

The extra 384 or 896 bytes are allocated once when constructing an encoder.
They match the added reference slots in its two bucket arrays with four-byte
references. Choosing 128 instead of 64 costs an additional 512 bytes per encoder.
These values are constructor allocation, not total connection memory or a retained
heap measurement. Construction timings reflect repeated creation of short-lived
encoders, not a complete TCP/TLS/gRPC connection handshake.

## Validation, artifacts, and reproduction

All **294,912** validation blocks (8192 for each of 36 combinations, including
omitted-setting STABLE cases) round-tripped through the real Netty HPACK decoder.
Validation checked every decoded header and the effective table size. Both JMH
runs completed without failed forks.

- [Benchmark source](../src/jmh/java/io/grpc/benchmarks/netty/HpackTableSizeBenchmark.java)
- [Byte profiler](../src/jmh/java/io/grpc/benchmarks/netty/HpackTableSizeProfiler.java)
- [Reproduction instructions](README.md)
- [Main raw JMH JSON](results/2026-09-25-main.json) and [log](results/2026-09-25-main.log)
- [Omitted-setting raw JMH JSON](results/2026-09-25-omitted-setting.json) and [log](results/2026-09-25-omitted-setting.log)
- [Validation log](results/2026-09-25-validation.log)

The raw JSON, JMH logs, and validation log from this run are archived in
`results/` on the benchmark branch. New runs write to `build/` by default.

The benchmark runs sequentially on a shared workstation without CPU affinity.
JIT choices, machine activity, and execution order can affect timings, especially
small differences. There is no network, TLS, application execution, timed decoder,
or production metadata trace in these measurements. HPACK bytes are not total
network bytes, and allocated bytes are not retained heap. Larger arrays do not
eliminate hashing of high-cardinality metadata or make never-index policies
irrelevant.

The original 1/4/8 KiB cap comparison only applies when the peer explicitly sends
a table-size setting on this Netty version; do not apply it unconditionally to
connections using the implicit default.
