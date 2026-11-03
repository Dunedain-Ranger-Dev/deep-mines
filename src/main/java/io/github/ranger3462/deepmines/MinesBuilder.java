package io.github.ranger3462.deepmines;

import static io.github.ranger3462.deepmines.MinesSpec.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import io.github.ranger3462.deepmines.MinesPieces.Kind;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonLayout;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonLayout.Cell;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonLayout.Connection;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonLayout.Room;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.AlwaysTrueTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.ProcessorRule;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Turns a floor's layout (from Dungeon Delving's generator) into the Mines' pieces in the world: a room per cell, a
 * corridor per connection and a cap in every doorway with no corridor. Rooms and corridors are carved fresh, each from
 * its own seed ({@link RoomCarver}), unless a hand-built one is picked instead. Then each piece's ore spots become ore
 * from the floor's table (one ore for the whole of a vein room), and its spawners get their mobs. A room or corridor is deepslate, with deepslate ore, as
 * often as its floor's depth says ({@link OreTable#deepslateChance}).
 */
public final class MinesBuilder {
    /**
     * One piece to place, by the minimum corner of the space it fills once turned: a saved piece ({@code template}), or
     * with no template, one carved from {@code seed}. A carved room's {@code openings} are its sides with a corridor.
     */
    public record Placement(@Nullable ResourceLocation template, Kind kind, BlockPos targetMin, Rotation rotation, Mirror mirror, long seed,
            Set<Direction> openings) {
        public static Placement saved(ResourceLocation template, Kind kind, BlockPos targetMin, Rotation rotation, Mirror mirror) {
            return new Placement(template, kind, targetMin, rotation, mirror, 0, Set.of());
        }

        public static Placement carved(Kind kind, BlockPos targetMin, Rotation rotation, long seed, Set<Direction> openings) {
            return new Placement(null, kind, targetMin, rotation, Mirror.NONE, seed, openings);
        }

        public boolean isCarved() {
            return template == null;
        }

        /** The space the piece fills. */
        public BoundingBox box() {
            Vec3i size = kind.size();
            boolean turned = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
            int sx = turned ? size.getZ() : size.getX();
            int sz = turned ? size.getX() : size.getZ();
            return new BoundingBox(targetMin.getX(), targetMin.getY(), targetMin.getZ(), targetMin.getX() + sx - 1,
                    targetMin.getY() + size.getY() - 1, targetMin.getZ() + sz - 1);
        }
    }

    /** What a piece's stone becomes in deepslate: the stones together, tuff with deepslate. */
    private static final RuleProcessor DEEPSLATE = new RuleProcessor(List.of(
            rule(Blocks.STONE, Blocks.DEEPSLATE),
            rule(Blocks.ANDESITE, Blocks.TUFF),
            rule(Blocks.DIORITE, Blocks.TUFF),
            rule(Blocks.GRANITE, Blocks.TUFF),
            rule(Blocks.COBBLESTONE, Blocks.COBBLED_DEEPSLATE),
            rule(Blocks.MOSSY_COBBLESTONE, Blocks.COBBLED_DEEPSLATE)));

    /** Keeps a piece's chunks loaded while it's built: a minute, which is plenty. */
    private static final TicketType<ChunkPos> BUILDING = TicketType.create(DeepMines.MODID + "_building", Comparator.comparingLong(ChunkPos::toLong), 1200);

    /** The mobs spawners get, until the Mines' own are picked (MINE-25). */
    private static final List<EntityType<?>> SPAWNER_MOBS = List.of(EntityType.ZOMBIE, EntityType.SKELETON);

    private MinesBuilder() {}

    private static ProcessorRule rule(Block from, Block to) {
        return new ProcessorRule(new BlockMatchTest(from), AlwaysTrueTest.INSTANCE, to.defaultBlockState());
    }

