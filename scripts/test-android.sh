#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# A user-level org.gradle.java.home overrides JAVA_HOME unless set explicitly.
TEST_JAVA_HOME="${ANDROID_TEST_JAVA_HOME:-}"
if [[ -z "$TEST_JAVA_HOME" ]]; then
  for candidate in /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; do
    if [[ -x "$candidate/bin/java" ]]; then
      TEST_JAVA_HOME="$candidate"
      break
    fi
  done
fi
TEST_JAVA_HOME="${TEST_JAVA_HOME:-${JAVA_HOME:-}}"
if [[ -z "$TEST_JAVA_HOME" ]]; then
  echo "Set JAVA_HOME or ANDROID_TEST_JAVA_HOME to a Gradle-compatible JDK (Java 21 recommended)." >&2
  exit 2
fi
cd "$ROOT"
export JAVA_HOME="$TEST_JAVA_HOME"
exec ./gradlew "-Dorg.gradle.java.home=$TEST_JAVA_HOME" testDebugUnitTest --console=plain --no-problems-report "$@"
