package io.integrationplatform.routing.adapter.file;

import io.integrationplatform.routing.stage2.Stage2Send;
import java.util.Optional;

/** T-150: the composition-supplied content of one File send - the mapped file name and the complete body. */
@FunctionalInterface
public interface FileTargetContent {

    Optional<Content> contentFor(Stage2Send send);

    record Content(String fileName, byte[] body) {
    }
}
