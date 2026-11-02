package io.github.ranger3462.deepmines.datagen;

import static io.github.ranger3462.dungeondelving.dungeon.RoomSpec.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import com.google.common.hash.Hashing;

import io.github.ranger3462.deepmines.DeepMines;
import io.github.ranger3462.deepmines.MinesFloors;
import net.minecraft.core.Direction;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Generates the Mines' pieces into {@code data/deepmines/structure/mines/}, to Dungeon Delving's
 * {@link io.github.ranger3462.dungeondelving.dungeon.RoomSpec} sizes:
 * <ul>
 * <li>Caves ({@code common_cave_*}): rough rock carved into a chamber of overlapping hollows, with a passage in from
 * every doorway, glow lichen on the floor and cobwebs in the high corners. Each has its own seed and shape.</li>
 * <li>The arrival cave ({@code start_arrival}): a floor's first cave, with a lit clearing in the middle for the ladder up
 * (and, on floor 1, where players come in).</li>
 * <li>The ladder shaft ({@code boss_ladder_shaft}): a floor's farthest cave, with a timber headframe over the ladder
 * down. Dungeon Delving makes a layout's farthest room its "boss" room, hence the prefix.</li>
 * <li>The corridor ({@code tunnel_mineshaft}): a timbered mineshaft in the style of YUNG's Better Mineshafts, with plank
 * flooring, log legs and beams, torches and cobwebs.</li>
 * <li>The cap ({@code cap_rock}): a plug of rough rock.</li>
 * </ul>
 * The centers of the arrival and ladder caves are kept clear: the floor code puts the ladders there. Ore and mob
 * markers come later (MINE-7, MINE-8). Run with {@code ./gradlew runData}; the output is committed. Only Dungeon
 * Delving's size constants are used here (copied in when compiling), so data generation runs without it.
 */
public class MinePieceProvider implements DataProvider {
    private static final int MAX = ROOM_SIZE - 1;
    private static final int MID = ROOM_SIZE / 2;
    private static final int TOP = ROOM_HEIGHT - 1;

    private final PackOutput.PathProvider structures;

    public MinePieceProvider(PackOutput output) {
        this.structures = output.createPathProvider(PackOutput.Target.DATA_PACK, "structure");
    }

    /** Every piece, keyed by file name. */
    public static Map<String, PieceBuilder> pieces() {
        Map<String, PieceBuilder> pieces = new LinkedHashMap<>();
        pieces.put("common_cave_hollow", cave(new Random(101), 11, 5));
        pieces.put("common_cave_long", cave(new Random(202), 9, 7));
        pieces.put("common_cave_tall", cave(new Random(303), 10, 6));
        pieces.put("common_cave_tight", cave(new Random(404), 7, 9));
        pieces.put("start_arrival", arrival());
        pieces.put("boss_ladder_shaft", ladderShaft());
        pieces.put("tunnel_mineshaft", mineshaft(new Random(505)));
        pieces.put("cap_rock", cap(new Random(606)));
        return pieces;
    }

