# Deep Mines

A mining dimension for NeoForge 1.21.1 that resets on a schedule, so nobody has to strip-mine the overworld.

- **Getting there:** build a nether-style portal frame out of **deepslate bricks** and light it with flint and steel.
  Players can build their own, or admins can build one somewhere public. It leads to the Mines' entrance hall, whose
  ladder up takes you back out where you came in.
- **The Mines:** 100 floors below the entrance hall, each big rooms of rock (3 to 5 near the top, up to 16 to 25 below floor 75) joined by timbered mineshafts,
  with a ladder down in the farthest room. The deeper the floor, the better its ore, and deepslate from about floor 50.
  Rooms are common (the usual amount of ore, a cave to dig down or up into or old timbered workings), quarries (packed with ore), flooded (a deep pool
  to dive into), infested (spawners) or vein rooms (timbered galleries after very large veins of one ore), and every
  room and corridor is carved fresh, so no two are alike. Floors are
  built the first time someone reaches them, a little each tick so the server doesn't freeze. Around the floors is
  cave terrain (vanilla's caves world type with overworld biomes, plus its water and lava pockets, which YUNG's
  Better Caves needs), until the floors replace it.
- **Resets:** once `resetDays` days (default 7, in `config/deepmines-common.toml`) have passed, the Mines are wiped
  the next time the server starts. Players are warned when they arrive and every 5 minutes after, and anyone who logs
  back in to reset Mines is sent to their spawn point. Portals elsewhere keep working and make a fresh portal in the
  new Mines.

Where the mod is headed: [the design doc](docs/design/deep-mines.md).

## Commands

- `/deepmines when`: when the Mines reset.
- `/deepmines reset` (operators): reset the Mines the next time the server starts.
- `/deepmines floor <number>` (operators): go to a floor of the Mines, building it if no one has been there; 0 is the
  entrance hall. Each floor has a ladder down in its farthest room and a ladder up in its first; right-click one to
  use it.

## Requirements

- NeoForge 21.1.259 or later for Minecraft 1.21.1
- [Custom Portal API Reforged](https://modrinth.com/mod/custom-portal-api-reforged) (the portal)
- [Dungeon Delving](https://github.com/Dunedain-Ranger-Dev/dungeon-delving) (builds the Mines' floors), and Apotheosis,
  which it needs

To build, check out dungeon-delving next to this repository; Gradle builds it from source.

## Building

`./gradlew build` (Java 21). `./gradlew runClient` to play, `./gradlew runGameTestServer` for the tests, and
`./gradlew runData` to regenerate the Mines' fixed pieces, the start hall and the cap (`src/generated/resources/`,
committed). Rooms and corridors are carved by `RoomCarver` as floors are built.
