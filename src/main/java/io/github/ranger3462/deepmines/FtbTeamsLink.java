package io.github.ranger3462.deepmines;

import java.util.Optional;
import java.util.UUID;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import net.minecraft.server.level.ServerPlayer;

/** The FTB Teams link, only loaded when FTB Teams is installed ({@link MinesTeams}). */
final class FtbTeamsLink {
    private FtbTeamsLink() {}

    /** The FTB party team a player is in, if any (everyone also has a team of their own, which doesn't count). */
    static Optional<UUID> partyOf(ServerPlayer player) {
        if (!FTBTeamsAPI.api().isManagerLoaded()) {
            return Optional.empty();
        }
        return FTBTeamsAPI.api().getManager().getTeamForPlayer(player).filter(Team::isPartyTeam).map(Team::getId);
    }
}
