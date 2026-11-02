package io.github.ranger3462.deepmines.datagen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A simple box of blocks that can be saved in the vanilla structure (.nbt) format, the same format a structure block
 * produces (a copy of Dungeon Delving's, so data generation doesn't need Dungeon Delving loaded). Every position starts as air, so placing the piece clears whatever was there, unless it is marked as
 * untouched with {@link #untouched}, which leaves the world as it was (like structure void).
 */
public class PieceBuilder {
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final BlockState[] blocks;

    public PieceBuilder(int sizeX, int sizeY, int sizeZ) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.blocks = new BlockState[sizeX * sizeY * sizeZ];
        fill(0, 0, 0, sizeX - 1, sizeY - 1, sizeZ - 1, Blocks.AIR);
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public PieceBuilder set(int x, int y, int z, Block block) {
        return set(x, y, z, block.defaultBlockState());
    }

    public PieceBuilder set(int x, int y, int z, BlockState state) {
        blocks[index(x, y, z)] = state;
        return this;
    }

    /** Marks the inclusive box between the two corners as untouched: placing the piece leaves those blocks alone. */
    public PieceBuilder untouched(int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    blocks[index(x, y, z)] = null;
                }
            }
        }
        return this;
    }

    /** Fills the inclusive box between the two corners. */
    public PieceBuilder fill(int x1, int y1, int z1, int x2, int y2, int z2, Block block) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    set(x, y, z, block);
                }
            }
        }
        return this;
    }

    public CompoundTag toNbt() {
        Map<BlockState, Integer> palette = new LinkedHashMap<>();
        ListTag blockList = new ListTag();
        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    BlockState state = blocks[index(x, y, z)];
                    if (state == null) {
                        continue; // Untouched positions are simply left out of the structure.
                    }
                    int stateId = palette.computeIfAbsent(state, s -> palette.size());
                    CompoundTag entry = new CompoundTag();
                    entry.put("pos", intList(x, y, z));
                    entry.putInt("state", stateId);
                    blockList.add(entry);
                }
            }
        }

        ListTag paletteList = new ListTag();
        List<BlockState> states = new ArrayList<>(palette.keySet());
        for (BlockState state : states) {
            paletteList.add(NbtUtils.writeBlockState(state));
        }

        CompoundTag tag = new CompoundTag();
        tag.put("size", intList(sizeX, sizeY, sizeZ));
        tag.put("palette", paletteList);
        tag.put("blocks", blockList);
        tag.put("entities", new ListTag());
        return NbtUtils.addCurrentDataVersion(tag);
    }

    private int index(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            throw new IndexOutOfBoundsException("(" + x + ", " + y + ", " + z + ") is outside " + sizeX + "x" + sizeY + "x" + sizeZ);
        }
        return (y * sizeZ + z) * sizeX + x;
    }

    private static ListTag intList(int a, int b, int c) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(a));
        list.add(IntTag.valueOf(b));
        list.add(IntTag.valueOf(c));
        return list;
    }
}
