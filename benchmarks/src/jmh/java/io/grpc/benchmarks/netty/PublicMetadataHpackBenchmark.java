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
import io.netty.handler.codec.http2.DefaultHttp2HeadersEncoder;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2HeadersEncoder;
import io.netty.util.AsciiString;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
 * Direct HPACK counterpart to {@link NeverIndexHighCardinalityBenchmark}.
 *
 * <p>The header block mirrors the gRPC request pseudo-headers, reserved transport headers, five
 * 100,000-cardinality metadata fields, and ten low-cardinality metadata fields used by the public
 * end-to-end benchmark. The value-length parameter compares conventional short identifiers with
 * synthetic opaque values large enough to churn the 4 KiB HPACK dynamic table. Synthetic value
 * selection occurs outside the measured invocation. The encoder and its dynamic table persist for
 * the full trial. The dynamic-table policy compares Netty's default 4 KiB table with a zero-sized
 * table, while retaining static-table references and Huffman encoding in both cases.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 5)
@Fork(1)
@Threads(1)
public class PublicMetadataHpackBenchmark {
  private static final int REQUESTS_PER_CYCLE = 100_000;
  private static final int BODY_SIZE = 32;
  private static final int FIXED_NON_HPACK_BYTES = 9 + 9 + BODY_SIZE;
  private static final AtomicLong HTTP2_OUTBOUND_BYTES = new AtomicLong();

  public enum DynamicTablePolicy {
    TABLE_4K,
    TABLE_DISABLED
  }

  public enum ValueLength {
    LOW_LENGTH,
    HIGH_LENGTH
  }

  @Param({"TABLE_4K", "TABLE_DISABLED"})
  public DynamicTablePolicy dynamicTablePolicy;

  @Param({"LOW_LENGTH", "HIGH_LENGTH"})
  public ValueLength valueLength;

  private DefaultHttp2HeadersEncoder encoder;
  private HeaderBlock headers;
  private ByteBuf output;
  private int requestIndex;

  @Setup(Level.Trial)
  public void setUpTrial() throws Http2Exception {
    HeaderSpec[] headerSpecs = createHeaderSpecs(valueLength);
    for (HeaderSpec headerSpec : headerSpecs) {
      headerSpec.initializeValues();
    }
    encoder = new DefaultHttp2HeadersEncoder(Http2HeadersEncoder.NEVER_SENSITIVE);
    encoder.maxHeaderTableSize(
        dynamicTablePolicy == DynamicTablePolicy.TABLE_DISABLED ? 0 : 4_096);
    headers = new HeaderBlock(headerSpecs);
    output = Unpooled.buffer(4_096);
  }

  @Setup(Level.Invocation)
  public void selectRequest() {
    headers.selectRequest(requestIndex++);
    if (requestIndex == REQUESTS_PER_CYCLE) {
      requestIndex = 0;
    }
  }

  @TearDown(Level.Trial)
  public void tearDownTrial() {
    if (output != null) {
      output.release();
    }
    if (encoder != null) {
      encoder.close();
    }
  }

  @Benchmark
  public int encodeHeaders() throws Http2Exception {
    output.clear();
    encoder.encodeHeaders(3, headers, output);
    int encodedHeaderBytes = output.readableBytes();
    HTTP2_OUTBOUND_BYTES.addAndGet(encodedHeaderBytes + FIXED_NON_HPACK_BYTES);
    return encodedHeaderBytes;
  }

  static long http2OutboundBytes() {
    return HTTP2_OUTBOUND_BYTES.get();
  }

