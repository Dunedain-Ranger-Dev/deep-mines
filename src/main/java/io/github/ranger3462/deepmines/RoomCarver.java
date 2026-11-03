package io.github.ranger3462.deepmines;

import static io.github.ranger3462.deepmines.MinesGrid.*;
import static io.github.ranger3462.deepmines.MinesSpec.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import io.github.ranger3462.deepmines.MinesPieces.Kind;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;

/**
 * Carves the Mines' rooms and corridors when a floor is built, each from its own seed, so no two are alike. Every
 * room starts as a block of rock and has a cave carved in it at doorway height, so there's rock below to dig down into
 * and rock above to dig up into. Ore spots mark where veins go; they become the floor's ore as the room is placed.
 * <ul>
 * <li>Common rooms: the usual amount of ore. The "dig down" ones have it mostly under the cave's floor, the "dig up"
 * ones (with lower caves) mostly over it. Old abandoned drifts lead off the cave towards veins, and end in a shaft sunk
 * (or raised) towards more.</li>
 * <li>Quarries: a stepped open pit, packed with ore around its walls.</li>
 * <li>Flooded rooms: a deep pool to dive into, with its ore around the pool's walls and bottom.</li>
 * <li>Infested rooms: a cave with spawners, bones and cobwebs, and the usual amount of ore.</li>
 * <li>Corridors: low, timbered mineshafts in the style of YUNG's Better Mineshafts, with a little ore.</li>
 * </ul>
 * Every room is checked against the rules a floor relies on ({@link #problems}): if one breaks them, it's carved again
 * from the next seed, and as a last resort plank walkways are laid from each doorway to the middle.
 */
public final class RoomCarver {
    private static final int MAX = ROOM_SIZE - 1;
    private static final int TOP = ROOM_HEIGHT - 1;
    /** The middle of a corridor's width, where its rails run. */
    private static final int MID_TUNNEL = TUNNEL_WIDTH / 2;
    /** How many times a room is carved again before it gets walkways. */
    private static final int TRIES = 4;

    /** A carved room or corridor; {@code tries} is how many carvings it took, and {@code bridged} whether it needed walkways. */
    public record Carved(MinesGrid grid, int tries, boolean bridged) {}

    private RoomCarver() {}

    /** Carves a room or corridor of a kind from a seed: the same seed always gives the same one. */
    public static Carved carve(Kind kind, long seed) {
        MinesGrid grid = null;
        for (int t = 0; t < TRIES; t++) {
            grid = carveOnce(kind, new Random(seed + t * 0x9E3779B97F4A7C15L));
            if (problems(grid, kind).isEmpty()) {
                return new Carved(grid, t + 1, false);
            }
        }
        walkways(grid);
        return new Carved(grid, TRIES, true);
    }

    /** One try at carving: may break the rules. */
    public static MinesGrid carveOnce(Kind kind, Random random) {
        return switch (kind) {
            case COMMON -> common(random, random.nextBoolean());
            case QUARRY -> quarry(random);
            case FLOODED -> flooded(random);
            case INFESTED -> infested(random);
            case TUNNEL -> mineshaft(random, random.nextInt(3) == 0);
            default -> throw new IllegalArgumentException("Mines " + kind + " pieces aren't carved");
        };
    }

    // --- Rooms ---

    /** A block of rock the size of a room, ready to carve. */
    public static MinesGrid room(Random random) {
        return new MinesGrid(ROOM_SIZE, ROOM_HEIGHT, ROOM_SIZE, random, 22);
    }

