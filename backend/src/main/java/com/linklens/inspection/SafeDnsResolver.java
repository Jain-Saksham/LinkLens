package com.linklens.inspection;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;

/**
 * Resolves a host and refuses it if ANY of its addresses is non-public.
 *
 * <p>Plugging the check into the HTTP client's resolver (rather than checking a URL before fetching)
 * closes the DNS-rebinding gap: the client connects to exactly the addresses validated here, so an
 * attacker's DNS can't answer "public" to the check and "127.0.0.1" to the connect. It also runs for
 * every redirect hop, because each hop resolves through this class.
 */
public final class SafeDnsResolver implements DnsResolver {

    private final AddressPolicy policy;

    public SafeDnsResolver(AddressPolicy policy) {
        this.policy = policy;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = SystemDefaultDnsResolver.INSTANCE.resolve(host);
        for (InetAddress address : addresses) {
            if (!policy.isAllowed(address)) {
                throw new BlockedAddressException(host, address);
            }
        }
        return addresses;
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return SystemDefaultDnsResolver.INSTANCE.resolveCanonicalHostname(host);
    }

    /** Subclass of UnknownHostException so the HTTP client propagates it unchanged. */
    public static final class BlockedAddressException extends UnknownHostException {
        public BlockedAddressException(String host, InetAddress address) {
            super(host + " resolves to a non-public address (" + address.getHostAddress() + ")");
        }
    }
}
