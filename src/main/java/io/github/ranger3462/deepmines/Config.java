package io.github.ranger3462.deepmines;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Settings in {@code config/deepmines-common.toml}. */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue RESET_DAYS = BUILDER
            .comment("How many days between resets of the Mines. Once that long has passed, the Mines are wiped the next time",
                    "the server starts; anything left in them is lost. 0 turns resets off.")
            .defineInRange("resetDays", 7, 0, 365);

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}
}
