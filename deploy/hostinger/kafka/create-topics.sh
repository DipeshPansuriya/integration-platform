#!/bin/sh
# kafka-init: creates the platform's record topics (auto-create is off). Names: SystemTopicRefusal.PLATFORM_TOPICS
# (platform-config .../SystemTopicRefusal.java:21-22) = the closed register of ADR-005. Idempotent (--if-not-exists).
# Partitions default 3 like the test harness (E18Base.java:133); ADR-005 D6 names 12 - raise KAFKA_PARTITIONS to match.
# DRY_RUN=1 prints the commands only.
set -eu
BOOT="${KAFKA_BOOTSTRAP:-kafka:9092}"
PARTS="${KAFKA_PARTITIONS:-3}"
TOPICS="platform.message.accepted platform.delivery.due platform.poison"
BIN="${KAFKA_BIN:-/opt/kafka/bin/kafka-topics.sh}"
run() { if [ "${DRY_RUN:-0}" = 1 ]; then echo "$@"; else "$@"; fi; }
for t in $TOPICS; do
  run "$BIN" --bootstrap-server "$BOOT" --create --if-not-exists --topic "$t" --partitions "$PARTS" \
    --replication-factor 1 --config retention.ms=604800000 --config retention.bytes=268435456
done
run "$BIN" --bootstrap-server "$BOOT" --list