    /** The same swap, for carved pieces. */
    private static BlockState deepslate(BlockState state) {
        if (state.is(Blocks.STONE)) {
            return Blocks.DEEPSLATE.defaultBlockState();
        } else if (state.is(Blocks.ANDESITE) || state.is(Blocks.DIORITE) || state.is(Blocks.GRANITE)) {
            return Blocks.TUFF.defaultBlockState();
        } else if (state.is(Blocks.COBBLESTONE) || state.is(Blocks.MOSSY_COBBLESTONE)) {
            return Blocks.COBBLED_DEEPSLATE.defaultBlockState();
        }
        return state;
    }

    /**
     * A room or corridor of a kind: usually carved, from a seed off {@code random}; sometimes (as often as the config
     * says) a hand-built one, if there are any of the kind.
     */
    private static Placement roomOrCorridor(Kind kind, MinesPieces pieces, BlockPos min, Rotation rotation, Mirror mirror, Set<Direction> openings,
            RandomSource random) {
        long seed = random.nextLong();
        if (!pieces.pool(kind).isEmpty() && random.nextInt(100) < Config.HAND_BUILT.get()) {
            return Placement.saved(pieces.pick(kind, random), kind, min, rotation, mirror);
        }
        // Carved rooms are made for their spot, so they're never turned; corridors are carved along z, and turned to run along x.
        return Placement.carved(kind, min, kind == Kind.TUNNEL ? rotation : Rotation.NONE, seed, openings);
    }

    /** The minimum corner of a cell's room, for a floor whose first room's floor is centered on {@code center}. */
    public static BlockPos roomMin(BlockPos center, Cell cell) {
        return center.offset(cell.x() * GRID_PITCH - MID, 0, cell.z() * GRID_PITCH - MID);
    }

    /** Every piece of a floor, in the order to build them outward from its first room. */
    public static List<Placement> plan(DungeonLayout layout, Map<Cell, Kind> kinds, MinesPieces pieces, BlockPos center, RandomSource random) {
        return plan(layout, kinds, pieces, center, random, Cell.ORIGIN);
    }

    /**
     * Every piece of a floor, in the order to build them so the floor fills in outward from the room at {@code from}:
     * rooms nearest-first, each after the corridors joining it to rooms already built and before the caps in its
     * doorways with no corridor.
     */
    public static List<Placement> plan(DungeonLayout layout, Map<Cell, Kind> kinds, MinesPieces pieces, BlockPos center, RandomSource random,
            Cell from) {
        Map<Cell, Integer> distances = distances(layout, from);
        List<Room> rooms = layout.rooms().values().stream()
                .sorted(Comparator.comparingInt((Room room) -> distances.getOrDefault(room.cell(), Integer.MAX_VALUE))
                        .thenComparingInt(room -> room.cell().x())
                        .thenComparingInt(room -> room.cell().z()))
                .toList();
        List<Connection> connections = layout.connections().stream()
                .sorted(Comparator.comparingInt((Connection c) -> c.from().x()).thenComparingInt(c -> c.from().z())
                        .thenComparing(Connection::alongX))
                .toList();
        List<Placement> placements = new ArrayList<>();
        Set<Cell> built = new HashSet<>();
        int max = ROOM_SIZE - 1;
        for (Room room : rooms) {
            Cell cell = room.cell();
            for (Connection connection : connections) {
                Cell other = connection.from().equals(cell) ? connection.to() : connection.to().equals(cell) ? connection.from() : null;
                if (other == null || !built.contains(other)) {
                    continue;
                }
                BlockPos fromMin = roomMin(center, connection.from());
                // Corridors run along z; turn them a quarter for east-west ones. Either way one starts just outside the
                // room, centered on its doorway.
                if (connection.alongX()) {
                    placements.add(roomOrCorridor(Kind.TUNNEL, pieces, fromMin.offset(ROOM_SIZE, TUNNEL_Y, TUNNEL_START), Rotation.CLOCKWISE_90, Mirror.NONE, Set.of(), random));
                } else {
                    placements.add(roomOrCorridor(Kind.TUNNEL, pieces, fromMin.offset(TUNNEL_START, TUNNEL_Y, ROOM_SIZE), Rotation.NONE, Mirror.NONE, Set.of(), random));
                }
            }

            Kind kind = kinds.getOrDefault(cell, Kind.COMMON);
            Set<Direction> openings = EnumSet.noneOf(Direction.class);
            for (Direction side : Direction.Plane.HORIZONTAL) {
                if (layout.connected(cell, side)) {
                    openings.add(side);
                }
            }
            BlockPos min = roomMin(center, cell);
            placements.add(roomOrCorridor(kind, pieces, min, room.rotation(), room.mirror(), openings, random));
            built.add(cell);

            for (Direction direction : new Direction[] { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST }) {
                if (layout.connected(cell, direction)) {
                    continue;
                }
                // Caps plug the doorway hole in the room's wall. They run along x; turn them a quarter for west and east.
                ResourceLocation cap = pieces.pick(Kind.CAP, random);
                placements.add(switch (direction) {
                    case NORTH -> Placement.saved(cap, Kind.CAP, min.offset(DOOR_START, DOOR_BOTTOM_Y, 0), Rotation.NONE, Mirror.NONE);
                    case SOUTH -> Placement.saved(cap, Kind.CAP, min.offset(DOOR_START, DOOR_BOTTOM_Y, max), Rotation.NONE, Mirror.NONE);
                    case WEST -> Placement.saved(cap, Kind.CAP, min.offset(0, DOOR_BOTTOM_Y, DOOR_START), Rotation.CLOCKWISE_90, Mirror.NONE);
                    default -> Placement.saved(cap, Kind.CAP, min.offset(max, DOOR_BOTTOM_Y, DOOR_START), Rotation.CLOCKWISE_90, Mirror.NONE);
                });
            }
        }
        return placements;
    }

