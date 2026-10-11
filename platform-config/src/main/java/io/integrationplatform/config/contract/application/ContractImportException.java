package io.integrationplatform.config.contract.application;

import io.integrationplatform.config.application.ConfigApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T-105: an imported contract document the parser refused - 400 with the refusal and the parser's own errors
 * ({@code {"error": ..., "parserErrors": [...]}}), so the configurator sees exactly what swagger-parser or the
 * networknt validator said. Leaves through the config error seam like every other {@link ConfigApiException}.
 */
public final class ContractImportException extends ConfigApiException {

    private ContractImportException(Map<String, Object> body) {
        super(400, body);
    }

    /** A refusal with at least one parser error (the message itself stands in when the parser named none). */
    static ContractImportException refused(String error, List<String> parserErrors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("parserErrors", parserErrors.isEmpty() ? List.of(error) : List.copyOf(parserErrors));
        return new ContractImportException(body);
    }
}
