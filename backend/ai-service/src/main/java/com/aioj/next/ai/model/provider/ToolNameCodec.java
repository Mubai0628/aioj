package com.aioj.next.ai.model.provider;

/**
 * Bijective mapping between internal dotted tool names ({@code context.search_exact})
 * and provider-safe wire names ({@code context__search_exact}). Kimi's function
 * name pattern forbids dots; a double underscore keeps the mapping reversible
 * because internal names never contain "__" (the tool registry enforces it).
 * Wire encoding is a provider concern, so the model package owns it (plan §3.2.3).
 */
public final class ToolNameCodec {

    private ToolNameCodec() {
    }

    public static String toWire(String internalName) {
        return internalName == null ? null : internalName.replace(".", "__");
    }

    public static String toInternal(String wireName) {
        return wireName == null ? null : wireName.replace("__", ".");
    }
}
