package io.github.ranger3462.deepmines;

import java.util.List;

import io.github.ranger3462.dungeondelving.dungeon.DungeonMarkers;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonBuildQueue;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonBuilder;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonBuilder.Placement;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonLayout;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonLayoutGenerator;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonPieces;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/**
 * Builds the Mines' floors with Dungeon Delving's layout generator and builder, from Deep Mines' own pieces in
 * {@code data/deepmines/structure/mines/} (caves are {@code common_}, corridors {@code tunnel_}, plugs {@code cap_}).
 * For now one layout is one floor; stacking floors with ladders between them comes next (MINE-14).
 */
public final class MinesFloors {
    /** The folder the Mines' pieces live in, inside {@code data/deepmines/structure/}. */
    public static final String PIECES = "mines";

    private MinesFloors() {}

    public static DungeonPieces pieces(MinecraftServer server) {
        return DungeonPieces.load(server, DeepMines.MODID, PIECES);
    }

    /** Where every piece of a floor of {@code caves} caves goes, with its first cave centered on {@code center}. */
    public static List<Placement> plan(MinecraftServer server, BlockPos center, int caves, RandomSource random) {
        DungeonLayout layout = DungeonLayoutGenerator.generate(random, caves);
        return DungeonBuilder.plan(layout, pieces(server), center, random);
    }

    /** Queues a floor to be built a few pieces per tick in {@code level}, as part of {@code region}. */
    public static void build(ServerLevel level, int region, BlockPos center, int caves, RandomSource random) {
        DungeonBuildQueue.add(level.dimension(), region, plan(level.getServer(), center, caves, random), DungeonMarkers.Rolls.fromConfig(1));
    }
}