    /** How many corridors each room is from {@code from}. */
    private static Map<Cell, Integer> distances(DungeonLayout layout, Cell from) {
        Map<Cell, Integer> distances = new HashMap<>(Map.of(from, 0));
        Deque<Cell> queue = new ArrayDeque<>(List.of(from));
        while (!queue.isEmpty()) {
            Cell cell = queue.poll();
            for (Direction side : Direction.Plane.HORIZONTAL) {
                Cell next = cell.neighbor(side);
                if (layout.connected(cell, side) && !distances.containsKey(next)) {
                    distances.put(next, distances.get(cell) + 1);
                    queue.add(next);
                }
            }
        }
        return distances;
    }

    /**
     * Places one piece of a floor so it exactly fills the space at its corner once turned, in stone or (as often as the
     * floor's depth says) deepslate. Whatever was there goes, water included. Then its ore spots become veins of the
     * floor's ore, and its spawners get mobs.
     */
    public static void place(ServerLevel level, Placement placement, int floor, RandomSource random) {
        MinesBuildQueue.Job job = build(placement, floor, random);
        while (!job.step(level)) {
            // On to the next step.
        }
    }

    /** {@link #place}, a step at a time: carving the piece, placing it a layer at a time, then its ore and spawners. */
    public static MinesBuildQueue.Job build(Placement placement, int floor, RandomSource random) {
        return new PieceBuild(placement, floor, random);
    }

    private static final class PieceBuild implements MinesBuildQueue.Job {
        private final Placement placement;
        private final int floor;
        private final RandomSource random;
        private final Set<BlockPos> spots = new HashSet<>();
        private final List<BlockPos> spawners = new ArrayList<>();
        @Nullable
        private Boolean deep;
        @Nullable
        private MinesGrid grid;
        private int layer;
        private boolean requested;

        PieceBuild(Placement placement, int floor, RandomSource random) {
            this.placement = placement;
            this.floor = floor;
            this.random = random;
        }

        /** Asks for the piece's chunks, which load (and generate, if new) off the server thread. */
        @Override
        public void prepare(ServerLevel level) {
            if (!requested) {
                requested = true;
                chunks().forEach(chunk -> level.getChunkSource().addRegionTicket(BUILDING, chunk, 0, chunk));
            }
        }

