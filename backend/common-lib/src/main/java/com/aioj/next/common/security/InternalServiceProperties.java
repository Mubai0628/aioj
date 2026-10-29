package com.aioj.next.common.security;

import java.util.LinkedHashMap;
import java.util.Map;

/** Directional credentials; never include this object or its values in logs. */
public class InternalServiceProperties {
    private com.aioj.next.common.http.InternalHttpProperties http = new com.aioj.next.common.http.InternalHttpProperties();
    public com.aioj.next.common.http.InternalHttpProperties getHttp() { return http; }
    public void setHttp(com.aioj.next.common.http.InternalHttpProperties value) { http = value; }
    private Map<String, Credential> inbound = new LinkedHashMap<>();
    private Map<String, Credential> outbound = new LinkedHashMap<>();

    public Map<String, Credential> getInbound() { return inbound; }
    public void setInbound(Map<String, Credential> inbound) { this.inbound = inbound; }
    public Map<String, Credential> getOutbound() { return outbound; }
    public void setOutbound(Map<String, Credential> outbound) { this.outbound = outbound; }

    public String outboundToken(String target) {
        Credential credential = outbound.get(target);
        return credential == null || credential.getToken() == null ? "" : credential.getToken();
    }

    public static class Credential {
        private String token = "";
        private String nextToken = "";
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getNextToken() { return nextToken; }
        public void setNextToken(String nextToken) { this.nextToken = nextToken; }
    }
}
