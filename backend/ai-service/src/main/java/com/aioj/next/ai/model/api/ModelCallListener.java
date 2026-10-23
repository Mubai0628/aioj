package com.aioj.next.ai.model.api;

/**
 * Observes every finished call, successful or not, once per call (retries
 * included). Implemented by ai-service's assembly layer (plan A1.6). It must not
 * throw: a failing listener is logged and ignored.
 */
public interface ModelCallListener {

    void onFinish(ModelCallRecord record);
}
