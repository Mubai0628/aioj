package com.aioj.next.agent.harness.tool.api;

/**
 * One link of the authorization chain; the pipeline checks scopes itself and
 * then asks every authorizer in order. An authorizer that throws counts as a
 * denial: authorization always fails closed.
 */
@FunctionalInterface
public interface ToolAuthorizer {
    Decision authorize(ToolDescriptor descriptor, ToolExecutionContext context);

    record Decision(boolean allowed, String reasonCode) {
        public static Decision allow() {
            return new Decision(true, "ALLOW");
        }

        public static Decision deny(String reasonCode) {
            return new Decision(false, reasonCode);
        }
    }
}
