package io.github.ranger3462.deepmines;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Which of the Mines' floors have been built, and where their ladders are. Saved with the level, so a reset (which
 * wipes the Mines' folder) forgets every floor along with it.
 */
public class MinesFloorsData extends SavedData {
    private static final String NAME = DeepMines.MODID + "_floors";

    /**
     * A built floor: where its ladder up and ladder down stand (the ladder up is absent on floor 1), and where players
     * arrive, next to each.
     */
    public record Floor(int number, BlockPos ladderUp, BlockPos ladderDown) {
        public BlockPos arrivalFromAbove() {
            return ladderUp.east(2);
        }

        public BlockPos arrivalFromBelow() {
            return ladderDown.east(2);
        }
    }

    private final Map<Integer, Floor> floors = new HashMap<>();

    public static MinesFloorsData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(MinesFloorsData::new, MinesFloorsData::load, null), NAME);
    }

    public Optional<Floor> floor(int number) {
        return Optional.ofNullable(floors.get(number));
    }

    public void put(Floor floor) {
        floors.put(floor.number(), floor);
        setDirty();
    }

    /** Forgets every floor (for tests; a real reset wipes the saved file). */
    public void clear() {
        floors.clear();
        setDirty();
    }

    private static MinesFloorsData load(CompoundTag tag, HolderLookup.Provider registries) {
        MinesFloorsData data = new MinesFloorsData();
        for (Tag entry : tag.getList("floors", Tag.TAG_COMPOUND)) {
            CompoundTag floor = (CompoundTag) entry;
            Optional<BlockPos> up = NbtUtils.readBlockPos(floor, "ladder_up");
            Optional<BlockPos> down = NbtUtils.readBlockPos(floor, "ladder_down");
            if (up.isPresent() && down.isPresent()) {
                data.floors.put(floor.getInt("number"), new Floor(floor.getInt("number"), up.get(), down.get()));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        floors.values().forEach(floor -> {
            CompoundTag entry = new CompoundTag();
            entry.putInt("number", floor.number());
            entry.put("ladder_up", NbtUtils.writeBlockPos(floor.ladderUp()));
            entry.put("ladder_down", NbtUtils.writeBlockPos(floor.ladderDown()));
            list.add(entry);
        });
        tag.put("floors", list);
        return tag;
    }
}
