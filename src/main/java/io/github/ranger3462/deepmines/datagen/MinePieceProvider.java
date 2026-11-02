package io.github.ranger3462.deepmines.datagen;

import static io.github.ranger3462.deepmines.MinesSpec.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import com.google.common.hash.Hashing;

import io.github.ranger3462.deepmines.DeepMines;
import io.github.ranger3462.deepmines.MinesPieces;
import io.github.ranger3462.deepmines.ModBlocks;
import net.minecraft.core.Direction;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;

/**
 * Generates the Mines' pieces into {@code data/deepmines/structure/mines/}, to
 * {@link io.github.ranger3462.deepmines.MinesSpec}'s sizes. Every room is a block of rock with a cave carved in it at
 * doorway height, so there's rock below to dig down into and rock above to dig up into. Ore spots
 * ({@code deepmines:ore_spot}) mark where veins go; they become the floor's ore when it's built.
 * <ul>
 * <li>Common rooms ({@code common_}): the usual amount of ore. The "dig down" ones have it mostly under the cave's
 * floor, the "dig up" ones (with lower caves) mostly over it. Old abandoned drifts lead off the cave towards veins, and
 * end in a shaft sunk (or raised) towards more.</li>
 * <li>Quarries ({@code quarry_}): a stepped open pit, packed with ore around its walls.</li>
 * <li>Flooded rooms ({@code flooded_}): a deep pool to dive into, with its ore around the pool's walls and bottom.</li>
 * <li>Infested rooms ({@code infested_}): a cave with spawners, bones and cobwebs, and the usual amount of ore.</li>
 * <li>Corridors ({@code tunnel_}): low, timbered mineshafts in the style of YUNG's Better Mineshafts, with a little ore.</li>
 * <li>The cap ({@code cap_}): plain rock.</li>
 * <li>The start hall ({@code start_}): the start floor's one room, the same in every Mines: a dwarven hall with a dais
 * in the middle (for the dwarf who'll give out quests), workshops along the walls, and the ladders down to floor 1 and
 * up out of the Mines.</li>
 * </ul>
 * The rock is stone, with patches of andesite, diorite and granite; deep floors turn it to deepslate and tuff as they're
 * built. Run with {@code ./gradlew runData}; the output is committed.
 */
public class MinePieceProvider implements DataProvider {
    private static final int MAX = ROOM_SIZE - 1;
    private static final int TOP = ROOM_HEIGHT - 1;
    /** The middle of a corridor's width, where its rails run. */
    private static final int MID_TUNNEL = TUNNEL_WIDTH / 2;

    private final PackOutput.PathProvider structures;

    public MinePieceProvider(PackOutput output) {
        this.structures = output.createPathProvider(PackOutput.Target.DATA_PACK, "structure");
    }

