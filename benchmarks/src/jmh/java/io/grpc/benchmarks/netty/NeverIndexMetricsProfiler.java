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

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Collection;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.results.AggregationPolicy;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.ScalarResult;

/** Reports process-wide CPU time and client outbound HTTP/2 bytes per benchmark operation. */
public final class NeverIndexMetricsProfiler implements InternalProfiler {
  private final com.sun.management.OperatingSystemMXBean operatingSystem;
  private long cpuTimeBefore;
  private long clientOutboundBytesBefore;

  public NeverIndexMetricsProfiler() {
    java.lang.management.OperatingSystemMXBean operatingSystem =
        ManagementFactory.getOperatingSystemMXBean();
    if (!(operatingSystem instanceof com.sun.management.OperatingSystemMXBean)) {
      throw new IllegalStateException("Process CPU time is unavailable on this JVM");
    }
    this.operatingSystem = (com.sun.management.OperatingSystemMXBean) operatingSystem;
  }

  @Override
  public String getDescription() {
    return "Process CPU time and client outbound HTTP/2 bytes per operation";
  }

  @Override
  public void beforeIteration(BenchmarkParams benchmarkParams, IterationParams iterationParams) {
    cpuTimeBefore = operatingSystem.getProcessCpuTime();
    clientOutboundBytesBefore = NeverIndexHighCardinalityBenchmark.clientOutboundBytes();
  }

  @Override
  @SuppressWarnings("rawtypes") // InternalProfiler declares its result using raw Result.
  public Collection<? extends Result> afterIteration(
      BenchmarkParams benchmarkParams,
      IterationParams iterationParams,
      IterationResult result) {
    long operations = result.getMetadata().getMeasuredOps();
    if (operations == 0) {
      return Arrays.asList();
    }
    long cpuNanos = operatingSystem.getProcessCpuTime() - cpuTimeBefore;
    long clientOutboundBytes =
        NeverIndexHighCardinalityBenchmark.clientOutboundBytes() - clientOutboundBytesBefore;
    return Arrays.asList(
        new ScalarResult(
            "processCpu",
            cpuNanos / (operations * 1_000.0),
            "us/op",
            AggregationPolicy.AVG),
        new ScalarResult(
            "clientOutboundBytes",
            clientOutboundBytes / (double) operations,
            "bytes/op",
            AggregationPolicy.AVG));
  }
}
