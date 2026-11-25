package io.github.ranger3462.deepmines.gametest;

import static io.github.ranger3462.deepmines.MinesSpec.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;

import io.github.ranger3462.deepmines.MinesBuildQueue;
import io.github.ranger3462.deepmines.DeepMines;
import io.github.ranger3462.deepmines.InMinesPlacement;
import io.github.ranger3462.deepmines.MinesBuilder;
import io.github.ranger3462.deepmines.MinesBuilder.Placement;
import io.github.ranger3462.deepmines.MinesFloors;
import io.github.ranger3462.deepmines.MinesFloorsData;
import io.github.ranger3462.deepmines.MinesGrid;
import io.github.ranger3462.deepmines.MinesLevels;
import io.github.ranger3462.deepmines.MinesLift;
import io.github.ranger3462.deepmines.MinesMobs;
import io.github.ranger3462.deepmines.MinesPieces;
import io.github.ranger3462.deepmines.MinesPieces.Kind;
import io.github.ranger3462.deepmines.MinesPortal;
import io.github.ranger3462.deepmines.MinesRegions;
import io.github.ranger3462.deepmines.MinesReset;
import io.github.ranger3462.deepmines.MinesTeams;
import io.github.ranger3462.deepmines.Config;
import io.github.ranger3462.deepmines.MinesEntrance;
import io.github.ranger3462.deepmines.ModBlocks;
import io.github.ranger3462.deepmines.OreTable;
import io.github.ranger3462.deepmines.OreTable.Ore;
import io.github.ranger3462.deepmines.RoomCarver;
import io.github.ranger3462.dungeondelving.dungeon.DungeonLoot;
import io.github.ranger3462.dungeondelving.dungeon.SpawnerWaves;
import io.github.ranger3462.dungeondelving.dungeon.layout.DungeonLayout.Cell;
import net.kyrptonaught.customportalapi.CustomPortalApiRegistry;
import net.kyrptonaught.customportalapi.util.PortalLink;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderSet;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
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
        helper.assertTrue(type != null && type.minY() == -64 && type.height() == 192, "the Mines should end at their bedrock roof");
        var file = server.getResourceManager().getResource(DeepMines.id("dimension/mines.json")).orElseThrow();
        try (var reader = file.openAsReader()) {
            var result = LevelStem.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), JsonParser.parseReader(reader));
            helper.assertTrue(result.isSuccess(), "the Mines dimension should decode: " + result.error().map(Object::toString).orElse(""));
            // One bare biome throughout: no caves carved, no structures or features, and no mobs spawning naturally.
            var biomes = result.getOrThrow().generator().getBiomeSource().possibleBiomes();
            helper.assertTrue(biomes.size() == 1 && biomes.iterator().next().is(DeepMines.id("mines")), "the Mines should be all one biome: " + biomes);
            Biome biome = biomes.iterator().next().value();
            for (GenerationStep.Carving step : GenerationStep.Carving.values()) {
                helper.assertTrue(!biome.getGenerationSettings().getCarvers(step).iterator().hasNext(), "the Mines' biome should carve no caves");
            }
            // Other mods add their own everywhere (Apotheosis' dungeons and spawners, which need open cave to place, and
            // the pack may add ore), but nothing of vanilla's.
            var vanilla = biome.getGenerationSettings().features().stream().flatMap(HolderSet::stream)
                    .filter(feature -> feature.is(key -> key.location().getNamespace().equals("minecraft"))).toList();
            helper.assertTrue(vanilla.isEmpty(), "the Mines' biome should place none of vanilla's features: " + vanilla);
            if (ModList.get().isLoaded("kubejs") && ModList.get().isLoaded("iceandfire")) { // The pack adds Ice and Fire's ore.
                for (String ore : List.of("silver_ore", "sapphire_ore")) {
                    ResourceLocation id = ResourceLocation.fromNamespaceAndPath("iceandfire", ore);
                    helper.assertTrue(biome.getGenerationSettings().features().stream().flatMap(HolderSet::stream).anyMatch(feature -> feature.is(id)),
                            "with the pack, Ice and Fire's " + ore + " generates in the Mines");
                }
            }
            for (MobCategory category : MobCategory.values()) {
                var mobs = biome.getMobSettings().getMobs(category).unwrap().stream().map(spawn -> BuiltInRegistries.ENTITY_TYPE.getKey(spawn.type)).toList();
                helper.assertTrue(mobs.isEmpty(), "nothing should spawn naturally in the Mines: " + category.getName() + " " + mobs);
            }
        } catch (IOException e) {
            helper.fail("couldn't read the Mines dimension: " + e);
        }
        helper.succeed();
    }

    /**
     * The Mines' terrain is solid rock, so the built floors are all there is, but with aquifers still turned on: YUNG's
     * Better Caves (in the pack) crashes chunk generation in any dimension without them, which broke the portal in the
     * pack. With no open space, they never place any water.
     */
    @GameTest(template = EMPTY)
    public static void theMinesTerrainIsSolidRock(GameTestHelper helper) {
        var settings = helper.getLevel().registryAccess().registryOrThrow(Registries.NOISE_SETTINGS).get(DeepMines.id("mines"));
        helper.assertTrue(settings != null && settings.aquifersEnabled(), "the Mines' noise settings should have aquifers on");
        DensityFunction density = settings.noiseRouter().finalDensity();
        for (int x = -2048; x <= 102400; x += 4093) {
            for (int y = -64; y < 128; y += 7) {
                helper.assertTrue(density.compute(new DensityFunction.SinglePointContext(x, y, x / 3)) > 0, "the Mines should be solid at " + x + ", " + y);
            }
        }
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
                "arrival portals should be placed well under the roof");
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
     * ({@link RoomCarver#problems}). Common rooms have the usual amount of ore, quarries far more, vein rooms more, corridors a little;
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
        helper.assertTrue(common > 100 && quarry > common * 1.5, "quarries should have far more ore than common rooms: " + common + ", " + quarry);
        double vein = ore.get(Kind.VEIN).stream().mapToInt(Integer::intValue).average().orElse(0);
        helper.assertTrue(vein > common * 1.2, "vein rooms should have more ore than common rooms: " + common + ", " + vein);
        helper.assertTrue(ore.get(Kind.COMMON).stream().allMatch(n -> n > 50), "every common room has a fair bit of ore: " + ore.get(Kind.COMMON));
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
        int roof = TUNNEL_ROOF;
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
     * tuff) and its ore to the deepslate kind; its ore spots all become ore, and its spawners become wave spawners of
     * the deepest tier's mobs (or, now and then with Mowzie's Mobs, it's a Wroughtnaut's hall instead). A
     * corridor keeps its rules, and turned to run east-west, stays open at both ends. A vein room's ore is all one kind.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void carvedPiecesArePlacedWithTheirOre(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos corner = helper.absolutePos(BlockPos.ZERO).above(150);
        MinesBuilder.place(level, Placement.carved(Kind.INFESTED, corner, Rotation.NONE, 3, EnumSet.of(Direction.NORTH, Direction.EAST)), 100, RandomSource.create(3));
        int[] counts = new int[4]; // stone, deepslate, ores, wave spawners of the deepest mobs
        Set<String> deepest = mobsOf(level, 100);
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
                if (deepest.contains(mob) && SpawnerWaves.isWaveSpawner(level, pos)) {
                    counts[3]++;
                } else {
                    problems.add("a spawner of '" + mob + "'");
                }
            }
        });
        List<Mob> halls = level.getEntitiesOfClass(Mob.class, AABB.of(Placement.carved(Kind.INFESTED, corner, Rotation.NONE, 3, Set.of()).box()));
        boolean hall = halls.stream().anyMatch(mob -> EntityType.getKey(mob.getType()).equals(MinesMobs.WROUGHTNAUT));
        halls.forEach(Entity::discard);
        clear(level, corner, Kind.INFESTED.size());

        MinesBuilder.place(level, Placement.carved(Kind.TUNNEL, corner, Rotation.NONE, 5, Set.of()), 1, RandomSource.create(5));
        checkTunnel(level, corner, "tunnel", problems);
        clear(level, corner, Kind.TUNNEL.size());
        MinesBuilder.place(level, Placement.carved(Kind.TUNNEL, corner, Rotation.CLOCKWISE_90, 5, Set.of()), 1, RandomSource.create(5));
        for (int across = TUNNEL_WALL; across < TUNNEL_WIDTH - TUNNEL_WALL; across++) {
            for (int y = TUNNEL_WALL; y <= TUNNEL_ROOF; y++) {
                for (int x : new int[] { 0, TUNNEL_LENGTH - 1 }) {
                    if (!clear(level, corner.offset(x, y, across))) {
                        problems.add("the turned tunnel is blocked at its end at " + x + "," + y + "," + across);
                    }
                }
            }
        }
        clear(level, corner, new Vec3i(TUNNEL_LENGTH, TUNNEL_HEIGHT, TUNNEL_WIDTH));

        // A vein room's ore is all of one kind.
        MinesBuilder.place(level, Placement.carved(Kind.VEIN, corner, Rotation.NONE, 7, EnumSet.of(Direction.SOUTH)), 30, RandomSource.create(7));
        checkRoom(level, corner, Kind.VEIN, "vein", problems);
        Set<Block> veinOres = new HashSet<>();
        forEach(corner, Kind.VEIN.size(), (pos, x, y, z) -> {
            var state = level.getBlockState(pos);
            if (state.is(Tags.Blocks.ORES)) {
                veinOres.add(state.getBlock());
            }
        });
        if (veinOres.size() != 1) {
            problems.add("a vein room should have one ore, not " + veinOres);
        }
        clear(level, corner, Kind.VEIN.size());

        helper.assertTrue(problems.isEmpty(), problems.size() + " problems, first: " + problems.stream().limit(3).toList());
        helper.assertTrue(counts[0] == 0 && counts[1] > 10000, "floor 100 is deepslate and tuff: " + counts[0] + " stone, " + counts[1] + " deepslate");
        helper.assertTrue(counts[2] > 150 && (counts[3] >= 2 || hall), counts[2] + " ore, " + counts[3] + " wave spawners of the deepest mobs");
        helper.succeed();
    }

    /**
     * The ore modifier scales how much ore rooms are carved with: about twice as much at 2, none at 0, and at 1 the
     * same rooms as without it.
     */
    @GameTest(template = EMPTY)
    public static void theOreModifierScalesTheOre(GameTestHelper helper) {
        Set<Direction> openings = EnumSet.of(Direction.NORTH, Direction.SOUTH);
        long[] ore = new long[4]; // at 1, at 2, at 0, without
        for (Kind kind : List.of(Kind.COMMON, Kind.QUARRY, Kind.FLOODED, Kind.INFESTED, Kind.VEIN)) {
            for (long seed = 1; seed <= 12; seed++) {
                ore[0] += RoomCarver.carve(kind, seed, openings, 1).grid().count(MinesGrid.ORE);
                ore[1] += RoomCarver.carve(kind, seed, openings, 2).grid().count(MinesGrid.ORE);
                ore[2] += RoomCarver.carve(kind, seed, openings, 0).grid().count(MinesGrid.ORE);
                ore[3] += RoomCarver.carve(kind, seed, openings).grid().count(MinesGrid.ORE);
            }
        }
        double ratio = (double) ore[1] / ore[0];
        helper.assertTrue(ore[0] == ore[3], "at 1, the same ore as without a modifier: " + ore[0] + " and " + ore[3]);
        helper.assertTrue(ratio > 1.6 && ratio < 2.4, "at 2, about twice the ore: " + ore[1] + " against " + ore[0]);
        helper.assertTrue(ore[2] == 0, "at 0, no ore: " + ore[2]);
        helper.succeed();
    }

    /**
     * Mithril, the dwarves' rare ore, is in the floors' ore from floor 6 when Iron's Spells is here; and with the pack,
     * mining it out of the Mines gives the ore and its raw Mithril, which Dungeon Delving would otherwise strip from
     * any loot made outside its dungeons.
     */
    @GameTest(template = EMPTY)
    public static void mithrilFromTheMinesIsKept(GameTestHelper helper) {
        if (!ModList.get().isLoaded("irons_spellbooks")) {
            helper.succeed();
            return;
        }
        helper.assertTrue(Ore.MITHRIL.installed() && Ore.MITHRIL.block(false) != Blocks.AIR && Ore.MITHRIL.block(true) != Blocks.AIR,
                "Mithril ore should be placed when Iron's Spells is here");
        if (ModList.get().isLoaded("kubejs")) { // The pack's data takes it off the dungeon-only list.
            for (String item : List.of("mithril_ore", "deepslate_mithril_ore", "raw_mithril")) {
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("irons_spellbooks", item)));
                helper.assertTrue(!stack.is(DungeonLoot.DUNGEON_ONLY), item + " shouldn't be dungeon-only, so mining it in the Mines keeps it");
            }
        }
        helper.succeed();
    }

    /** The installed mobs infested rooms can have on a floor. */
    private static Set<String> mobsOf(ServerLevel level, int floor) {
        Set<String> mobs = new HashSet<>();
        RandomSource random = RandomSource.create(floor);
        for (int i = 0; i < 500; i++) {
            MinesMobs.pick(level, floor, random).ifPresent(type -> mobs.add(EntityType.getKey(type).toString()));
        }
        return mobs;
    }

    /**
     * The deeper the floor, the older and darker what holds it: kobolds and vermin near the top, goblins below them,
     * then the restless dead. Mobs from mods that aren't here are left out.
     */
    @GameTest(template = EMPTY)
    public static void infestedMobsGoDeeper(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(MinesMobs.tier(1) == 1 && MinesMobs.tier(25) == 1 && MinesMobs.tier(26) == 2 && MinesMobs.tier(51) == 3
                && MinesMobs.tier(76) == 4 && MinesMobs.tier(100) == 4 && MinesMobs.tier(500) == 4, "tiers of 25 floors");
        Set<String> top = mobsOf(level, 10);
        Set<String> middle = mobsOf(level, 40);
        Set<String> deep = mobsOf(level, 60);
        Set<String> deepest = mobsOf(level, 90);
        helper.assertTrue(top.contains("dungeondelving:kobold_scavenger") && top.contains("minecraft:silverfish") && !top.contains("dungeondelving:goblin"),
                "floor 10: " + top);
        helper.assertTrue(middle.contains("dungeondelving:goblin") && !middle.contains("dungeondelving:kobold_scavenger"), "floor 40: " + middle);
        helper.assertTrue(deep.contains("dungeondelving:skeleton_swordsman") && !deep.contains("dungeondelving:goblin"), "floor 60: " + deep);
        helper.assertTrue(!deepest.isEmpty() && deepest.stream().noneMatch(mob -> mob.contains("kobold") || mob.contains("goblin")), "floor 90: " + deepest);
        for (Set<String> mobs : List.of(top, middle, deep, deepest)) {
            for (String mob : mobs) {
                helper.assertTrue(BuiltInRegistries.ENTITY_TYPE.containsKey(ResourceLocation.parse(mob)), mob + " isn't installed");
            }
        }
        helper.succeed();
    }

    /**
     * Fight them, then mine: an infested room's spawners each send one wave the first time someone comes near, and
     * break, so once its mobs are dealt with the room stays clear (until the reset).
     */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void clearedInfestedRoomsStayClear(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos corner = helper.absolutePos(BlockPos.ZERO).above(150);
        Placement room = Placement.carved(Kind.INFESTED, corner, Rotation.NONE, 11, EnumSet.of(Direction.NORTH));
        MinesBuilder.place(level, room, 10, RandomSource.create(11));
        List<BlockPos> spawners = new ArrayList<>();
        forEach(corner, Kind.INFESTED.size(), (pos, x, y, z) -> {
            if (level.getBlockState(pos).is(Blocks.SPAWNER)) {
                spawners.add(pos.immutable());
            }
        });
        helper.assertTrue(spawners.size() >= 2 && spawners.stream().allMatch(pos -> SpawnerWaves.isWaveSpawner(level, pos)), spawners.size() + " wave spawners");
        List<Mob> wave = new ArrayList<>();
        spawners.forEach(pos -> wave.addAll(SpawnerWaves.release(level, pos)));
        helper.assertTrue(!wave.isEmpty(), "the spawners should send their waves");
        wave.forEach(Entity::discard);
        int[] left = { 0 };
        forEach(corner, Kind.INFESTED.size(), (pos, x, y, z) -> left[0] += level.getBlockState(pos).is(Blocks.SPAWNER) ? 1 : 0);
        clear(level, corner, Kind.INFESTED.size());
        helper.assertTrue(left[0] == 0, left[0] + " spawners left to refill the room");
        helper.succeed();
    }

    /** Nothing spawns in the Mines on its own: only from the rooms (and the mobs placed in them). */
    @GameTest(template = EMPTY)
    public static void nothingSpawnsNaturallyInTheMines(GameTestHelper helper) {
        for (MobSpawnType type : List.of(MobSpawnType.NATURAL, MobSpawnType.CHUNK_GENERATION, MobSpawnType.PATROL)) {
            helper.assertTrue(MinesMobs.blocks(MinesPortal.MINES, type), type + " spawns should be stopped in the Mines");
            helper.assertTrue(!MinesMobs.blocks(Level.OVERWORLD, type), type + " spawns should still happen elsewhere");
        }
        for (MobSpawnType type : List.of(MobSpawnType.SPAWNER, MobSpawnType.STRUCTURE, MobSpawnType.COMMAND, MobSpawnType.SPAWN_EGG)) {
            helper.assertTrue(!MinesMobs.blocks(MinesPortal.MINES, type), type + " spawns should still happen in the Mines");
        }
        helper.succeed();
    }

    /** A mob placed in a room (a grottol, a trader, a critter) stands somewhere open on its floor, and stays. */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void mobsArePlacedOnARoomsFloor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos corner = helper.absolutePos(BlockPos.ZERO).above(150);
        Placement room = Placement.carved(Kind.COMMON, corner, Rotation.NONE, 13, EnumSet.of(Direction.SOUTH));
        MinesBuilder.place(level, room, 5, RandomSource.create(13));
        level.getEntitiesOfClass(Entity.class, AABB.of(room.box()), entity -> !(entity instanceof ServerPlayer)).forEach(Entity::discard);
        List<String> problems = new ArrayList<>();
        RandomSource random = RandomSource.create(13);
        for (int i = 0; i < 10; i++) {
            Entity placed = MinesMobs.placeIn(level, room.box(), EntityType.ZOMBIE, random);
            if (placed == null) {
                problems.add("nowhere found");
                continue;
            }
            BlockPos at = placed.blockPosition();
            if (!room.box().isInside(at) || !level.getBlockState(at.below()).isSolid() || !level.noCollision(placed)
                    || !((Mob) placed).isPersistenceRequired()) {
                problems.add("a mob at " + at.subtract(corner).toShortString());
            }
            placed.discard();
        }
        clear(level, corner, Kind.COMMON.size());
        helper.assertTrue(problems.isEmpty(), problems.toString());
        helper.assertTrue(MinesMobs.placeIn(level, room.box(), null, random) == null, "a mob that isn't installed is skipped");
        helper.succeed();
    }

    /**
     * With the pack, its mobs for the Mines (Ice and Fire's trolls and dread mobs, Mowzie's Wroughtnaut and grottol,
     * the vein goblin trader, the critters) can each be placed in a room, and are still there a second later. Those
     * that aren't installed are skipped.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400)
    public static void packMobsLiveInTheMines(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos corner = helper.absolutePos(BlockPos.ZERO).above(150);
        Placement room = Placement.carved(Kind.COMMON, corner, Rotation.NONE, 17, EnumSet.of(Direction.SOUTH));
        MinesBuilder.place(level, room, 5, RandomSource.create(17));
        // Nothing they fire gets far, in case anything else flies on like the lich's skulls.
        List<Entity> placed = new ArrayList<>();
        AABB near = AABB.of(room.box()).inflate(32);
        Runnable sweep = () -> level.getEntitiesOfClass(Projectile.class, near, shot -> placed.contains(shot.getOwner())).forEach(Entity::discard);
        helper.onEachTick(sweep);
        List<ResourceLocation> ids = new ArrayList<>(List.of(MinesMobs.WROUGHTNAUT, MinesMobs.GROTTOL, MinesMobs.TRADER));
        ids.addAll(MinesMobs.CRITTERS);
        // All but the dread lich: its skulls load chunks as they fly, and one that got out of this room into the test
        // world's open sky would go on generating chunks until the server ran out of memory. In the Mines they hit rock.
        MinesMobs.DEFAULT_INFESTED.stream().map(entry -> ResourceLocation.parse(entry.split("=")[0]))
                .filter(id -> id.getNamespace().equals("iceandfire") && !id.getPath().equals("dread_lich")).forEach(ids::add);
        List<String> problems = new ArrayList<>();
        RandomSource random = RandomSource.create(17);
        for (ResourceLocation id : ids) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
            if (type == null) {
                continue;
            }
            Entity entity = MinesMobs.placeIn(level, room.box(), type, random);
            if (entity == null) {
                problems.add(id + " couldn't be placed");
            } else {
                placed.add(entity);
            }
        }
        helper.runAfterDelay(20, () -> {
            placed.stream().filter(entity -> entity.isRemoved()).forEach(entity -> problems.add(EntityType.getKey(entity.getType()) + " was gone a second later"));
            sweep.run();
            placed.forEach(Entity::discard);
            clear(level, corner, Kind.COMMON.size());
            helper.assertTrue(problems.isEmpty(), problems.toString());
            helper.succeed();
        });
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
        helper.assertTrue(MinesFloorsData.get(level).floor(base, 1).isEmpty(), "floor 1 isn't built until someone goes down");
        // Deeper floors have more rooms.
        RandomSource random = RandomSource.create(1);
        for (int[] tier : new int[][] { { 1, 3, 5 }, { 25, 3, 5 }, { 26, 6, 9 }, { 50, 6, 9 }, { 51, 10, 15 }, { 75, 10, 15 }, { 76, 16, 25 }, { 100, 16, 25 } }) {
            for (int i = 0; i < 50; i++) {
                int rooms = MinesFloors.rooms(tier[0], random);
                helper.assertTrue(rooms >= tier[1] && rooms <= tier[2], "floor " + tier[0] + " has " + rooms + " rooms");
            }
        }

        ServerPlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "mines_tester"));
        Optional<BlockPos> toOne = MinesFloors.descend(player, level, base, MinesFloors.START);
        helper.assertTrue(MinesFloorsData.get(level).floor(base, 1).isEmpty() && MinesBuildQueue.pending() > 0, "floor 1 is built a bit at a time");
        MinesBuildQueue.finish(level.getServer());
        MinesFloorsData.Floor one = MinesFloorsData.get(level).floor(base, 1).orElseThrow();
        helper.assertTrue(toOne.equals(Optional.of(one.arrivalFromAbove())), "going down lands beside floor 1's ladder up: " + toOne);
        Optional<BlockPos> toTwo = MinesFloors.descend(player, level, base, 1);
        MinesBuildQueue.finish(level.getServer());
        MinesFloorsData.Floor two = MinesFloorsData.get(level).floor(base, 2).orElseThrow();
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
        helper.assertTrue(ore[0] > 50 && ore[1] == 0, "floor 1's first room has " + ore[0] + " ore and " + ore[1] + " spots left");

        Optional<BlockPos> up = MinesFloors.ascend(player, level, base, 2);
        helper.assertTrue(up.equals(Optional.of(one.arrivalFromBelow())), "going up lands beside floor 1's ladder down: " + up);
        Optional<BlockPos> toStart = MinesFloors.ascend(player, level, base, 1);
        helper.assertTrue(toStart.equals(Optional.of(start.arrivalFromBelow())), "and from floor 1 up to the start hall: " + toStart);
        helper.assertTrue(MinesFloors.ascend(player, level, base, MinesFloors.START).isEmpty(), "up from the start floor leads out of the Mines");
        MinesFloorsData.get(level).clear();
        helper.succeed();
    }

    /**
     * Torch light on a built floor fades out a level a block, chunk borders and all (MINE-37): on the server, a torch
     * in a run of open air across a chunk border lights each block along it at least 14 less its distance, once the
     * light engine (off the server thread) has caught up.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200000, batch = "light")
    public static void torchLightCrossesChunksOnBuiltFloors(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinesFloorsData.get(level).clear();
        BlockPos base = helper.absolutePos(BlockPos.ZERO).above(120);
        MinesFloors.floor(level, base, 1);
        BlockPos min = MinesBuilder.roomMin(MinesFloors.center(base, 1), Cell.ORIGIN);
        // A run of 14 open blocks east from a torch, crossing a chunk border.
        BlockPos torch = null;
        search:
        for (int y = WALK_Y + 1; y <= WALK_Y + 4; y++) {
            for (int z = 2; z < ROOM_SIZE - 2; z++) {
                for (int x = 1; x < ROOM_SIZE - 15; x++) {
                    BlockPos start = min.offset(x, y, z);
                    if (Math.floorMod(start.getX(), 16) < 3) {
                        continue;
                    }
                    boolean open = true;
                    for (int d = 0; d <= 13 && open; d++) {
                        open = level.getBlockState(start.east(d)).isAir();
                    }
                    if (open && (start.getX() >> 4) != (start.east(13).getX() >> 4)) {
                        torch = start;
                        break search;
                    }
                }
            }
        }
        helper.assertTrue(torch != null, "no open run across a chunk border in floor 1's first room");
        BlockPos at = torch;
        level.setBlock(at, Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
        // The light engine works off the server thread, and has a floor's worth of changes to catch up on first.
        helper.succeedWhen(() -> {
            List<String> dark = new ArrayList<>();
            for (int d = 0; d <= 13; d++) {
                BlockPos pos = at.east(d);
                int light = level.getBrightness(LightLayer.BLOCK, pos);
                if (light < 14 - d) {
                    dark.add(d + " east (x " + pos.getX() + ", chunk " + (pos.getX() >> 4) + "): " + light);
                }
            }
            helper.assertTrue(dark.isEmpty(), "torch light falls short: " + dark);
            MinesFloorsData.get(level).clear();
        });
    }

    /**
     * A player's level rises to each floor they climb down to by ladder, not going back up, and the start hall's lift
     * takes them to any floor down to it, by its ladder up, but no deeper.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400)
    public static void theLiftGoesDownToYourLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinesFloorsData.get(level).clear();
        MinesLevels.get(level).clear();
        BlockPos base = helper.absolutePos(BlockPos.ZERO).above(120);
        MinesFloorsData.Floor start = MinesFloors.floor(level, base, MinesFloors.START);
        BlockPos lift = MinesLift.pos(base);
        helper.assertTrue(level.getBlockState(lift).is(ModBlocks.LIFT.get()), "the start hall has a lift");
        helper.assertTrue(level.getBlockState(lift.above()).is(Blocks.CHAIN), "with its chain up into the vault");
        helper.assertTrue(MinesFloors.floorAt(base, lift) == MinesFloors.START && !lift.equals(start.ladderDown()), "beside the ladder down");

        ServerPlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "lift_tester"));
        MinesLevels levels = MinesLevels.get(level);
        helper.assertTrue(levels.level(player) == 0, "no level before going down");
        helper.assertTrue(MinesLift.goTo(player, level, base, 1).isEmpty(), "and the lift won't go anywhere");
        MinesFloors.descend(player, level, base, MinesFloors.START);
        MinesBuildQueue.finish(level.getServer());
        MinesFloors.descend(player, level, base, 1);
        MinesBuildQueue.finish(level.getServer());
        helper.assertTrue(levels.level(player) == 2, "two floors down by ladder is level 2: " + levels.level(player));
        MinesFloors.ascend(player, level, base, 2);
        MinesFloors.ascend(player, level, base, 1);
        helper.assertTrue(levels.level(player) == 2, "going back up keeps it: " + levels.level(player));

        MinesFloorsData.Floor one = MinesFloorsData.get(level).floor(base, 1).orElseThrow();
        MinesFloorsData.Floor two = MinesFloorsData.get(level).floor(base, 2).orElseThrow();
        helper.assertTrue(MinesLift.goTo(player, level, base, 2).equals(Optional.of(two.arrivalFromAbove())), "the lift goes to floor 2, by its ladder up");
        helper.assertTrue(MinesLift.goTo(player, level, base, 1).equals(Optional.of(one.arrivalFromAbove())), "and floor 1");
        helper.assertTrue(MinesLift.goTo(player, level, base, 3).isEmpty(), "but not floor 3");
        helper.assertTrue(levels.level(player) == 2, "and riding it doesn't raise the level");
        MinesFloorsData.get(level).clear();
        levels.clear();
        helper.succeed();
    }

    /**
     * Strict, solo only: on a team, floors reached raise the team's level and not the player's own, everyone on the
     * team shares it, and a player who leaves is back to their own level.
     */
    @GameTest(template = EMPTY)
    public static void teamLevelsAreTheTeams(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinesLevels levels = MinesLevels.get(level);
        levels.clear();
        ServerPlayer alice = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "team_tester_a"));
        ServerPlayer bob = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "team_tester_b"));
        UUID team = UUID.randomUUID();
        Map<UUID, UUID> teams = new java.util.HashMap<>();
        var before = MinesTeams.lookup;
        boolean wasOn = Config.TEAM_MINES.get();
        MinesTeams.lookup = player -> Optional.ofNullable(teams.get(player.getUUID()));
        Config.TEAM_MINES.set(true);
        try {
            levels.reached(alice, 3);
            helper.assertTrue(levels.level(alice) == 3 && levels.personal(alice.getUUID()) == 3, "solo, floors raise alice's own level");

            teams.put(alice.getUUID(), team);
            teams.put(bob.getUUID(), team);
            helper.assertTrue(levels.level(alice) == 0, "on a new team, alice has the team's level, not her own: " + levels.level(alice));
            levels.reached(alice, 2);
            levels.reached(bob, 7);
            helper.assertTrue(levels.teamLevel(team) == 7 && levels.level(alice) == 7 && levels.level(bob) == 7, "the team shares its deepest floor");
            helper.assertTrue(levels.personal(alice.getUUID()) == 3 && levels.personal(bob.getUUID()) == 0, "and its floors don't count for anyone's own");

            teams.remove(bob.getUUID());
            helper.assertTrue(levels.level(bob) == 0, "bob leaves the team, and keeps none of its progress: " + levels.level(bob));
            helper.assertTrue(levels.level(alice) == 7, "while the team keeps it");
            levels.reached(bob, 1);
            helper.assertTrue(levels.level(bob) == 1 && levels.teamLevel(team) == 7, "bob's floors are his own again");
        } finally {
            MinesTeams.lookup = before;
            Config.TEAM_MINES.set(wasOn);
            levels.clear();
        }
        helper.succeed();
    }

    /**
     * Team Mines: off, everyone shares the one Mines; on, each team has its own, each player in no team their own, and
     * no one belongs on anyone else's floors. Without FTB Teams (as in the tests), every player is on their own.
     */
    @GameTest(template = EMPTY)
    public static void eachTeamHasItsOwnMines(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinesRegions.get(level).clear();
        ServerPlayer alice = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "region_tester_a"));
        ServerPlayer bob = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "region_tester_b"));
        ServerPlayer carol = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "region_tester_c"));
        ServerPlayer dave = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "region_tester_d"));
        Map<UUID, UUID> teams = new java.util.HashMap<>();
        UUID red = UUID.randomUUID();
        UUID blue = UUID.randomUUID();
        teams.put(alice.getUUID(), red);
        teams.put(bob.getUUID(), red);
        teams.put(carol.getUUID(), blue);
        var before = MinesTeams.lookup;
        boolean wasOn = Config.TEAM_MINES.get();
        try {
            Config.TEAM_MINES.set(false);
            MinesTeams.lookup = player -> Optional.ofNullable(teams.get(player.getUUID()));
            for (ServerPlayer player : List.of(alice, carol, dave)) {
                helper.assertTrue(MinesTeams.base(level, player).equals(MinesFloors.BASE), "off, everyone shares the one Mines");
            }

            Config.TEAM_MINES.set(true);
            BlockPos redBase = MinesTeams.base(level, alice);
            BlockPos blueBase = MinesTeams.base(level, carol);
            BlockPos daveBase = MinesTeams.base(level, dave);
            helper.assertTrue(MinesTeams.base(level, bob).equals(redBase), "teammates share their Mines");
            helper.assertTrue(!redBase.equals(blueBase) && !redBase.equals(daveBase) && !blueBase.equals(daveBase) && !redBase.equals(MinesFloors.BASE),
                    "each team, and each player on their own, has their own Mines: " + redBase + " " + blueBase + " " + daveBase);
            // Far enough apart that no floor of one reaches another's: a floor stays within half a floor's spacing of its first room.
            helper.assertTrue(Math.abs(redBase.getZ() - blueBase.getZ()) >= 2 * MinesFloors.FLOOR_SPACING, "with plenty of rock between");
            BlockPos redHall = MinesLift.pos(redBase);
            BlockPos deepRed = MinesFloors.center(redBase, 60).offset(400, 20, 400);
            helper.assertTrue(MinesTeams.baseAt(redHall).equals(redBase) && MinesTeams.baseAt(deepRed).equals(redBase), "and every spot knows whose Mines it's in");
            helper.assertTrue(MinesTeams.belongs(level, bob, deepRed) && !MinesTeams.belongs(level, carol, deepRed) && !MinesTeams.belongs(level, dave, redHall),
                    "only the team belongs on its floors");
            helper.assertTrue(MinesTeams.belongs(level, carol, redHall.atY(0)), "anyone may be down in the rock below them, where the portals arrive");

            teams.remove(bob.getUUID());
            helper.assertTrue(!MinesTeams.base(level, bob).equals(redBase) && !MinesTeams.belongs(level, bob, deepRed),
                    "bob leaves the team: his Mines are his own again, and the team's aren't his");

            MinesTeams.lookup = MinesTeams::fromFtb;
            helper.assertTrue(MinesTeams.team(alice).isEmpty() && !MinesTeams.base(level, alice).equals(MinesTeams.base(level, carol)),
                    "without FTB Teams, every player is a team of one");
        } finally {
            MinesTeams.lookup = before;
            Config.TEAM_MINES.set(wasOn);
            MinesRegions.get(level).clear();
        }
        helper.succeed();
    }

    /** Ladders and floors are per Mines: one team's floor 1 being built doesn't make another's. */
    @GameTest(template = EMPTY, timeoutTicks = 400)
    public static void teamsFloorsAreTheirOwn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinesFloorsData data = MinesFloorsData.get(level);
        data.clear();
        BlockPos one = helper.absolutePos(BlockPos.ZERO).above(120);
        BlockPos two = one.south(MinesTeams.REGION_SPACING);
        MinesFloors.floor(level, one, MinesFloors.START);
        MinesFloors.floor(level, one, 1);
        helper.assertTrue(data.floor(one, 1).isPresent() && data.floor(two, 1).isEmpty() && data.floor(two, MinesFloors.START).isEmpty(),
                "one Mines' floors are built, and not the other's");
        data.clear();
        helper.succeed();
    }

    /**
     * The portal setting: open, the portal lets players in; closed, it doesn't, but the Mines' own trips (and Kingdom
     * Sim's gate) still go through. AUTO is open, unless Kingdom Sim is installed.
     */
    @GameTest(template = EMPTY)
    public static void thePortalSettingWorksBothWays(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "portal_tester"));
        Config.Portal was = Config.PORTAL.get();
        try {
            for (boolean anywhere : new boolean[] { true, false }) {
                Config.PORTAL.set(anywhere ? Config.Portal.OPEN : Config.Portal.CLOSED);
                var travel = new net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent(player, MinesPortal.MINES);
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(travel);
                helper.assertTrue(travel.isCanceled() != anywhere, "with the portal open " + anywhere + ", going in is " + (travel.isCanceled() ? "stopped" : "let through"));
            }
            helper.assertTrue(MinesEntrance.letIn(true, false), "the Mines' own trips go through with it closed");
            helper.assertTrue(Config.portalOpen(Config.Portal.AUTO, false) && !Config.portalOpen(Config.Portal.AUTO, true),
                    "AUTO is open without Kingdom Sim, and closed with it");
            helper.assertTrue(Config.portalOpen(Config.Portal.OPEN, true) && !Config.portalOpen(Config.Portal.CLOSED, false), "OPEN and CLOSED are always");
        } finally {
            Config.PORTAL.set(was);
        }
        helper.succeed();
    }

    /**
     * Levels are saved with the level they're for (the Mines, in play), in its own folder: the folder a reset deletes,
     * so a reset wipes every level along with the floors.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void levelsAreSavedWhereTheResetWipes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinesLevels levels = MinesLevels.get(level);
        levels.clear();
        levels.reached(FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "reset_tester")), 4);
        level.getDataStorage().save();
        Path folder = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT));
        Path file = folder.resolve("data").resolve(MinesLevels.fileName());
        MinesRegions.get(level).region(UUID.randomUUID());
        level.getDataStorage().save();
        Path regions = folder.resolve("data").resolve(MinesRegions.fileName());
        // Saved data is written off the server thread.
        helper.succeedWhen(() -> {
            helper.assertTrue(Files.exists(file), "levels are saved in the level's own folder: " + file);
            helper.assertTrue(Files.exists(regions), "and so are the teams' regions: " + regions);
            levels.clear();
            MinesRegions.get(level).clear();
        });
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
