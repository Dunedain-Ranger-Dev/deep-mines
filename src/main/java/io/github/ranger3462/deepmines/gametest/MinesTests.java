package io.github.ranger3462.deepmines.gametest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import io.github.ranger3462.deepmines.DeepMines;
import io.github.ranger3462.deepmines.InMinesPlacement;
import io.github.ranger3462.deepmines.MinesPortal;
import io.github.ranger3462.deepmines.MinesReset;
import net.kyrptonaught.customportalapi.CustomPortalApiRegistry;
import net.kyrptonaught.customportalapi.util.PortalLink;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Checks the Mines: the dimension's data, the portal, and the reset schedule. */
@GameTestHolder(DeepMines.MODID)
@PrefixGameTestTemplate(false)
public class MinesTests {
    /** An empty structure, since these tests don't build anything. */
    private static final String EMPTY = "empty";
    private static final long DAY = 24L * 60 * 60 * 1000;

    /**
     * The test server only creates the vanilla dimensions, so this checks the Mines' dimension type loaded and their
     * dimension file decodes, rather than the dimension itself.
     */
    @GameTest(template = EMPTY)
    public static void theMinesDimensionDataLoads(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var registries = server.registryAccess();
        var type = registries.registryOrThrow(Registries.DIMENSION_TYPE).get(MinesPortal.MINES.location());
        helper.assertTrue(type != null && type.minY() == -64 && type.height() == 192, "the Mines should end at the caves' bedrock roof");
        var file = server.getResourceManager().getResource(DeepMines.id("dimension/mines.json")).orElseThrow();
        try (var reader = file.openAsReader()) {
            var result = LevelStem.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), JsonParser.parseReader(reader));
            helper.assertTrue(result.isSuccess(), "the Mines dimension should decode: " + result.error().map(Object::toString).orElse(""));
        } catch (IOException e) {
            helper.fail("couldn't read the Mines dimension: " + e);
        }
        helper.succeed();
    }

    /**
     * The {@code deepmines:in_mines} filter keeps a feature out of every other dimension; with the pack, Ice and Fire's
     * silver and sapphire carry it, so they only generate in the Mines.
     */
    @GameTest(template = EMPTY)
    public static void inMinesOresStayOutOfTheOverworld(GameTestHelper helper) {
        var level = helper.getLevel();
        var context = new PlacementContext(level, level.getChunkSource().getGenerator(), Optional.empty());
        helper.assertTrue(InMinesPlacement.INSTANCE.getPositions(context, level.getRandom(), BlockPos.ZERO).findAny().isEmpty(),
                "nothing filtered to the Mines places in the overworld");
        var placed = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
        for (String ore : List.of("silver_ore", "sapphire_ore")) {
            PlacedFeature feature = placed.get(ResourceLocation.fromNamespaceAndPath("iceandfire", ore));
            if (feature != null && ModList.get().isLoaded("kubejs")) { // The pack's data adds the filter.
                helper.assertTrue(feature.placement().contains(InMinesPlacement.INSTANCE), "Ice and Fire's " + ore + " only generates in the Mines");
            }
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void deepslateBrickFramesLeadToTheMines(GameTestHelper helper) {
        PortalLink link = CustomPortalApiRegistry.getPortalLinkFromBase(MinesPortal.FRAME);
        helper.assertTrue(link != null && link.dimID.equals(MinesPortal.MINES.location()), "a deepslate brick frame should lead to the Mines");
        helper.assertTrue(link.portalSearchYTop == MinesPortal.ARRIVAL_TOP_Y && link.portalSearchYBottom == MinesPortal.ARRIVAL_BOTTOM_Y,
                "arrival portals should be placed in caves, not on the roof");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void resetsComeDueOnSchedule(GameTestHelper helper) {
        long last = 1_000_000L;
        helper.assertFalse(MinesReset.due(last, last + 6 * DAY, 7), "6 days in, a weekly reset isn't due");
        helper.assertTrue(MinesReset.due(last, last + 7 * DAY, 7), "7 days in, it is");
        helper.assertFalse(MinesReset.due(last, last + 700 * DAY, 0), "0 days turns resets off");
        helper.assertTrue(MinesReset.untilDue(last, last + 5 * DAY, 7) == 2 * DAY, "two days should be left");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aResetWipesTheDimensionFolder(GameTestHelper helper) {
        try {
            Path folder = Files.createTempDirectory("deepmines_test");
            Files.createDirectories(folder.resolve("region"));
            Files.writeString(folder.resolve("region").resolve("r.0.0.mca"), "chunks");
            MinesReset.wipe(folder);
            helper.assertFalse(Files.exists(folder), "the Mines folder should be gone");
            MinesReset.wipe(folder);
        } catch (IOException e) {
            helper.fail("wiping failed: " + e);
        }
        helper.succeed();
    }
}
