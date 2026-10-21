package io.integrationplatform.tracking.api;

/**
 * E13 seam (arjun-fullstack): one recorded stage of a message timeline. Timestamps are UTC instant strings;
 * {@code durationMs} is {@code finished - started}; {@code sincePreviousMs} is the gap since the previous stage ended.
 * Plain data, no Spring.
 */
public record StageTime(String stage, String startedAt, String finishedAt, Long durationMs, Long sincePreviousMs) {
}