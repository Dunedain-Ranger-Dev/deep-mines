package io.github.ranger3462.deepmines;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.UnaryOperator;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A piece of the Mines being carved: every position is rock (stone with patches of andesite, diorite and granite), air,
 * water, an ore spot, or a fixed block (timber, rails, decoration). Ore only goes into rock, and never in the outer
 * layer. {@link RoomCarver} carves rooms and corridors in one when a floor is built, then {@link #place} puts it in
 * the world.
 */
public final class MinesGrid {
    public static final byte ROCK = 0;
    public static final byte AIR = 1;
    public static final byte WATER = 2;
    public static final byte ORE = 3;
    public static final byte FIXED = 4;

    public final int sx;
    public final int sy;
    public final int sz;
    private final byte[] type;
    private final BlockState[] state;

    /** What a placed piece left for the builder to finish: its ore spots and spawners, in the world. */
    public record Placed(List<BlockPos> oreSpots, List<BlockPos> spawners) {}

    public MinesGrid(int sx, int sy, int sz, Random random, int patches) {
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

    public interface Cell {
        void at(int x, int y, int z);
    }

    public static void forBox(int x1, int y1, int z1, int x2, int y2, int z2, Cell cell) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    cell.at(x, y, z);
                }
            }
        }
    }

    public static int between(Random random, int lo, int hi) {
        return lo + random.nextInt(hi - lo + 1);
    }

    public boolean in(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < sx && y < sy && z < sz;
    }

    public boolean inside(int x, int y, int z) {
        return x > 0 && y > 0 && z > 0 && x < sx - 1 && y < sy - 1 && z < sz - 1;
    }

    public int index(int x, int y, int z) {
        return (y * sz + z) * sx + x;
    }

    /** The {@code {x, y, z}} of an {@link #index}. */
    public int[] position(int index) {
        return new int[] { index % sx, index / (sx * sz), (index / sx) % sz };
    }

    public byte type(int x, int y, int z) {
        return in(x, y, z) ? type[index(x, y, z)] : ROCK;
    }

    public boolean air(int x, int y, int z) {
        return type(x, y, z) == AIR;
    }

    public boolean open(int x, int y, int z) {
        byte t = type(x, y, z);
        return t == AIR || t == WATER;
    }

    public boolean rock(int x, int y, int z) {
        return in(x, y, z) && type[index(x, y, z)] == ROCK;
    }

    /** Rock or ore: solid ground that wasn't put there on purpose. */
    public boolean solidRock(int x, int y, int z) {
        byte t = type(x, y, z);
        return t == ROCK || t == ORE;
    }

    /** Opens a spot to air, unless it's on the outside or has something put there on purpose. */
    public void carve(int x, int y, int z) {
        if (inside(x, y, z) && type[index(x, y, z)] != FIXED) {
            type[index(x, y, z)] = AIR;
        }
    }

    public void put(int x, int y, int z, BlockState block) {
        if (in(x, y, z)) {
            type[index(x, y, z)] = FIXED;
            state[index(x, y, z)] = block;
        }
    }

    public void put(int x, int y, int z, Block block) {
        put(x, y, z, block.defaultBlockState());
    }

    /** Makes a spot rock, air, water or ore, whatever it was; rock that had something put there goes back to stone. */
    public void setType(int x, int y, int z, byte t) {
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
    public boolean oreable(int x, int y, int z) {
        return rock(x, y, z) && inside(x, y, z);
    }

    /** The block at a spot; an ore spot is {@code deepmines:ore_spot}. Outside the grid, stone. */
    public BlockState block(int x, int y, int z) {
        if (!in(x, y, z)) {
            return Blocks.STONE.defaultBlockState();
        }
        int i = index(x, y, z);
        return switch (type[i]) {
            case AIR -> Blocks.AIR.defaultBlockState();
            case WATER -> Blocks.WATER.defaultBlockState();
            case ORE -> ModBlocks.ORE_SPOT.get().defaultBlockState();
            default -> state[i];
        };
    }

    /** Whether someone can be in a spot: open, or something with nothing to bump into (rails, cobwebs, torches). */
    public boolean passable(int x, int y, int z) {
        byte t = type(x, y, z);
        if (t == AIR || t == WATER) {
            return true;
        }
        return t == FIXED && (climbable(x, y, z) || block(x, y, z).getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty());
    }

    /** Whether something stands firm here: anything that isn't {@link #passable} (out of the grid is rock). */
    public boolean solid(int x, int y, int z) {
        return !passable(x, y, z);
    }

    /** Whether a spot is open and dry: air, or something with nothing to bump into (a rail, say). */
    public boolean clear(int x, int y, int z) {
        byte t = type(x, y, z);
        return t == AIR || (t == FIXED && passable(x, y, z) && !climbable(x, y, z));
    }

    public boolean climbable(int x, int y, int z) {
        return type(x, y, z) == FIXED && block(x, y, z).is(Blocks.LADDER);
    }

    /** How many spots are of a type. */
    public int count(byte t) {
        int n = 0;
        for (byte b : type) {
            n += b == t ? 1 : 0;
        }
        return n;
    }

    /** How many spots hold a block. */
    public int count(Block block) {
        int n = 0;
        for (int i = 0; i < type.length; i++) {
            n += type[i] == FIXED && state[i].is(block) ? 1 : 0;
        }
        return n;
    }

    /**
     * Puts the piece in the world with its minimum corner at {@code min}, unturned or turned a quarter clockwise (so a
     * corridor running along z runs along x), with {@code rock} applied to every block (deepslate on deep floors). Ore
     * spots go in as rock for the builder to fill in.
     */
    public Placed place(ServerLevel level, BlockPos min, Rotation rotation, UnaryOperator<BlockState> rock) {
        boolean turned = rotation == Rotation.CLOCKWISE_90;
        if (!turned && rotation != Rotation.NONE) {
            throw new IllegalArgumentException("Mines pieces are only placed unturned or turned a quarter: " + rotation);
        }
        List<BlockPos> ore = new ArrayList<>();
        List<BlockPos> spawners = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState stone = rock.apply(Blocks.STONE.defaultBlockState());
        // Bottom up, so what hangs or stands on something goes in after it. Every block is already in its final shape,
        // so there's no need to update neighbors.
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    if (turned) {
                        pos.set(min.getX() + sz - 1 - z, min.getY() + y, min.getZ() + x);
                    } else {
                        pos.set(min.getX() + x, min.getY() + y, min.getZ() + z);
                    }
                    int i = index(x, y, z);
                    BlockState block;
                    if (type[i] == ORE) {
                        ore.add(pos.immutable());
                        block = stone;
                    } else {
                        block = rock.apply(block(x, y, z));
                        if (turned) {
                            block = block.rotate(Rotation.CLOCKWISE_90);
                        }
                        if (block.is(Blocks.SPAWNER)) {
                            spawners.add(pos.immutable());
                        }
                    }
                    level.setBlock(pos, block, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        }
        return new Placed(ore, spawners);
    }
}