  private static HeaderSpec[] createHeaderSpecs(ValueLength valueLength) {
    ValueGenerator requestIdGenerator;
    ValueGenerator traceIdGenerator;
    ValueGenerator requestContextGenerator;
    ValueGenerator correlationIdGenerator;
    ValueGenerator baggageGenerator;
    if (valueLength == ValueLength.LOW_LENGTH) {
      requestIdGenerator = new ValueGenerator() {
        @Override
        public String generate(int valueIndex) {
          return "00000000-0000-4000-8000-" + leftPadHex(valueIndex, 12);
        }
      };
      traceIdGenerator = new ValueGenerator() {
        @Override
        public String generate(int valueIndex) {
          return traceId(valueIndex);
        }
      };
      requestContextGenerator = new ValueGenerator() {
        @Override
        public String generate(int valueIndex) {
          return "00-" + traceId(valueIndex) + "-"
              + leftPadHex(valueIndex * 31L + 1, 16) + "-01";
        }
      };
      correlationIdGenerator = new ValueGenerator() {
        @Override
        public String generate(int valueIndex) {
          return "10000000-2000-4000-8000-" + leftPadHex(valueIndex, 12);
        }
      };
      baggageGenerator = new ValueGenerator() {
        @Override
        public String generate(int valueIndex) {
          return "request.id=" + traceId(valueIndex);
        }
      };
    } else {
      requestIdGenerator = opaqueValueGenerator("request-", 128);
      traceIdGenerator = opaqueValueGenerator("trace-", 256);
      requestContextGenerator = opaqueValueGenerator("context-", 512);
      correlationIdGenerator = opaqueValueGenerator("correlation-", 128);
      baggageGenerator = opaqueValueGenerator("baggage-", 256);
    }
    return new HeaderSpec[] {
        fixedHeader(":authority", "localhost:54321"),
        fixedHeader(":path", "/grpc.testing.BenchmarkService/UnaryCall"),
        fixedHeader(":method", "POST"),
        fixedHeader(":scheme", "http"),
        fixedHeader("content-type", "application/grpc"),
        fixedHeader("te", "trailers"),
        fixedHeader("user-agent", "grpc-java-netty/1.84.0-SNAPSHOT"),
        new HeaderSpec("x-request-id", 100_000, 100_000, requestIdGenerator),
        new HeaderSpec("x-cloud-trace-id", 100_000, 100_000, traceIdGenerator),
        new HeaderSpec("x-request-context", 100_000, 100_000, requestContextGenerator),
        new HeaderSpec("x-correlation-id", 100_000, 100_000, correlationIdGenerator),
        new HeaderSpec("baggage", 100_000, 100_000, baggageGenerator),
        fixedHeader("x-api-version", "v1", "v2"),
        fixedHeader(
            "x-client-region",
            "us-central1",
            "europe-west1",
            "asia-east1",
            "australia-southeast1"),
        fixedHeader("x-client-platform", "linux", "macos", "windows"),
        fixedHeader("accept-language", "en-US", "en-GB", "de-DE", "fr-FR"),
        fixedHeader("x-client-version", "1.0", "1.1", "2.0", "2.1"),
        fixedHeader("x-device-class", "server", "desktop", "mobile"),
        fixedHeader("x-retry-attempt", "0", "1", "2"),
        fixedHeader("x-feature-set", "stable", "beta"),
        fixedHeader("x-traffic-class", "standard", "priority"),
        fixedHeader("x-request-priority", "low", "normal", "high"),
    };
  }

  private static HeaderSpec fixedHeader(String name, final String... values) {
    return new HeaderSpec(name, REQUESTS_PER_CYCLE, values.length, new ValueGenerator() {
      @Override
      public String generate(int valueIndex) {
        return values[valueIndex];
      }
    });
  }

  private static ValueGenerator opaqueValueGenerator(final String prefix, final int length) {
    return new ValueGenerator() {
      @Override
      public String generate(int valueIndex) {
        return syntheticOpaqueValue(prefix, valueIndex, length);
      }
    };
  }

  private static String traceId(int valueIndex) {
    return "105445aa7843bc8bf206b120" + leftPadHex(valueIndex, 8);
  }

  private static String leftPadHex(long value, int length) {
    String hex = Long.toHexString(value);
    StringBuilder result = new StringBuilder(length);
    for (int i = hex.length(); i < length; i++) {
      result.append('0');
    }
    return result.append(hex).toString();
  }

