package com.aioj.next.agent.harness.tool;

import com.aioj.next.agent.harness.testing.Tools;
import com.aioj.next.agent.harness.tool.api.AgentTool;
import com.aioj.next.agent.harness.tool.api.ToolCatalog;
import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import com.aioj.next.agent.harness.tool.api.ToolExecutionContext;
import com.aioj.next.agent.harness.tool.api.ToolRejection;
import com.aioj.next.agent.harness.tool.api.ToolResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Registration (A2.4): a broken tool is left out and reported; it never stops startup. */
class ToolCatalogTest {

    @Test
    void brokenToolsAreLeftOutWithTheirReasonsAndTheRestStay() {
        AgentTool good = Tools.descriptor("kb.lookup").echo();
        AgentTool badName = Tools.descriptor("Lookup").echo();
        AgentTool identityField = Tools.descriptor("kb.mine").schema("""
                {"type":"object","properties":{"userId":{"type":"integer"}}}""").echo();
        AgentTool unsupported = Tools.descriptor("kb.any").schema("""
                {"type":"object","properties":{"q":{"anyOf":[{"type":"string"},{"type":"integer"}]}}}""").echo();
        AgentTool noTimeout = Tools.descriptor("kb.forever").timeout(Duration.ZERO).echo();
        AgentTool noCategory = Tools.descriptor("kb.uncounted").category(" ").echo();
        AgentTool nullDescriptor = new AgentTool() {
            @Override
            public ToolDescriptor descriptor() {
                return null;
            }

            @Override
            public ToolResult execute(ToolExecutionContext context, JsonNode input) {
                return null;
            }
        };

        ToolCatalog catalog = ToolCatalog.of(List.of(good, badName, identityField, unsupported, noTimeout, noCategory,
                nullDescriptor));

        assertThat(catalog.visible(Set.of("CHAT"))).extracting(ToolDescriptor::name).containsExactly("kb.lookup");
        assertThat(catalog.rejections()).extracting(ToolRejection::toolName)
                .containsExactly("Lookup", "kb.mine", "kb.any", "kb.forever", "kb.uncounted", null);
        assertThat(reasons(catalog, "Lookup")).anySatisfy(reason -> assertThat(reason).contains("dotted lowercase"));
        assertThat(reasons(catalog, "kb.mine")).anySatisfy(reason -> assertThat(reason).contains("$.userId"));
        assertThat(reasons(catalog, "kb.any")).anySatisfy(reason -> assertThat(reason).contains("MFJS-safe subset"));
        assertThat(reasons(catalog, "kb.forever")).anySatisfy(reason -> assertThat(reason).contains("timeout"));
        assertThat(reasons(catalog, "kb.uncounted")).anySatisfy(reason -> assertThat(reason).contains("budgetCategory"));
    }

    @Test
    void aDuplicateNameKeepsTheFirstTool() {
        Tools.Fake first = Tools.descriptor("kb.lookup").echo();
        Tools.Fake second = Tools.descriptor("kb.lookup").echo();

        ToolCatalog catalog = ToolCatalog.of(List.of(first, second));

        assertThat(catalog.find("kb.lookup")).containsSame(first);
        assertThat(catalog.rejections()).singleElement()
                .satisfies(rejection -> assertThat(rejection.reasons()).containsExactly("duplicate tool name"));
    }

    @Test
    void theAssemblingServiceCanForbidMoreInputFields() {
        AgentTool tool = Tools.descriptor("kb.scoped").schema("""
                {"type":"object","properties":{"projectKey":{"type":"string"}}}""").echo();

        assertThat(ToolCatalog.of(List.of(tool)).rejections()).isEmpty();
        assertThat(ToolCatalog.of(List.of(tool), Set.of("ProjectKey")).rejections()).singleElement()
                .satisfies(rejection -> assertThat(rejection.reasons()).singleElement().asString().contains("projectKey"));
    }

    @Test
    void visibleToolsAreSortedAndNeedEveryRequiredScope() {
        ToolCatalog catalog = ToolCatalog.of(List.of(
                Tools.descriptor("kb.zeta").echo(),
                Tools.descriptor("kb.alpha").echo(),
                Tools.descriptor("ops.reset").scopes("CHAT", "ADMIN").echo()));

        assertThat(catalog.visible(Set.of("CHAT"))).extracting(ToolDescriptor::name).containsExactly("kb.alpha", "kb.zeta");
        assertThat(catalog.visible(Set.of("CHAT", "ADMIN"))).extracting(ToolDescriptor::name)
                .containsExactly("kb.alpha", "kb.zeta", "ops.reset");
    }

    @Test
    void aRequiredToolThatWasLeftOutOrNeverRegisteredIsMissing() {
        ToolCatalog catalog = ToolCatalog.of(List.of(
                Tools.descriptor("kb.alpha").echo(),
                Tools.descriptor("kb.broken").schema("""
                        {"type":"object","properties":{"userId":{"type":"integer"}}}""").echo()));

        assertThat(catalog.missing(List.of("kb.alpha", "kb.broken", "kb.never"))).containsExactly("kb.broken", "kb.never");
        assertThat(catalog.missing(List.of("kb.alpha"))).isEmpty();
    }

    private static List<String> reasons(ToolCatalog catalog, String toolName) {
        return catalog.rejections().stream().filter(rejection -> toolName.equals(rejection.toolName()))
                .findFirst().orElseThrow().reasons();
    }
}
