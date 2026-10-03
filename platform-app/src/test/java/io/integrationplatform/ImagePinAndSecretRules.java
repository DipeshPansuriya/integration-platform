package io.integrationplatform;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R05 + R07 checker for compose files, the compose env example and Testcontainers image references.
 * Pinned = an exact version tag (digits.digits[.digits][-suffix]) or an @sha256 digest; never latest, a bare major
 * or no tag. A password/secret/token/key value must be a ${VAR} reference (whole value), never a literal.
 * ponytail: line-based scan, not a YAML parser - compose and test sources are small and written by us.
 */
final class ImagePinAndSecretRules {

    private static final Pattern PINNED_TAG = Pattern.compile("\\d+\\.\\d+(\\.\\d+)?(-[A-Za-z0-9][A-Za-z0-9._-]*)?");
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(PASSWORD|PASSWD|SECRET|TOKEN|API_?KEY|PRIVATE_?KEY).*");
    private static final Pattern VARIABLE_ONLY = Pattern.compile("\\$\\{[A-Za-z_][A-Za-z0-9_]*([:?\\-+][^}]*)?}");

    private static final Pattern COMPOSE_IMAGE = Pattern.compile("^\\s*image:\\s*(.*?)\\s*$");
    private static final Pattern COMPOSE_MAP_ENTRY = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*(.*?)\\s*$");
    private static final Pattern COMPOSE_LIST_ENTRY = Pattern.compile("^\\s*-\\s*([A-Za-z_][A-Za-z0-9_]*)=(.*?)\\s*$");
    private static final Pattern ENV_LINE = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=(.*)$");

    /** A string constant in a test source (TestImages style) or a literal passed to a container / DockerImageName. */
    private static final Pattern JAVA_IMAGE_LITERAL = Pattern.compile(
            "(?:String\\s+[A-Z][A-Z0-9_]*\\s*=\\s*|DockerImageName\\.parse\\(\\s*|new\\s+\\w*Container\\s*(?:<[^>]*>)?\\(\\s*)\"([^\"]*)\"");

    private ImagePinAndSecretRules() {
    }

    static List<String> checkCompose(String source, String content) {
        List<String> findings = new ArrayList<>();
        String[] lines = content.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = stripComment(lines[i]);
            Matcher image = COMPOSE_IMAGE.matcher(line);
            if (image.matches()) {
                String ref = unquote(image.group(1));
                if (!isPinned(ref)) {
                    findings.add(source + ":" + (i + 1) + " image not pinned to an exact version: " + ref);
                }
                continue;
            }
            Matcher entry = COMPOSE_LIST_ENTRY.matcher(line);
            if (!entry.matches()) {
                entry = COMPOSE_MAP_ENTRY.matcher(line);
            }
            boolean nestedBlock = entry.pattern() == COMPOSE_MAP_ENTRY && entry.matches() && entry.group(2).isEmpty();
            if (entry.matches() && !nestedBlock && SECRET_KEY.matcher(entry.group(1)).matches()) {
                String value = unquote(entry.group(2));
                if (!VARIABLE_ONLY.matcher(value).matches()) {
                    findings.add(source + ":" + (i + 1) + " " + entry.group(1) + " is not read from a ${VAR} reference");
                }
            }
        }
        return findings;
    }

    static List<String> checkJavaImageReferences(String source, String content) {
        List<String> findings = new ArrayList<>();
        Matcher literal = JAVA_IMAGE_LITERAL.matcher(content);
        while (literal.find()) {
            String ref = literal.group(1);
            boolean viaContainerApi = !literal.group().startsWith("String");
            boolean imageConstant = source.endsWith("TestImages.java") || looksLikeTaggedImage(ref);
            if ((viaContainerApi || imageConstant) && !isPinned(ref)) {
                findings.add(source + " image not pinned to an exact version: " + ref);
            }
        }
        return findings;
    }

    static List<String> checkEnvExample(String source, String content) {
        List<String> findings = new ArrayList<>();
        String[] lines = content.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            Matcher entry = ENV_LINE.matcher(stripComment(lines[i]));
            if (entry.matches() && !entry.group(2).isBlank()) {
                findings.add(source + ":" + (i + 1) + " " + entry.group(1) + " has a value; the example holds names only");
            }
        }
        return findings;
    }

    static boolean isPinned(String ref) {
        int at = ref.indexOf('@');
        if (at >= 0) {
            return DIGEST.matcher(ref.substring(at + 1)).matches();
        }
        int colon = ref.lastIndexOf(':');
        if (colon < 0 || colon < ref.lastIndexOf('/')) {
            return false;
        }
        return PINNED_TAG.matcher(ref.substring(colon + 1)).matches();
    }

    /** Outside TestImages a constant counts as an image when it reads repository:tag or repository@digest. */
    private static boolean looksLikeTaggedImage(String value) {
        return value.matches("[a-z0-9]+([._/-][a-z0-9]+)*(:[^\\s]*|@.*)");
    }

    private static String stripComment(String line) {
        int hash = line.indexOf(" #");
        return line.trim().startsWith("#") ? "" : hash >= 0 ? line.substring(0, hash) : line;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