        @Override
        public boolean ready(ServerLevel level) {
            prepare(level);
            return chunks().allMatch(chunk -> level.getChunkSource().getChunkNow(chunk.x, chunk.z) != null);
        }

        private Stream<ChunkPos> chunks() {
            BoundingBox box = placement.box();
            return ChunkPos.rangeClosed(new ChunkPos(box.minX() >> 4, box.minZ() >> 4), new ChunkPos(box.maxX() >> 4, box.maxZ() >> 4));
        }

        @Override
        public boolean step(ServerLevel level) {
            if (deep == null) {
                deep = random.nextDouble() < OreTable.deepslateChance(floor);
            }
            if (!placement.isCarved()) {
                placeSaved(level);
                finish(level);
                return true;
            }
            if (grid == null) {
                grid = RoomCarver.carve(placement.kind(), placement.seed(), placement.openings()).grid();
                return false;
            }
            if (layer < grid.sy) {
                grid.placeLayer(level, placement.targetMin(), placement.rotation(), deep ? MinesBuilder::deepslate : state -> state, layer++, spots, spawners);
                return false;
            }
            finish(level);
            return true;
        }

        private void placeSaved(ServerLevel level) {
            StructureTemplate template = level.getStructureManager().get(placement.template())
                    .orElseThrow(() -> new IllegalStateException("Missing Mines piece " + placement.template()));
            StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(placement.rotation()).setMirror(placement.mirror())
                    .setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING);
            if (deep) {
                settings.addProcessor(DEEPSLATE);
            }
            BoundingBox box = template.getBoundingBox(settings, BlockPos.ZERO);
            BlockPos pos = placement.targetMin().offset(-box.minX(), -box.minY(), -box.minZ());
            template.placeInWorld(level, pos, pos, settings, random, Block.UPDATE_CLIENTS);
            template.filterBlocks(pos, settings, ModBlocks.ORE_SPOT.get()).forEach(info -> spots.add(info.pos().immutable()));
            template.filterBlocks(pos, settings, Blocks.SPAWNER).forEach(info -> spawners.add(info.pos().immutable()));
        }

        private void finish(ServerLevel level) {
            // A vein room's ore is all one kind; everywhere else, each vein's is picked on its own.
            OreTable.Ore only = placement.kind() == Kind.VEIN ? OreTable.pick(floor, random) : null;
            for (List<BlockPos> vein : veins(spots)) {
                Block ore = (only != null ? only : OreTable.pick(floor, random)).block(deep);
                BlockState state = ore == Blocks.AIR ? (deep ? Blocks.DEEPSLATE : Blocks.STONE).defaultBlockState() : ore.defaultBlockState();
                vein.forEach(spot -> level.setBlock(spot, state, Block.UPDATE_CLIENTS));
            }
            for (BlockPos pos : spawners) {
                if (level.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner) {
                    spawner.setEntityId(SPAWNER_MOBS.get(random.nextInt(SPAWNER_MOBS.size())), random);
                    spawner.setChanged();
                }
            }
        }
    }

    /** Groups ore spots into veins: spots touching face to face are one vein. */
    static List<List<BlockPos>> veins(Set<BlockPos> spots) {
        List<List<BlockPos>> veins = new ArrayList<>();
        Set<BlockPos> left = new HashSet<>(spots);
        for (BlockPos start : spots) {
            if (!left.remove(start)) {
                continue;
            }
            List<BlockPos> vein = new ArrayList<>();
            Deque<BlockPos> queue = new ArrayDeque<>(List.of(start));
            while (!queue.isEmpty()) {
                BlockPos spot = queue.poll();
                vein.add(spot);
                for (Direction direction : Direction.values()) {
                    BlockPos next = spot.relative(direction);
                    if (left.remove(next)) {
                        queue.add(next);
                    }
                }
            }
            veins.add(vein);
        }
        return veins;
    }
}
