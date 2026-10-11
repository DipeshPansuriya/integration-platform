package io.integrationplatform.config.destination;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Optional;

/**
 * The address ranges no outbound call may reach (ADR-010 section 8), checked on every RESOLVED address: loopback
 * (127/8, ::1), this network (0/8), unspecified (::), private (10/8, 172.16/12, 192.168/16), carrier-grade NAT
 * (100.64/10), link-local incl. cloud metadata (169.254/16, fe80::/10), IPv6 unique-local (fc00::/7), multicast
 * (224/4, ff00::/8) and broadcast. IPv6 forms that embed an IPv4 address - IPv4-mapped (::ffff:0:0/96),
 * IPv4-compatible (::/96), NAT64 (64:ff9b::/96) and 6to4 (2002::/16) - are checked as the embedded IPv4 address.
 */
public final class RefusedRanges {

    private RefusedRanges() {
    }

    /** The refused range {@code address} falls in, by name; empty for a public address. */
    public static Optional<String> rangeOf(InetAddress address) {
        byte[] b = address.getAddress();
        return Optional.ofNullable(address instanceof Inet4Address || b.length == 4 ? ipv4(b, 0) : ipv6(b));
    }

    private static String ipv4(byte[] bytes, int at) {
        int a = bytes[at] & 0xff;
        int b = bytes[at + 1] & 0xff;
        if (a == 0) {
            return "this-network";
        }
        if (a == 127) {
            return "loopback";
        }
        if (a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)) {
            return "private";
        }
        if (a == 100 && b >= 64 && b <= 127) {
            return "carrier-grade-nat";
        }
        if (a == 169 && b == 254) {
            return "link-local";
        }
        if (a >= 224 && a <= 239) {
            return "multicast";
        }
        if (a == 255 && b == 255 && (bytes[at + 2] & 0xff) == 255 && (bytes[at + 3] & 0xff) == 255) {
            return "broadcast";
        }
        return null;
    }

    private static String ipv6(byte[] b) {
        boolean first10Zero = allZero(b, 0, 10);
        if (first10Zero && b[10] == 0 && b[11] == 0) { // ::/96 - unspecified, loopback, or IPv4-compatible
            boolean upperZero = b[12] == 0 && b[13] == 0 && b[14] == 0;
            if (upperZero && b[15] == 0) {
                return "unspecified";
            }
            if (upperZero && b[15] == 1) {
                return "loopback";
            }
            return ipv4(b, 12);
        }
        if (first10Zero && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) { // ::ffff:0:0/96
            return ipv4(b, 12);
        }
        if (b[0] == 0x00 && b[1] == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b && allZero(b, 4, 12)) {
            return ipv4(b, 12); // NAT64 64:ff9b::/96
        }
        if (b[0] == 0x20 && b[1] == 0x02) {
            return ipv4(b, 2); // 6to4 2002::/16
        }
        int first = b[0] & 0xff;
        if ((first & 0xfe) == 0xfc) {
            return "unique-local";
        }
        if (first == 0xfe && (b[1] & 0xc0) == 0x80) {
            return "link-local";
        }
        if (first == 0xff) {
            return "multicast";
        }
        return null;
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
