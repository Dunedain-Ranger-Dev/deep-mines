package io.github.ranger3462.deepmines;

import static io.github.ranger3462.deepmines.MinesSpec.*;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.ranger3462.deepmines.MinesBuilder.Placement;
import io.github.ranger3462.deepmines.MinesFloorsData.Floor;
import io.github.ranger3462.deepmines.MinesPieces.Kind;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonLayout;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonLayout.Cell;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonLayoutGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Builds the Mines' floors: Dungeon Delving's layout generator decides where a floor's rooms go and which are joined,
 * and {@link MinesBuilder} fills them with rooms and corridors carved fresh for the floor. Floors are built a little
 * each tick ({@link MinesBuildQueue}), starting with the room players arrive in, and filling in outward from it; players
 * are sent down (or up) once their room is ready. Each floor's seed comes from the world's and how many times the Mines
 * have been reset, so a floor left half built when the server stopped is built again the same.
 * <p>
 * Floor 0 is the start floor, the same for every Mines: one hall, where players come in, with a ladder up out of the
 * Mines and a ladder down to floor 1. Below it, floors sit side by side, each in its own {@link #FLOOR_SPACING}-block
 * stretch east of the last, and are built the first time someone reaches them. A floor's first room has the ladder up
 * and its farthest room the ladder down, each on the landing inside a doorway.
 */
public final class MinesFloors {
    /** The start floor, where the portal leads. */
    public static final int START = 0;
    /** How far apart floors are, east to west. A floor's rooms stay inside half of this either side of its first. */
    public static final int FLOOR_SPACING = 1024;
    /**
     * Where floor 1's first room is centered (at the bottom of the room); floor n is {@code (n - 1) * FLOOR_SPACING}
     * east of it. High enough to stay clear of the portals Custom Portal API makes in the caves below (up to Y 40).
     */
    public static final BlockPos BASE = new BlockPos(0, 60, 0);

    private MinesFloors() {}

    public static MinesPieces pieces(ServerLevel level) {
        return MinesPieces.load(level.getServer());
    }

    /** The center of a floor's first room (or the start floor's hall), at the bottom of the room. */
    public static BlockPos center(BlockPos base, int floor) {
        return base.east((floor - 1) * FLOOR_SPACING);
    }

    /** Which floor a position is on. */
    public static int floorAt(BlockPos base, BlockPos pos) {
        return Math.floorDiv(pos.getX() - base.getX() + FLOOR_SPACING / 2, FLOOR_SPACING) + 1;
    }

    /** A floor, built (all at once) if no one has reached it yet. */
    public static Floor floor(ServerLevel level, BlockPos base, int number) {
        Floor floor = floor(level, base, number, true);
        MinesBuildQueue.finish(level.getServer());
        return floor;
    }

    /**
     * A floor, which starts being built if no one has reached it yet: from the room with its ladder up if
     * {@code fromAbove}, or else the one with its ladder down. The start floor, just one hall, is built at once.
     */
    public static Floor floor(ServerLevel level, BlockPos base, int number, boolean fromAbove) {
        MinesFloorsData data = MinesFloorsData.get(level);
        Optional<Floor> known = data.known(number);
        if (known.isPresent()) {
            return known.get();
        }
        RandomSource random = RandomSource.create(seed(level, number));
        if (number == START) {
            long started = System.nanoTime();
            Floor floor = buildStart(level, center(base, START), random);
            DeepMines.LOGGER.info("Built floor {} of the Mines in {} ms", number, (System.nanoTime() - started) / 1_000_000);
            data.put(floor);
            return floor;
        }
        return buildFloor(level, center(base, number), number, rooms(number, random), random, fromAbove);
    }

    /** A floor's seed: the same for the same world, floor and number of resets. */
    private static long seed(ServerLevel level, int number) {
        return level.getSeed() ^ MinesReset.get().resets() * 0x9E3779B97F4A7C15L ^ (number + 1) * 0xC2B2AE3D27D4EB4FL;
    }

    /** How many rooms a floor has, more the deeper it is: 3 to 5 down to floor 25, 6 to 9 to 50, 10 to 15 to 75, then 16 to 25. */
    public static int rooms(int floor, RandomSource random) {
        int[] range = floor <= 25 ? new int[] { 3, 5 } : floor <= 50 ? new int[] { 6, 9 } : floor <= 75 ? new int[] { 10, 15 } : new int[] { 16, 25 };
        return range[0] + random.nextInt(range[1] - range[0] + 1);
    }

    /** What each room of a layout is: the first is common, the rest as the config's weights say. */
    public static Map<Cell, Kind> kinds(DungeonLayout layout, RandomSource random) {
        Map<Kind, Integer> weights = new LinkedHashMap<>();
        weights.put(Kind.COMMON, Config.COMMON_ROOMS.get());
        weights.put(Kind.QUARRY, Config.QUARRY_ROOMS.get());
        weights.put(Kind.FLOODED, Config.FLOODED_ROOMS.get());
        weights.put(Kind.INFESTED, Config.INFESTED_ROOMS.get());
        weights.put(Kind.VEIN, Config.VEIN_ROOMS.get());
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();

        Map<Cell, Kind> kinds = new LinkedHashMap<>();
        for (Cell cell : layout.rooms().keySet().stream().sorted(Comparator.comparingInt(Cell::x).thenComparingInt(Cell::z)).toList()) {
            Kind kind = Kind.COMMON;
            if (!cell.equals(Cell.ORIGIN) && total > 0) {
                int roll = random.nextInt(total);
                for (var entry : weights.entrySet()) {
                    roll -= entry.getValue();
                    if (roll < 0) {
                        kind = entry.getKey();
                        break;
                    }
                }
            }
            kinds.put(cell, kind);
        }
        return kinds;
    }

    /** Every piece of a floor of {@code rooms} rooms, with its first room centered on {@code center}. */
    public static List<Placement> plan(ServerLevel level, BlockPos center, int rooms, RandomSource random) {
        DungeonLayout layout = DungeonLayoutGenerator.generate(random, rooms);
        return MinesBuilder.plan(layout, kinds(layout, random), pieces(level), center, random);
    }

    /**
     * Queues a floor's building, its ladders (up in the first room, down in the farthest) each right after their room,
     * and the room at the end players arrive at ({@code fromAbove}, by the ladder up) first. It's marked built once
     * all of it is.
     */
    static Floor buildFloor(ServerLevel level, BlockPos center, int number, int rooms, RandomSource random, boolean fromAbove) {
        DungeonLayout layout = DungeonLayoutGenerator.generate(random, rooms);
        Cell farthest = layout.distancesFromStart().entrySet().stream()
                .max(Map.Entry.<Cell, Integer>comparingByValue().thenComparing(Map.Entry::getKey, Comparator.comparingInt(Cell::x).thenComparingInt(Cell::z)))
                .orElseThrow().getKey();
        BlockPos[] up = ladderSpot(layout, center, Cell.ORIGIN);
        BlockPos[] down = ladderSpot(layout, center, farthest);
        Floor floor = new Floor(number, up[0], down[0], up[1], down[1]);
        MinesFloorsData data = MinesFloorsData.get(level);
        data.startBuilding(floor);

        long started = System.nanoTime();
        BlockPos upRoom = MinesBuilder.roomMin(center, Cell.ORIGIN);
        BlockPos downRoom = MinesBuilder.roomMin(center, farthest);
        for (Placement placement : MinesBuilder.plan(layout, kinds(layout, random), pieces(level), center, random, fromAbove ? Cell.ORIGIN : farthest)) {
            MinesBuildQueue.add(level, placement.box(), MinesBuilder.build(placement, number, random), placement.kind() + " on floor " + number);
            if (placement.kind().room() && placement.targetMin().equals(upRoom)) {
                queueLadder(level, up, ModBlocks.LADDER_UP.get());
            } else if (placement.kind().room() && placement.targetMin().equals(downRoom)) {
                queueLadder(level, down, ModBlocks.LADDER_DOWN.get());
            }
        }
        MinesBuildQueue.then(level, () -> {
            data.put(floor);
            DeepMines.LOGGER.info("Built floor {} of the Mines ({} rooms) over {} ms", number, rooms, (System.nanoTime() - started) / 1_000_000);
        }, "floor " + number + " done");
        return floor;
    }

    private static void queueLadder(ServerLevel level, BlockPos[] spot, Block ladder) {
        BoundingBox box = BoundingBox.encapsulatingPositions(List.of(spot[0], spot[1], spot[1].above())).orElseThrow();
        MinesBuildQueue.add(level, box, l -> {
            placeLadder(l, spot, ladder);
            return true;
        }, "a ladder at " + spot[0].toShortString());
    }

    /** Builds the start floor: its hall, unturned, with a cap in every doorway, and its two ladders. */
    static Floor buildStart(ServerLevel level, BlockPos center, RandomSource random) {
        MinesPieces pieces = pieces(level);
        BlockPos min = MinesBuilder.roomMin(center, Cell.ORIGIN);
        MinesBuilder.place(level, Placement.saved(pieces.pick(Kind.START, random), Kind.START, min, Rotation.NONE, Mirror.NONE), START, random);
        BlockPos down = min.offset(START_LADDER_DOWN[0], START_LADDER_DOWN[1], START_LADDER_DOWN[2]);
        BlockPos up = min.offset(START_LADDER_UP[0], START_LADDER_UP[1], START_LADDER_UP[2]);
        // Arrive just inside the hall from each ladder.
        BlockPos[] downSpot = { down, down.south(2) };
        BlockPos[] upSpot = { up, up.north(2) };
        placeLadder(level, downSpot, ModBlocks.LADDER_DOWN.get());
        placeLadder(level, upSpot, ModBlocks.LADDER_UP.get());
        return new Floor(START, up, down, upSpot[1], downSpot[1]);
    }

    /**
     * Where a room's ladder goes, and where players arrive beside it: on the landing inside one of its doorways,
     * beside the way in. A doorway with no corridor (a dead end) is picked first.
     */
    private static BlockPos[] ladderSpot(DungeonLayout layout, BlockPos center, Cell cell) {
        Direction side = null;
        for (Direction direction : new Direction[] { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST }) {
            if (!layout.connected(cell, direction)) {
                side = direction;
                break;
            }
        }
        if (side == null) {
            side = Direction.NORTH;
        }
        BlockPos min = MinesBuilder.roomMin(center, cell);
        int[] ladder = fromWall(side, LANDING_START + 1, 2);
        int[] arrival = fromWall(side, DOOR_START, 2);
        return new BlockPos[] { min.offset(ladder[0], WALK_Y + 1, ladder[1]), min.offset(arrival[0], WALK_Y + 1, arrival[1]) };
    }

    /** Places a ladder, and makes sure there's room to stand where players arrive beside it. */
    private static void placeLadder(ServerLevel level, BlockPos[] spot, Block ladder) {
        level.setBlock(spot[0], ladder.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(spot[1], Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(spot[1].above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    /** Takes a player down from {@code floor} to the next, building it if needed. Returns where they were sent, if anywhere. */
    public static Optional<BlockPos> descend(ServerPlayer player, ServerLevel level, BlockPos base, int floor) {
        if (floor >= Config.FLOORS.get()) {
            player.displayClientMessage(Component.translatable("message.deepmines.bottom_floor"), true);
            return Optional.empty();
        }
        return Optional.of(arrive(player, level, base, floor(level, base, floor + 1, true).arrivalFromAbove(), floor + 1));
    }

    /**
     * Takes a player up from {@code floor} to the one above, arriving by its ladder down; from the start floor, out of
     * the Mines. Returns where in the Mines they were sent, if anywhere.
     */
    public static Optional<BlockPos> ascend(ServerPlayer player, ServerLevel level, BlockPos base, int floor) {
        if (floor <= START) {
            MinesEntrance.leave(player);
            return Optional.empty();
        }
        return Optional.of(arrive(player, level, base, floor(level, base, floor - 1, false).arrivalFromBelow(), floor - 1));
    }

    /** Sends a player to a floor, by its ladder up (on the start floor, where the portal brings them in). */
    public static BlockPos visit(ServerPlayer player, ServerLevel level, BlockPos base, int floor) {
        return arrive(player, level, base, floor(level, base, floor, true).arrivalFromAbove(), floor);
    }

    /**
     * Sends a player to a spot on a floor: now, or once it's built if it's still being dug out. Then the floor below
     * starts being built, if it isn't already, so it's likely ready by the time they get to its ladder.
     */
    private static BlockPos arrive(ServerPlayer player, ServerLevel level, BlockPos base, BlockPos pos, int floor) {
        if (MinesBuildQueue.building(level, pos)) {
            player.displayClientMessage(Component.translatable("message.deepmines.digging", floor), true);
        }
        MinesBuildQueue.whenBuilt(level, pos, () -> {
            if (player.hasDisconnected() || !player.isAlive()) {
                return;
            }
            MinesEntrance.teleportWithin(player, level, pos);
            player.displayClientMessage(floor == START ? Component.translatable("message.deepmines.start_floor")
                    : Component.translatable("message.deepmines.floor", floor), true);
            if (floor < Config.FLOORS.get()) {
                floor(level, base, floor + 1, true);
            }
        });
        return pos;
    }
}
