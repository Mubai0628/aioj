package com.aioj.next.ai.model.provider;

import com.aioj.next.ai.model.api.ModelEndpointConfig;
import com.aioj.next.ai.model.api.ModelErrorKind;
import com.aioj.next.ai.model.api.ModelException;
import com.aioj.next.ai.model.api.ProviderId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelCatalogTest {
    private final ModelCatalog catalog = new ModelCatalog();

    @Test
    void derivesEachProvidersCatalogUrl() {
        assertThat(catalog.modelsUri(endpoint(ProviderId.DEEPSEEK, "https://api.deepseek.com/chat/completions")))
                .hasToString("https://api.deepseek.com/models");
        assertThat(catalog.modelsUri(endpoint(ProviderId.KIMI, "https://api.moonshot.cn/v1/chat/completions")))
                .hasToString("https://api.moonshot.cn/v1/models");
        assertThat(catalog.modelsUri(endpoint(ProviderId.OPENAI_COMPATIBLE, "https://example.test/v1/chat/completions")))
                .hasToString("https://example.test/v1/models");
        assertThat(catalog.modelsUri(endpoint(ProviderId.OPENAI_COMPATIBLE, "https://example.test/v1/embeddings")))
                .hasToString("https://example.test/v1/models");
    }

    @Test
    void dashScopesCompatibleModeHasNoCatalog() {
        assertThatThrownBy(() -> catalog.modelsUri(endpoint(ProviderId.DASHSCOPE,
                "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings")))
                .isInstanceOfSatisfying(ModelException.class,
                        error -> assertThat(error.kind()).isEqualTo(ModelErrorKind.CAPABILITY_UNSUPPORTED));
    }

    private static ModelEndpointConfig endpoint(ProviderId provider, String baseUrl) {
        return ModelEndpointConfig.builder(provider).baseUrl(baseUrl).apiKey("sk-test").build();
    }
}
