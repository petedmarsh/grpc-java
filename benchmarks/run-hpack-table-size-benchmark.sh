#!/usr/bin/env bash
# Copyright 2026 The gRPC Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -euo pipefail

BENCHMARK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BENCHMARK_PROJECT="$BENCHMARK_ROOT/benchmarks/hpack-table-size"

"$BENCHMARK_ROOT/gradlew" -p "$BENCHMARK_PROJECT" --console=plain installDist

if [[ "${1:-}" == "--validate" ]]; then
  exec "$BENCHMARK_ROOT/gradlew" -p "$BENCHMARK_PROJECT" --console=plain validateFixtures
fi

exec "$BENCHMARK_PROJECT/build/install/hpack-table-size/bin/hpack-table-size" \
  'io.grpc.benchmarks.netty.HpackTableSizeBenchmark.*' -prof gc \
  -prof io.grpc.benchmarks.netty.HpackTableSizeProfiler -foe true "$@"
