/*
 * Copyright 2026 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.benchmarks.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersDecoder;
import io.netty.handler.codec.http2.DefaultHttp2HeadersEncoder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersEncoder;
import io.netty.util.AsciiString;
import io.netty.util.Version;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures the allocation and encoding cost of gRPC-shaped request metadata on one connection.
 *
 * <p>Use the isolated build in benchmarks/hpack-table-size to run against Netty 4.2.18.Final.
 * The 1024-byte peer setting holds HPACK capacity constant while changing hash bucket counts.
 * The 4096/8192-byte settings exercise the interaction with Netty's encoder capacity limit.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC"})
@Threads(1)
public class HpackTableSizeBenchmark {
  // Much larger than the largest dynamic table can retain: no request-specific value hits on wrap.
  private static final int REQUEST_CYCLE = 4096;
  private static final AsciiString[] PROTOCOL_NAMES = ascii(
      ":method", ":scheme", ":authority", ":path", "content-type", "te", "user-agent");
  private static final AsciiString[] PROTOCOL_VALUES = ascii(
      "POST", "https", "catalog.internal:443", "/catalog.v1.Catalog/GetItem",
      "application/grpc", "trailers", "grpc-java-netty/1.84.0");
  private static final AsciiString[] SHARED_NAMES = ascii(
      "x-api-version", "x-client-region", "x-client-platform", "accept-language",
      "x-client-version", "x-device-class", "x-retry-attempt", "x-feature-set",
      "x-traffic-class", "x-request-priority");
  private static final AsciiString[][] SHARED_VALUES = {
      ascii("v1", "v2"), ascii("eu-west1", "us-central1", "asia-east1", "eu-north1"),
      ascii("linux", "macos", "windows"), ascii("en-US", "de-DE", "fr-FR", "en-GB"),
      ascii("1.0", "1.1", "2.0", "2.1"), ascii("server", "desktop", "mobile"),
      ascii("0", "1", "2"), ascii("stable", "beta"), ascii("standard", "priority"),
      ascii("low", "normal", "high")
  };
  private static final AsciiString[] UNIQUE_NAMES = ascii(
      "x-request-id", "x-cloud-trace-id", "traceparent", "x-correlation-id", "baggage");

  public enum Workload {
    // Twelve repeated fields fit within 1 KiB; isolates a cache-friendly case.
    STABLE,
    // Seven protocol fields, ten low-cardinality fields, five request-specific values.
    MIXED_SHORT,
    MIXED_LARGE
  }

  @State(Scope.Thread)
  public static class TableSize {
    @Param({"16", "64", "128"})
    public int buckets;
  }

  @State(Scope.Thread)
  public static class EncodingState {
    @Param({"1024", "4096", "8192"})
    public int peerTableBytes;

    @Param({"STABLE", "MIXED_SHORT", "MIXED_LARGE"})
    public Workload workload;

    private DefaultHttp2HeadersEncoder encoder;
    private ByteBuf output;
    private byte[][][] uniqueValues;
    private int request;

    @Setup(Level.Trial)
    public void setup(TableSize tableSize) throws Exception {
      encoder = newEncoder(tableSize.buckets);
      // -1 models a peer omitting SETTINGS_HEADER_TABLE_SIZE: Netty keeps its initial 4 KiB.
      if (peerTableBytes != -1) {
        encoder.maxHeaderTableSize(peerTableBytes);
      }
      long expected = peerTableBytes == -1 ? 4096
          : Math.min(peerTableBytes, tableSize.buckets * 64L);
      if (encoder.maxHeaderTableSize() != expected) {
        throw new IllegalStateException("Expected capped encoder table " + expected
            + "; got " + encoder.maxHeaderTableSize() + ". Use Netty 4.2.18.Final.");
      }
      output = Unpooled.buffer(8192, 8192);
      int[] lengths = workload == Workload.MIXED_LARGE
          ? new int[] {128, 256, 512, 128, 256} : new int[] {36, 32, 55, 36, 43};
      if (workload != Workload.STABLE) {
        uniqueValues = new byte[UNIQUE_NAMES.length][REQUEST_CYCLE][];
        for (int field = 0; field < UNIQUE_NAMES.length; field++) {
          for (int index = 0; index < REQUEST_CYCLE; index++) {
            uniqueValues[field][index] = opaqueValue(field, index, lengths[field]);
          }
        }
      }
    }

    Http2Headers nextHeaders() {
      int index = request++ & (REQUEST_CYCLE - 1);
      Http2Headers headers = new DefaultHttp2Headers(false, 32);
      for (int i = 0; i < PROTOCOL_NAMES.length; i++) {
        headers.add(PROTOCOL_NAMES[i], PROTOCOL_VALUES[i]);
      }
      if (workload == Workload.STABLE) {
        for (int i = 0; i < 5; i++) {
          headers.add(SHARED_NAMES[i], SHARED_VALUES[i][0]);
        }
      } else {
        // Fresh wrappers mean high-cardinality value hashes are NOT cached between requests.
        // Raw metadata bytes are prepared outside timing, as by a caller before transport encoding.
        for (int i = 0; i < UNIQUE_NAMES.length; i++) {
          headers.add(UNIQUE_NAMES[i], new AsciiString(uniqueValues[i][index], false));
        }
        for (int i = 0; i < SHARED_NAMES.length; i++) {
          int choice = ((index * (2 * i + 1)) ^ (index >>> (i % 5 + 1))) & 0x7fffffff;
          headers.add(SHARED_NAMES[i], SHARED_VALUES[i][choice % SHARED_VALUES[i].length]);
        }
      }
      return headers;
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      if (output != null) {
        output.release();
      }
      if (encoder != null) {
        encoder.close();
      }
    }
  }

  @State(Scope.Thread)
  public static class Counters {
    private long encodedBytes;
    private long requests;

    @Setup(Level.Iteration)
    public void reset() {
      encodedBytes = 0;
      requests = 0;
    }

    @TearDown(Level.Iteration)
    public void publish() {
      HpackTableSizeProfiler.record(encodedBytes, requests);
    }
  }

  /** One request: construct headers and encode them using the connection's existing encoder. */
  @Benchmark
  public int encode(EncodingState state, Counters counters) throws Exception {
    Http2Headers headers = state.nextHeaders();
    state.output.clear();
    state.encoder.encodeHeaders(3, headers, state.output);
    int bytes = state.output.readableBytes();
    counters.encodedBytes += bytes;
    counters.requests++;
    return bytes;
  }

  /** One new connection's encoder; returning it prevents elimination of the bucket allocations. */
  @Benchmark
  public DefaultHttp2HeadersEncoder constructEncoder(TableSize tableSize) {
    // No buffers are allocated before SETTINGS, so this constructor needs no close/release.
    return newEncoder(tableSize.buckets);
  }

  private static DefaultHttp2HeadersEncoder newEncoder(int buckets) {
    // Match both grpc-java Netty handlers, including their disabled Huffman encoding.
    return new DefaultHttp2HeadersEncoder(
        Http2HeadersEncoder.NEVER_SENSITIVE, false, buckets, Integer.MAX_VALUE);
  }

  private static AsciiString[] ascii(String... values) {
    AsciiString[] result = new AsciiString[values.length];
    for (int i = 0; i < values.length; i++) {
      result[i] = AsciiString.cached(values[i]);
    }
    return result;
  }

  private static byte[] opaqueValue(int field, int request, int length) {
    byte[] bytes = new byte[length];
    byte[] alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        .getBytes(StandardCharsets.US_ASCII);
    int bits = (request + 1) * 0x9e3779b9 ^ (field + 1) * 0x85ebca6b;
    for (int i = 0; i < length; i++) {
      bits ^= bits << 13;
      bits ^= bits >>> 17;
      bits ^= bits << 5;
      bytes[i] = alphabet[bits & 63];
    }
    // Include the request index as a readable suffix to the deterministic pseudo-random value.
    byte[] suffix = Integer.toHexString(request).getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(suffix, 0, bytes, length - suffix.length, suffix.length);
    return bytes;
  }

  /** Validates every combination and round-trips a full request cycle before any timed run. */
  public static void main(String[] args) throws Exception {
    System.out.println("Netty: " + Version.identify().get("netty-codec-http2"));
    System.out.println("buckets,peerTableBytes,workload,effectiveTableBytes,meanHpackBytes");
    for (int buckets : new int[] {16, 64, 128}) {
      TableSize tableSize = new TableSize();
      tableSize.buckets = buckets;
      for (int peerBytes : new int[] {-1, 1024, 4096, 8192}) {
        for (Workload workload : Workload.values()) {
          EncodingState state = new EncodingState();
          state.peerTableBytes = peerBytes;
          state.workload = workload;
          try {
            state.setup(tableSize);
            DefaultHttp2HeadersDecoder decoder = new DefaultHttp2HeadersDecoder(false);
            if (peerBytes != -1) {
              decoder.configuration().maxHeaderTableSize(peerBytes);
            }
            long bytes = 0;
            // Two cycles also check that wraparound does not introduce false cache hits.
            for (int i = 0; i < REQUEST_CYCLE * 2; i++) {
              Http2Headers expected = state.nextHeaders();
              state.output.clear();
              state.encoder.encodeHeaders(3, expected, state.output);
              if (i >= REQUEST_CYCLE) {
                bytes += state.output.readableBytes();
              }
              Http2Headers decoded = decoder.decodeHeaders(3, state.output);
              if (!expected.equals(decoded) || state.output.isReadable()) {
                throw new AssertionError("HPACK round-trip failed: " + buckets + "/"
                    + peerBytes + "/" + workload + " request " + i);
              }
            }
            System.out.printf(Locale.ROOT, "%d,%d,%s,%d,%.3f%n", buckets, peerBytes,
                workload, state.encoder.maxHeaderTableSize(), bytes / (double) REQUEST_CYCLE);
          } finally {
            state.tearDown();
          }
        }
      }
    }
  }
}
