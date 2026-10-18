package com.linklens.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Literal addresses only: InetAddress.getByName on a literal never touches DNS. */
class AddressPolicyTest {

    private final AddressPolicy policy = new AddressPolicy(false);

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "127.8.9.10", "::1",                       // loopback
            "0.0.0.0", "::",                                         // unspecified
            "10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.1.1", // RFC 1918
            "169.254.169.254", "169.254.0.1", "fe80::1",             // link-local: cloud metadata lives here
            "100.64.0.1", "100.127.255.254",                         // carrier-grade NAT
            "192.0.0.1", "192.0.2.10", "198.18.0.1", "198.51.100.7", "203.0.113.9", // special/test ranges
            "224.0.0.1", "239.255.255.250", "255.255.255.255", "ff02::1", // multicast/broadcast
            "fc00::1", "fd00:ec2::254",                              // unique-local (AWS IPv6 metadata)
            "::ffff:127.0.0.1", "::ffff:169.254.169.254", "::ffff:10.0.0.1", // IPv4-mapped tricks
            "64:ff9b::a9fe:a9fe",                                    // NAT64 of 169.254.169.254
            "2001:db8::1"                                            // documentation
    })
    void blocksNonPublicAddresses(String literal) throws Exception {
        assertThat(policy.isAllowed(InetAddress.getByName(literal))).as(literal).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.215.14", "8.8.8.8", "1.1.1.1", "172.32.0.1", "100.128.0.1", "2606:4700:4700::1111", "::ffff:8.8.8.8"})
    void allowsPublicAddresses(String literal) throws Exception {
        assertThat(policy.isAllowed(InetAddress.getByName(literal))).as(literal).isTrue();
    }

    @Test
    void loopbackCanBeAllowedForTestsButNothingElse() throws Exception {
        AddressPolicy forTests = new AddressPolicy(true);
        assertThat(forTests.isAllowed(InetAddress.getByName("127.0.0.1"))).isTrue();
        assertThat(forTests.isAllowed(InetAddress.getByName("169.254.169.254"))).isFalse();
        assertThat(forTests.isAllowed(InetAddress.getByName("10.0.0.1"))).isFalse();
    }
}
