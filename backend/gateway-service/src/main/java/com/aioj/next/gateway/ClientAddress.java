package com.aioj.next.gateway;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Who sent a request (T2.2): {@code X-Forwarded-For} is believed only when the connection comes from
 * a trusted proxy, and then only as far as the trusted proxies wrote it: the N-th entry from the
 * right, N being how many trusted proxies stand in front of the gateway. Entries further left are
 * whatever the client sent and can be forged.
 */
final class ClientAddress {
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]+(%[0-9A-Za-z]+)?");

    private final List<Network> trustedProxies;
    private final int trustedHops;

    ClientAddress(List<String> trustedProxies, int trustedHops) {
        this.trustedProxies = networks(trustedProxies);
        this.trustedHops = Math.max(1, trustedHops);
    }

    /** The client's address as text; the connection's own address when nothing trustworthy says otherwise. */
    String resolve(InetAddress peer, List<String> forwardedFor) {
        if (peer == null) {
            return null;
        }
        if (!contains(trustedProxies, peer) || forwardedFor == null || forwardedFor.isEmpty()) {
            return text(peer);
        }
        List<String> hops = new ArrayList<>();
        for (String header : forwardedFor) {
            for (String part : header.split(",")) {
                if (!part.isBlank()) {
                    hops.add(part.trim());
                }
            }
        }
        if (hops.isEmpty()) {
            return text(peer);
        }
        // Fewer entries than proxies: the leftmost one is still a proxy's view of its client.
        String candidate = hops.get(Math.max(0, hops.size() - trustedHops));
        InetAddress parsed = parse(candidate);
        return parsed == null ? text(peer) : text(parsed);
    }

    /** The key a per-address limit counts under: an IPv6 client by its /64, as one host holds the whole /64. */
    static String limitKey(String address) {
        InetAddress parsed = parse(address);
        if (!(parsed instanceof Inet6Address)) {
            return address;
        }
        byte[] bytes = parsed.getAddress();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i += 2) {
            out.append(Integer.toHexString(((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff))).append(':');
        }
        return out.append(":/64").toString();
    }

    static boolean contains(List<Network> networks, InetAddress address) {
        for (Network network : networks) {
            if (network.contains(address)) {
                return true;
            }
        }
        return false;
    }

    static List<Network> networks(List<String> cidrs) {
        List<Network> result = new ArrayList<>();
        if (cidrs != null) {
            for (String cidr : cidrs) {
                if (cidr != null && !cidr.isBlank()) {
                    result.add(Network.parse(cidr.trim()));
                }
            }
        }
        return List.copyOf(result);
    }

    /** An IP literal only: a host name here would mean a DNS lookup on the request path. */
    static InetAddress parse(String value) {
        if (value == null) {
            return null;
        }
        String literal = value.trim();
        if (literal.startsWith("[") && literal.contains("]")) {
            literal = literal.substring(1, literal.indexOf(']'));
        } else if (IPV4.matcher(literal.replaceFirst(":\\d+$", "")).matches()) {
            literal = literal.replaceFirst(":\\d+$", "");
        }
        boolean four = IPV4.matcher(literal).matches();
        if (four && !octetsInRange(literal)) {
            return null; // 999.1.1.1 would otherwise be looked up as a host name
        }
        if (!four && !(literal.contains(":") && IPV6.matcher(literal).matches())) {
            return null;
        }
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    private static boolean octetsInRange(String dotted) {
        for (String octet : dotted.split("\\.")) {
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    private static String text(InetAddress address) {
        return address.getHostAddress();
    }

    /** An address block in CIDR form; an IPv4 block also matches IPv4-mapped IPv6 addresses. */
    record Network(byte[] base, int prefix) {
        static Network parse(String cidr) {
            int slash = cidr.indexOf('/');
            InetAddress address = ClientAddress.parse(slash < 0 ? cidr : cidr.substring(0, slash));
            if (address == null) {
                throw new IllegalArgumentException("Not an address block: " + cidr);
            }
            int bits = address.getAddress().length * 8;
            int prefix = slash < 0 ? bits : Integer.parseInt(cidr.substring(slash + 1));
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("Bad prefix length: " + cidr);
            }
            return new Network(address.getAddress(), prefix);
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length == 16 && base.length == 4 && isIpv4Mapped(candidate)) {
                byte[] four = new byte[4];
                System.arraycopy(candidate, 12, four, 0, 4);
                candidate = four;
            }
            if (candidate.length != base.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (candidate[i] != base[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xff << (8 - rest)) & 0xff;
            return (candidate[full] & mask) == (base[full] & mask);
        }

        private static boolean isIpv4Mapped(byte[] bytes) {
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) {
                    return false;
                }
            }
            return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
        }
    }
}
