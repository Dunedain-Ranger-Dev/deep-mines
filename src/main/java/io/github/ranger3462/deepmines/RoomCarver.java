package io.github.ranger3462.deepmines;

import static io.github.ranger3462.deepmines.MinesGrid.*;
import static io.github.ranger3462.deepmines.MinesSpec.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import io.github.ranger3462.deepmines.MinesPieces.Kind;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.RailShape;

/**
 * Carves the Mines' rooms and corridors when a floor is built, each from its own seed, so no two are alike. Every
 * room starts as a block of rock and has a cave (or old workings) carved in it at doorway height, so there's rock below to dig down into
 * and rock above to dig up into. Ore spots mark where veins go; they become the floor's ore as the room is placed.
 * <ul>
 * <li>Common rooms: the usual amount of ore. Half are caves: the "dig down" ones have it mostly under the cave's floor,
 * the "dig up" ones (with lower caves) mostly over it, and old abandoned drifts lead off the cave towards veins and end
 * in a shaft sunk (or raised) towards more. The other half are old workings like a vein room's, with ore of every
 * kind.</li>
 * <li>Quarries: one open hall with a stepped open pit in the middle, packed with ore around its walls.</li>
 * <li>Flooded rooms: a deep pool to dive into, with its ore around the pool's walls and bottom.</li>
 * <li>Infested rooms: a cave with spawners, bones and cobwebs, and the usual amount of ore.</li>
 * <li>Vein rooms: no cave, but timbered galleries driven through the rock after one ore, in very large veins.</li>
 * <li>Corridors: timbered mineshafts in the style of YUNG's Better Mineshafts, with a little ore.</li>
 * </ul>
 * A cave's floor rises and falls a little, but stays level along its rail line; its walls, roof and floor are worn
 * into bulges and hollows; and its roof has no notches or slots left in it.
 * A room's rail line runs in from each doorway with a corridor to the middle, so following the rails leads on and a
 * doorway without them is a dead end. Every vein of ore shows somewhere, or touches one that does, so there's always
 * something to see that's worth digging towards. Every room is checked against the rules a floor relies on
 * ({@link #problems}): if one breaks them, it's carved again from the next seed, and as a last resort plank walkways
 * are laid from each doorway to the middle.
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

    /**
     * Carves a room or corridor of a kind from a seed: the same seed always gives the same one. {@code openings} are
     * the sides of a room with a corridor; its rail line runs in from each of them (corridors ignore it).
     */
    public static Carved carve(Kind kind, long seed, Set<Direction> openings) {
        MinesGrid grid = null;
        for (int t = 0; t < TRIES; t++) {
            grid = carveOnce(kind, new Random(seed + t * 0x9E3779B97F4A7C15L), openings);
            if (problems(grid, kind).isEmpty()) {
                return new Carved(grid, t + 1, false);
            }
        }
        walkways(grid);
        finish(grid, new Random(seed), openings);
        return new Carved(grid, TRIES, true);
    }

    /** One try at carving: may break the rules. */
    public static MinesGrid carveOnce(Kind kind, Random random, Set<Direction> openings) {
        MinesGrid g = switch (kind) {
            case COMMON -> common(random, random.nextBoolean());
            case QUARRY -> quarry(random);
            case FLOODED -> flooded(random);
            case INFESTED -> infested(random);
            case VEIN -> veinRoom(random);
            case TUNNEL -> mineshaft(random, random.nextInt(3) == 0);
            default -> throw new IllegalArgumentException("Mines " + kind + " pieces aren't carved");
        };
        if (kind == Kind.TUNNEL) {
            dropUnsupportedRails(g);
            showOre(g, random);
        } else {
            finishRoom(g);
            finish(g, random, openings);
        }
        return g;
    }

    /**
     * The last of a room: its rail line, no rails left hanging, every hanging lantern on a chain up to the roof, and
     * every vein of ore shown or leading to one that is.
     */
    private static void finish(MinesGrid g, Random random, Set<Direction> openings) {
        railLine(g, random, openings);
        dropUnsupportedRails(g);
        hangLanterns(g);
        showOre(g, random);
    }

    // --- Rooms ---

    /** A block of rock the size of a room, ready to carve. */
    public static MinesGrid room(Random random) {
        return new MinesGrid(ROOM_SIZE, ROOM_HEIGHT, ROOM_SIZE, random, 22);
    }

    /**
     * A passage in from each doorway at doorway height, a little wider than the doorway, to the middle of the room. Once
     * past the landing it wanders a little (never off the rail line) and its roof rises and falls a step at a time.
     */
    private static void passages(MinesGrid g, Random random) {
        for (Direction side : Direction.Plane.HORIZONTAL) {
            int wobble = 0;
            int rise = 0;
            for (int depth = 1; depth <= MID; depth++) {
                if (depth > LANDING_DEPTH + 1 && random.nextInt(4) == 0) {
                    wobble = Math.max(-2, Math.min(2, wobble + random.nextInt(3) - 1));
                }
                if (depth > LANDING_DEPTH + 1 && random.nextInt(5) == 0) {
                    rise = Math.max(0, Math.min(2, rise + random.nextInt(3) - 1));
                }
                int top = DOOR_BOTTOM_Y + DOOR_HEIGHT - 1 + rise;
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
     * The ways in, the main chamber (off the middle by up to {@code drift}), and a few smaller ones running off it, on a
     * floor that rises and falls by up to {@code rough} blocks.
     */
    private static void cave(MinesGrid g, Random random, double radius, double height, int alcoves, int drift, int rough) {
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
        terrain(g, random, rough);
        roughen(g, random);
    }

    /**
     * Gives a cave's floor some rise and fall, up to {@code amp} blocks either way, everywhere but along the rail lines
     * (a band 7 wide from each doorway to the middle), which stay level and ease into it. Neighboring spots differ by a
     * block at most, so it can all be walked; a rise stops short of leaving less than 2 blocks to walk under.
     */
    private static void terrain(MinesGrid g, Random random, int amp) {
        double f1 = 0.14 + random.nextDouble() * 0.08;
        double f2 = 0.14 + random.nextDouble() * 0.08;
        double f3 = 0.1 + random.nextDouble() * 0.06;
        double p1 = random.nextDouble() * Math.PI * 2;
        double p2 = random.nextDouble() * Math.PI * 2;
        double p3 = random.nextDouble() * Math.PI * 2;
        int[][] off = new int[ROOM_SIZE][ROOM_SIZE];
        for (int x = 1; x < MAX; x++) {
            for (int z = 1; z < MAX; z++) {
                int band = Math.min(Math.abs(x - MID), Math.abs(z - MID));
                double ramp = Math.max(0, Math.min(1, (band - 3) / 4.0));
                double n = Math.sin(x * f1 + p1) * Math.cos(z * f2 + p2) + 0.7 * Math.sin((x - z) * f3 + p3);
                off[x][z] = Math.max(-amp, Math.min(amp, (int) Math.round(amp * ramp * n / 1.4)));
            }
        }
        // Wherever neighbors differ by more than a block, the one farther from level comes back a step, until none do.
        for (boolean changed = true; changed;) {
            changed = false;
            for (int x = 1; x < MAX; x++) {
                for (int z = 1; z < MAX; z++) {
                    for (int[] n : new int[][] { { x + 1, z }, { x, z + 1 } }) {
                        int a = off[x][z];
                        int b = off[n[0]][n[1]];
                        if (Math.abs(a - b) > 1) {
                            if (Math.abs(a) > Math.abs(b)) {
                                off[x][z] -= Integer.signum(a);
                            } else {
                                off[n[0]][n[1]] -= Integer.signum(b);
                            }
                            changed = true;
                        }
                    }
                }
            }
        }
        for (int x = 1; x < MAX; x++) {
            for (int z = 1; z < MAX; z++) {
                int k = off[x][z];
                if (k == 0 || inLanding(x, z) || !g.solidRock(x, WALK_Y, z) || !g.air(x, WALK_Y + 1, z)) {
                    continue;
                }
                if (k > 0) {
                    int rise = 0;
                    while (rise < k && g.air(x, WALK_Y + rise + 2, z) && g.air(x, WALK_Y + rise + 3, z)) {
                        rise++;
                    }
                    for (int y = WALK_Y + 1; y <= WALK_Y + rise; y++) {
                        g.setType(x, y, z, ROCK);
                    }
                } else {
                    for (int y = WALK_Y + k + 1; y <= WALK_Y; y++) {
                        g.carve(x, y, z);
                    }
                }
            }
        }
    }

    /**
     * Fills in notches left in a cave's roof: open spots with rock over them and rock on two opposite sides (or on three),
     * a slot or groove, as long as there's still room to walk under them.
     */
    private static void smoothRoof(MinesGrid g) {
        for (int pass = 0; pass < 3; pass++) {
            boolean changed = false;
            for (int y = TOP - 1; y >= WALK_Y + 3; y--) {
                for (int x = 1; x < MAX; x++) {
                    for (int z = 1; z < MAX; z++) {
                        if (!g.air(x, y, z) || !g.solidRock(x, y + 1, z) || !g.air(x, y - 1, z) || !g.air(x, y - 2, z) || inLanding(x, z)) {
                            continue;
                        }
                        boolean w = g.solidRock(x - 1, y, z);
                        boolean e = g.solidRock(x + 1, y, z);
                        boolean n = g.solidRock(x, y, z - 1);
                        boolean s = g.solidRock(x, y, z + 1);
                        int sides = (w ? 1 : 0) + (e ? 1 : 0) + (n ? 1 : 0) + (s ? 1 : 0);
                        if ((w && e) || (n && s) || sides >= 3) {
                            g.setType(x, y, z, ROCK);
                            changed = true;
                        }
                    }
                }
            }
            if (!changed) {
                return;
            }
        }
    }

    /**
     * Wears a cave's rock faces (walls, roof and floor) into bulges and hollows, by smooth 3D noise, so it isn't all
     * straight lines: where the noise is high the face is dug back, where it's low it bulges out. Two passes, so a face
     * can move up to 2 blocks. The landings stay as they are, and along the rail lines the floor stays level with room
     * to walk.
     */
    private static void roughen(MinesGrid g, Random random) {
        Noise broad = new Noise(random.nextLong());
        Noise fine = new Noise(random.nextLong());
        for (int pass = 0; pass < 2; pass++) {
            List<int[]> dig = new ArrayList<>();
            List<int[]> fill = new ArrayList<>();
            for (int x = 2; x <= MAX - 2; x++) {
                for (int z = 2; z <= MAX - 2; z++) {
                    if (inLanding(x, z)) {
                        continue;
                    }
                    boolean band = Math.abs(x - MID) <= 3 || Math.abs(z - MID) <= 3;
                    for (int y = 2; y <= TOP - 2; y++) {
                        boolean rock = g.rock(x, y, z);
                        boolean air = g.air(x, y, z);
                        if (!rock && !air || band && y <= WALK_Y + (air ? 3 : 0)) {
                            continue;
                        }
                        boolean face = false;
                        for (Direction d : Direction.values()) {
                            int nx = x + d.getStepX();
                            int ny = y + d.getStepY();
                            int nz = z + d.getStepZ();
                            if (rock ? g.air(nx, ny, nz) : g.rock(nx, ny, nz)) {
                                face = true;
                                break;
                            }
                        }
                        if (!face) {
                            continue;
                        }
                        double v = 0.65 * broad.at(x / 6.0, y / 4.0, z / 6.0) + 0.35 * fine.at(x / 2.5, y / 2.5, z / 2.5);
                        if (rock && v > 0.2) {
                            dig.add(new int[] { x, y, z });
                        } else if (air && y > WALK_Y && v < -0.3) {
                            fill.add(new int[] { x, y, z });
                        }
                    }
                }
            }
            dig.forEach(p -> g.carve(p[0], p[1], p[2]));
            fill.forEach(p -> g.setType(p[0], p[1], p[2], ROCK));
        }
    }

    /** Smooth 3D value noise, from -1 to 1, changing over about a unit. */
    private static final class Noise {
        private final long seed;

        Noise(long seed) {
            this.seed = seed;
        }

        double at(double x, double y, double z) {
            int x0 = (int) Math.floor(x);
            int y0 = (int) Math.floor(y);
            int z0 = (int) Math.floor(z);
            double fx = ease(x - x0);
            double fy = ease(y - y0);
            double fz = ease(z - z0);
            double result = 0;
            for (int i = 0; i < 8; i++) {
                int dx = i & 1;
                int dy = i >> 1 & 1;
                int dz = i >> 2 & 1;
                double w = (dx == 1 ? fx : 1 - fx) * (dy == 1 ? fy : 1 - fy) * (dz == 1 ? fz : 1 - fz);
                result += w * corner(x0 + dx, y0 + dy, z0 + dz);
            }
            return result;
        }

        private static double ease(double t) {
            return t * t * (3 - 2 * t);
        }

        private double corner(int x, int y, int z) {
            long h = seed ^ x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
            h = (h ^ h >>> 31) * 0xBF58476D1CE4E5B9L;
            h = (h ^ h >>> 29) * 0x94D049BB133111EBL;
            h ^= h >>> 32;
            return (h >>> 11) * 0x1.0p-53 * 2 - 1;
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

    // --- The rail line ---

    /**
     * The room's rail line: from each doorway with a corridor, straight in to the middle along the doorway's center,
     * where the lines meet (on a curve, if two meet at a corner) on a wooden junction. Its bed is planks 3 wide all the
     * way, a trestle where there's no floor (over a pit or pool, standing on posts down its edges every 4 blocks), with
     * room to walk beside it.
     */
    private static void railLine(MinesGrid g, Random random, Set<Direction> openings) {
        if (openings.isEmpty()) {
            return;
        }
        BlockState post = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
        // The junction: a plank platform 7 across, framed in stripped logs, clear over it, with a lantern on a post at
        // each corner (and the post run down to the ground, where it stands over a pit).
        for (int x = MID - 3; x <= MID + 3; x++) {
            for (int z = MID - 3; z <= MID + 3; z++) {
                boolean edgeX = Math.abs(x - MID) == 3;
                boolean edgeZ = Math.abs(z - MID) == 3;
                g.put(x, WALK_Y, z, edgeX || edgeZ ? post.setValue(RotatedPillarBlock.AXIS, edgeX ? Direction.Axis.Z : Direction.Axis.X) : planks(random));
                for (int y = WALK_Y + 1; y <= WALK_Y + 3; y++) {
                    g.setType(x, y, z, AIR);
                }
            }
        }
        for (int[] c : new int[][] { { -1, -1 }, { 1, -1 }, { -1, 1 }, { 1, 1 } }) {
            int x = MID + c[0] * 3;
            int z = MID + c[1] * 3;
            g.put(x, WALK_Y + 1, z, Blocks.SPRUCE_FENCE);
            g.put(x, WALK_Y + 2, z, Blocks.LANTERN);
            for (int y = WALK_Y - 1; g.open(x, y, z); y--) {
                g.put(x, y, z, post);
            }
        }
        for (Direction side : openings) {
            RailShape shape = side.getAxis() == Direction.Axis.Z ? RailShape.NORTH_SOUTH : RailShape.EAST_WEST;
            for (int depth = 0; depth <= MID; depth++) {
                for (int across = MID - 1; across <= MID + 1; across++) {
                    int[] p = fromWall(side, across, depth);
                    boolean gap = g.passable(p[0], WALK_Y, p[1]);
                    if (depth > 0 && depth < MID - 3) {
                        g.put(p[0], WALK_Y, p[1], bed(random, depth, side.getClockWise().getAxis()));
                    }
                    for (int y = WALK_Y - 1; gap && depth > 0 && across != MID && depth % 4 == 0 && g.open(p[0], y, p[1]); y--) {
                        g.put(p[0], y, p[1], post);
                    }
                    for (int y = WALK_Y + 1; y <= WALK_Y + 3 && depth > 0; y++) {
                        g.setType(p[0], y, p[1], AIR);
                    }
                }
                int[] p = fromWall(side, MID, depth);
                g.put(p[0], WALK_Y + 1, p[1], Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, shape));
            }
        }
        g.put(MID, WALK_Y + 1, MID, Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, hub(openings)));
    }

    /** How the rail in the middle of a room lies, for the sides its lines come in from. */
    private static RailShape hub(Set<Direction> openings) {
        boolean n = openings.contains(Direction.NORTH);
        boolean s = openings.contains(Direction.SOUTH);
        boolean e = openings.contains(Direction.EAST);
        boolean w = openings.contains(Direction.WEST);
        if ((n || s) && !(e || w) || (n && s)) {
            return RailShape.NORTH_SOUTH;
        } else if (!(n || s) || (e && w)) {
            return RailShape.EAST_WEST;
        }
        return n ? (e ? RailShape.NORTH_EAST : RailShape.NORTH_WEST) : (e ? RailShape.SOUTH_EAST : RailShape.SOUTH_WEST);
    }

    /** Takes out any rail with nothing solid under it. */
    private static void dropUnsupportedRails(MinesGrid g) {
        for (int x = 0; x < g.sx; x++) {
            for (int y = 0; y < g.sy; y++) {
                for (int z = 0; z < g.sz; z++) {
                    if (g.block(x, y, z).is(Blocks.RAIL) && !g.solid(x, y - 1, z)) {
                        g.setType(x, y, z, AIR);
                    }
                }
            }
        }
    }

    /** Hangs every hanging lantern with open air over it on a chain, up to whatever is over it. */
    private static void hangLanterns(MinesGrid g) {
        for (int x = 0; x < g.sx; x++) {
            for (int z = 0; z < g.sz; z++) {
                for (int y = 0; y < g.sy - 1; y++) {
                    BlockState block = g.block(x, y, z);
                    boolean hung = block.is(Blocks.CHAIN) || block.is(Blocks.LANTERN) && block.getValue(LanternBlock.HANGING);
                    if (hung && g.air(x, y + 1, z)) {
                        g.put(x, y + 1, z, Blocks.CHAIN);
                    }
                }
            }
        }
    }

    /**
     * A block of a track's bed, {@code along} it: a sleeper (a stripped spruce log lying across the track, along
     * {@code across}) every other block, otherwise planks, mostly spruce, some darker or paler, old boards patched in.
     */
    private static BlockState bed(Random random, int along, Direction.Axis across) {
        if (Math.floorMod(along, 2) == 0) {
            return Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, across);
        }
        return planks(random);
    }

    /** Old planks: mostly spruce, some dark oak, now and then oak or a stripped log's end. */
    private static BlockState planks(Random random) {
        int roll = random.nextInt(20);
        return (roll < 13 ? Blocks.SPRUCE_PLANKS : roll < 17 ? Blocks.DARK_OAK_PLANKS : roll < 19 ? Blocks.OAK_PLANKS : Blocks.STRIPPED_SPRUCE_WOOD)
                .defaultBlockState();
    }

    // --- Ore that shows ---

    /** How far a buried vein may run on to show itself; one farther in moves to the rock face instead. */
    private static final int LEAD = 6;

    /** A vein of ore: spots of it touching face to face (which become one ore when the floor is built). */
    private static List<int[]> vein(MinesGrid g, boolean[] seen, int x, int y, int z) {
        List<int[]> vein = new ArrayList<>();
        Deque<int[]> queue = new ArrayDeque<>();
        seen[g.index(x, y, z)] = true;
        queue.add(new int[] { x, y, z });
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            vein.add(c);
            for (Direction d : Direction.values()) {
                int nx = c[0] + d.getStepX();
                int ny = c[1] + d.getStepY();
                int nz = c[2] + d.getStepZ();
                if (g.type(nx, ny, nz) == ORE && !seen[g.index(nx, ny, nz)]) {
                    seen[g.index(nx, ny, nz)] = true;
                    queue.add(new int[] { nx, ny, nz });
                }
            }
        }
        return vein;
    }

    private static List<List<int[]>> veins(MinesGrid g) {
        List<List<int[]>> veins = new ArrayList<>();
        boolean[] seen = new boolean[g.sx * g.sy * g.sz];
        for (int x = 0; x < g.sx; x++) {
            for (int y = 0; y < g.sy; y++) {
                for (int z = 0; z < g.sz; z++) {
                    if (g.type(x, y, z) == ORE && !seen[g.index(x, y, z)]) {
                        veins.add(vein(g, seen, x, y, z));
                    }
                }
            }
        }
        return veins;
    }

    /** Whether a spot of rock has open space beside it, so it can be seen. */
    private static boolean shows(MinesGrid g, int x, int y, int z) {
        for (Direction d : Direction.values()) {
            if (g.passable(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ())) {
                return true;
            }
        }
        return false;
    }

    /** Whether a spot touches (even only at an edge or corner) a spot already marked. */
    private static boolean touches(MinesGrid g, boolean[] marked, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (g.in(x + dx, y + dy, z + dz) && marked[g.index(x + dx, y + dy, z + dz)]) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Which veins can be found: those that show, and those touching (even at a corner) one that can be found. The rest
     * are buried out of sight, and nothing would lead a player to them.
     */
    private static boolean[] found(MinesGrid g, List<List<int[]>> veins, List<List<int[]>> buried) {
        boolean[] found = new boolean[g.sx * g.sy * g.sz];
        List<List<int[]>> left = new ArrayList<>();
        for (List<int[]> vein : veins) {
            if (vein.stream().anyMatch(c -> shows(g, c[0], c[1], c[2]))) {
                vein.forEach(c -> found[g.index(c[0], c[1], c[2])] = true);
            } else {
                left.add(vein);
            }
        }
        for (boolean more = true; more;) {
            more = false;
            for (var it = left.iterator(); it.hasNext();) {
                List<int[]> vein = it.next();
                if (vein.stream().anyMatch(c -> touches(g, found, c[0], c[1], c[2]))) {
                    vein.forEach(c -> found[g.index(c[0], c[1], c[2])] = true);
                    it.remove();
                    more = true;
                }
            }
        }
        buried.addAll(left);
        return found;
    }

    /** The veins nothing leads to. */
    public static List<List<int[]>> buriedVeins(MinesGrid g) {
        List<List<int[]>> buried = new ArrayList<>();
        found(g, veins(g), buried);
        return buried;
    }

    /**
     * Makes every vein findable. A buried one near open space or a findable vein runs on through the rock to it (a lead
     * worth following in); one farther in moves to the rock face, at about the same height, and grows from there.
     */
    private static void showOre(MinesGrid g, Random random) {
        List<List<int[]>> buried = new ArrayList<>();
        boolean[] found = found(g, veins(g), buried);
        List<int[]> faces = new ArrayList<>();
        for (int x = 1; x < g.sx - 1; x++) {
            for (int y = 1; y < g.sy - 1; y++) {
                for (int z = 1; z < g.sz - 1; z++) {
                    if (g.oreable(x, y, z) && shows(g, x, y, z)) {
                        faces.add(new int[] { x, y, z });
                    }
                }
            }
        }
        for (List<int[]> vein : buried) {
            List<int[]> lead = lead(g, found, vein);
            if (lead != null) {
                for (int[] c : lead) {
                    g.setType(c[0], c[1], c[2], ORE);
                }
                vein.forEach(c -> found[g.index(c[0], c[1], c[2])] = true);
                lead.forEach(c -> found[g.index(c[0], c[1], c[2])] = true);
                continue;
            }
            vein.forEach(c -> g.setType(c[0], c[1], c[2], ROCK));
            if (faces.isEmpty()) {
                continue;
            }
            int height = vein.get(0)[1];
            int[] pick = faces.get(random.nextInt(faces.size()));
            for (int tries = 0; tries < 40 && Math.abs(pick[1] - height) > 6; tries++) {
                pick = faces.get(random.nextInt(faces.size()));
            }
            vein(g, random, pick[0], pick[1], pick[2], vein.size());
            if (g.type(pick[0], pick[1], pick[2]) == ORE) {
                // What grew (with anything it ran into) shows now.
                Deque<int[]> queue = new ArrayDeque<>(List.of(pick));
                found[g.index(pick[0], pick[1], pick[2])] = true;
                while (!queue.isEmpty()) {
                    int[] c = queue.poll();
                    for (Direction d : Direction.values()) {
                        int nx = c[0] + d.getStepX();
                        int ny = c[1] + d.getStepY();
                        int nz = c[2] + d.getStepZ();
                        if (g.type(nx, ny, nz) == ORE && !found[g.index(nx, ny, nz)]) {
                            found[g.index(nx, ny, nz)] = true;
                            queue.add(new int[] { nx, ny, nz });
                        }
                    }
                }
            }
        }
    }

    /**
     * The shortest run of rock (up to {@link #LEAD} long) from a vein to a spot that shows or touches a findable vein,
     * or null if there's none that close.
     */
    private static List<int[]> lead(MinesGrid g, boolean[] found, List<int[]> vein) {
        Map<Integer, Integer> from = new HashMap<>();
        Deque<int[]> queue = new ArrayDeque<>();
        for (int[] c : vein) {
            from.put(g.index(c[0], c[1], c[2]), g.index(c[0], c[1], c[2]));
            queue.add(new int[] { c[0], c[1], c[2], 0 });
        }
        while (!queue.isEmpty()) {
            int[] c = queue.poll();
            if (c[3] >= LEAD) {
                continue;
            }
            for (Direction d : Direction.values()) {
                int nx = c[0] + d.getStepX();
                int ny = c[1] + d.getStepY();
                int nz = c[2] + d.getStepZ();
                int i = g.index(nx, ny, nz);
                if (!g.oreable(nx, ny, nz) || from.containsKey(i)) {
                    continue;
                }
                from.put(i, g.index(c[0], c[1], c[2]));
                if (shows(g, nx, ny, nz) || touches(g, found, nx, ny, nz)) {
                    List<int[]> lead = new ArrayList<>();
                    for (int at = i; at != from.get(at); at = from.get(at)) {
                        lead.add(g.position(at));
                    }
                    return lead;
                }
                queue.add(new int[] { nx, ny, nz, c[3] + 1 });
            }
        }
        return null;
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
        BlockState foot = Blocks.BARREL.defaultBlockState();
        BlockState leg = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
        BlockState beam = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, alongX ? Direction.Axis.Z : Direction.Axis.X);
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
                g.put(mid[0], WALK_Y, mid[1], bed(random, a, alongX ? Direction.Axis.Z : Direction.Axis.X));
            }
            int step = (a - start) * dir;
            boolean nearEnd = (end - a) * dir < 2;
            if (step % 3 == 1 && !nearEnd) {
                // A support: legs on barrels either side, a beam over. Some have rotted away.
                if (random.nextInt(5) != 0) {
                    for (int across : new int[] { line - 1, line + 1 }) {
                        int[] p = at(alongX, a, across);
                        g.put(p[0], WALK_Y + 1, p[1], foot);
                        if (random.nextInt(4) != 0) {
                            g.put(p[0], WALK_Y + 2, p[1], leg);
                        }
                    }
                    for (int across = line - 1; across <= line + 1; across++) {
                        int[] p = at(alongX, a, across);
                        g.put(p[0], WALK_Y + 3, p[1], beam);
                    }
                }
            } else if (random.nextInt(8) == 0) {
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
            // A winze: sunk down from the drift's floor, with a ladder down the face.
            int bottom = between(random, 5, 8);
            for (int[] h : hole) {
                for (int y = bottom + 1; y <= WALK_Y; y++) {
                    g.carve(h[0], y, h[1]);
                }
            }
            for (int y = bottom + 1; y <= WALK_Y + 1; y++) {
                g.put(ladderAt[0], y, ladderAt[1], ladder);
            }
            // A short drive on from the bottom, beside the ladder, into the pocket of ore it was after.
            int reach = between(random, 3, 5);
            for (int a = end + dir; a != end + dir * reach; a += dir) {
                int[] p = at(alongX, a, line + 1);
                g.carve(p[0], bottom + 1, p[1]);
                g.carve(p[0], bottom + 2, p[1]);
            }
            int[] pocket = at(alongX, end + dir * (reach + 1), line + 1);
            stope(g, random, pocket[0], bottom + 1, pocket[1]);
            vein(g, random, ladderAt[0], bottom, ladderAt[1], between(random, 6, 10));
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
            // It broke into a pocket of ore at the top.
            int[] over = at(alongX, end, line);
            stope(g, random, over[0], top + 1, over[1]);
        }
    }

    /** A small pocket worked out of the rock, 3 across and 2 high, with veins showing in its walls and roof. */
    private static void stope(MinesGrid g, Random random, int cx, int cy, int cz) {
        forBox(cx - 1, cy, cz - 1, cx + 1, cy + 1, cz + 1, g::carve);
        for (int i = 0, veins = between(random, 2, 3); i < veins; i++) {
            Direction d = Direction.values()[1 + random.nextInt(5)]; // Up or a side, never the floor.
            int x = cx + d.getStepX() * 2 + (d.getAxis() == Direction.Axis.X ? 0 : between(random, -1, 1));
            int y = cy + (d == Direction.UP ? 2 : random.nextInt(2));
            int z = cz + d.getStepZ() * 2 + (d.getAxis() == Direction.Axis.Z ? 0 : between(random, -1, 1));
            vein(g, random, x, y, z, between(random, 6, 10));
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
     * A common room, with the usual amount of ore: old workings like a vein room's (half of them), or a cave. Dig-down
     * caves are taller, with most of the ore under the floor and old shafts sunk towards it; dig-up caves are low,
     * with most of it overhead and shafts raised towards it.
     */
    private static MinesGrid common(Random random, boolean up) {
        MinesGrid g = room(random);
        if (random.nextBoolean()) {
            // Old workings instead of a cave, with the usual ore of every kind in smaller veins.
            workings(g, random, 6, 10);
            Near workings = (x, y, z) -> y > WALK_Y;
            veinsNear(g, random, between(random, 15, 20), workings, 3, 4, 9);
            veins(g, random, between(random, 5, 8), 2, TOP - 2, 4, 9);
            decorate(g, random, 30);
            return g;
        }
        cave(g, random, between(random, 10, 15), up ? between(random, 3, 5) : between(random, 6, 10), between(random, 3, 7), 4, 3);
        drifts(g, random, between(random, 1, 3), true, up);
        smoothRoof(g);
        int main = between(random, 16, 21);
        int other = between(random, 5, 8);
        if (up) {
            veins(g, random, main, WALK_Y + 6, TOP - 2, 4, 9);
            veins(g, random, other, 2, WALK_Y - 1, 4, 9);
        } else {
            veins(g, random, main, 2, WALK_Y - 1, 4, 9);
            veins(g, random, other, WALK_Y + 1, TOP - 2, 4, 9);
        }
        decorate(g, random, 24);
        return g;
    }

    /**
     * A quarry: one open hall out to the room's outer walls, on a flat floor under a domed roof, with a stepped open pit
     * in the middle, each step 3 down from the last with a ladder down it, and ore packed into the pit's walls and floors.
     */
    private static MinesGrid quarry(Random random) {
        MinesGrid g = room(random);
        passages(g, random);
        int edge = between(random, 5, 7);
        int rise = between(random, 5, 8);
        double p1 = random.nextDouble() * Math.PI * 2;
        double p2 = random.nextDouble() * Math.PI * 2;
        for (int x = 3; x <= MAX - 3; x++) {
            for (int z = 3; z <= MAX - 3; z++) {
                double dx = (x - MID) / (double) (MID - 3);
                double dz = (z - MID) / (double) (MID - 3);
                double d = Math.min(1, Math.sqrt(dx * dx + dz * dz));
                double h = edge + rise * (1 - d * d) + 0.8 * Math.sin(x * 0.3 + p1) * Math.cos(z * 0.27 + p2);
                int top = Math.min(TOP - 3, WALK_Y + (int) Math.round(h));
                for (int y = WALK_Y + 1; y <= top; y++) {
                    g.carve(x, y, z);
                }
            }
        }
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
            // A ladder down each step, on the east side, against the step, beside where the rail line crosses.
            for (int y = floor + 1; y <= WALK_Y - 3 * k; y++) {
                g.put(MID + s, y, MID + 3, ladder);
            }
        }
        smoothRoof(g);
        Near pit = (x, y, z) -> y <= WALK_Y && Math.abs(x - MID) <= widest + 1 && Math.abs(z - MID) <= widest + 1;
        veinsNear(g, random, between(random, 35, 45), pit, 3, 4, 9);
        veins(g, random, 10, 2, TOP - 2, 4, 9);

        // A boardwalk 3 wide round the pit's rim, with stripped logs along its edge.
        for (int x = 2; x <= MAX - 2; x++) {
            for (int z = 2; z <= MAX - 2; z++) {
                if (!g.rock(x, WALK_Y, z) || !g.air(x, WALK_Y + 1, z) || inLanding(x, z)) {
                    continue;
                }
                int near = 4;
                Direction.Axis lies = Direction.Axis.X;
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        int d = Math.max(Math.abs(dx), Math.abs(dz));
                        if (d < near && g.air(x + dx, WALK_Y, z + dz)) {
                            near = d;
                            lies = Math.abs(dx) >= Math.abs(dz) ? Direction.Axis.Z : Direction.Axis.X;
                        }
                    }
                }
                if (near == 1) {
                    g.put(x, WALK_Y, z, Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, lies));
                } else if (near <= 3) {
                    g.put(x, WALK_Y, z, planks(random));
                }
            }
        }

        // Lanterns on posts out on the rim at the corners.
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
        decorate(g, random, 30);
        return g;
    }

    /**
     * A flooded room: a cave around a deep pool, whose surface is level with the floor. The ore is around the pool's
     * walls and bottom, with a few underwater pockets off its sides; glow lichen on the walls lights it.
     */
    private static MinesGrid flooded(Random random) {
        MinesGrid g = room(random);
        cave(g, random, between(random, 14, 16), between(random, 7, 11), between(random, 3, 5), 2, 3);
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
        smoothRoof(g);
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
        veinsNear(g, random, between(random, 24, 31), pool, 3, 4, 9);
        veins(g, random, 6, WALK_Y + 1, TOP - 2, 4, 9);

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
        decorate(g, random, 30);
        return g;
    }

    /**
     * An infested room: a cave held by mobs from two or three spawners, with bones in the walls, skulls on the floor,
     * cobwebs, an old drift and the usual amount of ore.
     */
    private static MinesGrid infested(Random random) {
        MinesGrid g = room(random);
        cave(g, random, between(random, 12, 14), between(random, 5, 9), between(random, 3, 7), 2, 3);
        drifts(g, random, between(random, 0, 2), false, false);
        smoothRoof(g);
        veins(g, random, between(random, 30, 40), 2, WALK_Y - 1, 4, 9);
        veins(g, random, between(random, 12, 18), WALK_Y + 1, TOP - 2, 4, 9);

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
            forBox(sx - 1, WALK_Y, sz - 1, sx + 1, WALK_Y, sz + 1, (x, y, z) -> g.setType(x, y, z, ROCK));
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
                    if (!inLane(x, z) && y <= WALK_Y + 2 && random.nextInt(70) == 0) {
                        g.put(x, y, z, Blocks.COBWEB);
                    }
                }
            }
        }
        decorate(g, random, 10);
        return g;
    }

    // --- Corridors ---

    /**
     * A mine support across a passage 7 wide, centered on {@code line}, for someone standing at {@code y}: stripped
     * spruce legs standing on barrels, a beam 7 wide across their tops leaving 5 blocks under it, an upside-down spruce
     * stair in each inside corner, and spruce trapdoors flush against the beam and the leg beside each stair. With
     * {@code lit}, a lantern hangs from the middle of the beam.
     */
    private static void support(MinesGrid g, boolean alongX, int along, int line, int y, boolean lit) {
        Direction low = alongX ? Direction.NORTH : Direction.WEST;
        BlockState foot = Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP);
        BlockState leg = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
        BlockState beam = leg.setValue(RotatedPillarBlock.AXIS, alongX ? Direction.Axis.Z : Direction.Axis.X);
        BlockState brace = Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.HALF, Half.TOP);
        BlockState trapdoor = Blocks.SPRUCE_TRAPDOOR.defaultBlockState();
        for (int s : new int[] { -1, 1 }) {
            Direction toLeg = s < 0 ? low : low.getOpposite();
            put(g, alongX, along, line + 3 * s, y, foot);
            for (int h = 1; h <= 4; h++) {
                put(g, alongX, along, line + 3 * s, y + h, leg);
            }
            put(g, alongX, along, line + 2 * s, y + 4, brace.setValue(StairBlock.FACING, toLeg));
            // Under the beam, shut and up against it; down the leg, open and against it (an open trapdoor lies on the
            // side away from where it faces).
            put(g, alongX, along, line + s, y + 4, trapdoor.setValue(TrapDoorBlock.HALF, Half.TOP));
            put(g, alongX, along, line + 2 * s, y + 3, trapdoor.setValue(TrapDoorBlock.OPEN, true).setValue(TrapDoorBlock.FACING, toLeg.getOpposite()));
        }
        for (int across = line - 3; across <= line + 3; across++) {
            put(g, alongX, along, across, y + 5, beam);
        }
        if (lit) {
            put(g, alongX, along, line, y + 4, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
    }

    private static void put(MinesGrid g, boolean alongX, int along, int across, int y, BlockState state) {
        int[] p = at(alongX, along, across);
        g.put(p[0], y, p[1], state);
    }

    /** Whether a support has a lantern: most do. */
    private static boolean lit(Random random) {
        return random.nextInt(4) != 0;
    }

    /**
     * A gallery in a vein room: a passage 7 wide and 6 high on the walking floor, along x or z ({@code line} across),
     * from {@code from} to {@code to}, with a support every 4 to 6 blocks from {@code first} in. With {@code rails}, an
     * old rail line runs down it, some of it gone.
     */
    private static void gallery(MinesGrid g, Random random, boolean alongX, int line, int from, int to, int first, boolean rails) {
        int dir = to > from ? 1 : -1;
        BlockState rail = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, alongX ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH);
        for (int a = from; a != to + dir; a += dir) {
            for (int across = line - 3; across <= line + 3; across++) {
                int[] p = at(alongX, a, across);
                for (int y = WALK_Y + 1; y <= WALK_Y + 6; y++) {
                    g.carve(p[0], y, p[1]);
                }
            }
            int[] mid = at(alongX, a, line);
            if (rails && random.nextInt(4) != 0) {
                g.put(mid[0], WALK_Y + 1, mid[1], rail);
                g.put(mid[0], WALK_Y, mid[1], bed(random, a, alongX ? Direction.Axis.Z : Direction.Axis.X));
            }
        }
        for (int a = from + dir * first; (to - a) * dir >= 2; a += dir * between(random, 4, 6)) {
            support(g, alongX, a, line, WALK_Y + 1, lit(random));
        }
    }

    /**
     * A vein room: no cave, but old workings driven through the rock after one ore, in very large veins showing in the
     * galleries' walls and roofs, all of one ore, picked when the floor is built.
     */
    private static MinesGrid veinRoom(Random random) {
        MinesGrid g = room(random);
        workings(g, random, 20, 35);
        Near workings = (x, y, z) -> y > WALK_Y;
        veinsNear(g, random, between(random, 3, 4), workings, 2, 50, 90);
        decorate(g, random, 30);
        return g;
    }

    /**
     * Old workings: timbered galleries in from every doorway to a hall in the middle, and crosscuts off them (one to a
     * quarter of the room) each ending at a vein of {@code faceMin} to {@code faceMax} ore.
     */
    private static void workings(MinesGrid g, Random random, int faceMin, int faceMax) {
        int hall = between(random, 4, 6);
        forBox(MID - hall, WALK_Y + 1, MID - hall, MID + hall, WALK_Y + 6, MID + hall, g::carve);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            boolean alongX = side.getAxis() == Direction.Axis.X;
            int[] from = fromWall(side, MID, 1);
            int[] to = fromWall(side, MID, MID - hall - 1);
            gallery(g, random, alongX, MID, alongX ? from[0] : from[1], alongX ? to[0] : to[1], LANDING_DEPTH + 1, false);
        }
        List<int[]> quarters = new ArrayList<>(List.of(new int[] { -1, -1 }, new int[] { 1, -1 }, new int[] { -1, 1 }, new int[] { 1, 1 }));
        Collections.shuffle(quarters, random);
        for (int[] q : quarters.subList(0, between(random, 2, 4))) {
            // Off the north or south gallery along x, or off the west or east one along z, into that quarter.
            boolean alongX = random.nextBoolean();
            Direction side = alongX ? (q[1] < 0 ? Direction.NORTH : Direction.SOUTH) : (q[0] < 0 ? Direction.WEST : Direction.EAST);
            int dir = alongX ? q[0] : q[1];
            int[] p = fromWall(side, MID, between(random, LANDING_DEPTH + 4, MID - hall - 5));
            int line = alongX ? p[1] : p[0];
            int length = between(random, 6, 12);
            gallery(g, random, alongX, line, MID + dir * 4, MID + dir * (3 + length), 2, true);
            int[] face = at(alongX, MID + dir * (5 + length), line);
            vein(g, random, face[0], WALK_Y + between(random, 2, 4), face[1], between(random, faceMin, faceMax));
        }
        // Lanterns hung in the hall's corners.
        for (int[] c : new int[][] { { -1, -1 }, { 1, -1 }, { -1, 1 }, { 1, 1 } }) {
            g.put(MID + c[0] * (hall - 1), WALK_Y + 6, MID + c[1] * (hall - 1), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }
    }

    /**
     * A timbered mineshaft: rock two thick around a passage 7 wide and 6 high, with a plank floor and a rail line down
     * the middle, and every 4 to 6 blocks a {@link #support} with a lantern by most of them. A few small veins show in
     * the walls. A caved-in one has a fall of rubble along one side somewhere along it.
     */
    private static MinesGrid mineshaft(Random random, boolean caved) {
        MinesGrid g = new MinesGrid(TUNNEL_WIDTH, TUNNEL_HEIGHT, TUNNEL_LENGTH, random, 3);
        int lo = TUNNEL_WALL;
        int hi = TUNNEL_WIDTH - 1 - TUNNEL_WALL;
        int floor = TUNNEL_WALL - 1;
        int roof = TUNNEL_ROOF;
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

        BlockState rail = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.NORTH_SOUTH);
        List<Integer> supports = new ArrayList<>();
        for (int z = between(random, 1, 3); z < TUNNEL_LENGTH - 1; z += between(random, 4, 6)) {
            supports.add(z);
            support(g, false, z, MID_TUNNEL, TUNNEL_WALL, lit(random));
        }
        for (int z = 0; z < TUNNEL_LENGTH; z++) {
            boolean clear = !supports.contains(z);
            g.put(MID_TUNNEL, TUNNEL_WALL, z, rail);
            for (int x = MID_TUNNEL - 1; x <= MID_TUNNEL + 1; x++) {
                g.put(x, floor, z, bed(random, z, Direction.Axis.X));
            }
            for (int x : new int[] { lo, hi }) {
                if (clear && random.nextInt(12) == 0) {
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
                if (supports.contains(z)) {
                    continue;
                }
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
        // The ends stay open to the doorways, and the rails run on into the rooms.
        for (int x = lo; x <= hi; x++) {
            for (int y = TUNNEL_WALL; y <= roof; y++) {
                for (int z : new int[] { 0, TUNNEL_LENGTH - 1 }) {
                    g.setType(x, y, z, AIR);
                }
            }
        }
        g.put(MID_TUNNEL, TUNNEL_WALL, 0, rail);
        g.put(MID_TUNNEL, TUNNEL_WALL, TUNNEL_LENGTH - 1, rail);
        return g;
    }

    // --- The rules ---

    /**
     * What's wrong with a carved room or corridor, if anything. Both: every vein of ore shows, or touches one that
     * does, and every rail has something under it. Rooms: solid all round but their doorways, a landing inside each, a
     * way to walk from every doorway to every other, water only in flooded rooms, spawners only in infested ones.
     * Corridors: solid all round, open at both ends, with a clear walkway.
     */
    public static List<String> problems(MinesGrid g, Kind kind) {
        List<String> problems = new ArrayList<>();
        for (List<int[]> vein : buriedVeins(g)) {
            int[] c = vein.get(0);
            problems.add("a buried vein of " + vein.size() + " at " + c[0] + "," + c[1] + "," + c[2]);
        }
        for (int x = 0; x < g.sx; x++) {
            for (int y = 0; y < g.sy; y++) {
                for (int z = 0; z < g.sz; z++) {
                    if (g.block(x, y, z).is(Blocks.RAIL) && !g.solid(x, y - 1, z)) {
                        problems.add("a rail with nothing under it at " + x + "," + y + "," + z);
                    }
                }
            }
        }
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
                    if (doorway ? !g.clear(x, y, z) : !g.rock(x, y, z)) {
                        problems.add((doorway ? "a blocked doorway at " : "a hole in the outside at ") + x + "," + y + "," + z);
                    }
                }
            }
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            for (int depth = 1; depth <= LANDING_DEPTH; depth++) {
                for (int across = LANDING_START; across < LANDING_START + LANDING_WIDTH; across++) {
                    int[] p = fromWall(side, across, depth);
                    if (!g.solid(p[0], WALK_Y, p[1])) {
                        problems.add("no floor in the " + side + " landing at " + p[0] + "," + p[1]);
                    }
                    for (int y = DOOR_BOTTOM_Y; y < DOOR_BOTTOM_Y + DOOR_HEIGHT; y++) {
                        if (!g.clear(p[0], y, p[1])) {
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
        int roof = TUNNEL_ROOF;
        for (int x = 0; x < g.sx; x++) {
            for (int y = 0; y < g.sy; y++) {
                for (int z = 0; z < g.sz; z++) {
                    boolean passage = x >= lo && x <= hi && y >= TUNNEL_WALL && y <= roof;
                    if (!passage && (g.open(x, y, z) || (g.type(x, y, z) == FIXED && g.passable(x, y, z)))) {
                        problems.add("a hole in the rock at " + x + "," + y + "," + z);
                    }
                    if (passage && (z == 0 || z == g.sz - 1) && !g.clear(x, y, z)) {
                        problems.add("a blocked end at " + x + "," + y + "," + z);
                    }
                    // Clear to walk down the middle, up to the lanterns hung under the beams.
                    if (x == MID_TUNNEL && y >= TUNNEL_WALL && y < roof - 1 && !g.passable(x, y, z)) {
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
