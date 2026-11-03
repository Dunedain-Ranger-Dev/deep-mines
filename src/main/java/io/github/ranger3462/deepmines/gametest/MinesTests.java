package io.github.ranger3462.deepmines.gametest;

import static io.github.ranger3462.deepmines.MinesSpec.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;

import io.github.ranger3462.deepmines.DeepMines;
import io.github.ranger3462.deepmines.InMinesPlacement;
import io.github.ranger3462.deepmines.MinesBuilder;
import io.github.ranger3462.deepmines.MinesBuilder.Placement;
import io.github.ranger3462.deepmines.MinesFloors;
import io.github.ranger3462.deepmines.MinesFloorsData;
import io.github.ranger3462.deepmines.MinesGrid;
import io.github.ranger3462.deepmines.MinesPieces;
import io.github.ranger3462.deepmines.MinesPieces.Kind;
import io.github.ranger3462.deepmines.MinesPortal;
import io.github.ranger3462.deepmines.MinesReset;
import io.github.ranger3462.deepmines.ModBlocks;
import io.github.ranger3462.deepmines.OreTable;
import io.github.ranger3462.deepmines.OreTable.Ore;
import io.github.ranger3462.deepmines.RoomCarver;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonLayout.Cell;
import net.kyrptonaught.customportalapi.CustomPortalApiRegistry;
import net.kyrptonaught.customportalapi.util.PortalLink;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Checks the Mines: the dimension's data, the portal, the reset schedule, the pieces, the ore, and building floors. */
@GameTestHolder(DeepMines.MODID)
@PrefixGameTestTemplate(false)
public class MinesTests {
    /** An empty structure, since these tests don't build anything. */
    private static final String EMPTY = "empty";
    private static final long DAY = 24L * 60 * 60 * 1000;