    @Override
    public CompletableFuture<?> run(CachedOutput output) {
        return CompletableFuture.allOf(pieces().entrySet().stream().map(entry -> CompletableFuture.runAsync(() -> {
            Path path = structures.file(DeepMines.id(MinesFloors.PIECES + "/" + entry.getKey()), "nbt");
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

    // --- Caves ---

    /**
     * A cave: solid rock, hollowed out by a central chamber of the given radius, {@code hollows} smaller overlapping
     * hollows around it, and a passage in from each doorway. Floor, ceiling and walls stay solid (doorways aside), as the
     * piece rules need.
     */
    private static PieceBuilder cave(Random random, int chamberRadius, int hollows) {
        PieceBuilder room = new PieceBuilder(ROOM_SIZE, ROOM_HEIGHT, ROOM_SIZE);
        boolean[][][] open = new boolean[ROOM_SIZE][ROOM_HEIGHT][ROOM_SIZE];

        hollow(open, MID, 4, MID, chamberRadius, 5, chamberRadius);
        for (int i = 0; i < hollows; i++) {
            int r = 3 + random.nextInt(4);
            hollow(open, 5 + random.nextInt(ROOM_SIZE - 10), 2 + random.nextInt(6), 5 + random.nextInt(ROOM_SIZE - 10), r, 2 + random.nextInt(3), r);
        }
        // A passage in from each doorway: the doorway's width and height, wobbling a little as it goes.
        for (Direction side : Direction.Plane.HORIZONTAL) {
            int wobble = 0;
            for (int depth = 0; depth <= MID; depth++) {
                if (depth > 3 && random.nextInt(4) == 0) {
                    wobble = Math.max(-2, Math.min(2, wobble + random.nextInt(3) - 1));
                }
                for (int across = DOOR_START + wobble; across < DOOR_START + DOOR_WIDTH + wobble; across++) {
                    for (int y = DOOR_BOTTOM_Y; y < DOOR_BOTTOM_Y + DOOR_HEIGHT; y++) {
                        int[] p = sidePos(side, across, depth);
                        // The wall itself only opens at the doorway, so the frame around it stays solid.
                        if (depth > 0 || (inDoorSpan(across) && inDoorHeight(y))) {
                            open[p[0]][y][p[1]] = true;
                        }
                    }
                }
            }
        }

        for (int x = 0; x < ROOM_SIZE; x++) {
            for (int y = 0; y < ROOM_HEIGHT; y++) {
                for (int z = 0; z < ROOM_SIZE; z++) {
                    room.set(x, y, z, open[x][y][z] ? Blocks.AIR.defaultBlockState() : rock(random));
                }
            }
        }
        decorate(room, open, random);
        return room;
    }

    /** Opens an ellipsoid, kept inside the walls, floor and ceiling. */
    private static void hollow(boolean[][][] open, int cx, int cy, int cz, int rx, int ry, int rz) {
        for (int x = 1; x < MAX; x++) {
            for (int y = 1; y < TOP; y++) {
                for (int z = 1; z < MAX; z++) {
                    double dx = (x - cx) / (double) rx;
                    double dy = (y - cy) / (double) ry;
                    double dz = (z - cz) / (double) rz;
                    if (dx * dx + dy * dy + dz * dz <= 1.0) {
                        open[x][y][z] = true;
                    }
                }
            }
        }
    }

    /** A position {@code depth} blocks in from a wall, {@code across} along it. */
    private static int[] sidePos(Direction side, int across, int depth) {
        return switch (side) {
            case NORTH -> new int[] { across, depth };
            case SOUTH -> new int[] { across, MAX - depth };
            case WEST -> new int[] { depth, across };
            default -> new int[] { MAX - depth, across };
        };
    }

    /** Glow lichen on the floor, cobwebs in high corners, and the odd pile of gravel against a wall. */
    private static void decorate(PieceBuilder room, boolean[][][] open, Random random) {
        BlockState lichen = Blocks.GLOW_LICHEN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true);
        for (int x = 1; x < MAX; x++) {
            for (int z = 1; z < MAX; z++) {
                for (int y = 1; y < TOP; y++) {
                    if (!open[x][y][z]) {
                        continue;
                    }
                    boolean onFloor = !open[x][y - 1][z];
                    boolean underRoof = !open[x][y + 1][z];
                    if (onFloor && !inLane(x, z) && random.nextInt(14) == 0) {
                        room.set(x, y, z, lichen);
                    } else if (onFloor && !inLane(x, z) && againstWall(open, x, y, z) && random.nextInt(10) == 0) {
                        room.set(x, y, z, Blocks.GRAVEL);
                    } else if (underRoof && y > DOOR_BOTTOM_Y + DOOR_HEIGHT && againstWall(open, x, y, z) && random.nextInt(6) == 0) {
                        room.set(x, y, z, Blocks.COBWEB);
                    }
                }
            }
        }
    }

    // Dungeon Delving's RoomSpec has these too, but calling them would load it; its constants are copied in at compile time.
    private static boolean inDoorSpan(int i) {
        return i >= DOOR_START && i < DOOR_START + DOOR_WIDTH;
    }

    private static boolean inDoorHeight(int y) {
        return y >= DOOR_BOTTOM_Y && y < DOOR_BOTTOM_Y + DOOR_HEIGHT;
    }

    /** Whether a spot is on the straight line in from a doorway, kept free of clutter. */
    private static boolean inLane(int x, int z) {
        return inDoorSpan(x) || inDoorSpan(z);
    }

    private static boolean againstWall(boolean[][][] open, int x, int y, int z) {
        return !open[x - 1][y][z] || !open[x + 1][y][z] || !open[x][y][z - 1] || !open[x][y][z + 1];
    }

    /** Rough rock: mostly stone, with andesite, tuff, cobblestone and a little diorite and granite. */
    private static BlockState rock(Random random) {
        int roll = random.nextInt(100);
        Block block = roll < 60 ? Blocks.STONE
                : roll < 72 ? Blocks.ANDESITE
                : roll < 82 ? Blocks.TUFF
                : roll < 90 ? Blocks.COBBLESTONE
                : roll < 95 ? Blocks.DIORITE
                : Blocks.GRANITE;
        return block.defaultBlockState();
    }

    /** Clears a flat-floored box of air (inclusive), keeping the floor beneath it solid. */
    private static void clearing(PieceBuilder room, Random random, int x1, int z1, int x2, int z2, int height) {
        room.fill(x1, 1, z1, x2, height, z2, Blocks.AIR);
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                room.set(x, 0, z, rock(random));
            }
        }
    }

    /** The floor's first cave: a lit clearing in the middle, where the ladder up stands and players arrive. */
    private static PieceBuilder arrival() {
        Random random = new Random(707);
        PieceBuilder room = cave(random, 11, 4);
        clearing(room, random, MID - 5, MID - 5, MID + 5, MID + 5, 6);
        for (int[] c : new int[][] { { MID - 5, MID - 5 }, { MID + 5, MID - 5 }, { MID - 5, MID + 5 }, { MID + 5, MID + 5 } }) {
            room.set(c[0], 1, c[1], Blocks.LANTERN);
        }
        // A few supplies against the clearing's edge, clear of the doorway lanes.
        room.set(MID - 4, 1, MID - 5, Blocks.BARREL);
        room.set(MID - 3, 1, MID - 5, Blocks.BARREL);
        room.set(MID - 4, 2, MID - 5, Blocks.BARREL);
        room.set(MID + 5, 1, MID - 4, Blocks.CRAFTING_TABLE);
        return room;
    }

    /** The floor's farthest cave: a timber headframe over the middle, where the ladder down goes. */
    private static PieceBuilder ladderShaft() {
        Random random = new Random(808);
        PieceBuilder room = cave(random, 11, 4);
        clearing(room, random, MID - 6, MID - 6, MID + 6, MID + 6, 9);
        BlockState post = Blocks.STRIPPED_OAK_LOG.defaultBlockState();
        BlockState beamX = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        BlockState beamZ = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
        int lo = MID - 4;
        int hi = MID + 4;
        int beamY = 8;
        for (int x : new int[] { lo, hi }) {
            for (int z : new int[] { lo, hi }) {
                for (int y = 1; y < beamY; y++) {
                    room.set(x, y, z, post);
                }
            }
        }
        for (int i = lo; i <= hi; i++) {
            room.set(i, beamY, lo, beamX);
            room.set(i, beamY, hi, beamX);
            room.set(lo, beamY, i, beamZ);
            room.set(hi, beamY, i, beamZ);
            room.set(i, beamY, MID, beamX); // The crossbeam the hoist hangs from.
        }
        for (int y = 5; y < beamY; y++) {
            room.set(MID, y, MID, Blocks.CHAIN);
        }
        for (int[] c : new int[][] { { lo, lo }, { hi, lo }, { lo, hi }, { hi, hi } }) {
            room.set(c[0] + (c[0] == lo ? 1 : -1), 1, c[1], Blocks.LANTERN);
        }
        return room;
    }

    // --- Corridor and cap ---

    /**
     * A timbered mineshaft: rock all round, a 7 x 7 passage with a plank floor, and every 6 blocks a set of supports
     * (log legs against the walls and a beam overhead) with a torch on one leg. The walkway in the middle stays clear.
     */
    private static PieceBuilder mineshaft(Random random) {
        PieceBuilder tunnel = new PieceBuilder(TUNNEL_WIDTH, TUNNEL_HEIGHT, TUNNEL_LENGTH);
        int maxX = TUNNEL_WIDTH - 1;
        int roof = TUNNEL_HEIGHT - 2;
        for (int x = 0; x < TUNNEL_WIDTH; x++) {
            for (int y = 0; y < TUNNEL_HEIGHT; y++) {
                for (int z = 0; z < TUNNEL_LENGTH; z++) {
                    tunnel.set(x, y, z, rock(random));
                }
            }
        }
        tunnel.fill(1, 1, 0, maxX - 1, roof, TUNNEL_LENGTH - 1, Blocks.AIR);
        for (int x = 1; x < maxX; x++) {
            for (int z = 0; z < TUNNEL_LENGTH; z++) {
                int roll = random.nextInt(10);
                tunnel.set(x, 0, z, roll < 8 ? Blocks.OAK_PLANKS : roll < 9 ? Blocks.COBBLESTONE : Blocks.COARSE_DIRT);
            }
        }

        BlockState leg = Blocks.OAK_LOG.defaultBlockState();
        BlockState beam = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        boolean torchLeft = true;
        for (int z = 3; z < TUNNEL_LENGTH; z += 6) {
            for (int y = 1; y < roof; y++) {
                tunnel.set(1, y, z, leg);
                tunnel.set(maxX - 1, y, z, leg);
            }
            for (int x = 1; x < maxX; x++) {
                tunnel.set(x, roof, z, beam);
            }
            // A torch on the side of one leg, facing along the corridor: beside the walkway, never in it.
            if (z + 1 < TUNNEL_LENGTH) {
                tunnel.set(torchLeft ? 1 : maxX - 1, 4, z + 1, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.SOUTH));
            }
            torchLeft = !torchLeft;
        }
        // Cobwebs up in the corners between supports, and gravel spilled along the walls.
        for (int z = 0; z < TUNNEL_LENGTH; z++) {
            for (int x : new int[] { 1, maxX - 1 }) {
                if (betweenSupports(z) && random.nextInt(4) == 0) {
                    tunnel.set(x, roof, z, Blocks.COBWEB);
                }
                if (betweenSupports(z) && random.nextInt(7) == 0) {
                    tunnel.set(x, 1, z, Blocks.GRAVEL);
                }
            }
        }
        return tunnel;
    }

    /** Whether a slice of corridor is clear of supports (every 6 blocks from z 3) and their torches (just after). */
    private static boolean betweenSupports(int z) {
        return (z - 3) % 6 != 0 && (z - 4) % 6 != 0;
    }

    /** A plug of rough rock for a doorway with no corridor. */
    private static PieceBuilder cap(Random random) {
        PieceBuilder cap = new PieceBuilder(CAP_WIDTH, CAP_HEIGHT, CAP_DEPTH);
        for (int x = 0; x < CAP_WIDTH; x++) {
            for (int y = 0; y < CAP_HEIGHT; y++) {
                cap.set(x, y, 0, rock(random));
            }
        }
        return cap;
    }
}
