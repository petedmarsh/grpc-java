# Suggested PR

## Title

netty: Increase HPACK encoder bucket count to 128

## Description

Increase the HPACK encoder bucket count from 16 to 128 for both clients and servers. Netty 4.1.138/4.2.18 limits encoder tables to `bucket count × 64` when processing a peer's table-size setting, so this allows up to 8 KiB within the peer's advertised limit. Advertised table sizes and the Netty dependency version are unchanged.

[Separate JMH benchmark and results](https://github.com/petedmarsh/grpc-java/blob/hpack-bucket-size-benchmark/benchmarks/hpack-table-size/RESULTS.md): at the same 4 KiB capacity, 128 buckets reduced synthetic mixed-header processing time by 24–28%, with unchanged per-request allocation and encoded size. Encoder construction allocated an additional 896 bytes with compressed references.

Validation: 129 client/server handler tests and `:grpc-netty:checkstyleMain` passed. The benchmark uses Netty 4.2.18.Final; handler tests use the repository's pinned 4.2.17.Final.

Related to #12973.

## Branches and results

- [Benchmark branch](https://github.com/petedmarsh/grpc-java/tree/hpack-bucket-size-benchmark)
- [128-bucket change branch](https://github.com/petedmarsh/grpc-java/tree/netty-hpack-128-buckets)
- [Benchmark results](RESULTS.md)
- [Open a PR with this title and description](https://github.com/grpc/grpc-java/compare/master...petedmarsh:grpc-java:netty-hpack-128-buckets?expand=1&title=netty%3A%20Increase%20HPACK%20encoder%20bucket%20count%20to%20128&body=Increase%20the%20HPACK%20encoder%20bucket%20count%20from%2016%20to%20128%20for%20both%20clients%20and%20servers.%20Netty%204.1.138%2F4.2.18%20limits%20encoder%20tables%20to%20%60bucket%20count%20%C3%97%2064%60%20when%20processing%20a%20peer's%20table-size%20setting%2C%20so%20this%20allows%20up%20to%208%20KiB%20within%20the%20peer's%20advertised%20limit.%20Advertised%20table%20sizes%20and%20the%20Netty%20dependency%20version%20are%20unchanged.%0A%0A%5BSeparate%20JMH%20benchmark%20and%20results%5D(https%3A%2F%2Fgithub.com%2Fpetedmarsh%2Fgrpc-java%2Fblob%2Fhpack-bucket-size-benchmark%2Fbenchmarks%2Fhpack-table-size%2FRESULTS.md)%3A%20at%20the%20same%204%20KiB%20capacity%2C%20128%20buckets%20reduced%20synthetic%20mixed-header%20processing%20time%20by%2024%E2%80%9328%25%2C%20with%20unchanged%20per-request%20allocation%20and%20encoded%20size.%20Encoder%20construction%20allocated%20an%20additional%20896%20bytes%20with%20compressed%20references.%0A%0AValidation%3A%20129%20client%2Fserver%20handler%20tests%20and%20%60%3Agrpc-netty%3AcheckstyleMain%60%20passed.%20The%20benchmark%20uses%20Netty%204.2.18.Final%3B%20handler%20tests%20use%20the%20repository's%20pinned%204.2.17.Final.%0A%0ARelated%20to%20%2312973.%0A)