    /**
     * A passage in from each doorway at doorway height, a little wider than the doorway, wandering a little once past
     * the landing, to the middle of the room.
     */
    private static void passages(MinesGrid g, Random random) {
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
     * A domed chamber standing on the walking floor ({@link MinesSpec#WALK_Y}): an irregular outline about
     * {@code radius} across, rising to about {@code height} over its middle.
     */
    private static void chamber(MinesGrid g, Random random, int cx, int cz, double radius, double height) {
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

    /**
     * The ways in, the main chamber (off the middle by up to {@code drift}), and a few smaller ones running off it.
     */
    private static void cave(MinesGrid g, Random random, double radius, double height, int alcoves, int drift) {
        passages(g, random);
        int cx = MID + between(random, -drift, drift);
        int cz = MID + between(random, -drift, drift);
        chamber(g, random, cx, cz, radius, height);
        for (int i = 0; i < alcoves; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double d = radius * (0.7 + random.nextDouble() * 0.3);
            int ax = (int) Math.round(cx + Math.cos(angle) * d);
            int az = (int) Math.round(cz + Math.sin(angle) * d);
            chamber(g, random, Math.max(9, Math.min(MAX - 9, ax)), Math.max(9, Math.min(MAX - 9, az)), 4 + random.nextInt(4), 2 + random.nextInt(4));
        }
    }

    /** Whether a spot is in a landing, which is kept as it is. */
    public static boolean inLanding(int x, int z) {
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
    private static void finishRoom(MinesGrid g) {
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
    public static void sealOutside(MinesGrid g) {
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

    /**
     * The last resort for a room that breaks the rules: a plank walkway from each doorway straight to the middle, with
     * clear air over it, then sealed again.
     */
    private static void walkways(MinesGrid g) {
        BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            for (int depth = LANDING_DEPTH + 1; depth <= MID + 1; depth++) {
                for (int across = MID - 1; across <= MID + 1; across++) {
                    int[] p = fromWall(side, across, depth);
                    if (!g.solidRock(p[0], WALK_Y, p[1])) {
                        g.put(p[0], WALK_Y, p[1], planks);
                    }
                    for (int y = WALK_Y + 1; y <= WALK_Y + 3; y++) {
                        g.setType(p[0], y, p[1], AIR);
                    }
                }
            }
        }
        finishRoom(g);
    }

    // --- Ore ---

    /** Grows a vein of up to {@code size} ore spots from a starting spot, through rock only. */
    private static void vein(MinesGrid g, Random random, int x, int y, int z, int size) {
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
    private static void veins(MinesGrid g, Random random, int count, int yMin, int yMax, int sizeMin, int sizeMax) {
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
    private static void veinsNear(MinesGrid g, Random random, int count, Near near, int reach, int sizeMin, int sizeMax) {
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
    private static void drift(MinesGrid g, Random random, boolean alongX, int line, int dir, int length, boolean shaft, boolean up) {
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
    private static void drifts(MinesGrid g, Random random, int count, boolean shaft, boolean up) {
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
    private static void decorate(MinesGrid g, Random random, int cobwebChance) {
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

    private static boolean againstWall(MinesGrid g, int x, int y, int z) {
        return g.solidRock(x - 1, y, z) || g.solidRock(x + 1, y, z) || g.solidRock(x, y, z - 1) || g.solidRock(x, y, z + 1);
    }

    // --- The kinds of room ---

    /**
     * A common room: a cave with the usual amount of ore. Dig-down caves are taller, with most of the ore under the
     * floor and old shafts sunk towards it; dig-up caves are low, with most of it overhead and shafts raised towards it.
     */
    private static MinesGrid common(Random random, boolean up) {
        MinesGrid g = room(random);
        cave(g, random, between(random, 10, 15), up ? between(random, 3, 5) : between(random, 6, 10), between(random, 3, 7), 4);
        drifts(g, random, between(random, 1, 3), true, up);
        int main = between(random, 65, 85);
        int other = between(random, 20, 30);
        if (up) {
            veins(g, random, main, WALK_Y + 6, TOP - 2, 4, 9);
            veins(g, random, other, 2, WALK_Y - 1, 4, 9);
        } else {
            veins(g, random, main, 2, WALK_Y - 1, 4, 9);
            veins(g, random, other, WALK_Y + 1, TOP - 2, 4, 9);
        }
        decorate(g, random, 8);
        finishRoom(g);
        return g;
    }

    /**
     * A quarry: a high hall with a stepped open pit in the middle, each step 3 down from the last with a ladder down it,
     * and ore packed into the pit's walls and floors.
     */
    private static MinesGrid quarry(Random random) {
        MinesGrid g = room(random);
        cave(g, random, between(random, 16, 18), between(random, 9, 13), between(random, 2, 4), 1);
        int steps = between(random, 4, 5);
        int widest = between(random, 13, 15);
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST);
        for (int k = 0; k < steps; k++) {
            int s = Math.max(2, widest - k * (widest / steps));
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
        // A ledge round the rim, so the ways in all meet without going down into the pit.
        for (int x = MID - widest - 4; x <= MID + widest + 4; x++) {
            for (int z = MID - widest - 4; z <= MID + widest + 4; z++) {
                if (Math.abs(x - MID) + Math.abs(z - MID) <= widest * 1.6 + 5 && g.solidRock(x, WALK_Y, z)) {
                    for (int y = WALK_Y + 1, top = WALK_Y + between(random, 3, 4); y <= top; y++) {
                        g.carve(x, y, z);
                    }
                }
            }
        }
        Near pit = (x, y, z) -> y <= WALK_Y && Math.abs(x - MID) <= widest + 1 && Math.abs(z - MID) <= widest + 1;
        veinsNear(g, random, between(random, 140, 180), pit, 3, 4, 9);
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
        return g;
    }

    /**
     * A flooded room: a cave around a deep pool, whose surface is level with the floor. The ore is around the pool's
     * walls and bottom, with a few underwater pockets off its sides; glow lichen on the walls lights it.
     */
    private static MinesGrid flooded(Random random) {
        MinesGrid g = room(random);
        cave(g, random, between(random, 14, 16), between(random, 7, 11), between(random, 3, 5), 2);
        int cx = MID + between(random, -3, 3);
        int cz = MID + between(random, -3, 3);
        double p1 = random.nextDouble() * Math.PI * 2;
        double p2 = random.nextDouble() * Math.PI * 2;
        double size = between(random, 9, 12);
        int depth = between(random, 9, 12);
        for (int x = 2; x <= MAX - 2; x++) {
            for (int z = 2; z <= MAX - 2; z++) {
                double dx = x - cx;
                double dz = z - cz;
                double angle = Math.atan2(dz, dx);
                double r = size + 2 * Math.sin(2 * angle + p1) + Math.sin(3 * angle + p2);
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d >= r) {
                    continue;
                }
                int bottom = WALK_Y - 3 - depth + (int) Math.round(depth * (d / r) * (d / r));
                for (int y = Math.max(2, bottom) + 1; y <= WALK_Y; y++) {
                    g.carve(x, y, z);
                }
            }
        }
        // Pockets off the pool's sides, deep down.
        for (int i = 0, pockets = between(random, 2, 5); i < pockets; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            int px = (int) Math.round(cx + Math.cos(angle) * size);
            int pz = (int) Math.round(cz + Math.sin(angle) * size);
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
        veinsNear(g, random, between(random, 95, 125), pool, 3, 4, 9);
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
        return g;
    }

    /**
     * An infested room: a cave held by mobs from two or three spawners, with bones in the walls, skulls on the floor,
     * lots of cobwebs, an old drift and the usual amount of ore.
     */
    private static MinesGrid infested(Random random) {
        MinesGrid g = room(random);
        cave(g, random, between(random, 12, 14), between(random, 5, 9), between(random, 3, 7), 2);
        drifts(g, random, between(random, 0, 2), false, false);
        veins(g, random, between(random, 60, 80), 2, WALK_Y - 1, 4, 9);
        veins(g, random, between(random, 25, 35), WALK_Y + 1, TOP - 2, 4, 9);

        int[][] corners = { { -1, -1 }, { 1, -1 }, { -1, 1 }, { 1, 1 } };
        int skip = random.nextInt(corners.length);
        boolean three = random.nextInt(3) != 0;
        for (int i = 0; i < corners.length; i++) {
            if (i == skip || (!three && i == (skip + 2) % corners.length)) {
                continue;
            }
            int d = between(random, 6, 9);
            int sx = MID + corners[i][0] * d;
            int sz = MID + corners[i][1] * d;
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
        return g;
    }

    // --- Corridors ---

    /**
     * A low timbered mineshaft: rock two thick around a doorway-sized passage with a plank floor, and every 4 to 6
     * blocks a set of supports (log legs and a beam overhead that leaves 3 blocks to walk under) with a torch on one
     * leg. A few small veins show in the walls. A caved-in one has a fall of rubble along one side somewhere along it.
     */
    private static MinesGrid mineshaft(Random random, boolean caved) {
        MinesGrid g = new MinesGrid(TUNNEL_WIDTH, TUNNEL_HEIGHT, TUNNEL_LENGTH, random, 3);
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
        for (int i = 0, veins = between(random, 3, 5); i < veins; i++) {
            int z = between(random, 2, TUNNEL_LENGTH - 3);
            int roll = random.nextInt(3);
            int x = roll == 0 ? lo - 1 : roll == 1 ? hi + 1 : between(random, lo, hi);
            int y = roll < 2 ? between(random, TUNNEL_WALL, roof) : roof + 1;
            vein(g, random, x, y, z, between(random, 2, 4));
        }

        BlockState leg = Blocks.OAK_LOG.defaultBlockState();
        BlockState beam = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        BlockState rail = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.NORTH_SOUTH);
        boolean torchLeft = random.nextBoolean();
        List<Integer> supports = new ArrayList<>();
        for (int z = between(random, 1, 3); z < TUNNEL_LENGTH - 1; z += between(random, 4, 6)) {
            supports.add(z);
            for (int y = TUNNEL_WALL; y < roof; y++) {
                g.put(lo, y, z, leg);
                g.put(hi, y, z, leg);
            }
            for (int x = lo; x <= hi; x++) {
                g.put(x, roof, z, beam);
            }
            // A torch on the side of one leg, facing along the corridor: beside the walkway, never in it.
            if (random.nextInt(5) != 0) {
                g.put(torchLeft ? lo : hi, TUNNEL_WALL + 1, z + 1, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.SOUTH));
            }
            torchLeft = !torchLeft;
        }
        for (int z = 0; z < TUNNEL_LENGTH; z++) {
            boolean clear = !supports.contains(z) && !supports.contains(z - 1);
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
            // A fall from the roof along one side, leaving the walkway open.
            int side = random.nextBoolean() ? hi : lo;
            int inner = side == hi ? hi - 1 : lo + 1;
            int from = between(random, 6, TUNNEL_LENGTH - 12);
            int to = from + between(random, 4, 6);
            for (int z = from; z <= to; z++) {
                int height = z == from || z == to ? 1 : 2;
                for (int y = TUNNEL_WALL; y < TUNNEL_WALL + height; y++) {
                    g.put(side, y, z, random.nextBoolean() ? Blocks.GRAVEL : Blocks.COBBLESTONE);
                }
                if (z > from + 1 && z < to - 1) {
                    g.put(inner, TUNNEL_WALL, z, Blocks.GRAVEL);
                }
                g.setType(side, roof, z, ROCK);
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
        return g;
    }

    // --- The rules ---

    /**
     * What's wrong with a carved room or corridor, if anything. Rooms: solid all round but their doorways, a landing
     * inside each, a way to walk from every doorway to every other, water only in flooded rooms, spawners only in
     * infested ones. Corridors: solid all round, open at both ends, with a clear walkway.
     */
    public static List<String> problems(MinesGrid g, Kind kind) {
        List<String> problems = new ArrayList<>();
        if (kind == Kind.TUNNEL) {
            tunnelProblems(g, problems);
            return problems;
        }
        for (int x = 0; x <= MAX; x++) {
            for (int y = 0; y <= TOP; y++) {
                for (int z = 0; z <= MAX; z++) {
                    if (g.inside(x, y, z)) {
                        continue;
                    }
                    boolean doorway = inDoorHeight(y) && ((inDoorSpan(x) && (z == 0 || z == MAX)) || (inDoorSpan(z) && (x == 0 || x == MAX)));
                    if (doorway ? !g.air(x, y, z) : !g.rock(x, y, z)) {
                        problems.add((doorway ? "a blocked doorway at " : "a hole in the outside at ") + x + "," + y + "," + z);
                    }
                }
            }
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            for (int depth = 1; depth <= LANDING_DEPTH; depth++) {
                for (int across = LANDING_START; across < LANDING_START + LANDING_WIDTH; across++) {
                    int[] p = fromWall(side, across, depth);
                    if (!g.solidRock(p[0], WALK_Y, p[1])) {
                        problems.add("no floor in the " + side + " landing at " + p[0] + "," + p[1]);
                    }
                    for (int y = DOOR_BOTTOM_Y; y < DOOR_BOTTOM_Y + DOOR_HEIGHT; y++) {
                        if (!g.air(p[0], y, p[1])) {
                            problems.add("the " + side + " landing is blocked at " + p[0] + "," + y + "," + p[1]);
                        }
                    }
                }
            }
        }
        int water = g.count(WATER);
        if ((kind == Kind.FLOODED) != (water > 0) || (kind == Kind.FLOODED && water < 500)) {
            problems.add(water + " water");
        }
        int spawners = g.count(Blocks.SPAWNER);
        if ((kind == Kind.INFESTED) != (spawners >= 2)) {
            problems.add(spawners + " spawners");
        }
        if (problems.isEmpty()) {
            boolean[] reached = walk(g, fromWall(Direction.NORTH, MID, 2));
            for (Direction side : Direction.Plane.HORIZONTAL) {
                int[] p = fromWall(side, MID, 2);
                if (!reached[(WALK_Y + 1) * ROOM_SIZE * ROOM_SIZE + p[1] * ROOM_SIZE + p[0]]) {
                    problems.add("no way to walk from the north doorway to the " + side + " one");
                }
            }
        }
        return problems;
    }

    private static void tunnelProblems(MinesGrid g, List<String> problems) {
        int lo = TUNNEL_WALL;
        int hi = TUNNEL_WIDTH - 1 - TUNNEL_WALL;
        int roof = TUNNEL_WALL + DOOR_HEIGHT - 1;
        for (int x = 0; x < g.sx; x++) {
            for (int y = 0; y < g.sy; y++) {
                for (int z = 0; z < g.sz; z++) {
                    boolean passage = x >= lo && x <= hi && y >= TUNNEL_WALL && y <= roof;
                    if (!passage && (g.open(x, y, z) || (g.type(x, y, z) == FIXED && g.passable(x, y, z)))) {
                        problems.add("a hole in the rock at " + x + "," + y + "," + z);
                    }
                    if (passage && (z == 0 || z == g.sz - 1) && !g.air(x, y, z)) {
                        problems.add("a blocked end at " + x + "," + y + "," + z);
                    }
                    if (x == MID_TUNNEL && y >= TUNNEL_WALL && y < roof && !g.passable(x, y, z)) {
                        problems.add("the walkway is blocked at " + x + "," + y + "," + z);
                    }
                }
            }
        }
    }

    /**
     * Everywhere someone could get to on foot from a spot just inside a room (at {@code {x, z}}, standing on the walking
     * floor): walking, stepping up a block, dropping down, climbing ladders and swimming. Indexed {@code (y * size + z) *
     * size + x}, by where their feet are.
     */
    private static boolean[] walk(MinesGrid g, int[] from) {
        boolean[] seen = new boolean[g.sx * g.sy * g.sz];
        Deque<int[]> queue = new ArrayDeque<>();
        visit(g, seen, queue, from[0], WALK_Y + 1, from[1]);
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            int x = c[0];
            int y = c[1];
            int z = c[2];
            boolean climbing = g.climbable(x, y, z) || g.type(x, y, z) == WATER;
            if (climbing && g.passable(x, y + 2, z)) {
                visit(g, seen, queue, x, y + 1, z);
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                int nx = x + d.getStepX();
                int nz = z + d.getStepZ();
                if (g.passable(nx, y, nz) && g.passable(nx, y + 1, nz)) {
                    int ny = y;
                    while (ny > 1 && !supported(g, nx, ny, nz)) {
                        ny--;
                    }
                    visit(g, seen, queue, nx, ny, nz);
                } else if (g.passable(nx, y + 1, nz) && g.passable(nx, y + 2, nz) && g.passable(x, y + 2, z)) {
                    visit(g, seen, queue, nx, y + 1, nz);
                }
            }
        }
        return seen;
    }

    private static boolean supported(MinesGrid g, int x, int y, int z) {
        return !g.passable(x, y - 1, z) || g.climbable(x, y, z) || g.climbable(x, y - 1, z) || g.type(x, y, z) == WATER;
    }

    private static void visit(MinesGrid g, boolean[] seen, Deque<int[]> queue, int x, int y, int z) {
        if (!g.in(x, y, z) || !g.passable(x, y, z) || !g.passable(x, y + 1, z) || !supported(g, x, y, z)) {
            return;
        }
        int i = (y * g.sz + z) * g.sx + x;
        if (!seen[i]) {
            seen[i] = true;
            queue.add(new int[] { x, y, z });
        }
    }
}
