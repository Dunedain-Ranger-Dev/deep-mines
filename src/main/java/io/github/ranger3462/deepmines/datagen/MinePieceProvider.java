package io.github.ranger3462.deepmines.datagen;

import static io.github.ranger3462.deepmines.MinesSpec.*;

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
import io.github.ranger3462.deepmines.MinesGrid;
import io.github.ranger3462.deepmines.MinesPieces;
import io.github.ranger3462.deepmines.RoomCarver;
import net.minecraft.core.Direction;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;

/**
 * Generates the Mines' fixed pieces into {@code data/deepmines/structure/mines/}, to
 * {@link io.github.ranger3462.deepmines.MinesSpec}'s sizes: the ones that are the same everywhere. Rooms and corridors
 * aren't here; they're carved fresh for every floor ({@link RoomCarver}).
 * <ul>
 * <li>The cap ({@code cap_}): plain rock, to plug a doorway with no corridor.</li>
 * <li>The start hall ({@code start_}): the start floor's one room, the same in every Mines: a dwarven hall with a dais
 * in the middle (for the dwarf who'll give out quests), workshops along the walls, and the ladders down to floor 1 and
 * up out of the Mines.</li>
 * </ul>
 * Run with {@code ./gradlew runData}; the output is committed.
 */
public class MinePieceProvider implements DataProvider {
    private static final int MAX = ROOM_SIZE - 1;

    private final PackOutput.PathProvider structures;

    public MinePieceProvider(PackOutput output) {
        this.structures = output.createPathProvider(PackOutput.Target.DATA_PACK, "structure");
    }

    /** Every piece, keyed by file name. */
    public static Map<String, PieceBuilder> pieces() {
        Map<String, PieceBuilder> pieces = new LinkedHashMap<>();
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

    /** A carved grid as a piece to save. */
    private static PieceBuilder build(MinesGrid g) {
        PieceBuilder piece = new PieceBuilder(g.sx, g.sy, g.sz);
        for (int x = 0; x < g.sx; x++) {
            for (int y = 0; y < g.sy; y++) {
                for (int z = 0; z < g.sz; z++) {
                    piece.set(x, y, z, g.block(x, y, z));
                }
            }
        }
        return piece;
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
        MinesGrid g = RoomCarver.room(random);
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
                        g.setType(x, y, z, MinesGrid.AIR);
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
        MinesGrid.forBox(MID - 3, START_FLOOR_Y, MID - 3, MID + 3, START_FLOOR_Y, MID + 3, (x, y, z) -> g.put(x, y, z, Blocks.POLISHED_GRANITE));
        MinesGrid.forBox(MID - 1, START_FLOOR_Y, MID - 1, MID + 1, START_FLOOR_Y, MID + 1, (x, y, z) -> g.put(x, y, z, chiseled));
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
            g.setType(spot[0], spot[1], spot[2], MinesGrid.AIR);
            g.setType(spot[0], spot[1] + 1, spot[2], MinesGrid.AIR);
        }
        RoomCarver.sealOutside(g);
        return build(g);
    }
}
