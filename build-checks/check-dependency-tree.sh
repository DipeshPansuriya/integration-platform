#!/usr/bin/env bash
# R12 dependency-tree check (T-087, scenario "The dependency tree converges").
# Runs dependency:tree over every reactor module and fails unless the resolved trees hold
#   - exactly 1 kafka-clients version, and it is 4.3.1
#   - exactly 1 networknt json-schema-validator version
#   - no spring-kafka at all
# Usage: build-checks/check-dependency-tree.sh   (from anywhere; needs no Docker)
set -u
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root" || exit 2

expected_kafka="4.3.1"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
tree="$out/tree.txt"

if [ ! -x ./mvnw ] || [ ! -f ./pom.xml ]; then
  echo "FAIL: no Maven build here (./mvnw or ./pom.xml missing)"
  exit 1
fi

if ! ./mvnw -q -B dependency:tree -DoutputFile="$tree" -DappendOutput=true > "$out/mvn.log" 2>&1; then
  echo "FAIL: mvn dependency:tree did not run"
  tail -n 30 "$out/mvn.log"
  exit 1
fi

# Tree lines look like "+- org.apache.kafka:kafka-clients:jar:4.3.1:compile".
versions_of() { grep -oE "$1:jar:[^: ]+" "$tree" | awk -F: '{print $4}' | sort -u; }

kafka="$(versions_of 'org\.apache\.kafka:kafka-clients')"
networknt="$(versions_of 'com\.networknt:json-schema-validator')"
spring_kafka_hits="$(grep -c 'org\.springframework\.kafka:' "$tree")"
kafka_count="$(printf '%s' "$kafka" | grep -c .)"
networknt_count="$(printf '%s' "$networknt" | grep -c .)"

echo "kafka-clients versions: ${kafka_count} [$(echo $kafka)]"
echo "networknt json-schema-validator versions: ${networknt_count} [$(echo $networknt)]"
echo "spring-kafka hits: ${spring_kafka_hits}"

fail=0
[ "$kafka_count" -eq 1 ] && [ "$kafka" = "$expected_kafka" ] \
  || { echo "FAIL: expected exactly 1 kafka-clients version ($expected_kafka)"; fail=1; }
[ "$networknt_count" -eq 1 ] \
  || { echo "FAIL: expected exactly 1 networknt json-schema-validator version"; fail=1; }
[ "$spring_kafka_hits" -eq 0 ] \
  || { echo "FAIL: spring-kafka is on the classpath"; fail=1; }

[ "$fail" -eq 0 ] && echo "PASS: dependency tree converges"
exit "$fail"
