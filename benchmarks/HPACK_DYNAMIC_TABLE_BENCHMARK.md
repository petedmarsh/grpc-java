# HPACK dynamic table benchmark

This benchmark compares Netty's default 4 KiB HPACK dynamic table with a disabled dynamic table.
Disabling the table retains HPACK static-table references and Huffman encoding.

The public workload contains five metadata fields with 100,000 distinct values per 100,000 requests
and ten low-cardinality metadata fields. It has two high-cardinality value profiles:

| Profile | Value lengths |
| --- | --- |
| Low length | Conventional request, trace, context, correlation, and baggage identifiers |
| High length | Synthetic opaque values of 128, 256, and 512 bytes |

The end-to-end benchmark sends a repeated protobuf request and response with a 32-byte payload over
one persistent plaintext HTTP/2 connection. All identifiers and opaque values are generated
deterministically; the corpus contains no credentials or production data.

## Run configuration

| Property | Direct HPACK | End-to-end gRPC |
| --- | ---: | ---: |
| JMH forks | 1 | 1 |
| Warmup | 5 iterations x 2 seconds | 5 iterations x 1 second |
| Measurement | 10 iterations x 5 seconds | 5 iterations x 2 seconds |
| Threads | 1 | 1 |
| JMH version | 1.36 | 1.36 |
| JVM | OpenJDK 21.0.12 | OpenJDK 21.0.12 |

Errors below are the half-width of JMH's 99.9% confidence interval.

## Direct HPACK encoder

| Value profile | Dynamic table | Throughput (ops/s) | Modeled HTTP/2 bytes/op | Process CPU (us/op) |
| --- | --- | ---: | ---: | ---: |
| Low length | 4 KiB | 1,357,718.734 +/- 100,611.389 | 336.458 +/- 0.001 | 0.752 +/- 0.050 |
| Low length | Disabled | 2,692,014.649 +/- 22,285.047 | 681.583 +/- 0.001 | 0.383 +/- 0.003 |
| High length | 4 KiB | 440,768.952 +/- 1,948.723 | 1,397.064 +/- 0.001 | 2.282 +/- 0.009 |
| High length | Disabled | 520,610.758 +/- 1,259.308 | 1,663.064 +/- 0.001 | 1.931 +/- 0.005 |

### Direct change when disabling the table

Positive throughput is better; negative bytes and CPU are better.

| Value profile | Throughput | Modeled bytes | Process CPU |
| --- | ---: | ---: | ---: |
| Low length | +98.275% | +102.576% | -49.069% |
| High length | +18.114% | +19.040% | -15.381% |

The direct encoder results show statistically resolved CPU and throughput improvements, accompanied
by statistically resolved increases in encoded bytes.

## End-to-end unary gRPC

| Value profile | Dynamic table | Throughput (RPC/s) | Client bytes/RPC | Process CPU (us/RPC) |
| --- | --- | ---: | ---: | ---: |
| Low length | 4 KiB | 24,807.936 +/- 4,357.322 | 350.793 +/- 0.004 | 36.032 +/- 7.189 |
| Low length | Disabled | 25,666.263 +/- 983.054 | 717.585 +/- 0.002 | 34.700 +/- 1.669 |
| High length | 4 KiB | 24,187.775 +/- 1,832.197 | 1,524.586 +/- 0.004 | 36.926 +/- 2.476 |
| High length | Disabled | 25,873.456 +/- 3,399.571 | 1,803.585 +/- 0.002 | 34.459 +/- 4.809 |

### End-to-end change when disabling the table

| Value profile | Throughput | Client bytes | Process CPU |
| --- | ---: | ---: | ---: |
| Low length | +3.460% | +104.561% | -3.697% |
| High length | +6.969% | +18.300% | -6.681% |

The end-to-end CPU and throughput confidence intervals overlap, so those observed changes are not
statistically resolved by this run. The client-byte increases are resolved.

## Interpretation

Disabling the dynamic table avoids insertion, eviction, and lookup work, which materially reduces
the isolated encoder cost. It also prevents reusable low-cardinality and transport fields from being
retained. The result is a CPU-versus-wire-size tradeoff: the disabled table is cheaper to encode but
sends more bytes for both value profiles.

The byte penalty is much larger for the low-length profile. For the high-length profile, the request
is already dominated by near-unique opaque values, so losing compression of reusable fields has a
smaller proportional effect.

## Reproduction

Build and run the direct HPACK benchmark:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  benchmarks/run-public-metadata-hpack-benchmark.sh
```

Build and run the end-to-end benchmark:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  benchmarks/run-never-index-high-cardinality-benchmark.sh
```
