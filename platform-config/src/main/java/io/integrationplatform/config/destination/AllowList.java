package io.integrationplatform.config.destination;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The operator's destination allow-list (ADR-010 section 8): exact host names, or {@code *.domain} for the domain's
 * sub-domains (never the bare domain, never a look-alike suffix). Case-insensitive. Empty refuses everything.
 */
public final class AllowList {

    private final List<String> exact;
    private final List<String> suffixes;

    private AllowList(List<String> exact, List<String> suffixes) {
        this.exact = exact;
        this.suffixes = suffixes;
    }

    public static AllowList of(List<String> patterns) {
        List<String> clean = Objects.requireNonNull(patterns, "patterns").stream()
                .filter(Objects::nonNull)
                .map(AllowList::normalise)
                .filter(p -> !p.isEmpty())
                .toList();
        return new AllowList(
                clean.stream().filter(p -> !p.startsWith("*.")).toList(),
                clean.stream().filter(p -> p.startsWith("*.") && p.length() > 2).map(p -> p.substring(1)).toList());
    }

    public boolean allows(String host) {
        if (host == null) {
            return false;
        }
        String name = normalise(host);
        if (exact.contains(name)) {
            return true;
        }
        for (String suffix : suffixes) { // ".partner.example": a label must precede it
            if (name.length() > suffix.length() && name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    static String normalise(String host) {
        String name = host.trim().toLowerCase(Locale.ROOT);
        return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    }
}
