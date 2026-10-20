package io.integrationplatform.config.system.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * T-182 (E10-F1-S5; ADR-010 section 5): the outcome table is the DEFAULT and a target's error dictionary may
 * re-class a status for that target - but never a rule the runtime refuses to bend. {@code OutcomeClassifier}
 * ignores the dictionary for 400, 401, 403 and 422, and a 2xx can never be anything but SUCCESS, so a dictionary
 * that pretends otherwise is a configurator's mistake: it is refused where the target is saved, not discovered
 * when a delivery dies.
 *
 * <p>A class an entry shares with the default table is refused too (503 is already TRANSIENT): the dictionary is
 * for documented target behaviour, not noise that survives a review of "what does this target override".
 */
public final class ErrorDictionaryCheck {

    /** The same codes {@code OutcomeClassifier} keeps permanently PERMANENT (400/401/403/422), plus auth 401/403. */
    private static final Set<Integer> NEVER_RETRYABLE = Set.of(400, 401, 403, 422);

    /** The classes an override may name - SUCCESS is not one of them (a 2xx is a delivered message). */
    private static final Set<String> OVERRIDE_CLASSES = Set.of("TRANSIENT", "AMBIGUOUS", "PERMANENT");

    private ErrorDictionaryCheck() {
    }

    /** Every problem with the dictionary node, as messages without the {@code settings.errorDictionary} prefix. */
    public static List<String> entryErrors(JsonNode dictionary) {
        List<String> errors = new ArrayList<>();
        for (String key : dictionary.propertyNames()) {
            JsonNode value = dictionary.get(key);
            String klass = value.isString() ? value.stringValue().trim().toUpperCase(Locale.ROOT) : "";
            int status;
            try {
                status = Integer.parseInt(key.trim());
            } catch (NumberFormatException notANumber) {
                errors.add("\"" + clip(key) + "\" is not an HTTP status code");
                continue;
            }
            if (status < 300 || status > 599) {
                errors.add(status + (status < 300 ? " is a success status - a dictionary never re-classes a 2xx"
                        : " is not an HTTP status (300-599)"));
                continue;
            }
            if (!OVERRIDE_CLASSES.contains(klass)) {
                errors.add(status + " class \"" + clip(klass) + "\" is not one of TRANSIENT, AMBIGUOUS, PERMANENT");
                continue;
            }
            if (NEVER_RETRYABLE.contains(status) && !klass.equals("PERMANENT")) {
                errors.add(status + " is never retryable - 400, 401, 403 and 422 stay PERMANENT whatever the "
                        + "dictionary says (ADR-010 section 5)");
                continue;
            }
            if (status == 429 && klass.equals("PERMANENT")) {
                errors.add("429 is never PERMANENT - the target said \"later\" (ADR-010 section 5)");
                continue;
            }
            if (defaultClass(status).equals(klass)) {
                errors.add(status + " is already " + klass + " in the default table - an override must change "
                        + "something");
            }
        }
        return List.copyOf(errors);
    }

    /** The default class of a status the dictionary cannot change by rule: mirrors {@code OutcomeClassifier}. */
    private static String defaultClass(int status) {
        if (NEVER_RETRYABLE.contains(status)) {
            return "PERMANENT";
        }
        if (status == 429 || status == 503) {
            return "TRANSIENT";
        }
        if (status == 408 || status >= 500) {
            return "AMBIGUOUS";
        }
        return "PERMANENT";
    }

    private static String clip(String text) {
        String flat = text.replaceAll("\\p{Cntrl}", "_");
        return flat.length() > 64 ? flat.substring(0, 64) + "..." : flat;
    }
}
