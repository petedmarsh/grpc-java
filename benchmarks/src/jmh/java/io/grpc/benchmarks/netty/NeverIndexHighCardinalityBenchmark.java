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

import com.google.protobuf.ByteString;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.benchmarks.proto.BenchmarkServiceGrpc;
import io.grpc.benchmarks.proto.Messages.Payload;
import io.grpc.benchmarks.proto.Messages.SimpleRequest;
import io.grpc.benchmarks.proto.Messages.SimpleResponse;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FileRegion;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
 * Measures the end-to-end effect of disabling the HPACK dynamic table.
 *
 * <p>The benchmark sends real plaintext HTTP/2 traffic over a loopback TCP connection. Each unary
 * RPC reuses the same small protobuf request and response, 100,000 distinct sets of five
 * high-cardinality metadata values rotate between calls, and ten low-cardinality fields reuse
 * small value sets. The value-length parameter compares conventional short identifiers with
 * synthetic opaque values large enough to churn the 4 KiB HPACK dynamic table. The wire-byte
 * metric counts bytes handed to the client socket, including HTTP/2 framing and DATA bodies but
 * excluding TCP/IP
 * headers and retransmissions. Run it with {@code
 * benchmarks/run-never-index-high-cardinality-benchmark.sh} to include process-wide CPU and client
 * outbound wire-byte metrics. The normal policy uses the default 4 KiB table. The disabled policy
 * calls {@link NettyChannelBuilder#disableHpackDynamicTable()}, which disables dynamic-table use in
 * both directions while retaining static-table references and Huffman encoding.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@Threads(1)
public class NeverIndexHighCardinalityBenchmark {
  private static final int CARDINALITY = 100_000;
  private static final int BODY_SIZE = 32;

  private static final Metadata.Key<String> REQUEST_ID_KEY =
      Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> CLOUD_TRACE_ID_KEY =
      Metadata.Key.of("x-cloud-trace-id", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> REQUEST_CONTEXT_KEY =
      Metadata.Key.of("x-request-context", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> CORRELATION_ID_KEY =
      Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> BAGGAGE_KEY =
      Metadata.Key.of("baggage", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> API_VERSION_KEY =
      Metadata.Key.of("x-api-version", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> CLIENT_REGION_KEY =
      Metadata.Key.of("x-client-region", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> CLIENT_PLATFORM_KEY =
      Metadata.Key.of("x-client-platform", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> ACCEPT_LANGUAGE_KEY =
      Metadata.Key.of("accept-language", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> CLIENT_VERSION_KEY =
      Metadata.Key.of("x-client-version", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> DEVICE_CLASS_KEY =
      Metadata.Key.of("x-device-class", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> RETRY_ATTEMPT_KEY =
      Metadata.Key.of("x-retry-attempt", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> FEATURE_SET_KEY =
      Metadata.Key.of("x-feature-set", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> TRAFFIC_CLASS_KEY =
      Metadata.Key.of("x-traffic-class", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> REQUEST_PRIORITY_KEY =
      Metadata.Key.of("x-request-priority", Metadata.ASCII_STRING_MARSHALLER);

  private static final String[] API_VERSIONS = {"v1", "v2"};
  private static final String[] CLIENT_REGIONS =
      {"us-central1", "europe-west1", "asia-east1", "australia-southeast1"};
  private static final String[] CLIENT_PLATFORMS = {"linux", "macos", "windows"};
  private static final String[] ACCEPT_LANGUAGES = {"en-US", "en-GB", "de-DE", "fr-FR"};
  private static final String[] CLIENT_VERSIONS = {"1.0", "1.1", "2.0", "2.1"};
  private static final String[] DEVICE_CLASSES = {"server", "desktop", "mobile"};
  private static final String[] RETRY_ATTEMPTS = {"0", "1", "2"};
  private static final String[] FEATURE_SETS = {"stable", "beta"};
  private static final String[] TRAFFIC_CLASSES = {"standard", "priority"};
  private static final String[] REQUEST_PRIORITIES = {"low", "normal", "high"};
  private static final AtomicLong CLIENT_OUTBOUND_BYTES = new AtomicLong();

  private static final ByteString BODY = ByteString.copyFrom(new byte[BODY_SIZE]);
  private static final SimpleRequest REQUEST = SimpleRequest.newBuilder()
      .setPayload(Payload.newBuilder().setBody(BODY))
      .build();
  private static final SimpleResponse RESPONSE = SimpleResponse.newBuilder()
      .setPayload(Payload.newBuilder().setBody(BODY))
      .build();

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

  private Server server;
  private ManagedChannel channel;
  private EventLoopGroup clientEventLoopGroup;
  private BenchmarkServiceGrpc.BenchmarkServiceBlockingStub stub;

  @Setup(Level.Trial)
  public void setUp() throws Exception {
    server = NettyServerBuilder.forPort(0, InsecureServerCredentials.create())
        .directExecutor()
        .addService(new ConstantResponseService())
        .build()
        .start();

    @SuppressWarnings("deprecation") // Wait a bit before migrating to the Netty 4.2 API.
    EventLoopGroup eventLoopGroup = new io.netty.channel.nio.NioEventLoopGroup(1);
    clientEventLoopGroup = eventLoopGroup;

    NettyChannelBuilder channelBuilder = NettyChannelBuilder
        .forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort()))
        .channelFactory(new CountingChannelFactory(), InetSocketAddress.class)
        .eventLoopGroup(eventLoopGroup)
        .directExecutor()
        .usePlaintext()
        .intercept(new RotatingMetadataInterceptor(valueLength));
    if (dynamicTablePolicy == DynamicTablePolicy.TABLE_DISABLED) {
      channelBuilder.disableHpackDynamicTable();
    }
    channel = channelBuilder.build();
    stub = BenchmarkServiceGrpc.newBlockingStub(channel);

    // Establish the connection before JMH starts measuring an iteration.
    stub.unaryCall(REQUEST);
  }

  @TearDown(Level.Trial)
  public void tearDown() throws Exception {
    if (channel != null) {
      channel.shutdownNow();
      channel.awaitTermination(5, TimeUnit.SECONDS);
    }
    if (server != null) {
      server.shutdownNow();
      server.awaitTermination(5, TimeUnit.SECONDS);
    }
    if (clientEventLoopGroup != null) {
      clientEventLoopGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
    }
  }

  @Benchmark
  public SimpleResponse unaryRpc() {
    return stub.unaryCall(REQUEST);
  }

  static long clientOutboundBytes() {
    return CLIENT_OUTBOUND_BYTES.get();
  }

  private static final class ConstantResponseService
      extends BenchmarkServiceGrpc.BenchmarkServiceImplBase {
    @Override
    public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
      responseObserver.onNext(RESPONSE);
      responseObserver.onCompleted();
    }
  }

  private static final class RotatingMetadataInterceptor implements ClientInterceptor {
    private final Metadata[] metadata;
    private int index;

    RotatingMetadataInterceptor(ValueLength valueLength) {
      metadata = createMetadata(valueLength);
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method,
        io.grpc.CallOptions callOptions,
        io.grpc.Channel next) {
      return new SimpleForwardingClientCall<ReqT, RespT>(
          next.newCall(method, callOptions)) {
        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          headers.merge(metadata[index++]);
          if (index == CARDINALITY) {
            index = 0;
          }
          super.start(responseListener, headers);
        }
      };
    }

    private static Metadata[] createMetadata(ValueLength valueLength) {
      Metadata[] values = new Metadata[CARDINALITY];
      for (int i = 0; i < values.length; i++) {
        Metadata headers = new Metadata();
        if (valueLength == ValueLength.LOW_LENGTH) {
          String traceId = "105445aa7843bc8bf206b120" + leftPadHex(i, 8);
          headers.put(REQUEST_ID_KEY, "00000000-0000-4000-8000-" + leftPadHex(i, 12));
          headers.put(CLOUD_TRACE_ID_KEY, traceId);
          headers.put(REQUEST_CONTEXT_KEY,
              "00-" + traceId + "-" + leftPadHex(i * 31L + 1, 16) + "-01");
          headers.put(
              CORRELATION_ID_KEY, "10000000-2000-4000-8000-" + leftPadHex(i, 12));
          headers.put(BAGGAGE_KEY, "request.id=" + traceId);
        } else {
          headers.put(REQUEST_ID_KEY, syntheticOpaqueValue("request-", i, 128));
          headers.put(CLOUD_TRACE_ID_KEY, syntheticOpaqueValue("trace-", i, 256));
          headers.put(REQUEST_CONTEXT_KEY, syntheticOpaqueValue("context-", i, 512));
          headers.put(CORRELATION_ID_KEY, syntheticOpaqueValue("correlation-", i, 128));
          headers.put(BAGGAGE_KEY, syntheticOpaqueValue("baggage-", i, 256));
        }
        headers.put(API_VERSION_KEY, select(i, API_VERSIONS));
        headers.put(CLIENT_REGION_KEY, select(i, CLIENT_REGIONS));
        headers.put(CLIENT_PLATFORM_KEY, select(i, CLIENT_PLATFORMS));
        headers.put(ACCEPT_LANGUAGE_KEY, select(i, ACCEPT_LANGUAGES));
        headers.put(CLIENT_VERSION_KEY, select(i, CLIENT_VERSIONS));
        headers.put(DEVICE_CLASS_KEY, select(i, DEVICE_CLASSES));
        headers.put(RETRY_ATTEMPT_KEY, select(i, RETRY_ATTEMPTS));
        headers.put(FEATURE_SET_KEY, select(i, FEATURE_SETS));
        headers.put(TRAFFIC_CLASS_KEY, select(i, TRAFFIC_CLASSES));
        headers.put(REQUEST_PRIORITY_KEY, select(i, REQUEST_PRIORITIES));
        values[i] = headers;
      }
      return values;
    }
  }

  private static String select(int requestIndex, String... values) {
    return values[requestIndex % values.length];
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

  private static final class CountingChannelFactory implements ChannelFactory<NioSocketChannel> {
    @Override
    public NioSocketChannel newChannel() {
      return new CountingNioSocketChannel();
    }
  }

  private static final class CountingNioSocketChannel extends NioSocketChannel {
    CountingNioSocketChannel() {
      pipeline().addFirst("benchmark-wire-byte-counter", new WireByteCountingHandler());
    }
  }

  private static final class WireByteCountingHandler extends ChannelOutboundHandlerAdapter {
    @Override
    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
      if (message instanceof ByteBuf) {
        CLIENT_OUTBOUND_BYTES.addAndGet(((ByteBuf) message).readableBytes());
      } else if (message instanceof FileRegion) {
        CLIENT_OUTBOUND_BYTES.addAndGet(((FileRegion) message).count());
      }
      ChannelFuture unused = ctx.write(message, promise);
    }
  }
}
