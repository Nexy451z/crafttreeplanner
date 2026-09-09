package com.nexy451z.nexcrafttree;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * クライアント設定。探索上限（深度・ノード数・時間）を設定ファイルで調整できる。
 * 画面内の上限プリセットは ResolverLimits で実行時に上書きする（設定ファイルの値は初期値）。
 */
public final class Config {
    private Config() {
    }

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue CONFIG_MAX_DEPTH;
    public static final ModConfigSpec.IntValue CONFIG_MAX_NODES;
    public static final ModConfigSpec.IntValue CONFIG_TIMEOUT_MS;
    public static final ModConfigSpec.BooleanValue CONFIG_DEMOTE_INFO_CATEGORIES;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("NexCraftTree client settings").push("search");
        CONFIG_MAX_DEPTH = builder
                .comment("Max recipe tree depth. Deeper = more chained intermediates resolved.")
                .defineInRange("maxDepth", 16, 4, 64);
        CONFIG_MAX_NODES = builder
                .comment("Max expanded tree nodes per search. Higher = larger trees but slower.")
                .defineInRange("maxNodes", 250, 50, 10000);
        CONFIG_TIMEOUT_MS = builder
                .comment("Hard time budget (ms) for one tree search. The search is cut off when exceeded.")
                .defineInRange("timeoutMs", 300, 100, 20000);
        CONFIG_DEMOTE_INFO_CATEGORIES = builder
                .comment("Score down villager trade / quest / drop style categories that cannot be executed directly.")
                .define("demoteInfoCategories", true);
        builder.pop();
        SPEC = builder.build();
    }
}


