package io.integrationplatform;

import java.util.List;

/** R05 + R07 checker for compose files and Testcontainers image references (filled in after the failing test). */
final class ImagePinAndSecretRules {

    private ImagePinAndSecretRules() {
    }

    static List<String> checkCompose(String source, String content) {
        return List.of();
    }

    static List<String> checkJavaImageReferences(String source, String content) {
        return List.of();
    }

    static List<String> checkEnvExample(String source, String content) {
        return List.of();
    }
}
