#!/bin/bash -e
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

cd "$(dirname "$0")/.."

./gradlew :grpc-benchmarks:jmhJar -PskipAndroid=true -PskipCodegen=true

if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
else
  JAVA_BIN="java"
fi

JMH_JARS=(benchmarks/build/libs/grpc-benchmarks-*-jmh.jar)
if [[ ${#JMH_JARS[@]} -ne 1 || ! -f "${JMH_JARS[0]}" ]]; then
  echo "Expected exactly one grpc-benchmarks JMH jar" >&2
  exit 1
fi

exec "$JAVA_BIN" -jar "${JMH_JARS[0]}" \
  'io.grpc.benchmarks.netty.PublicMetadataHpackBenchmark' \
  -prof io.grpc.benchmarks.netty.PublicMetadataHpackProfiler \
  "$@"
