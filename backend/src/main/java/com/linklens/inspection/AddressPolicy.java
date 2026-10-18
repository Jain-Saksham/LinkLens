package com.linklens.inspection;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * SSRF guard: LinkLens fetches URLs that anyone can submit, so it must never be tricked into calling
 * its own network: databases, admin ports, or the cloud metadata service (169.254.169.254) that hands
 * out credentials. Only globally routable unicast addresses are allowed.
 */
public final class AddressPolicy {

    private final boolean allowLoopback;

    public AddressPolicy(boolean allowLoopback) {
        this.allowLoopback = allowLoopback;
    }

    public boolean isAllowed(InetAddress address) {
        if (address.isLoopbackAddress()) {
            return allowLoopback;
        }
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false; // 0.0.0.0, 169.254/16 + fe80::/10 (metadata!), 10/8, 172.16/12, 192.168/16, multicast
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return isPublicV4(b);
        }
        if (address instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) {
                return false; // fc00::/7 unique local (includes AWS IPv6 metadata fd00:ec2::254)
            }
            if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) {
                return false; // 2001:db8::/32 documentation
            }
            if (isEmbeddedV4Prefix(b)) {
                // ::ffff:a.b.c.d, ::a.b.c.d and 64:ff9b::a.b.c.d carry an IPv4 address: judge that instead.
                return isAllowed(v4Of(b));
            }
            return true;
        }
        return false;
    }

    private static boolean isPublicV4(byte[] b) {
        int b0 = b[0] & 0xff;
        int b1 = b[1] & 0xff;
        int b2 = b[2] & 0xff;
        if (b0 == 0) return false;                                 // 0.0.0.0/8 "this network"
        if (b0 == 100 && (b1 & 0xc0) == 64) return false;          // 100.64/10 carrier-grade NAT
        if (b0 == 192 && b1 == 0 && b2 == 0) return false;         // 192.0.0.0/24 IETF protocol assignments
        if (b0 == 192 && b1 == 0 && b2 == 2) return false;         // TEST-NET-1
        if (b0 == 198 && (b1 == 18 || b1 == 19)) return false;     // 198.18/15 benchmarking
        if (b0 == 198 && b1 == 51 && b2 == 100) return false;      // TEST-NET-2
        if (b0 == 203 && b1 == 0 && b2 == 113) return false;       // TEST-NET-3
        return b0 < 224;                                           // 224+ multicast/reserved/broadcast
    }

    private static boolean isEmbeddedV4Prefix(byte[] b) {
        boolean firstTenZero = true;
        for (int i = 0; i < 10; i++) {
            firstTenZero &= b[i] == 0;
        }
        boolean mapped = firstTenZero && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
        boolean compatible = firstTenZero && b[10] == 0 && b[11] == 0;
        boolean nat64 = (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b;
        return mapped || compatible || nat64;
    }

    private static InetAddress v4Of(byte[] b) {
        try {
            return InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]});
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
