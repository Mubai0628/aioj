package com.aioj.next.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** Client address resolution and the per-address limits at the gateway (T2.2). */
@ConfigurationProperties("aioj.gateway")
public class GatewayProtectionProperties {
    private final ClientIp clientIp = new ClientIp();
    private final RateLimit rateLimit = new RateLimit();

    public ClientIp getClientIp() {
        return clientIp;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public static class ClientIp {
        /** Connections whose X-Forwarded-For is believed: the nginx in front, the Compose networks, loopback. */
        private List<String> trustedProxies = new ArrayList<>(List.of(
                "127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"));
        /** Trusted proxies in front of the gateway that append to X-Forwarded-For (the web nginx: 1). */
        private int trustedHops = 1;

        public List<String> getTrustedProxies() {
            return trustedProxies;
        }

        public void setTrustedProxies(List<String> trustedProxies) {
            this.trustedProxies = trustedProxies;
        }

        public int getTrustedHops() {
            return trustedHops;
        }

        public void setTrustedHops(int trustedHops) {
            this.trustedHops = trustedHops;
        }
    }

    public static class RateLimit {
        private boolean enabled = true;
        /** Client blocks (CIDR) the per-address limits skip, such as a lab's shared egress address. */
        private List<String> sharedNetworks = new ArrayList<>();
        private Bucket auth = new Bucket(30, 30);
        private Bucket submit = new Bucket(60, 60);
        private Bucket aiChat = new Bucket(30, 30);
        private Bucket codeRun = new Bucket(30, 30);
        private Bucket draft = new Bucket(10, 6);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getSharedNetworks() {
            return sharedNetworks;
        }

        public void setSharedNetworks(List<String> sharedNetworks) {
            this.sharedNetworks = sharedNetworks;
        }

        public Bucket getAuth() {
            return auth;
        }

        public void setAuth(Bucket auth) {
            this.auth = auth;
        }

        public Bucket getSubmit() {
            return submit;
        }

        public void setSubmit(Bucket submit) {
            this.submit = submit;
        }

        public Bucket getAiChat() {
            return aiChat;
        }

        public void setAiChat(Bucket aiChat) {
            this.aiChat = aiChat;
        }

        public Bucket getCodeRun() {
            return codeRun;
        }

        public void setCodeRun(Bucket codeRun) {
            this.codeRun = codeRun;
        }

        public Bucket getDraft() {
            return draft;
        }

        public void setDraft(Bucket draft) {
            this.draft = draft;
        }
    }

    /** A token bucket counted in requests: {@code capacity} at once, {@code refillPerMinute} back each minute. */
    public static class Bucket {
        private int capacity;
        private int refillPerMinute;

        public Bucket() {
        }

        Bucket(int capacity, int refillPerMinute) {
            this.capacity = capacity;
            this.refillPerMinute = refillPerMinute;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getRefillPerMinute() {
            return refillPerMinute;
        }

        public void setRefillPerMinute(int refillPerMinute) {
            this.refillPerMinute = refillPerMinute;
        }

        /**
         * The Redis script refills whole tokens per second, so a slower refill charges several tokens
         * per request: 30 a minute is one token a second at two per request.
         */
        int tokensPerRequest() {
            return refillPerMinute >= 60 ? 1 : Math.max(1, Math.round(60f / Math.max(1, refillPerMinute)));
        }

        int replenishPerSecond() {
            return refillPerMinute >= 60 ? Math.max(1, refillPerMinute / 60) : 1;
        }

        long burstTokens() {
            return (long) Math.max(1, capacity) * tokensPerRequest();
        }

        /** Seconds until one request's worth of tokens is back. */
        long retryAfterSeconds() {
            return Math.max(1, (long) Math.ceil(tokensPerRequest() / (double) replenishPerSecond()));
        }
    }
}
