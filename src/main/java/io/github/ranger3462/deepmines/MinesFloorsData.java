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
 * Which of the Mines' floors have been built, and where their ladders are, for each Mines in the dimension (one shared,
 * or one per team with Team Mines on: see {@link MinesTeams}), told apart by where their floor 1 is (their base). Saved with the level, so a reset (which
 * wipes the Mines' folder) forgets every floor along with it. Floors still being built are only kept until the server
 * stops: one left half built is built again, from the start, the next time someone reaches it.
 */
public class MinesFloorsData extends SavedData {
    private static final String NAME = DeepMines.MODID + "_floors";

    /**
     * A built floor: where its ladder up and ladder down stand, and where players arrive beside each (coming down from
     * above, or up from below). Floor 0 is the start floor, whose ladder up leads out of the Mines.
     */
    public record Floor(int number, BlockPos ladderUp, BlockPos ladderDown, BlockPos arrivalFromAbove, BlockPos arrivalFromBelow) {}

    private final Map<BlockPos, Map<Integer, Floor>> floors = new HashMap<>();
    private final Map<BlockPos, Map<Integer, Floor>> building = new HashMap<>();

    public static MinesFloorsData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(MinesFloorsData::new, MinesFloorsData::load, null), NAME);
    }

    /** A built floor of the Mines at {@code base}. */
    public Optional<Floor> floor(BlockPos base, int number) {
        return Optional.ofNullable(floors.getOrDefault(base, Map.of()).get(number));
    }

    /** A floor that's built, or being built. */
    public Optional<Floor> known(BlockPos base, int number) {
        return floor(base, number).or(() -> Optional.ofNullable(building.getOrDefault(base, Map.of()).get(number)));
    }

    public void put(BlockPos base, Floor floor) {
        building.getOrDefault(base, new HashMap<>()).remove(floor.number());
        floors.computeIfAbsent(base.immutable(), b -> new HashMap<>()).put(floor.number(), floor);
        setDirty();
    }

    /** Notes a floor whose building has started, until it's {@link #put} once done. */
    public void startBuilding(BlockPos base, Floor floor) {
        building.computeIfAbsent(base.immutable(), b -> new HashMap<>()).put(floor.number(), floor);
    }

    /** Forgets every floor (for tests; a real reset wipes the saved file). */
    public void clear() {
        floors.clear();
        building.clear();
        setDirty();
    }

    private static MinesFloorsData load(CompoundTag tag, HolderLookup.Provider registries) {
        MinesFloorsData data = new MinesFloorsData();
        for (Tag entry : tag.getList("floors", Tag.TAG_COMPOUND)) {
            CompoundTag floor = (CompoundTag) entry;
            Optional<BlockPos> up = NbtUtils.readBlockPos(floor, "ladder_up");
            Optional<BlockPos> down = NbtUtils.readBlockPos(floor, "ladder_down");
            Optional<BlockPos> fromAbove = NbtUtils.readBlockPos(floor, "arrival_from_above");
            Optional<BlockPos> fromBelow = NbtUtils.readBlockPos(floor, "arrival_from_below");
            // Floors saved before the arrivals were (from 0.1.0's smaller floors) are forgotten, and built again.
            if (up.isPresent() && down.isPresent() && fromAbove.isPresent() && fromBelow.isPresent()) {
                int number = floor.getInt("number");
                // Floors saved before Team Mines are the shared Mines'.
                BlockPos base = NbtUtils.readBlockPos(floor, "base").orElse(MinesFloors.BASE);
                data.floors.computeIfAbsent(base, b -> new HashMap<>()).put(number, new Floor(number, up.get(), down.get(), fromAbove.get(), fromBelow.get()));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        floors.forEach((base, built) -> built.values().forEach(floor -> {
            CompoundTag entry = new CompoundTag();
            entry.put("base", NbtUtils.writeBlockPos(base));
            entry.putInt("number", floor.number());
            entry.put("ladder_up", NbtUtils.writeBlockPos(floor.ladderUp()));
            entry.put("ladder_down", NbtUtils.writeBlockPos(floor.ladderDown()));
            entry.put("arrival_from_above", NbtUtils.writeBlockPos(floor.arrivalFromAbove()));
            entry.put("arrival_from_below", NbtUtils.writeBlockPos(floor.arrivalFromBelow()));
            list.add(entry);
        }));
        tag.put("floors", list);
        return tag;
    }
}
