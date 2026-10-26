package io.integrationplatform.routing.adapter.file;

/** One resolved File send: the target's folder, the mapped file name and the already-complete body bytes. */
public record FileMessage(FileSettings settings, String fileName, byte[] body) {
}