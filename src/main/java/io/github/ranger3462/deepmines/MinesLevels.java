package io.github.ranger3462.deepmines;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * How deep everyone has been in the Mines since the last reset: each player's personal level, and each team's. A level
 * is the deepest floor reached by its ladder down from the floor above, and the lobby lift ({@link MinesLift}) goes down
 * to any floor down to it.
 * <ul>
 * <li>Strict, solo only: floors reached while on a team count only for the team. A player who leaves a team is back
 * to their own personal level, and keeps none of the team's.</li>
 * <li>Saved with the Mines' own level data, so the reset (which wipes the Mines' folder) wipes every level with the
 * floors: each week is a fresh climb.</li>
 * </ul>
 * Who's on which team is {@link MinesTeams#team}: no one, with Team Mines off.
 */
public class MinesLevels extends SavedData {
    private static final String NAME = DeepMines.MODID + "_levels";

    private final Map<UUID, Integer> players = new HashMap<>();
    private final Map<UUID, Integer> teamLevels = new HashMap<>();

    public static MinesLevels get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(MinesLevels::new, MinesLevels::load, null), NAME);
    }

    /** The file this is saved to, in a level's data folder (the Mines', in play). */
    public static String fileName() {
        return NAME + ".dat";
    }

    /** A player's level: their team's, if they're on one, or else their own. 0 if they've not been down a floor yet. */
    public int level(ServerPlayer player) {
        Optional<UUID> team = MinesTeams.team(player);
        return team.isPresent() ? teamLevel(team.get()) : personal(player.getUUID());
    }

    /** A player's personal level, from floors reached on their own. */
    public int personal(UUID player) {
        return players.getOrDefault(player, 0);
    }

    public int teamLevel(UUID team) {
        return teamLevels.getOrDefault(team, 0);
    }

    /** A player has reached a floor going down: it raises their team's level, if they're on one, or else their own. */
    public void reached(ServerPlayer player, int floor) {
        Optional<UUID> team = MinesTeams.team(player);
        Map<UUID, Integer> levels = team.isPresent() ? teamLevels : players;
        UUID id = team.orElse(player.getUUID());
        if (floor > levels.getOrDefault(id, 0)) {
            levels.put(id, floor);
            setDirty();
        }
    }

    /** Forgets every level (for tests; a real reset wipes the saved file). */
    public void clear() {
        players.clear();
        teamLevels.clear();
        setDirty();
    }

    private static MinesLevels load(CompoundTag tag, HolderLookup.Provider registries) {
        MinesLevels data = new MinesLevels();
        read(tag.getCompound("players"), data.players);
        read(tag.getCompound("teams"), data.teamLevels);
        return data;
    }

    private static void read(CompoundTag tag, Map<UUID, Integer> levels) {
        for (String key : tag.getAllKeys()) {
            try {
                levels.put(UUID.fromString(key), tag.getInt(key));
            } catch (IllegalArgumentException e) {
                DeepMines.LOGGER.warn("Skipping a Mines level saved for {}, which isn't a UUID", key);
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("players", write(players));
        tag.put("teams", write(teamLevels));
        return tag;
    }

    private static CompoundTag write(Map<UUID, Integer> levels) {
        CompoundTag tag = new CompoundTag();
        levels.forEach((id, level) -> tag.putInt(id.toString(), level));
        return tag;
    }
}
