package io.integrationplatform.tenancy.internal.web;

import io.integrationplatform.tenancy.port.DeniedTenantAttempt.Channel;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds the places a request names a tenant (architecture.md section 5, control T1): any header whose name contains
 * "tenant", a query parameter and a JSON body field named tenantId, tenant_id or tenant. The values are only compared,
 * never used and never logged.
 */
final class TenantFieldScanner {

    /** How much of a JSON body is inspected; the controller still receives the whole body. */
    static final int BODY_LIMIT = 256 * 1024;

    private static final Set<String> FIELD_NAMES = Set.of("tenantid", "tenant_id", "tenant");

    /** The JSON body fields that name a customer (E4 T-098, ADR-007 Decision 2 body cross-check). */
    private static final Set<String> CUSTOMER_FIELD_NAMES = Set.of("customerid", "customer_id");

    record TenantField(Channel channel, String value) {
    }

    /**
     * A JSON body field naming a customer.
     *
     * @param value the scalar value, or {@code null} for a structured value (which never names the bound customer)
     */
    record CustomerField(String value) {
    }

    private TenantFieldScanner() {
    }

    /**
     * Every customerId / customer_id field of a JSON body, at any depth (a bulk body names it per record), any case.
     * A JSON null names no customer (absent); a structured value is kept with a null value. A malformed body keeps
     * the fields found before the break - the controller rejects such a body itself.
     */
    static List<CustomerField> customerFields(byte[] body) {
        List<CustomerField> found = new ArrayList<>();
        if (body.length == 0) {
            return found;
        }
        try (JsonParser parser = JsonMapper.shared().createParser(body)) {
            for (JsonToken token = parser.nextToken(); token != null; token = parser.nextToken()) {
                if (token == JsonToken.PROPERTY_NAME
                        && CUSTOMER_FIELD_NAMES.contains(parser.currentName().toLowerCase(Locale.ROOT))) {
                    JsonToken value = parser.nextToken();
                    if (value == null || value == JsonToken.VALUE_NULL) {
                        continue;
                    }
                    found.add(new CustomerField(value.isScalarValue() ? parser.getString() : null));
                }
            }
        } catch (JacksonException truncatedOrMalformed) {
            // ponytail: as scanJson - the controller rejects a malformed body; the fields found so far still count.
        }
        return found;
    }

    static List<TenantField> scan(HttpServletRequest request, byte[] jsonBodyPrefix) {
        List<TenantField> found = new ArrayList<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            if (name.toLowerCase(Locale.ROOT).contains("tenant")) {
                for (String value : Collections.list(request.getHeaders(name))) {
                    found.add(new TenantField(Channel.HEADER, value));
                }
            }
        }
        scanQuery(request.getQueryString(), found);
        scanJson(jsonBodyPrefix, found);
        return found;
    }

    static boolean isJson(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.isCompatibleWith(type) || type.getSubtype().endsWith("+json");
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    /** The first {@link #BODY_LIMIT} bytes of a JSON body; for any other body nothing is read. */
    static byte[] jsonBodyPrefix(HttpServletRequest request) {
        if (!isJson(request)) {
            return new byte[0];
        }
        try (InputStream in = request.getInputStream()) {
            return in.readNBytes(BODY_LIMIT);
        } catch (IOException | IllegalStateException e) {
            return new byte[0];
        }
    }

    private static void scanQuery(String query, List<TenantField> found) {
        if (query == null) {
            return;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            if (FIELD_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                found.add(new TenantField(Channel.QUERY_PARAMETER, eq < 0 ? "" : decode(pair.substring(eq + 1))));
            }
        }
    }

    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return raw;
        }
    }

    /** Field names at any depth; a truncated or malformed body keeps what was found before the break. */
    private static void scanJson(byte[] body, List<TenantField> found) {
        if (body.length == 0) {
            return;
        }
        try (JsonParser parser = JsonMapper.shared().createParser(body)) {
            for (JsonToken token = parser.nextToken(); token != null; token = parser.nextToken()) {
                if (token == JsonToken.PROPERTY_NAME
                        && FIELD_NAMES.contains(parser.currentName().toLowerCase(Locale.ROOT))) {
                    JsonToken value = parser.nextToken();
                    // A structured value names no tenant we could match, so it never counts as the bound one.
                    found.add(new TenantField(Channel.BODY_FIELD,
                            value != null && value.isScalarValue() ? parser.getString() : ""));
                }
            }
        } catch (JacksonException truncatedOrMalformed) {
            // ponytail: the controller rejects a malformed body itself; inspection keeps the fields found so far.
        }
    }
}
