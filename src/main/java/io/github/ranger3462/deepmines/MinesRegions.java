package io.github.ranger3462.deepmines;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Which stretch of the Mines dimension each team's Mines are in (Team Mines, {@link MinesTeams}). Region 0 is the shared
 * Mines; each team (or player on their own) gets the next free region the first time one of them goes in. Saved with
 * the Mines' own level data, so the reset hands them out afresh.
 */
public class MinesRegions extends SavedData {
    private static final String NAME = DeepMines.MODID + "_regions";

    private final Map<UUID, Integer> regions = new HashMap<>();
    private int next = 1;

    public static MinesRegions get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(MinesRegions::new, MinesRegions::load, null), NAME);
    }

    public static String fileName() {
        return NAME + ".dat";
    }

    /** The region of a team's (or a player's own) Mines, handed out the first time it's asked for. */
    public int region(UUID owner) {
        Integer region = regions.get(owner);
        if (region == null) {
            region = next++;
            regions.put(owner, region);
            setDirty();
        }
        return region;
    }

    /** Forgets every region (for tests; a real reset wipes the saved file). */
    public void clear() {
        regions.clear();
        next = 1;
        setDirty();
    }

    private static MinesRegions load(CompoundTag tag, HolderLookup.Provider registries) {
        MinesRegions data = new MinesRegions();
        CompoundTag saved = tag.getCompound("regions");
        for (String key : saved.getAllKeys()) {
            try {
                data.regions.put(UUID.fromString(key), saved.getInt(key));
            } catch (IllegalArgumentException e) {
                DeepMines.LOGGER.warn("Skipping a Mines region saved for {}, which isn't a UUID", key);
            }
        }
        data.next = Math.max(1, tag.getInt("next"));
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag saved = new CompoundTag();
        regions.forEach((owner, region) -> saved.putInt(owner.toString(), region));
        tag.put("regions", saved);
        tag.putInt("next", next);
        return tag;
    }
}
