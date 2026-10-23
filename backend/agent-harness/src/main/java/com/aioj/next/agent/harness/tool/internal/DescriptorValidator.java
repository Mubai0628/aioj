package com.aioj.next.agent.harness.tool.internal;

import com.aioj.next.agent.harness.tool.api.ToolDescriptor;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Registration rules for tool descriptors; a descriptor with any violation is left out of the catalog. */
public final class DescriptorValidator {
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");
    /** Identity and permission fields a model must never be able to supply. */
    private static final Set<String> FORBIDDEN_INPUT_FIELDS = Set.of(
            "userid", "creatoruserid", "role", "isadmin", "visibility", "permission", "tenantid",
            "workspaceid", "jobid", "runid", "agentrunid", "turnid", "conversationid"
    );

    private DescriptorValidator() {
    }

    public static List<String> violations(ToolDescriptor descriptor) {
        return violations(descriptor, Set.of());
    }

    public static List<String> violations(ToolDescriptor descriptor, Set<String> extraForbiddenInputFields) {
        Set<String> forbidden = new java.util.HashSet<>(FORBIDDEN_INPUT_FIELDS);
        if (extraForbiddenInputFields != null) {
            extraForbiddenInputFields.forEach(field -> forbidden.add(field.toLowerCase(Locale.ROOT)));
        }
        List<String> violations = new ArrayList<>();
        if (descriptor == null) {
            violations.add("descriptor() returned null");
            return violations;
        }
        String name = descriptor.name();
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            violations.add("name must be dotted lowercase, e.g. context.search_exact");
        } else if (name.contains("__")) {
            violations.add("name must not contain consecutive underscores (reserved for wire-name encoding)");
        }
        if (blank(descriptor.version())) {
            violations.add("version is required");
        }
        if (blank(descriptor.description())) {
            violations.add("description is required");
        }
        JsonNode schema = descriptor.inputSchema();
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asString(null))) {
            violations.add("inputSchema must be a JSON object schema");
        } else {
            if (!SchemaValidator.isSupportedSubset(schema)) {
                violations.add("inputSchema uses keywords outside the supported MFJS-safe subset");
            }
            collectForbiddenFields(schema, "$", forbidden, violations);
        }
        if (descriptor.maxResultTokens() <= 0) {
            violations.add("maxResultTokens must be positive");
        }
        if (descriptor.timeout() == null || descriptor.timeout().isNegative() || descriptor.timeout().isZero()) {
            violations.add("timeout must be a positive duration");
        }
        if (blank(descriptor.budgetCategory())) {
            violations.add("budgetCategory is required");
        }
        if (descriptor.executionClass() == null) {
            violations.add("executionClass is required");
        }
        if (descriptor.failurePolicy() == null) {
            violations.add("failurePolicy is required");
        }
        if (descriptor.auditLevel() == null) {
            violations.add("auditLevel is required");
        }
        return violations;
    }

    private static void collectForbiddenFields(JsonNode schema, String path, Set<String> forbidden,
                                               List<String> violations) {
        JsonNode properties = schema.path("properties");
        if (properties.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = properties.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (forbidden.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                    violations.add("input field " + path + "." + entry.getKey()
                            + " is an identity/permission field the model must never supply");
                }
                collectForbiddenFields(entry.getValue(), path + "." + entry.getKey(), forbidden, violations);
            }
        }
        JsonNode items = schema.path("items");
        if (items.isObject()) {
            collectForbiddenFields(items, path + "[]", forbidden, violations);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
