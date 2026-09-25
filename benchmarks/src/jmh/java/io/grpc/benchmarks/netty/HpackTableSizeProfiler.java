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

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.results.AggregationPolicy;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.ScalarResult;

/** Measures actual HPACK bytes per request without atomic operations in the measured method. */
public final class HpackTableSizeProfiler implements InternalProfiler {
  private static final AtomicLong BYTES = new AtomicLong();
  private static final AtomicLong REQUESTS = new AtomicLong();

  static void record(long bytes, long requests) {
    BYTES.addAndGet(bytes);
    REQUESTS.addAndGet(requests);
  }

  @Override
  public String getDescription() {
    return "Actual HPACK payload bytes per encoded request";
  }

  @Override
  public void beforeIteration(BenchmarkParams benchmarkParams, IterationParams iterationParams) {
    BYTES.set(0);
    REQUESTS.set(0);
  }

  @Override
  @SuppressWarnings("rawtypes") // InternalProfiler's signature uses raw Result.
  public Collection<? extends Result> afterIteration(
      BenchmarkParams benchmarkParams, IterationParams iterationParams, IterationResult result) {
    long requests = REQUESTS.get();
    if (requests == 0) {
      return Collections.emptyList();
    }
    return Collections.singletonList(new ScalarResult(
        "encodedBytes", BYTES.get() / (double) requests, "B/op", AggregationPolicy.AVG));
  }
}
