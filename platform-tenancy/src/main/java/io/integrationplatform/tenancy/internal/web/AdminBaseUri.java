package io.integrationplatform.tenancy.internal.web;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * F1 (row 417): the rule for the optional Keycloak admin base URI. The app sends an admin-capable bearer token and the
 * new client secret to this address, so an unsafe value stops the application at start-up (fail closed) instead of
 * logging a warning.
 *
 * <p>Accepted: {@code https}, or plain {@code http} to an internal host - a single-label service name (no dots), a
 * loopback address, or an RFC1918 / unique-local (ULA) address. An {@code https} host other than the issuer host needs
 * the explicit opt-in {@code platform.tenancy.identity-admin.allow-external} (env {@code KEYCLOAK_ADMIN_ALLOW_EXTERNAL},
 * default off). User info, a query, a fragment, a port outside 1-65535 and any scheme but http(s) are refused. Unset or
 * blank = null (the admin URL is derived from the issuer, as before).
 *
 * <p>No DNS lookup is ever made: only a literal address is parsed. A refusal names the settings and never the value
 * or any part of it (it may hold a password), and carries no cause (the parser's exception quotes its input).
 */
final class AdminBaseUri {

    private static final String REFUSED = "The identity provider admin base URI "
            + "(platform.tenancy.identity-admin.base-uri, env KEYCLOAK_ADMIN_BASE_URI) is refused: ";

    /** A single-label service name: starts with a letter; letters, digits and hyphens only (no all-digit, hex or octal host). */
    private static final Pattern SERVICE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9-]*");

    private AdminBaseUri() {
    }

    /** The slash-trimmed admin base, or null when unset or blank; {@link IllegalArgumentException} when unsafe. */
    static String resolve(String raw, String issuer, boolean allowExternal) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String base = stripSlash(raw.strip());
        URI parsed;
        try {
            parsed = new URI(base);
        } catch (URISyntaxException malformed) {
            throw refused("it must be a plain http(s) server address (no user info, query or fragment)");
        }
        String scheme = parsed.getScheme();
        boolean http = "http".equalsIgnoreCase(scheme);
        boolean https = "https".equalsIgnoreCase(scheme);
        String authority = parsed.getRawAuthority();
        if (!http && !https || authority == null) {
            throw refused("it must be a plain http(s) server address (no user info, query or fragment)");
        }
        if (parsed.getRawUserInfo() != null || authority.indexOf('@') >= 0) {
            throw refused("it must not contain user info (credentials in the URL)");
        }
        if (parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
            throw refused("it must be a plain http(s) server address (no user info, query or fragment)");
        }
        String host = parsed.getHost();
        if (host == null) {
            throw refused(authority.indexOf('_') >= 0
                    ? "the host name contains an underscore, which is not valid in a host name; use a hyphen or an IP address"
                    : "it must be a plain http(s) server address (no user info, query or fragment)");
        }
        int port = parsed.getPort();
        if (authority.endsWith(":") || port == 0 || port > 65535) {
            throw refused("the port must be a number from 1 to 65535");
        }
        if (http && !internalHost(host)) {
            throw refused("plain http is accepted only to an internal host (a single-label service name, a loopback "
                    + "or a private address); the admin token and the client secret must not cross an untrusted "
                    + "network unencrypted - use https or an internal address");
        }
        if (https && !allowExternal && !sameHost(host, issuer)) {
            throw refused("https to a host other than the issuer host is accepted only with "
                    + "platform.tenancy.identity-admin.allow-external=true (env KEYCLOAK_ADMIN_ALLOW_EXTERNAL, "
                    + "default off)");
        }
        return base;
    }

    private static IllegalArgumentException refused(String reason) {
        return new IllegalArgumentException(REFUSED + reason);
    }

    private static boolean sameHost(String host, String issuer) {
        try {
            String issuerHost = new URI(issuer.strip()).getHost();
            return issuerHost != null && unbracket(issuerHost).equalsIgnoreCase(unbracket(host));
        } catch (URISyntaxException unreadable) {
            return false;
        }
    }

    /** Loopback, RFC1918 or ULA literal, or a service name that is one label of letters, digits and hyphens. */
    private static boolean internalHost(String host) {
        String h = unbracket(host);
        if (h.indexOf(':') >= 0) {
            try {
                // ofLiteral parses an address literal only and never queries DNS (it refuses a name)
                return privateAddress(InetAddress.ofLiteral(h));
            } catch (IllegalArgumentException notLiteral) {
                return false;
            }
        }
        byte[] v4 = ipv4(h);
        if (v4 != null) {
            return privateV4(v4);
        }
        return SERVICE_NAME.matcher(h).matches();
    }

    private static boolean privateAddress(InetAddress ip) {
        if (ip instanceof Inet4Address) {
            return privateV4(ip.getAddress());
        }
        if (ip instanceof Inet6Address) {
            return ip.isLoopbackAddress() || (ip.getAddress()[0] & 0xFE) == 0xFC;   // ::1 and fc00::/7
        }
        return false;
    }

    private static boolean privateV4(byte[] ip) {
        int a = ip[0] & 0xFF;
        int b = ip[1] & 0xFF;
        return a == 127 || a == 10 || a == 172 && b >= 16 && b <= 31 || a == 192 && b == 168;
    }

    /** A dotted quad of plain decimal octets 0-255 (no leading zeros: the notation is ambiguous), else null. */
    private static byte[] ipv4(String h) {
        String[] parts = h.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3 || p.length() > 1 && p.charAt(0) == '0' || !p.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return null;
            }
            int n = Integer.parseInt(p);
            if (n > 255) {
                return null;
            }
            out[i] = (byte) n;
        }
        return out;
    }

    private static String unbracket(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private static String stripSlash(String uri) {
        int end = uri.length();
        while (end > 0 && uri.charAt(end - 1) == '/') {
            end--;
        }
        return uri.substring(0, end);
    }
}