  private static String syntheticOpaqueValue(String prefix, int valueIndex, int length) {
    String suffix = leftPadHex(valueIndex, 8);
    char[] value = new char[length];
    prefix.getChars(0, prefix.length(), value, 0);
    int state = valueIndex ^ 0x6d2b79f5;
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    for (int i = prefix.length(); i < length - suffix.length(); i++) {
      state ^= state << 13;
      state ^= state >>> 17;
      state ^= state << 5;
      value[i] = alphabet.charAt(state & 63);
    }
    suffix.getChars(0, suffix.length(), value, length - suffix.length());
    return new String(value);
  }

  private interface ValueGenerator {
    String generate(int valueIndex);
  }

  private static final class HeaderSpec {
    private final AsciiString name;
    private final int cardinality;
    private final int presenceStride;
    private final ValueGenerator valueGenerator;
    private AsciiString[] values;

    HeaderSpec(
        String name, int requestsPresent, int cardinality, ValueGenerator valueGenerator) {
      if (requestsPresent <= 0 || REQUESTS_PER_CYCLE % requestsPresent != 0) {
        throw new IllegalArgumentException("Presence must divide the request cycle: " + name);
      }
      if (cardinality <= 0 || cardinality > requestsPresent) {
        throw new IllegalArgumentException("Invalid cardinality for " + name);
      }
      this.name = AsciiString.cached(name);
      this.cardinality = cardinality;
      this.presenceStride = REQUESTS_PER_CYCLE / requestsPresent;
      this.valueGenerator = valueGenerator;
    }

    void initializeValues() {
      values = new AsciiString[cardinality];
      for (int i = 0; i < values.length; i++) {
        values[i] = new AsciiString(valueGenerator.generate(i));
      }
    }

    boolean isPresent(int selectedRequest) {
      return selectedRequest % presenceStride == 0;
    }

    AsciiString value(int selectedRequest) {
      int occurrence = selectedRequest / presenceStride;
      return values[occurrence % cardinality];
    }
  }

  /** A zero-allocation view of one request's pre-generated header names and values. */
  private static final class HeaderBlock extends DefaultHttp2Headers {
    private final HeaderSpec[] headerSpecs;
    private final HeaderIterator firstIterator = new HeaderIterator();
    private final HeaderIterator secondIterator = new HeaderIterator();
    private boolean useSecondIterator;
    private int selectedRequest;

    HeaderBlock(HeaderSpec[] headerSpecs) {
      super(false);
      this.headerSpecs = headerSpecs;
    }

    void selectRequest(int selectedRequest) {
      this.selectedRequest = selectedRequest;
      useSecondIterator = false;
    }

    @Override
    public Iterator<Map.Entry<CharSequence, CharSequence>> iterator() {
      HeaderIterator iterator = useSecondIterator ? secondIterator : firstIterator;
      useSecondIterator = true;
      iterator.reset();
      return iterator;
    }

    private final class HeaderIterator
        implements Iterator<Map.Entry<CharSequence, CharSequence>>,
            Map.Entry<CharSequence, CharSequence> {
      private int nextSpecIndex;
      private HeaderSpec nextSpec;
      private HeaderSpec currentSpec;

      void reset() {
        nextSpecIndex = 0;
        nextSpec = null;
        currentSpec = null;
      }

      @Override
      public boolean hasNext() {
        if (nextSpec != null) {
          return true;
        }
        while (nextSpecIndex < headerSpecs.length) {
          HeaderSpec candidate = headerSpecs[nextSpecIndex++];
          if (candidate.isPresent(selectedRequest)) {
            nextSpec = candidate;
            return true;
          }
        }
        return false;
      }

      @Override
      public Map.Entry<CharSequence, CharSequence> next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        currentSpec = nextSpec;
        nextSpec = null;
        return this;
      }

      @Override
      public CharSequence getKey() {
        return currentSpec.name;
      }

      @Override
      public CharSequence getValue() {
        return currentSpec.value(selectedRequest);
      }

      @Override
      public CharSequence setValue(CharSequence value) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void remove() {
        throw new UnsupportedOperationException();
      }
    }
  }
}
