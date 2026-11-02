package io.github.ranger3462.deepmines;

import static io.github.ranger3462.dungeondelving.dungeon.RoomSpec.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.ranger3462.deepmines.MinesFloorsData.Floor;
import io.github.ranger3462.dungeondelving.dungeon.DungeonMarkers;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonBuildQueue;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonBuilder;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonBuilder.Placement;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonLayout;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonLayout.Cell;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonLayout.RoomKind;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonLayoutGenerator;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonPieces;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Builds the Mines' floors with Dungeon Delving's layout generator and builder, from Deep Mines' own pieces in
 * {@code data/deepmines/structure/mines/} (caves are {@code common_}, corridors {@code tunnel_}, plugs {@code cap_}).
 * <p>
 * Floors sit side by side, each in its own {@link #FLOOR_SPACING}-block stretch east of the last, and are built the
 * first time someone reaches them. A floor's first cave has the ladder up (except on floor 1) and its farthest cave
 * the ladder down to the next floor.
 */
public final class MinesFloors {
    /** The folder the Mines' pieces live in, inside {@code data/deepmines/structure/}. */
    public static final String PIECES = "mines";
    /** How far apart floors are, east to west. A layout stays well inside half of this. */
    public static final int FLOOR_SPACING = 1024;
    /** Where floor 1's first cave is centered in the Mines; floor n is {@code (n - 1) * FLOOR_SPACING} east of it. */
    public static final BlockPos BASE = new BlockPos(0, 0, 0);

    private MinesFloors() {}

    public static DungeonPieces pieces(MinecraftServer server) {
        return DungeonPieces.load(server, DeepMines.MODID, PIECES);
    }

    /** Where every piece of a floor of {@code caves} caves goes, with its first cave centered on {@code center}. */
    public static List<Placement> plan(MinecraftServer server, BlockPos center, int caves, RandomSource random) {
        return DungeonBuilder.plan(DungeonLayoutGenerator.generate(random, caves), pieces(server), center, random);
    }

    /** Queues a floor to be built a few pieces per tick in {@code level}, as part of {@code region}. */
    public static void build(ServerLevel level, int region, BlockPos center, int caves, RandomSource random) {
        DungeonBuildQueue.add(level.dimension(), region, plan(level.getServer(), center, caves, random), DungeonMarkers.Rolls.fromConfig(1));
    }

    /** The center of a floor's first cave. */
    public static BlockPos center(BlockPos base, int floor) {
        return base.east((floor - 1) * FLOOR_SPACING);
    }

    /** Which floor a position is on. */
    public static int floorAt(BlockPos base, BlockPos pos) {
        return Math.floorDiv(pos.getX() - base.getX() + FLOOR_SPACING / 2, FLOOR_SPACING) + 1;
    }

    /** A floor, built now if no one has reached it yet. */
    public static Floor floor(ServerLevel level, BlockPos base, int number) {
        MinesFloorsData data = MinesFloorsData.get(level);
        Optional<Floor> built = data.floor(number);
        if (built.isPresent()) {
            return built.get();
        }
        Floor floor = buildFloor(level, center(base, number), number, Config.CAVES_PER_FLOOR.get(), RandomSource.create(level.getRandom().nextLong()));
        data.put(floor);
        return floor;
    }

    /**
     * Builds a floor all at once (a few rooms, so a short pause the first time anyone reaches it), then its ladders: up
     * in the first cave, down in the farthest. On the first floor there is no ladder up; players arrive where it would be.
     */
    static Floor buildFloor(ServerLevel level, BlockPos center, int number, int caves, RandomSource random) {
        DungeonLayout layout = DungeonLayoutGenerator.generate(random, caves);
        DungeonMarkers.Rolls rolls = DungeonMarkers.Rolls.fromConfig(1);
        DungeonBuilder.plan(layout, pieces(level.getServer()), center, random).forEach(placement -> DungeonBuilder.place(level, placement, rolls));

        // The generator makes its farthest room the "boss" room: here, the cave with the way down.
        Cell farthest = layout.rooms().values().stream().filter(room -> room.kind() == RoomKind.BOSS).findFirst()
                .map(DungeonLayout.Room::cell)
                .orElseGet(() -> layout.distancesFromStart().entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey());
        BlockPos up = caveFloorMiddle(center, Cell.ORIGIN);
        BlockPos down = caveFloorMiddle(center, farthest);
        if (number > 1) {
            placeLadder(level, up, ModBlocks.LADDER_UP.get());
        } else {
            clearAround(level, up);
        }
        placeLadder(level, down, ModBlocks.LADDER_DOWN.get());
        return new Floor(number, up, down);
    }

    /** The block just above the middle of a cave's floor. */
    private static BlockPos caveFloorMiddle(BlockPos center, Cell cell) {
        return DungeonBuilder.roomMin(center, cell).offset(ROOM_SIZE / 2, 1, ROOM_SIZE / 2);
    }

    private static void placeLadder(ServerLevel level, BlockPos pos, Block ladder) {
        clearAround(level, pos);
        level.setBlock(pos, ladder.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    /** Clears room for a ladder and a player to stand beside it, whatever the cave piece has there. */
    private static void clearAround(ServerLevel level, BlockPos pos) {
        for (BlockPos clear : BlockPos.betweenClosed(pos.offset(-1, 0, -1), pos.offset(3, 2, 1))) {
            level.setBlock(clear, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** Takes a player down from {@code floor} to the next, building it if needed. Returns where they were sent, if anywhere. */
    public static Optional<BlockPos> descend(ServerPlayer player, ServerLevel level, BlockPos base, int floor) {
        if (floor >= Config.FLOORS.get()) {
            player.displayClientMessage(Component.translatable("message.deepmines.bottom_floor"), true);
            return Optional.empty();
        }
        return Optional.of(arrive(player, level, floor(level, base, floor + 1).arrivalFromAbove(), floor + 1));
    }

    /** Takes a player up from {@code floor} to the one above, arriving by its ladder down. Returns where they were sent, if anywhere. */
    public static Optional<BlockPos> ascend(ServerPlayer player, ServerLevel level, BlockPos base, int floor) {
        if (floor <= 1) {
            return Optional.empty();
        }
        return Optional.of(arrive(player, level, floor(level, base, floor - 1).arrivalFromBelow(), floor - 1));
    }

    /** Sends a player to a floor's first cave (for operators, and until the portal leads to floor 1). */
    public static void visit(ServerPlayer player, ServerLevel level, BlockPos base, int floor) {
        arrive(player, level, floor(level, base, floor).arrivalFromAbove(), floor);
    }

    private static BlockPos arrive(ServerPlayer player, ServerLevel level, BlockPos pos, int floor) {
        player.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, player.getYRot(), player.getXRot());
        player.displayClientMessage(Component.translatable("message.deepmines.floor", floor), true);
        return pos;
    }
}