    /**
     * The test server only creates the vanilla dimensions, so this checks the Mines' dimension type loaded and their
     * dimension file decodes, rather than the dimension itself.
     */
    @GameTest(template = EMPTY)
    public static void theMinesDimensionDataLoads(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var registries = server.registryAccess();
        var type = registries.registryOrThrow(Registries.DIMENSION_TYPE).get(MinesPortal.MINES.location());
        helper.assertTrue(type != null && type.minY() == -64 && type.height() == 192, "the Mines should end at the caves' bedrock roof");
        var file = server.getResourceManager().getResource(DeepMines.id("dimension/mines.json")).orElseThrow();
        try (var reader = file.openAsReader()) {
            var result = LevelStem.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), JsonParser.parseReader(reader));
            helper.assertTrue(result.isSuccess(), "the Mines dimension should decode: " + result.error().map(Object::toString).orElse(""));
        } catch (IOException e) {
            helper.fail("couldn't read the Mines dimension: " + e);
        }
        helper.succeed();
    }

    /**
     * The Mines' terrain is vanilla's caves with aquifers turned on: YUNG's Better Caves (in the pack) crashes chunk
     * generation in any dimension without them, which broke the portal in the pack.
     */
    @GameTest(template = EMPTY)
    public static void theMinesTerrainHasAquifers(GameTestHelper helper) {
        var settings = helper.getLevel().registryAccess().registryOrThrow(Registries.NOISE_SETTINGS).get(DeepMines.id("mines"));
        helper.assertTrue(settings != null && settings.aquifersEnabled(), "the Mines' noise settings should have aquifers on");
        helper.succeed();
    }

    /**
     * The {@code deepmines:in_mines} filter keeps a feature out of every other dimension; with the pack, Ice and Fire's
     * silver and sapphire carry it, so they only generate in the Mines.
     */
    @GameTest(template = EMPTY)
    public static void inMinesOresStayOutOfTheOverworld(GameTestHelper helper) {
        var level = helper.getLevel();
        var context = new PlacementContext(level, level.getChunkSource().getGenerator(), Optional.empty());
        helper.assertTrue(InMinesPlacement.INSTANCE.getPositions(context, level.getRandom(), BlockPos.ZERO).findAny().isEmpty(),
                "nothing filtered to the Mines places in the overworld");
        var placed = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
        for (String ore : List.of("silver_ore", "sapphire_ore")) {
            PlacedFeature feature = placed.get(ResourceLocation.fromNamespaceAndPath("iceandfire", ore));
            if (feature != null && ModList.get().isLoaded("kubejs")) { // The pack's data adds the filter.
                helper.assertTrue(feature.placement().contains(InMinesPlacement.INSTANCE), "Ice and Fire's " + ore + " only generates in the Mines");
            }
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void deepslateBrickFramesLeadToTheMines(GameTestHelper helper) {
        PortalLink link = CustomPortalApiRegistry.getPortalLinkFromBase(MinesPortal.FRAME);
        helper.assertTrue(link != null && link.dimID.equals(MinesPortal.MINES.location()), "a deepslate brick frame should lead to the Mines");
        helper.assertTrue(link.portalSearchYTop == MinesPortal.ARRIVAL_TOP_Y && link.portalSearchYBottom == MinesPortal.ARRIVAL_BOTTOM_Y,
                "arrival portals should be placed in caves, not on the roof");
        helper.assertTrue(MinesFloors.BASE.getY() > MinesPortal.ARRIVAL_TOP_Y, "and below the floors, so they never cut into one");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void resetsComeDueOnSchedule(GameTestHelper helper) {
        long last = 1_000_000L;
        helper.assertFalse(MinesReset.due(last, last + 6 * DAY, 7), "6 days in, a weekly reset isn't due");
        helper.assertTrue(MinesReset.due(last, last + 7 * DAY, 7), "7 days in, it is");
        helper.assertFalse(MinesReset.due(last, last + 700 * DAY, 0), "0 days turns resets off");
        helper.assertTrue(MinesReset.untilDue(last, last + 5 * DAY, 7) == 2 * DAY, "two days should be left");
        helper.succeed();
    }

    /**
     * A floor's plan: carved rooms and corridors (the mod has no hand-built ones) and the Mines' own caps, its first room
     * common, a corridor per join and a cap per dead end.
     */
    @GameTest(template = EMPTY)
    public static void floorsArePlannedFromTheMinesOwnPieces(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<Placement> placements = MinesFloors.plan(level, BlockPos.ZERO, 5, RandomSource.create(1));
        for (Placement placement : placements) {
            if (placement.kind().carved()) {
                helper.assertTrue(placement.isCarved(), "rooms and corridors are carved: " + placement);
                helper.assertTrue(placement.kind() == Kind.TUNNEL || placement.rotation() == Rotation.NONE, "and rooms aren't turned: " + placement);
            } else {
                helper.assertTrue(!placement.isCarved() && placement.template().getNamespace().equals(DeepMines.MODID)
                        && placement.template().getPath().startsWith(MinesPieces.FOLDER + "/" + placement.kind().prefix()),
                        "every saved piece should be the Mines' own, of its kind: " + placement.template());
            }
        }
        helper.assertTrue(placements.stream().filter(Placement::isCarved).map(Placement::seed).distinct().count()
                == placements.stream().filter(Placement::isCarved).count(), "each carved from its own seed");
        long rooms = placements.stream().filter(p -> p.kind().room()).count();
        long tunnels = placements.stream().filter(p -> p.kind() == Kind.TUNNEL).count();
        long caps = placements.stream().filter(p -> p.kind() == Kind.CAP).count();
        helper.assertTrue(rooms == 5 && caps == rooms * 4 - tunnels * 2, rooms + " rooms, " + tunnels + " corridors, " + caps + " caps");
        helper.assertTrue(placements.get(0).kind() == Kind.COMMON, "the first room is common: " + placements.get(0).kind());
        helper.assertTrue(placements.stream().noneMatch(p -> p.kind() == Kind.START), "the start hall is only on the start floor");
        helper.succeed();
    }

    /**
     * The ore table is the design doc's (and {@code ore-table.py}'s): coal and copper up top, no Mithril above floor 6,
     * and deepslate from about floor 45 down.
     */
    @GameTest(template = EMPTY)
    public static void theOreTableMatchesTheDesign(GameTestHelper helper) {
        Map<Ore, Double> one = OreTable.mix(1);
        helper.assertTrue(Math.abs(one.get(Ore.COAL) - 50) < 1 && Math.abs(one.get(Ore.COPPER) - 40) < 1, "floor 1 is half coal, 40% copper: " + one);
        helper.assertTrue(OreTable.mix(5).get(Ore.MITHRIL) == 0 && Math.abs(OreTable.mix(6).get(Ore.MITHRIL) - 0.5) < 1e-9, "Mithril starts on floor 6");
        helper.assertTrue(Math.abs(OreTable.mix(100).get(Ore.MITHRIL) - 2) < 1e-9, "at 2% on floor 100");
        Map<Ore, Double> seventy = OreTable.mix(70);
        helper.assertTrue(Math.abs(seventy.get(Ore.DIAMOND) - 29) < 1 && seventy.get(Ore.COAL) < 0.05, "floor 70 is diamond country: " + seventy);
        for (int floor : new int[] { 1, 30, 70, 100 }) {
            double total = OreTable.mix(floor).values().stream().mapToDouble(Double::doubleValue).sum();
            helper.assertTrue(Math.abs(total - 100) < 1e-6, "floor " + floor + " adds up to " + total);
        }
        helper.assertTrue(OreTable.deepslateChance(40) == 0 && OreTable.deepslateChance(55) == 1 && OreTable.deepslateChance(MinesFloors.START) == 0,
                "stone up top, deepslate from floor 50 or so");
        helper.succeed();
    }

    /**
     * The saved pieces load at the right size: the cap is solid, and the start hall sealed, with room at its ladders.
     * The mod has no saved rooms or corridors.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void savedPiecesFollowTheRules(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinesPieces pieces = MinesFloors.pieces(level);
        helper.assertTrue(pieces.skipped().isEmpty(), "no piece should be skipped: " + pieces.skipped());
        for (Kind kind : Kind.values()) {
            helper.assertTrue(kind.carved() != pieces.all().containsValue(kind), "saved " + kind.prefix() + " pieces only for the fixed kinds");
        }

        BlockPos corner = helper.absolutePos(BlockPos.ZERO).above(150);
        List<String> problems = new ArrayList<>();
        for (var piece : pieces.all().entrySet()) {
            Kind kind = piece.getValue();
            String name = piece.getKey().getPath();
            StructureTemplate template = level.getStructureManager().get(piece.getKey()).orElseThrow();
            if (!template.filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), ModBlocks.ORE_SPOT.get()).isEmpty()) {
                problems.add(name + " has ore");
            }
            template.placeInWorld(level, corner, corner, new StructurePlaceSettings().setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING), level.getRandom(), Block.UPDATE_CLIENTS);
            Vec3i size = kind.size();
            if (kind == Kind.START) {
                checkStart(level, corner, name, problems);
            } else {
                forEach(corner, size, (pos, x, y, z) -> {
                    if (!solid(level, pos)) {
                        problems.add(name + ": a hole at " + x + "," + y + "," + z);
                    }
                });
            }
            for (BlockPos pos : BlockPos.betweenClosed(corner, corner.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1))) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        helper.assertTrue(problems.isEmpty(), problems.size() + " problems, first: " + problems.stream().limit(4).toList());
        helper.succeed();
    }

    /** How many of each carved kind {@link #carvedRoomsFollowTheRules} carves. */
    private static final int CARVINGS = 60;

    /**
     * Carves {@link #CARVINGS} of every kind of room and corridor, and every one keeps the rules a floor relies on
     * ({@link RoomCarver#problems}). Common rooms have the usual amount of ore, quarries far more, corridors a little;
     * few need carving again, and hardly any need walkways.
     */
    @GameTest(template = EMPTY)
    public static void carvedRoomsFollowTheRules(GameTestHelper helper) {
        List<String> problems = new ArrayList<>();
        Map<Kind, List<Integer>> ore = new EnumMap<>(Kind.class);
        int retried = 0;
        int bridged = 0;
        int total = 0;
        long started = System.nanoTime();
        for (Kind kind : Kind.values()) {
            if (!kind.carved()) {
                continue;
            }
            for (int seed = 0; seed < CARVINGS; seed++) {
                String name = kind + " " + seed;
                // Rooms with every mix of corridors, from one to all four sides.
                Set<Direction> openings = EnumSet.noneOf(Direction.class);
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    if (((seed % 15 + 1) & (1 << side.get2DDataValue())) != 0) {
                        openings.add(side);
                    }
                }
                RoomCarver.Carved carved = RoomCarver.carve(kind, seed * 7919L + kind.ordinal(), openings);
                RoomCarver.problems(carved.grid(), kind).forEach(problem -> problems.add(name + ": " + problem));
                ore.computeIfAbsent(kind, k -> new ArrayList<>()).add(carved.grid().count(MinesGrid.ORE));
                retried += carved.tries() > 1 ? 1 : 0;
                bridged += carved.bridged() ? 1 : 0;
                total++;
            }
        }
        DeepMines.LOGGER.info("Carved {} rooms and corridors in {} ms: {} carved again, {} with walkways; ore on average {}", total,
                (System.nanoTime() - started) / 1_000_000, retried, bridged,
                ore.entrySet().stream().map(e -> e.getKey() + " " + Math.round(e.getValue().stream().mapToInt(Integer::intValue).average().orElse(0))).toList());
        helper.assertTrue(problems.isEmpty(), problems.size() + " problems, first: " + problems.stream().limit(4).toList());
        helper.assertTrue(bridged * 20 <= total, bridged + " of " + total + " needed walkways");
        double common = ore.get(Kind.COMMON).stream().mapToInt(Integer::intValue).average().orElse(0);
        double quarry = ore.get(Kind.QUARRY).stream().mapToInt(Integer::intValue).average().orElse(0);
        helper.assertTrue(common > 300 && quarry > common * 1.5, "quarries should have far more ore than common rooms: " + common + ", " + quarry);
        helper.assertTrue(ore.get(Kind.COMMON).stream().allMatch(n -> n > 200), "every common room has a fair bit of ore: " + ore.get(Kind.COMMON));
        helper.assertTrue(ore.get(Kind.TUNNEL).stream().allMatch(n -> n < 30)
                && ore.get(Kind.TUNNEL).stream().mapToInt(Integer::intValue).average().orElse(0) > 3, "corridors have a little ore: " + ore.get(Kind.TUNNEL));
        Set<Direction> all = EnumSet.allOf(Direction.class);
        all.removeIf(d -> d.getAxis().isVertical());
        helper.assertTrue(RoomCarver.carve(Kind.QUARRY, 42, all).grid().count(MinesGrid.ORE) == RoomCarver.carve(Kind.QUARRY, 42, all).grid().count(MinesGrid.ORE)
                && RoomCarver.carve(Kind.COMMON, 1, all).grid().count(MinesGrid.ORE) != RoomCarver.carve(Kind.COMMON, 2, all).grid().count(MinesGrid.ORE),
                "the same seed carves the same room, and different seeds different ones");
        helper.succeed();
    }

    private interface Spot {
        void at(BlockPos pos, int x, int y, int z);
    }

    private static void forEach(BlockPos corner, Vec3i size, Spot spot) {
        for (int x = 0; x < size.getX(); x++) {
            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < size.getZ(); z++) {
                    spot.at(corner.offset(x, y, z), x, y, z);
                }
            }
        }
    }

    /** Nothing to bump into, and no water: air, or a rail. */
    private static boolean clear(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    private static boolean solid(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return !state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    private static void checkRoom(ServerLevel level, BlockPos corner, Kind kind, String name, List<String> problems) {
        int max = ROOM_SIZE - 1;
        int[] water = { 0 };
        int[] spawners = { 0 };
        forEach(corner, kind.size(), (pos, x, y, z) -> {
            var state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) {
                water[0]++;
            }
            if (state.is(Blocks.SPAWNER)) {
                spawners[0]++;
            }
            boolean outside = x == 0 || y == 0 || z == 0 || x == max || y == ROOM_HEIGHT - 1 || z == max;
            if (!outside) {
                return;
            }
            boolean doorway = inDoorHeight(y) && ((inDoorSpan(x) && (z == 0 || z == max)) || (inDoorSpan(z) && (x == 0 || x == max)));
            if (doorway ? !clear(level, pos) : !solid(level, pos)) {
                problems.add(name + (doorway ? ": a blocked doorway at " : ": a hole in the outside at ") + x + "," + y + "," + z);
            }
        });
        for (Direction side : Direction.Plane.HORIZONTAL) {
            for (int depth = 1; depth <= LANDING_DEPTH; depth++) {
                for (int across = LANDING_START; across < LANDING_START + LANDING_WIDTH; across++) {
                    int[] p = fromWall(side, across, depth);
                    if (!solid(level, corner.offset(p[0], WALK_Y, p[1]))) {
                        problems.add(name + ": no floor in the " + side + " landing at " + p[0] + "," + p[1]);
                    }
                    for (int y = DOOR_BOTTOM_Y; y < DOOR_BOTTOM_Y + DOOR_HEIGHT; y++) {
                        if (!clear(level, corner.offset(p[0], y, p[1]))) {
                            problems.add(name + ": the " + side + " landing is blocked at " + p[0] + "," + y + "," + p[1]);
                        }
                    }
                }
            }
        }
        if ((kind == Kind.FLOODED) != (water[0] > 0) || (kind == Kind.FLOODED && water[0] < 500)) {
            problems.add(name + ": " + water[0] + " water");
        }
        if ((kind == Kind.INFESTED) != (spawners[0] >= 2)) {
            problems.add(name + ": " + spawners[0] + " spawners");
        }
    }

    private static void checkTunnel(ServerLevel level, BlockPos corner, String name, List<String> problems) {
        int lo = TUNNEL_WALL;
        int hi = TUNNEL_WIDTH - 1 - TUNNEL_WALL;
        int roof = TUNNEL_WALL + DOOR_HEIGHT - 1;
        forEach(corner, Kind.TUNNEL.size(), (pos, x, y, z) -> {
            boolean passage = x >= lo && x <= hi && y >= TUNNEL_WALL && y <= roof;
            if (!passage && !solid(level, pos)) {
                problems.add(name + ": a hole in the rock at " + x + "," + y + "," + z);
            }
            if (passage && (z == 0 || z == TUNNEL_LENGTH - 1) && !clear(level, pos)) {
                problems.add(name + ": a blocked end at " + x + "," + y + "," + z);
            }
            if (x == TUNNEL_WIDTH / 2 && y >= TUNNEL_WALL && y < roof - 1 && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                problems.add(name + ": the walkway is blocked at " + x + "," + y + "," + z);
            }
        });
    }

    private static void checkStart(ServerLevel level, BlockPos corner, String name, List<String> problems) {
        int max = ROOM_SIZE - 1;
        forEach(corner, Kind.START.size(), (pos, x, y, z) -> {
            boolean outside = x == 0 || y == 0 || z == 0 || x == max || y == ROOM_HEIGHT - 1 || z == max;
            if (outside && !solid(level, pos)) {
                problems.add(name + ": a hole in the outside at " + x + "," + y + "," + z);
            }
        });
        for (int[] spot : new int[][] { START_LADDER_DOWN, START_LADDER_UP }) {
            BlockPos pos = corner.offset(spot[0], spot[1], spot[2]);
            if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir() || !solid(level, pos.below())) {
                problems.add(name + ": no room for the ladder at " + spot[0] + "," + spot[1] + "," + spot[2]);
            }
        }
    }

    /**
     * A carved room placed on a deep floor keeps the rules in the world too, with its stone turned to deepslate (and
     * tuff) and its ore to the deepslate kind; its ore spots all become ore, and its spawners get undead to spawn. A
     * corridor keeps its rules, and turned to run east-west, stays open at both ends.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void carvedPiecesArePlacedWithTheirOre(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos corner = helper.absolutePos(BlockPos.ZERO).above(150);
        MinesBuilder.place(level, Placement.carved(Kind.INFESTED, corner, Rotation.NONE, 3, EnumSet.of(Direction.NORTH, Direction.EAST)), 100, RandomSource.create(3));
        int[] counts = new int[4]; // stone, deepslate, ores, spawners with undead
        List<String> problems = new ArrayList<>();
        checkRoom(level, corner, Kind.INFESTED, "infested", problems);
        forEach(corner, Kind.INFESTED.size(), (pos, x, y, z) -> {
            var state = level.getBlockState(pos);
            if (state.is(Blocks.STONE) || state.is(Blocks.ANDESITE) || state.is(Blocks.DIORITE) || state.is(Blocks.GRANITE)) {
                counts[0]++;
            } else if (state.is(Blocks.DEEPSLATE) || state.is(Blocks.TUFF)) {
                counts[1]++;
            } else if (state.is(ModBlocks.ORE_SPOT.get())) {
                problems.add("an ore spot left at " + x + "," + y + "," + z);
            } else if (state.is(Tags.Blocks.ORES)) {
                counts[2]++;
                if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().startsWith("deepslate_")) {
                    problems.add("stone ore on floor 100: " + state);
                }
            } else if (state.is(Blocks.SPAWNER) && level.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner) {
                String mob = spawner.getSpawner().save(new CompoundTag()).getCompound("SpawnData").getCompound("entity").getString("id");
                if (mob.equals("minecraft:zombie") || mob.equals("minecraft:skeleton")) {
                    counts[3]++;
                } else {
                    problems.add("a spawner of '" + mob + "'");
                }
            }
        });
        clear(level, corner, Kind.INFESTED.size());

        MinesBuilder.place(level, Placement.carved(Kind.TUNNEL, corner, Rotation.NONE, 5, Set.of()), 1, RandomSource.create(5));
        checkTunnel(level, corner, "tunnel", problems);
        clear(level, corner, Kind.TUNNEL.size());
        MinesBuilder.place(level, Placement.carved(Kind.TUNNEL, corner, Rotation.CLOCKWISE_90, 5, Set.of()), 1, RandomSource.create(5));
        for (int across = TUNNEL_WALL; across < TUNNEL_WALL + DOOR_WIDTH; across++) {
            for (int y = TUNNEL_WALL; y < TUNNEL_WALL + DOOR_HEIGHT; y++) {
                for (int x : new int[] { 0, TUNNEL_LENGTH - 1 }) {
                    if (!clear(level, corner.offset(x, y, across))) {
                        problems.add("the turned tunnel is blocked at its end at " + x + "," + y + "," + across);
                    }
                }
            }
        }
        clear(level, corner, new Vec3i(TUNNEL_LENGTH, TUNNEL_HEIGHT, TUNNEL_WIDTH));

        helper.assertTrue(problems.isEmpty(), problems.size() + " problems, first: " + problems.stream().limit(3).toList());
        helper.assertTrue(counts[0] == 0 && counts[1] > 10000, "floor 100 is deepslate and tuff: " + counts[0] + " stone, " + counts[1] + " deepslate");
        helper.assertTrue(counts[2] > 300 && counts[3] >= 2, counts[2] + " ore, " + counts[3] + " spawners of undead");
        helper.succeed();
    }

    private static void clear(ServerLevel level, BlockPos corner, Vec3i size) {
        for (BlockPos pos : BlockPos.betweenClosed(corner, corner.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1))) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /**
     * The start floor and the floors below it are built when first reached, with the start hall's ladder down leading to
     * floor 1, each floor's ladder down to the next, and ladders up back again; from the start floor, up leads out.
     * Built in the test level, as the test server has no Mines.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400)
    public static void laddersLinkTheFloors(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinesFloorsData.get(level).clear();
        BlockPos base = helper.absolutePos(BlockPos.ZERO).above(120);

        MinesFloorsData.Floor start = MinesFloors.floor(level, base, MinesFloors.START);
        helper.assertTrue(level.getBlockState(start.ladderDown()).is(ModBlocks.LADDER_DOWN.get()), "the start hall has a ladder down");
        helper.assertTrue(level.getBlockState(start.ladderUp()).is(ModBlocks.LADDER_UP.get()), "and a ladder up and out");
        helper.assertTrue(MinesFloors.floorAt(base, start.ladderDown()) == MinesFloors.START, "it's on the start floor");
        helper.assertTrue(MinesFloorsData.get(level).floor(1).isEmpty(), "floor 1 isn't built until someone goes down");

        ServerPlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "mines_tester"));
        Optional<BlockPos> toOne = MinesFloors.descend(player, level, base, MinesFloors.START);
        MinesFloorsData.Floor one = MinesFloorsData.get(level).floor(1).orElseThrow();
        helper.assertTrue(toOne.equals(Optional.of(one.arrivalFromAbove())), "going down lands beside floor 1's ladder up: " + toOne);
        Optional<BlockPos> toTwo = MinesFloors.descend(player, level, base, 1);
        MinesFloorsData.Floor two = MinesFloorsData.get(level).floor(2).orElseThrow();
        helper.assertTrue(toTwo.equals(Optional.of(two.arrivalFromAbove())), "and on to floor 2: " + toTwo);
        for (MinesFloorsData.Floor floor : List.of(start, one, two)) {
            helper.assertTrue(level.getBlockState(floor.ladderUp()).is(ModBlocks.LADDER_UP.get()), "floor " + floor.number() + " has a ladder up");
            helper.assertTrue(level.getBlockState(floor.ladderDown()).is(ModBlocks.LADDER_DOWN.get()), "and a ladder down");
            helper.assertTrue(MinesFloors.floorAt(base, floor.ladderDown()) == floor.number(), "which knows its floor");
            for (BlockPos arrival : List.of(floor.arrivalFromAbove(), floor.arrivalFromBelow())) {
                helper.assertTrue(level.getBlockState(arrival).isAir() && level.getBlockState(arrival.above()).isAir() && solid(level, arrival.below()),
                        "with room to stand on floor " + floor.number() + " at " + arrival);
            }
        }
        // The first room's ore spots have all become ore.
        BlockPos first = MinesBuilder.roomMin(MinesFloors.center(base, 1), Cell.ORIGIN);
        int[] ore = { 0, 0 };
        forEach(first, Kind.COMMON.size(), (pos, x, y, z) -> {
            var state = level.getBlockState(pos);
            ore[0] += state.is(Tags.Blocks.ORES) ? 1 : 0;
            ore[1] += state.is(ModBlocks.ORE_SPOT.get()) ? 1 : 0;
        });
        helper.assertTrue(ore[0] > 300 && ore[1] == 0, "floor 1's first room has " + ore[0] + " ore and " + ore[1] + " spots left");

        Optional<BlockPos> up = MinesFloors.ascend(player, level, base, 2);
        helper.assertTrue(up.equals(Optional.of(one.arrivalFromBelow())), "going up lands beside floor 1's ladder down: " + up);
        Optional<BlockPos> toStart = MinesFloors.ascend(player, level, base, 1);
        helper.assertTrue(toStart.equals(Optional.of(start.arrivalFromBelow())), "and from floor 1 up to the start hall: " + toStart);
        helper.assertTrue(MinesFloors.ascend(player, level, base, MinesFloors.START).isEmpty(), "up from the start floor leads out of the Mines");
        MinesFloorsData.get(level).clear();
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aResetWipesTheDimensionFolder(GameTestHelper helper) {
        try {
            Path folder = Files.createTempDirectory("deepmines_test");
            Files.createDirectories(folder.resolve("region"));
            Files.writeString(folder.resolve("region").resolve("r.0.0.mca"), "chunks");
            MinesReset.wipe(folder);
            helper.assertFalse(Files.exists(folder), "the Mines folder should be gone");
            MinesReset.wipe(folder);
        } catch (IOException e) {
            helper.fail("wiping failed: " + e);
        }
        helper.succeed();
    }
}
