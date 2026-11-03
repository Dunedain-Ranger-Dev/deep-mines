package io.github.ranger3462.deepmines;

import static io.github.ranger3462.deepmines.MinesSpec.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import io.github.ranger3462.deepmines.MinesPieces.Kind;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonLayout;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonLayout.Cell;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonLayout.Connection;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonLayout.Room;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
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
 * from the floor's table, and its spawners get their mobs. A room or corridor is deepslate, with deepslate ore, as
 * often as its floor's depth says ({@link OreTable#deepslateChance}).
 */
public final class MinesBuilder {
    /**
     * One piece to place, by the minimum corner of the space it fills once turned: a saved piece ({@code template}), or
     * with no template, one carved from {@code seed}.
     */
    public record Placement(@Nullable ResourceLocation template, Kind kind, BlockPos targetMin, Rotation rotation, Mirror mirror, long seed) {
        public static Placement saved(ResourceLocation template, Kind kind, BlockPos targetMin, Rotation rotation, Mirror mirror) {
            return new Placement(template, kind, targetMin, rotation, mirror, 0);
        }

        public static Placement carved(Kind kind, BlockPos targetMin, Rotation rotation, long seed) {
            return new Placement(null, kind, targetMin, rotation, Mirror.NONE, seed);
        }

        public boolean isCarved() {
            return template == null;
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
    private static Placement roomOrCorridor(Kind kind, MinesPieces pieces, BlockPos min, Rotation rotation, Mirror mirror, RandomSource random) {
        long seed = random.nextLong();
        if (!pieces.pool(kind).isEmpty() && random.nextInt(100) < Config.HAND_BUILT.get()) {
            return Placement.saved(pieces.pick(kind, random), kind, min, rotation, mirror);
        }
        // Carved rooms are made for their spot, so they're never turned; corridors are carved along z, and turned to run along x.
        return Placement.carved(kind, min, kind == Kind.TUNNEL ? rotation : Rotation.NONE, seed);
    }

    /** The minimum corner of a cell's room, for a floor whose first room's floor is centered on {@code center}. */
    public static BlockPos roomMin(BlockPos center, Cell cell) {
        return center.offset(cell.x() * GRID_PITCH - MID, 0, cell.z() * GRID_PITCH - MID);
    }

    /** Every piece of a floor: rooms nearest-first from the first one, then corridors, then caps. */
    public static List<Placement> plan(DungeonLayout layout, Map<Cell, Kind> kinds, MinesPieces pieces, BlockPos center, RandomSource random) {
        List<Placement> placements = new ArrayList<>();
        Map<Cell, Integer> distances = layout.distancesFromStart();
        List<Room> rooms = layout.rooms().values().stream()
                .sorted(Comparator.comparingInt((Room room) -> distances.getOrDefault(room.cell(), Integer.MAX_VALUE))
                        .thenComparingInt(room -> room.cell().x())
                        .thenComparingInt(room -> room.cell().z()))
                .toList();
        for (Room room : rooms) {
            Kind kind = kinds.getOrDefault(room.cell(), Kind.COMMON);
            placements.add(roomOrCorridor(kind, pieces, roomMin(center, room.cell()), room.rotation(), room.mirror(), random));
        }

        for (Connection connection : layout.connections().stream()
                .sorted(Comparator.comparingInt((Connection c) -> c.from().x()).thenComparingInt(c -> c.from().z())
                        .thenComparing(Connection::alongX))
                .toList()) {
            BlockPos fromMin = roomMin(center, connection.from());
            // Corridors run along z; turn them a quarter for east-west ones. Either way one starts just outside the
            // room, centered on its doorway.
            if (connection.alongX()) {
                placements.add(roomOrCorridor(Kind.TUNNEL, pieces, fromMin.offset(ROOM_SIZE, TUNNEL_Y, TUNNEL_START), Rotation.CLOCKWISE_90, Mirror.NONE, random));
            } else {
                placements.add(roomOrCorridor(Kind.TUNNEL, pieces, fromMin.offset(TUNNEL_START, TUNNEL_Y, ROOM_SIZE), Rotation.NONE, Mirror.NONE, random));
            }
        }

        int max = ROOM_SIZE - 1;
        for (Room room : rooms) {
            BlockPos min = roomMin(center, room.cell());
            for (Direction direction : new Direction[] { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST }) {
                if (layout.connected(room.cell(), direction)) {
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

    /**
     * Places one piece of a floor so it exactly fills the space at its corner once turned, in stone or (as often as the
     * floor's depth says) deepslate. Whatever was there goes, water included. Then its ore spots become veins of the
     * floor's ore, and its spawners get mobs.
     */
    public static void place(ServerLevel level, Placement placement, int floor, RandomSource random) {
        boolean deep = random.nextDouble() < OreTable.deepslateChance(floor);
        Set<BlockPos> spots = new HashSet<>();
        List<BlockPos> spawners = new ArrayList<>();
        if (placement.isCarved()) {
            MinesGrid.Placed placed = RoomCarver.carve(placement.kind(), placement.seed()).grid()
                    .place(level, placement.targetMin(), placement.rotation(), deep ? MinesBuilder::deepslate : state -> state);
            spots.addAll(placed.oreSpots());
            spawners.addAll(placed.spawners());
        } else {
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

        for (List<BlockPos> vein : veins(spots)) {
            Block ore = OreTable.pick(floor, random).block(deep);
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