    /** Every piece, keyed by file name. */
    public static Map<String, PieceBuilder> pieces() {
        Map<String, PieceBuilder> pieces = new LinkedHashMap<>();
        pieces.put("common_dig_down_a", common(new Random(101), false));
        pieces.put("common_dig_down_b", common(new Random(202), false));
        pieces.put("common_dig_up_a", common(new Random(303), true));
        pieces.put("common_dig_up_b", common(new Random(404), true));
        pieces.put("quarry_pit_a", quarry(new Random(505)));
        pieces.put("quarry_pit_b", quarry(new Random(515)));
        pieces.put("flooded_pool_a", flooded(new Random(606)));
        pieces.put("flooded_pool_b", flooded(new Random(616)));
        pieces.put("infested_nest_a", infested(new Random(707)));
        pieces.put("infested_nest_b", infested(new Random(717)));
        pieces.put("tunnel_mineshaft", mineshaft(new Random(808), false));
        pieces.put("tunnel_mineshaft_caved", mineshaft(new Random(818), true));
        pieces.put("cap_rock", cap());
        pieces.put("start_hall", startHall(new Random(1001)));
        return pieces;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput output) {
        return CompletableFuture.allOf(pieces().entrySet().stream().map(entry -> CompletableFuture.runAsync(() -> {
            Path path = structures.file(DeepMines.id(MinesPieces.FOLDER + "/" + entry.getKey()), "nbt");
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                NbtIo.writeCompressed(entry.getValue().toNbt(), bytes);
                byte[] data = bytes.toByteArray();
                @SuppressWarnings("deprecation") // Vanilla data providers hash with SHA-1 as well.
                var hash = Hashing.sha1().hashBytes(data);
                output.writeIfNeeded(path, data, hash);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to save " + path, e);
            }
        })).toArray(CompletableFuture[]::new));
    }

    @Override
    public String getName() {
        return "Deep Mines pieces";
    }

    // --- The grid a piece is carved in ---

    private static final byte ROCK = 0;
    private static final byte AIR = 1;
    private static final byte WATER = 2;
    private static final byte ORE = 3;
    private static final byte FIXED = 4;

    /**
     * A piece being carved: every position is rock (stone with patches of andesite, diorite and granite), air, water,
     * an ore spot, or a fixed block (timber, rails, decoration). Ore only goes into rock, and never in the outer layer.
     */
    private static final class Grid {
        final int sx;
        final int sy;
        final int sz;
        final byte[] type;
        final BlockState[] state;

        Grid(int sx, int sy, int sz, Random random, int patches) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.type = new byte[sx * sy * sz];
            this.state = new BlockState[sx * sy * sz];
            Arrays.fill(state, Blocks.STONE.defaultBlockState());
            // The other stones come in patches, never speckled through the stone.
            for (int i = 0; i < patches; i++) {
                int roll = random.nextInt(4);
                BlockState stone = (roll < 2 ? Blocks.ANDESITE : roll < 3 ? Blocks.DIORITE : Blocks.GRANITE).defaultBlockState();
                int cx = random.nextInt(sx);
                int cy = random.nextInt(sy);
                int cz = random.nextInt(sz);
                int r = 2 + random.nextInt(4);
                int ry = Math.max(1, r - 1 - random.nextInt(2));
                forBox(cx - r, cy - ry, cz - r, cx + r, cy + ry, cz + r, (x, y, z) -> {
                    double dx = (x - cx) / (double) r;
                    double dy = (y - cy) / (double) ry;
                    double dz = (z - cz) / (double) r;
                    if (in(x, y, z) && dx * dx + dy * dy + dz * dz <= 1) {
                        state[index(x, y, z)] = stone;
                    }
                });
            }
        }

        boolean in(int x, int y, int z) {
            return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz;
        }

        boolean inside(int x, int y, int z) {
            return x > 0 && y > 0 && z > 0 && x < sx - 1 && y < sy - 1 && z < sz - 1;
        }

        int index(int x, int y, int z) {
            return (y * sz + z) * sx + x;
        }

        byte type(int x, int y, int z) {
            return in(x, y, z) ? type[index(x, y, z)] : ROCK;
        }

        boolean air(int x, int y, int z) {
            return type(x, y, z) == AIR;
        }

        boolean open(int x, int y, int z) {
            byte t = type(x, y, z);
            return t == AIR || t == WATER;
        }

        boolean rock(int x, int y, int z) {
            return in(x, y, z) && type[index(x, y, z)] == ROCK;
        }

        /** Rock or ore: solid ground that wasn't put there on purpose. */
        boolean solidRock(int x, int y, int z) {
            byte t = type(x, y, z);
            return t == ROCK || t == ORE;
        }

        /** Opens a spot to air, unless it's on the outside or has something put there on purpose. */
        void carve(int x, int y, int z) {
            if (inside(x, y, z) && type[index(x, y, z)] != FIXED) {
                type[index(x, y, z)] = AIR;
            }
        }

        void put(int x, int y, int z, BlockState block) {
            if (in(x, y, z)) {
                type[index(x, y, z)] = FIXED;
                state[index(x, y, z)] = block;
            }
        }

        void put(int x, int y, int z, Block block) {
            put(x, y, z, block.defaultBlockState());
        }

        /** Makes a spot rock, air, water or ore, whatever it was; rock that had something put there goes back to stone. */
        void setType(int x, int y, int z, byte t) {
            if (!in(x, y, z)) {
                return;
            }
            int i = index(x, y, z);
            if (t == ROCK && type[i] == FIXED) {
                state[i] = Blocks.STONE.defaultBlockState();
            }
            type[i] = t;
        }

        /** Whether ore may go here: rock, inside the outer layer. */
        boolean oreable(int x, int y, int z) {
            return rock(x, y, z) && inside(x, y, z);
        }

        PieceBuilder build() {
            PieceBuilder piece = new PieceBuilder(sx, sy, sz);
            BlockState ore = ModBlocks.ORE_SPOT.get().defaultBlockState();
            for (int x = 0; x < sx; x++) {
                for (int y = 0; y < sy; y++) {
                    for (int z = 0; z < sz; z++) {
                        int i = index(x, y, z);
                        piece.set(x, y, z, switch (type[i]) {
                            case AIR -> Blocks.AIR.defaultBlockState();
                            case WATER -> Blocks.WATER.defaultBlockState();
                            case ORE -> ore;
                            default -> state[i];
                        });
                    }
                }
            }
            return piece;
        }
    }

    private interface Cell {
        void at(int x, int y, int z);
    }

    private static void forBox(int x1, int y1, int z1, int x2, int y2, int z2, Cell cell) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    cell.at(x, y, z);
                }
            }
        }
    }

    private static int between(Random random, int lo, int hi) {
        return lo + random.nextInt(hi - lo + 1);
    }

    // --- Rooms ---

    private static Grid room(Random random) {
        return new Grid(ROOM_SIZE, ROOM_HEIGHT, ROOM_SIZE, random, 22);
    }

    /**
     * A passage in from each doorway at doorway height, a little wider than the doorway, wandering a little once past
     * the landing, to the middle of the room.
     */
    private static void passages(Grid g, Random random) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            int wobble = 0;
            for (int depth = 1; depth <= MID; depth++) {
                if (depth > LANDING_DEPTH + 1 && random.nextInt(4) == 0) {
                    wobble = Math.max(-3, Math.min(3, wobble + random.nextInt(3) - 1));
                }
                int top = DOOR_BOTTOM_Y + DOOR_HEIGHT - 1 + (depth > LANDING_DEPTH ? random.nextInt(2) : 0);
                for (int across = DOOR_START - 1 + wobble; across <= DOOR_START + DOOR_WIDTH + wobble; across++) {
                    int[] p = fromWall(side, across, depth);
                    for (int y = DOOR_BOTTOM_Y; y <= top; y++) {
                        g.carve(p[0], y, p[1]);
                    }
                }
            }
        }
    }

    /**
     * A domed chamber standing on the walking floor ({@link io.github.ranger3462.deepmines.MinesSpec#WALK_Y}): an
     * irregular outline about {@code radius} across, rising to about {@code height} over its middle.
     */
    private static void chamber(Grid g, Random random, int cx, int cz, double radius, double height) {
        double p1 = random.nextDouble() * Math.PI * 2;
        double p2 = random.nextDouble() * Math.PI * 2;
        double p3 = random.nextDouble() * Math.PI * 2;
        double a1 = radius * 0.18;
        double a2 = radius * 0.1;
        for (int x = 2; x <= MAX - 2; x++) {
            for (int z = 2; z <= MAX - 2; z++) {
                double dx = x - cx;
                double dz = z - cz;
                double angle = Math.atan2(dz, dx);
                double r = radius + a1 * Math.sin(2 * angle + p1) + a2 * Math.sin(3 * angle + p2);
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d >= r) {
                    continue;
                }
                double h = 2 + height * Math.sqrt(1 - (d / r) * (d / r)) + 1.2 * Math.sin(x * 0.45 + p3) * Math.cos(z * 0.38 - p3);
                int top = Math.min(TOP - 3, WALK_Y + (int) Math.round(h));
                for (int y = WALK_Y + 1; y <= top; y++) {
                    g.carve(x, y, z);
                }
            }
        }
    }

    /** The ways in, the main chamber in the middle, and a few smaller ones running off it. */
    private static void cave(Grid g, Random random, double radius, double height, int alcoves) {
        passages(g, random);
        chamber(g, random, MID, MID, radius, height);
        for (int i = 0; i < alcoves; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double d = radius * (0.7 + random.nextDouble() * 0.3);
            int cx = (int) Math.round(MID + Math.cos(angle) * d);
            int cz = (int) Math.round(MID + Math.sin(angle) * d);
            chamber(g, random, Math.max(9, Math.min(MAX - 9, cx)), Math.max(9, Math.min(MAX - 9, cz)), 4 + random.nextInt(4), 2 + random.nextInt(4));
        }
    }

    /** Whether a spot is in a landing, which is kept as it is. */
    private static boolean inLanding(int x, int z) {
        return (inLandingSpan(x) && (z <= LANDING_DEPTH || z >= MAX - LANDING_DEPTH))
                || (inLandingSpan(z) && (x <= LANDING_DEPTH || x >= MAX - LANDING_DEPTH));
    }

    /** Whether a spot is on the way in from a doorway to the middle, kept clear of clutter. */
    private static boolean inLane(int x, int z) {
        return (x >= DOOR_START - 4 && x <= DOOR_START + DOOR_WIDTH + 3) || (z >= DOOR_START - 4 && z <= DOOR_START + DOOR_WIDTH + 3);
    }

    /**
     * Seals a room: a landing in front of each doorway (solid floor, clear air up to the doorway's top), every block on
     * the outside of rock, and the doorways open.
     */
    private static void finishRoom(Grid g) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            for (int depth = 1; depth <= LANDING_DEPTH; depth++) {
                for (int across = LANDING_START; across < LANDING_START + LANDING_WIDTH; across++) {
                    int[] p = fromWall(side, across, depth);
                    if (!g.solidRock(p[0], WALK_Y, p[1])) {
                        g.setType(p[0], WALK_Y, p[1], ROCK);
                    }
                    for (int y = DOOR_BOTTOM_Y; y < DOOR_BOTTOM_Y + DOOR_HEIGHT; y++) {
                        g.setType(p[0], y, p[1], AIR);
                    }
                }
            }
        }
        sealOutside(g);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            for (int across = DOOR_START; across < DOOR_START + DOOR_WIDTH; across++) {
                int[] p = fromWall(side, across, 0);
                for (int y = DOOR_BOTTOM_Y; y < DOOR_BOTTOM_Y + DOOR_HEIGHT; y++) {
                    g.setType(p[0], y, p[1], AIR);
                }
            }
        }
    }

    /** Makes every block on the outside of a piece rock, so nothing leaks in or out but through its doorways. */
    private static void sealOutside(Grid g) {
        for (int x = 0; x < g.sx; x++) {
            for (int y = 0; y < g.sy; y++) {
                for (int z = 0; z < g.sz; z++) {
                    if (!g.inside(x, y, z) && g.type(x, y, z) != ROCK) {
                        g.setType(x, y, z, ROCK);
                    }
                }
            }
        }
    }

    // --- Ore ---

    /** Grows a vein of up to {@code size} ore spots from a starting spot, through rock only. */
    private static void vein(Grid g, Random random, int x, int y, int z, int size) {
        if (!g.oreable(x, y, z)) {
            return;
        }
        List<int[]> spots = new ArrayList<>();
        g.setType(x, y, z, ORE);
        spots.add(new int[] { x, y, z });
        Direction[] directions = Direction.values();
        for (int tries = size * 8; spots.size() < size && tries > 0; tries--) {
            int[] from = spots.get(random.nextInt(spots.size()));
            Direction d = directions[random.nextInt(directions.length)];
            int nx = from[0] + d.getStepX();
            int ny = from[1] + d.getStepY();
            int nz = from[2] + d.getStepZ();
            if (g.oreable(nx, ny, nz)) {
                g.setType(nx, ny, nz, ORE);
                spots.add(new int[] { nx, ny, nz });
            }
        }
    }

    /** Scatters {@code count} veins through the rock between two heights. */
    private static void veins(Grid g, Random random, int count, int yMin, int yMax, int sizeMin, int sizeMax) {
        for (int placed = 0, tries = 0; placed < count && tries < count * 20; tries++) {
            int x = between(random, 2, g.sx - 3);
            int y = between(random, yMin, yMax);
            int z = between(random, 2, g.sz - 3);
            if (g.oreable(x, y, z)) {
                vein(g, random, x, y, z, between(random, sizeMin, sizeMax));
                placed++;
            }
        }
    }

    /** What an open spot must be to have ore put near it. */
    private interface Near {
        boolean test(int x, int y, int z);
    }

    /**
     * Puts {@code count} veins in the rock close to open spots that pass {@code near} (a pit's walls, a pool's sides),
     * within {@code reach} blocks of one, so they show or nearly show.
     */
    private static void veinsNear(Grid g, Random random, int count, Near near, int reach, int sizeMin, int sizeMax) {
        List<int[]> faces = new ArrayList<>();
        for (int x = 1; x < g.sx - 1; x++) {
            for (int y = 1; y < g.sy - 1; y++) {
                for (int z = 1; z < g.sz - 1; z++) {
                    if (g.open(x, y, z) && near.test(x, y, z)) {
                        faces.add(new int[] { x, y, z });
                    }
                }
            }
        }
        if (faces.isEmpty()) {
            return;
        }
        Direction[] directions = Direction.values();
        for (int placed = 0, tries = 0; placed < count && tries < count * 30; tries++) {
            int[] face = faces.get(random.nextInt(faces.size()));
            Direction d = directions[random.nextInt(directions.length)];
            int steps = between(random, 1, reach);
            int x = face[0] + d.getStepX() * steps;
            int y = face[1] + d.getStepY() * steps;
            int z = face[2] + d.getStepZ() * steps;
            if (g.oreable(x, y, z)) {
                vein(g, random, x, y, z, between(random, sizeMin, sizeMax));
                placed++;
            }
        }
    }

    // --- Old workings: drifts, with a shaft at the end ---

    /** A spot along a drift: {@code along} the drift's axis, {@code line} across it. */
    private static int[] at(boolean alongX, int along, int line) {
        return alongX ? new int[] { along, line } : new int[] { line, along };
    }

    /**
     * An old abandoned drift: a narrow passage leading off the cave into the rock, with broken timber supports and
     * rails, ending at a face with a vein just past it. With {@code shaft}, it ends in a shaft instead, sunk down
     * ({@code up} false) or raised up, with more ore past that. Runs along x or z, {@code line} across, heading
     * {@code dir} from wherever it leaves the cave.
     */
    private static void drift(Grid g, Random random, boolean alongX, int line, int dir, int length, boolean shaft, boolean up) {
        int along = MID;
        while (along > 6 && along < MAX - 6) {
            int[] p = at(alongX, along, line);
            if (!g.air(p[0], WALK_Y + 1, p[1])) {
                break;
            }
            along += dir;
        }
        int start = along;
        int end = Math.max(7, Math.min(MAX - 7, start + dir * (length - 1)));
        if ((end - start) * dir < 3) {
            return;
        }
        BlockState rail = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, alongX ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH);
        BlockState leg = Blocks.OAK_LOG.defaultBlockState();
        BlockState beam = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, alongX ? Direction.Axis.Z : Direction.Axis.X);
        for (int a = start; a != end + dir; a += dir) {
            for (int across = line - 1; across <= line + 1; across++) {
                int[] p = at(alongX, a, across);
                for (int y = WALK_Y + 1; y <= WALK_Y + 3; y++) {
                    g.carve(p[0], y, p[1]);
                }
            }
            int[] mid = at(alongX, a, line);
            if (random.nextInt(4) != 0) {
                g.put(mid[0], WALK_Y + 1, mid[1], rail);
            }
            int step = (a - start) * dir;
            boolean nearEnd = (end - a) * dir < 2;
            if (step % 3 == 1 && !nearEnd) {
                // A support: legs either side, a beam over. Some have rotted away.
                if (random.nextInt(5) != 0) {
                    for (int across : new int[] { line - 1, line + 1 }) {
                        int[] p = at(alongX, a, across);
                        g.put(p[0], WALK_Y + 1, p[1], leg);
                        if (random.nextInt(4) != 0) {
                            g.put(p[0], WALK_Y + 2, p[1], leg);
                        }
                    }
                    for (int across = line - 1; across <= line + 1; across++) {
                        int[] p = at(alongX, a, across);
                        g.put(p[0], WALK_Y + 3, p[1], beam);
                    }
                }
            } else if (random.nextInt(3) == 0) {
                int[] corner = at(alongX, a, random.nextBoolean() ? line - 1 : line + 1);
                g.put(corner[0], WALK_Y + 3, corner[1], Blocks.COBWEB);
            }
        }

        // What it was dug for.
        Direction ahead = alongX ? (dir > 0 ? Direction.EAST : Direction.WEST) : (dir > 0 ? Direction.SOUTH : Direction.NORTH);
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, ahead.getOpposite());
        if (!shaft) {
            int[] face = at(alongX, end + dir * 2, line);
            vein(g, random, face[0], WALK_Y + 2, face[1], between(random, 10, 14));
            return;
        }
        int[] ladderAt = at(alongX, end, line);
        int[][] hole = { at(alongX, end, line), at(alongX, end, line + 1), at(alongX, end - dir, line), at(alongX, end - dir, line + 1) };
        if (!up) {
            // A winze: sunk down from the drift's floor, with a ladder down the face and a chain hung beside it.
            int bottom = between(random, 5, 8);
            for (int[] h : hole) {
                for (int y = bottom + 1; y <= WALK_Y; y++) {
                    g.carve(h[0], y, h[1]);
                }
            }
            for (int y = bottom + 1; y <= WALK_Y + 1; y++) {
                g.put(ladderAt[0], y, ladderAt[1], ladder);
            }
            int[] chain = at(alongX, end - dir, line + 1);
            for (int y = WALK_Y - 4; y <= WALK_Y + 3; y++) {
                g.put(chain[0], y, chain[1], Blocks.CHAIN);
            }
            // A short drive on from the bottom, beside the ladder, and the ore it was after.
            for (int a = end + dir; a != end + dir * 4; a += dir) {
                int[] p = at(alongX, a, line + 1);
                g.carve(p[0], bottom + 1, p[1]);
                g.carve(p[0], bottom + 2, p[1]);
            }
            int[] past = at(alongX, end + dir * 6, line + 1);
            vein(g, random, past[0], bottom + 1, past[1], between(random, 10, 14));
            vein(g, random, ladderAt[0], bottom - 2, ladderAt[1], between(random, 6, 10));
        } else {
            // A raise: dug up from the drift's roof, with a ladder up the face.
            int top = between(random, 29, 33);
            for (int[] h : hole) {
                for (int y = WALK_Y + 4; y <= top; y++) {
                    g.carve(h[0], y, h[1]);
                }
            }
            for (int y = WALK_Y + 1; y <= top; y++) {
                g.put(ladderAt[0], y, ladderAt[1], ladder);
            }
            int[] over = at(alongX, end - dir, line);
            vein(g, random, over[0], top + 2, over[1], between(random, 10, 14));
            int[] beside = at(alongX, end + dir * 2, line + 1);
            vein(g, random, beside[0], top - 1, beside[1], between(random, 6, 10));
        }
    }

    /** Drifts heading off the cave, taking turns along x and z, on alternate sides and clear of the ways in. */
    private static void drifts(Grid g, Random random, int count, boolean shaft, boolean up) {
        boolean alongX = random.nextBoolean();
        int side = random.nextBoolean() ? 1 : -1;
        for (int i = 0; i < count; i++) {
            int line = MID + side * between(random, 9, 11);
            int dir = random.nextBoolean() ? 1 : -1;
            drift(g, random, alongX, line, dir, between(random, 7, 11), shaft, up);
            alongX = !alongX;
            side = -side;
        }
    }

    // --- Decoration ---

    /** Glow lichen on the floor, gravel against the walls and cobwebs up high, clear of the landings and the ways in. */
    private static void decorate(Grid g, Random random, int cobwebChance) {
        BlockState lichen = Blocks.GLOW_LICHEN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true);
        for (int x = 1; x < MAX; x++) {
            for (int z = 1; z < MAX; z++) {
                if (inLanding(x, z)) {
                    continue;
                }
                for (int y = 1; y < TOP; y++) {
                    if (!g.air(x, y, z)) {
                        continue;
                    }
                    boolean onFloor = g.solidRock(x, y - 1, z);
                    boolean underRoof = g.solidRock(x, y + 1, z);
                    boolean wall = againstWall(g, x, y, z);
                    if (onFloor && !inLane(x, z) && random.nextInt(14) == 0) {
                        g.put(x, y, z, lichen);
                    } else if (onFloor && wall && !inLane(x, z) && random.nextInt(12) == 0) {
                        g.put(x, y, z, Blocks.GRAVEL);
                    } else if (underRoof && wall && y > WALK_Y + 4 && random.nextInt(cobwebChance) == 0) {
                        g.put(x, y, z, Blocks.COBWEB);
                    }
                }
            }
        }
    }

    private static boolean againstWall(Grid g, int x, int y, int z) {
        return g.solidRock(x - 1, y, z) || g.solidRock(x + 1, y, z) || g.solidRock(x, y, z - 1) || g.solidRock(x, y, z + 1);
    }

    // --- The kinds of room ---

    /**
     * A common room: a cave with the usual amount of ore. Dig-down caves are taller, with most of the ore under the
     * floor and old shafts sunk towards it; dig-up caves are low, with most of it overhead and shafts raised towards it.
     */
    private static PieceBuilder common(Random random, boolean up) {
        Grid g = room(random);
        cave(g, random, 12 + random.nextInt(3), up ? 4 : 8, 5);
        drifts(g, random, 2, true, up);
        if (up) {
            veins(g, random, 75, WALK_Y + 6, TOP - 2, 4, 9);
            veins(g, random, 25, 2, WALK_Y - 1, 4, 9);
        } else {
            veins(g, random, 75, 2, WALK_Y - 1, 4, 9);
            veins(g, random, 25, WALK_Y + 1, TOP - 2, 4, 9);
        }
        decorate(g, random, 8);
        finishRoom(g);
        return g.build();
    }

    /**
     * A quarry: a high hall with a stepped open pit in the middle, each step 3 down from the last with a ladder down it,
     * and ore packed into the pit's walls and floors.
     */
    private static PieceBuilder quarry(Random random) {
        Grid g = room(random);
        cave(g, random, 17, 11, 3);
        int[] sizes = { 15, 12, 9, 6, 3 };
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST);
        for (int k = 0; k < sizes.length; k++) {
            int s = sizes[k];
            int floor = WALK_Y - 3 * (k + 1);
            for (int x = MID - s; x <= MID + s; x++) {
                for (int z = MID - s; z <= MID + s; z++) {
                    // Corners rounded off, a little unevenly.
                    if (Math.abs(x - MID) + Math.abs(z - MID) > s * 1.6 + random.nextInt(2)) {
                        continue;
                    }
                    for (int y = floor + 1; y <= WALK_Y; y++) {
                        g.carve(x, y, z);
                    }
                }
            }
            // A ladder down each step, on the east side, against the step.
            for (int y = floor + 1; y <= WALK_Y - 3 * k; y++) {
                g.put(MID + s, y, MID, ladder);
            }
        }
        Near pit = (x, y, z) -> y <= WALK_Y && Math.abs(x - MID) <= 16 && Math.abs(z - MID) <= 16;
        veinsNear(g, random, 160, pit, 3, 4, 9);
        veins(g, random, 40, 2, TOP - 2, 4, 9);

        // Lanterns on posts out on the rim at the corners, and a chain hoist hanging into the pit.
        for (int[] c : new int[][] { { -1, -1 }, { 1, -1 }, { -1, 1 }, { 1, 1 } }) {
            for (int d = 10; d < 22; d++) {
                int x = MID + c[0] * d;
                int z = MID + c[1] * d;
                if (g.air(x, WALK_Y + 1, z) && g.air(x, WALK_Y + 2, z) && g.solidRock(x, WALK_Y, z)) {
                    g.put(x, WALK_Y + 1, z, Blocks.OAK_FENCE);
                    g.put(x, WALK_Y + 2, z, Blocks.LANTERN);
                    break;
                }
            }
        }
        for (int y = WALK_Y - 8; g.air(MID, y, MID); y++) {
            g.put(MID, y, MID, Blocks.CHAIN);
        }
        decorate(g, random, 10);
        finishRoom(g);
        return g.build();
    }

    /**
     * A flooded room: a cave around a deep pool, whose surface is level with the floor. The ore is around the pool's
     * walls and bottom, with a few underwater pockets off its sides; glow lichen on the walls lights it.
     */
    private static PieceBuilder flooded(Random random) {
        Grid g = room(random);
        cave(g, random, 15, 9, 4);
        int cx = MID + between(random, -3, 3);
        int cz = MID + between(random, -3, 3);
        double p1 = random.nextDouble() * Math.PI * 2;
        double p2 = random.nextDouble() * Math.PI * 2;
        for (int x = 2; x <= MAX - 2; x++) {
            for (int z = 2; z <= MAX - 2; z++) {
                double dx = x - cx;
                double dz = z - cz;
                double angle = Math.atan2(dz, dx);
                double r = 11 + 2 * Math.sin(2 * angle + p1) + Math.sin(3 * angle + p2);
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d >= r) {
                    continue;
                }
                int bottom = 3 + (int) Math.round(11 * (d / r) * (d / r));
                for (int y = bottom + 1; y <= WALK_Y; y++) {
                    g.carve(x, y, z);
                }
            }
        }
        // Pockets off the pool's sides, deep down.
        for (int i = 0; i < 4; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            int px = (int) Math.round(cx + Math.cos(angle) * 11);
            int pz = (int) Math.round(cz + Math.sin(angle) * 11);
            int py = between(random, 6, 11);
            int r = between(random, 2, 4);
            forBox(px - r, py - 2, pz - r, px + r, py + 2, pz + r, (x, y, z) -> {
                double dx = (x - px) / (double) r;
                double dy = (y - py) / 2.0;
                double dz = (z - pz) / (double) r;
                if (dx * dx + dy * dy + dz * dz <= 1 && x > 6 && z > 6 && x < MAX - 6 && z < MAX - 6) {
                    g.carve(x, y, z);
                }
            });
        }
        // Everything open at or under the floor fills with water.
        for (int x = 1; x < MAX; x++) {
            for (int y = 1; y <= WALK_Y; y++) {
                for (int z = 1; z < MAX; z++) {
                    if (g.air(x, y, z)) {
                        g.setType(x, y, z, WATER);
                    }
                }
            }
        }
        Near pool = (x, y, z) -> g.type(x, y, z) == WATER;
        veinsNear(g, random, 110, pool, 3, 4, 9);
        veins(g, random, 25, WALK_Y + 1, TOP - 2, 4, 9);

        // Under the water: lichen on the walls, seagrass and gravel on the bottom.
        for (int x = 1; x < MAX; x++) {
            for (int y = 2; y < WALK_Y; y++) {
                for (int z = 1; z < MAX; z++) {
                    if (g.type(x, y, z) != WATER) {
                        continue;
                    }
                    if (g.solidRock(x, y - 1, z) && g.type(x, y + 1, z) == WATER) {
                        int roll = random.nextInt(10);
                        if (roll < 3) {
                            g.put(x, y, z, Blocks.SEAGRASS);
                        } else if (roll < 5 && g.rock(x, y - 1, z)) {
                            g.put(x, y - 1, z, random.nextBoolean() ? Blocks.GRAVEL : Blocks.CLAY);
                        }
                        continue;
                    }
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        if (g.solidRock(x + d.getStepX(), y, z + d.getStepZ()) && random.nextInt(7) == 0) {
                            g.put(x, y, z, Blocks.GLOW_LICHEN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(d), true)
                                    .setValue(BlockStateProperties.WATERLOGGED, true));
                            break;
                        }
                    }
                }
            }
        }
        // A few lanterns along the shore.
        for (int i = 0, placed = 0; i < 400 && placed < 5; i++) {
            int x = between(random, 8, MAX - 8);
            int z = between(random, 8, MAX - 8);
            boolean shore = g.air(x, WALK_Y + 1, z) && g.solidRock(x, WALK_Y, z) && (g.type(x + 1, WALK_Y, z) == WATER
                    || g.type(x - 1, WALK_Y, z) == WATER || g.type(x, WALK_Y, z + 1) == WATER || g.type(x, WALK_Y, z - 1) == WATER);
            if (shore && !inLanding(x, z)) {
                g.put(x, WALK_Y + 1, z, Blocks.LANTERN);
                placed++;
            }
        }
        decorate(g, random, 10);
        finishRoom(g);
        return g.build();
    }

    /**
     * An infested room: a cave held by mobs from three spawners, with bones in the walls, skulls on the floor, lots of
     * cobwebs, an old drift and the usual amount of ore.
     */
    private static PieceBuilder infested(Random random) {
        Grid g = room(random);
        cave(g, random, 13, 7, 5);
        drifts(g, random, 1, false, false);
        veins(g, random, 70, 2, WALK_Y - 1, 4, 9);
        veins(g, random, 30, WALK_Y + 1, TOP - 2, 4, 9);

        int[][] corners = { { -1, -1 }, { 1, -1 }, { -1, 1 }, { 1, 1 } };
        int skip = random.nextInt(corners.length);
        for (int i = 0; i < corners.length; i++) {
            if (i == skip) {
                continue;
            }
            int sx = MID + corners[i][0] * 7;
            int sz = MID + corners[i][1] * 7;
            forBox(sx - 1, WALK_Y + 1, sz - 1, sx + 1, WALK_Y + 3, sz + 1, g::carve);
            g.put(sx, WALK_Y + 1, sz, Blocks.SPAWNER);
            for (int b = 0; b < 4; b++) {
                int bx = sx + between(random, -2, 2);
                int bz = sz + between(random, -2, 2);
                if (g.air(bx, WALK_Y + 1, bz) && g.solidRock(bx, WALK_Y, bz)) {
                    g.put(bx, WALK_Y + 1, bz, random.nextInt(3) == 0 ? Blocks.SKELETON_SKULL : Blocks.BONE_BLOCK);
                }
            }
        }
        // Bones showing in the walls, cobwebs everywhere off the way in.
        for (int x = 1; x < MAX; x++) {
            for (int z = 1; z < MAX; z++) {
                for (int y = WALK_Y + 1; y < TOP; y++) {
                    if (!g.air(x, y, z) || inLanding(x, z)) {
                        continue;
                    }
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        int wx = x + d.getStepX();
                        int wz = z + d.getStepZ();
                        if (g.rock(wx, y, wz) && g.inside(wx, y, wz) && random.nextInt(30) == 0) {
                            g.put(wx, y, wz, Blocks.BONE_BLOCK);
                        }
                    }
                    if (!inLane(x, z) && y <= WALK_Y + 2 && random.nextInt(35) == 0) {
                        g.put(x, y, z, Blocks.COBWEB);
                    }
                }
            }
        }
        decorate(g, random, 4);
        finishRoom(g);
        return g.build();
    }

    // --- Corridor and cap ---

    /**
     * A low timbered mineshaft: rock two thick around a doorway-sized passage with a plank floor, and every 5 blocks a
     * set of supports (log legs and a beam overhead that leaves 3 blocks to walk under) with a torch on one leg. A few
     * small veins show in the walls. The caved-in one has a fall of rubble along one side halfway along.
     */
    private static PieceBuilder mineshaft(Random random, boolean caved) {
        Grid g = new Grid(TUNNEL_WIDTH, TUNNEL_HEIGHT, TUNNEL_LENGTH, random, 3);
        int lo = TUNNEL_WALL;
        int hi = TUNNEL_WIDTH - 1 - TUNNEL_WALL;
        int floor = TUNNEL_WALL - 1;
        int roof = TUNNEL_WALL + DOOR_HEIGHT - 1;
        for (int z = 0; z < TUNNEL_LENGTH; z++) {
            for (int x = lo; x <= hi; x++) {
                for (int y = TUNNEL_WALL; y <= roof; y++) {
                    g.setType(x, y, z, AIR);
                }
                int roll = random.nextInt(10);
                g.put(x, floor, z, roll < 8 ? Blocks.OAK_PLANKS : roll < 9 ? Blocks.COBBLESTONE : Blocks.COARSE_DIRT);
            }
        }
        // A few small veins in the walls and roof.
        for (int i = 0; i < 4; i++) {
            int z = between(random, 2, TUNNEL_LENGTH - 3);
            int roll = random.nextInt(3);
            int x = roll == 0 ? lo - 1 : roll == 1 ? hi + 1 : between(random, lo, hi);
            int y = roll < 2 ? between(random, TUNNEL_WALL, roof) : roof + 1;
            vein(g, random, x, y, z, between(random, 2, 4));
        }

        BlockState leg = Blocks.OAK_LOG.defaultBlockState();
        BlockState beam = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        BlockState rail = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.NORTH_SOUTH);
        boolean torchLeft = true;
        for (int z = 2; z < TUNNEL_LENGTH; z += 5) {
            for (int y = TUNNEL_WALL; y < roof; y++) {
                g.put(lo, y, z, leg);
                g.put(hi, y, z, leg);
            }
            for (int x = lo; x <= hi; x++) {
                g.put(x, roof, z, beam);
            }
            // A torch on the side of one leg, facing along the corridor: beside the walkway, never in it.
            if (z + 1 < TUNNEL_LENGTH) {
                g.put(torchLeft ? lo : hi, TUNNEL_WALL + 1, z + 1, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.SOUTH));
            }
            torchLeft = !torchLeft;
        }
        for (int z = 0; z < TUNNEL_LENGTH; z++) {
            boolean clear = (z - 2) % 5 != 0 && (z - 3) % 5 != 0;
            if (random.nextInt(5) != 0) {
                g.put(MID_TUNNEL, TUNNEL_WALL, z, rail);
            }
            for (int x : new int[] { lo, hi }) {
                if (clear && random.nextInt(4) == 0) {
                    g.put(x, roof, z, Blocks.COBWEB);
                }
                if (clear && random.nextInt(7) == 0) {
                    g.put(x, TUNNEL_WALL, z, Blocks.GRAVEL);
                }
            }
        }
        if (caved) {
            // A fall from the roof along the east side, leaving the walkway open.
            for (int z = 13; z <= 18; z++) {
                int height = z == 13 || z == 18 ? 1 : 2;
                for (int y = TUNNEL_WALL; y < TUNNEL_WALL + height; y++) {
                    g.put(hi, y, z, random.nextBoolean() ? Blocks.GRAVEL : Blocks.COBBLESTONE);
                }
                if (z > 14 && z < 17) {
                    g.put(hi - 1, TUNNEL_WALL, z, Blocks.GRAVEL);
                }
                g.setType(hi, roof, z, ROCK);
            }
        }
        sealOutside(g);
        // The ends stay open to the doorways.
        for (int x = lo; x <= hi; x++) {
            for (int y = TUNNEL_WALL; y <= roof; y++) {
                for (int z : new int[] { 0, TUNNEL_LENGTH - 1 }) {
                    g.setType(x, y, z, AIR);
                }
            }
        }
        return g.build();
    }

    /** A plug of plain rock, with no ore, for a doorway with no corridor. */
    private static PieceBuilder cap() {
        PieceBuilder cap = new PieceBuilder(CAP_WIDTH, CAP_HEIGHT, CAP_DEPTH);
        cap.fill(0, 0, 0, CAP_WIDTH - 1, CAP_HEIGHT - 1, CAP_DEPTH - 1, Blocks.STONE);
        return cap;
    }

    // --- The start hall ---

    /**
     * The start floor's hall: a vaulted dwarven hall of stone brick, with rows of pillars, lanterns hung down the middle,
     * a raised dais in the middle for the quest giver, workshops along the walls, a timber headframe at the north end
     * over the ladder down, and a stair at the south end by the ladder out. Never turned, so the ladders' places are
     * fixed ({@link io.github.ranger3462.deepmines.MinesSpec#START_LADDER_DOWN},
     * {@link io.github.ranger3462.deepmines.MinesSpec#START_LADDER_UP}).
     */
    private static PieceBuilder startHall(Random random) {
        Grid g = room(random);
        int lo = 7;
        int hi = MAX - 7;
        int floor = START_FLOOR_Y - 1;
        BlockState bricks = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState chiseled = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
        int[] vault = new int[ROOM_SIZE];
        for (int x = 0; x < ROOM_SIZE; x++) {
            double t = (Math.max(lo, Math.min(hi, x)) - MID) / (double) (hi - lo + 2) * 2;
            vault[x] = START_FLOOR_Y + 9 + (int) Math.round(6 * (1 - t * t));
        }
        for (int x = lo - 1; x <= hi + 1; x++) {
            for (int z = lo - 1; z <= hi + 1; z++) {
                boolean wall = x < lo || x > hi || z < lo || z > hi;
                boolean edge = x == lo || x == hi || z == lo || z == hi;
                g.put(x, floor, z, wall || edge ? bricks : Blocks.POLISHED_ANDESITE.defaultBlockState());
                for (int y = START_FLOOR_Y; y <= vault[x] + 1; y++) {
                    if (wall || y == vault[x] + 1) {
                        g.put(x, y, z, bricks);
                    } else {
                        g.setType(x, y, z, AIR);
                    }
                }
            }
        }
        // Pillars down both sides.
        for (int px : new int[] { 13, MAX - 14 }) {
            for (int pz : new int[] { 13, 21, 28, 36 }) {
                for (int x = px; x <= px + 1; x++) {
                    for (int z = pz; z <= pz + 1; z++) {
                        for (int y = START_FLOOR_Y; y <= vault[x]; y++) {
                            g.put(x, y, z, y == START_FLOOR_Y || y == vault[x] ? chiseled : bricks);
                        }
                    }
                }
            }
        }
        // Lanterns hung down the middle.
        for (int z : new int[] { 17, MID, 32 }) {
            int top = vault[MID];
            for (int y = top - 3; y <= top; y++) {
                g.put(MID, y, z, Blocks.CHAIN);
            }
            g.put(MID, top - 4, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
        // The dais, with a lectern at its north edge facing the hall; the middle is left for the quest giver.
        forBox(MID - 3, START_FLOOR_Y, MID - 3, MID + 3, START_FLOOR_Y, MID + 3, (x, y, z) -> g.put(x, y, z, Blocks.POLISHED_GRANITE));
        forBox(MID - 1, START_FLOOR_Y, MID - 1, MID + 1, START_FLOOR_Y, MID + 1, (x, y, z) -> g.put(x, y, z, chiseled));
        g.put(MID, START_FLOOR_Y + 1, MID - 3, Blocks.LECTERN.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH));
        for (int[] c : new int[][] { { -3, -3 }, { 3, -3 }, { -3, 3 }, { 3, 3 } }) {
            g.put(MID + c[0], START_FLOOR_Y + 1, MID + c[1], Blocks.LANTERN);
        }
        // Workshops along the walls.
        g.put(lo, START_FLOOR_Y, 16, Blocks.BARREL);
        g.put(lo, START_FLOOR_Y, 17, Blocks.BARREL);
        g.put(lo, START_FLOOR_Y + 1, 16, Blocks.BARREL);
        g.put(lo, START_FLOOR_Y, 19, Blocks.CRAFTING_TABLE);
        for (int z : new int[] { 24, 25 }) {
            g.put(lo, START_FLOOR_Y, z, Blocks.FURNACE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.EAST));
        }
        g.put(lo, START_FLOOR_Y, 26, Blocks.BLAST_FURNACE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.EAST));
        g.put(lo, START_FLOOR_Y, 31, Blocks.SMITHING_TABLE);
        g.put(lo, START_FLOOR_Y, 32, Blocks.ANVIL.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        g.put(hi, START_FLOOR_Y, 16, Blocks.STONECUTTER.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.WEST));
        g.put(hi, START_FLOOR_Y, 19, Blocks.GRINDSTONE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.WEST));
        g.put(hi, START_FLOOR_Y, 24, Blocks.CARTOGRAPHY_TABLE);
        g.put(hi, START_FLOOR_Y, 31, Blocks.BARREL);
        g.put(hi, START_FLOOR_Y, 32, Blocks.BARREL);
        g.put(hi, START_FLOOR_Y + 1, 32, Blocks.BARREL);
        for (int z : new int[] { 10, 22, 29, 38 }) {
            g.put(lo, START_FLOOR_Y, z, Blocks.LANTERN);
            g.put(hi, START_FLOOR_Y, z, Blocks.LANTERN);
        }

        // The headframe over the ladder down, at the north end, with rails running up to it.
        int lx = START_LADDER_DOWN[0];
        int lz = START_LADDER_DOWN[2];
        BlockState post = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
        BlockState beamX = Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        BlockState beamZ = Blocks.SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
        int beamY = START_FLOOR_Y + 6;
        for (int x : new int[] { lx - 3, lx + 3 }) {
            for (int z : new int[] { lz - 2, lz + 1 }) {
                for (int y = START_FLOOR_Y; y < beamY; y++) {
                    g.put(x, y, z, post);
                }
            }
            for (int z = lz - 2; z <= lz + 1; z++) {
                g.put(x, beamY, z, beamZ);
            }
        }
        for (int x = lx - 3; x <= lx + 3; x++) {
            g.put(x, beamY, lz - 2, beamX);
            g.put(x, beamY, lz + 1, beamX);
            g.put(x, beamY, lz, beamX);
        }
        for (int y = START_FLOOR_Y + 3; y < beamY; y++) {
            g.put(lx, y, lz, Blocks.CHAIN);
        }
        BlockState rail = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST);
        for (int x = lx - 6; x <= lx - 2; x++) {
            g.put(x, START_FLOOR_Y, lz, rail);
        }

        // The stair at the south end, by the ladder out, under an arch.
        int ux = START_LADDER_UP[0];
        for (int step = 0; step < 4; step++) {
            int z = hi - 3 + step;
            for (int x = ux - 2; x <= ux + 2; x++) {
                for (int y = START_FLOOR_Y; y < START_FLOOR_Y + step; y++) {
                    g.put(x, y, z, bricks);
                }
                g.put(x, START_FLOOR_Y + step, z, Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
            }
        }
        for (int y = START_FLOOR_Y; y <= START_FLOOR_Y + 5; y++) {
            g.put(ux - 3, y, hi - 3, y == START_FLOOR_Y + 5 ? chiseled : bricks);
            g.put(ux + 3, y, hi - 3, y == START_FLOOR_Y + 5 ? chiseled : bricks);
        }
        for (int x = ux - 3; x <= ux + 3; x++) {
            g.put(x, START_FLOOR_Y + 6, hi - 3, chiseled);
        }

        // Keep the ladders' spots, and where players arrive beside them, clear.
        for (int[] spot : new int[][] { START_LADDER_DOWN, { lx, START_FLOOR_Y, lz + 2 }, START_LADDER_UP, { ux, START_FLOOR_Y, START_LADDER_UP[2] - 2 } }) {
            g.setType(spot[0], spot[1], spot[2], AIR);
            g.setType(spot[0], spot[1] + 1, spot[2], AIR);
        }
        sealOutside(g);
        return g.build();
    }
}
