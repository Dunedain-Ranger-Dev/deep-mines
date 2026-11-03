package io.github.ranger3462.deepmines;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Settings in {@code config/deepmines-common.toml}. */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue RESET_DAYS = BUILDER
            .comment("How many days between resets of the Mines. Once that long has passed, the Mines are wiped the next time",
                    "the server starts; anything left in them is lost. 0 turns resets off.")
            .defineInRange("resetDays", 7, 0, 365);

    public static final ModConfigSpec.IntValue FLOORS = BUILDER
            .comment("How many floors the Mines go down. Each floor is built the first time someone reaches it.")
            .defineInRange("floors", 100, 1, 1000);

    public static final ModConfigSpec.IntValue CAVES_PER_FLOOR = BUILDER
            .comment("How many rooms each floor has, joined by corridors. The first has the ladder up, the farthest the ladder down.")
            .defineInRange("cavesPerFloor", 3, 2, 20);

    static {
        BUILDER.comment("How often each kind of room comes up on a floor, as weights (the first room is always common).")
                .push("rooms");
    }

    public static final ModConfigSpec.IntValue COMMON_ROOMS = BUILDER
            .comment("Rooms with the usual amount of ore.")
            .defineInRange("common", 50, 0, 1000);

    public static final ModConfigSpec.IntValue QUARRY_ROOMS = BUILDER
            .comment("Quarries, packed with ore.")
            .defineInRange("quarry", 15, 0, 1000);

    public static final ModConfigSpec.IntValue FLOODED_ROOMS = BUILDER
            .comment("Flooded rooms: a deep pool to dive into, with ore around it.")
            .defineInRange("flooded", 15, 0, 1000);

    public static final ModConfigSpec.IntValue INFESTED_ROOMS = BUILDER
            .comment("Infested rooms, held by mobs from spawners.")
            .defineInRange("infested", 20, 0, 1000);

    public static final ModConfigSpec.IntValue VEIN_ROOMS = BUILDER
            .comment("Vein rooms: timbered galleries through the rock, with very large veins all of one ore.")
            .defineInRange("vein", 10, 0, 1000);

    public static final ModConfigSpec.IntValue HAND_BUILT = BUILDER
            .comment("Rooms and corridors are carved fresh for every floor. If a data pack adds hand-built ones (in",
                    "data/deepmines/structure/mines/, named by kind, such as quarry_big_pit.nbt), this is the percent chance one",
                    "of them is used instead, for a kind that has any.")
            .defineInRange("handBuilt", 25, 0, 100);

    static {
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}
}
