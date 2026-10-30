package com.aioj.next.gateway;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Who sent a request, as far as the proxies we run can vouch for it (T2.2). */
class ClientAddressTest {
    private static final List<String> TRUSTED = List.of("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12",
            "192.168.0.0/16");

    @Test
    void behindOneTrustedProxyTheRightmostEntryIsTheClientAndForgedOnesToItsLeftDoNotCount() throws Exception {
        ClientAddress addresses = new ClientAddress(TRUSTED, 1);
        InetAddress nginx = InetAddress.getByName("172.18.0.5");

        assertThat(addresses.resolve(nginx, List.of("203.0.113.7"))).isEqualTo("203.0.113.7");
        assertThat(addresses.resolve(nginx, List.of("1.1.1.1, 2.2.2.2, 203.0.113.7"))).isEqualTo("203.0.113.7");
        assertThat(addresses.resolve(nginx, List.of("1.1.1.1", "203.0.113.7"))).as("several header lines")
                .isEqualTo("203.0.113.7");
    }

    @Test
    void withTwoProxiesTheSecondEntryFromTheRightIsTheClient() throws Exception {
        ClientAddress addresses = new ClientAddress(TRUSTED, 2);

        assertThat(addresses.resolve(InetAddress.getByName("172.18.0.5"),
                List.of("9.9.9.9, 198.51.100.4, 172.18.0.1"))).isEqualTo("198.51.100.4");
    }

    @Test
    void aPeerOutsideTheTrustedNetworksIsTheClientWhateverItClaims() throws Exception {
        ClientAddress addresses = new ClientAddress(TRUSTED, 1);

        assertThat(addresses.resolve(InetAddress.getByName("203.0.113.9"), List.of("10.0.0.1")))
                .isEqualTo("203.0.113.9");
        assertThat(addresses.resolve(InetAddress.getByName("172.18.0.5"), List.of()))
                .as("no header: the connection").isEqualTo("172.18.0.5");
    }

    @Test
    void anEntryThatIsNotAnAddressFallsBackToTheConnectionWithoutAnyLookup() throws Exception {
        ClientAddress addresses = new ClientAddress(TRUSTED, 1);
        InetAddress nginx = InetAddress.getByName("172.18.0.5");

        for (String junk : List.of("unknown", "example.com", "999.1.1.1", "1.2.3", "<script>")) {
            assertThat(addresses.resolve(nginx, List.of(junk))).as(junk).isEqualTo("172.18.0.5");
        }
        assertThat(addresses.resolve(nginx, List.of("203.0.113.7:51234"))).isEqualTo("203.0.113.7");
        assertThat(addresses.resolve(nginx, List.of("[2001:db8::7]:443"))).isEqualTo("2001:db8:0:0:0:0:0:7");
    }

    @Test
    void anIpv6ClientIsCountedByItsSlash64() {
        assertThat(ClientAddress.limitKey("2001:db8:1:2:aaaa:bbbb:cccc:dddd")).isEqualTo("2001:db8:1:2::/64");
        assertThat(ClientAddress.limitKey("2001:db8:1:2::9")).isEqualTo("2001:db8:1:2::/64");
        assertThat(ClientAddress.limitKey("203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void networksMatchByPrefixAndRejectNonsense() throws Exception {
        List<ClientAddress.Network> networks = ClientAddress.networks(List.of("10.0.0.0/8", "198.51.100.0/24", "", " "));

        assertThat(ClientAddress.contains(networks, InetAddress.getByName("10.200.3.4"))).isTrue();
        assertThat(ClientAddress.contains(networks, InetAddress.getByName("198.51.100.250"))).isTrue();
        assertThat(ClientAddress.contains(networks, InetAddress.getByName("198.51.101.1"))).isFalse();
        assertThat(ClientAddress.contains(networks, InetAddress.getByName("::ffff:10.1.1.1"))).isTrue();
        assertThatThrownBy(() -> ClientAddress.networks(List.of("lab.example/24")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClientAddress.networks(List.of("10.0.0.0/33")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
