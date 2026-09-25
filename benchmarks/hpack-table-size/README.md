# HPACK hash bucket benchmark

This isolated JMH build compares the `16`, `64`, and `128` arguments passed to
`DefaultHttp2HeadersEncoder` by grpc-java. It pins **Netty 4.2.18.Final**, the first
4.2 release with the `nameEntries.length * 64` cap. It does not change grpc-java's
dependency versions or production code. JMH is pinned to 1.37.

See [the measured results](RESULTS.md) and [the suggested PR title and description](PR.md).
The original run's raw JSON and logs are archived in `results/`.

From the repository root, with a JDK available via `JAVA_HOME`:

```sh
bash benchmarks/run-hpack-table-size-benchmark.sh --validate
bash benchmarks/run-hpack-table-size-benchmark.sh \
  -rf json -rff benchmarks/hpack-table-size/build/results.json
# Additional control: omit SETTINGS_HEADER_TABLE_SIZE (all encoders stay at 4 KiB).
bash benchmarks/run-hpack-table-size-benchmark.sh -e '.*constructEncoder' \
  -p peerTableBytes=-1 -p workload=MIXED_SHORT,MIXED_LARGE \
  -rf json -rff benchmarks/hpack-table-size/build/omitted-setting.json
```

Defaults: one thread, three independent JVM forks per combination, three 1-second
warmup iterations and five 1-second measurement iterations, 512 MiB fixed heap,
G1 GC. The complete matrix is 27 encoding cases and three construction cases.
Expect roughly 15 minutes including JVM startup and validation.

`encode` constructs a fresh `DefaultHttp2Headers` and encodes it on a persistent
connection. Names and shared values are reused. Five request-specific values use
fresh `AsciiString` wrappers on every operation, so their hash computations and
wrapper allocations are measured. Immutable source bytes are generated before
timing; generating application metadata is outside scope. The 4096-request cycle
is longer than any table can retain. The output buffer is preallocated and reused.
Huffman encoding is disabled, matching grpc-java's constructors.

Workloads:

- `STABLE`: seven HTTP/2/gRPC fields and five repeated custom metadata fields;
  its dynamic entries fit within 1 KiB.
- `MIXED_SHORT`: seven HTTP/2/gRPC fields, ten low-cardinality metadata fields,
  and five request-specific values of 36, 32, 55, 36, and 43 bytes.
- `MIXED_LARGE`: the same structure, with request-specific values of 128, 256,
  512, 128, and 256 bytes. All metadata is eligible for indexing.

Peer settings of 1024 bytes hold effective HPACK capacity constant to isolate the
bucket-array change. Settings of 4096 and 8192 bytes model explicit advertisements
of the standard capacity and the proposed larger table. An optional `-1` parameter
models an omitted setting: all bucket counts keep the initial 4096-byte capacity,
because Netty applies the new clamp only when a table-size setting is received.
Trial setup checks the effective encoder capacity;
running accidentally against the old, uncapped Netty version fails immediately.
Validation decodes 8192 consecutive header blocks for each of the 36 combinations
and compares every decoded block with its input.

Metrics:

- Primary score: average nanoseconds per operation (lower is better).
- `gc.alloc.rate.norm`: bytes allocated per operation, including header
  construction and HPACK entry churn; excludes trial setup.
- `encodedBytes`: HPACK payload bytes per operation. This excludes HTTP/2 frame
  headers, message bodies, TLS, and TCP/IP overhead.
- `constructEncoder`: time and allocation per new encoder, measured separately
  from request processing. JMH consumes the returned encoder so its arrays escape.

This is a transport-encoding microbenchmark, not an end-to-end RPC benchmark.
`DefaultHttp2Headers` is a standard Netty container; grpc-java uses its own
outbound container. Absolute allocation and timings should not be read as total
gRPC request costs. Compare configurations within the same workload. GC allocation
measures churn, not retained heap size. Throughput can be derived as `1e9 / ns/op`.
