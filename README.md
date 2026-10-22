# Deep Mines

A mining dimension for NeoForge 1.21.1 that resets on a schedule, so nobody has to strip-mine the overworld.

- **Getting there:** build a nether-style portal frame out of **deepslate bricks** and light it with flint and steel.
  Players can build their own, or admins can build one somewhere public. The first trip makes a linked portal in a
  cave in the Mines; the same portal leads back.
- **The Mines:** cave terrain all the way down (vanilla's caves world type with overworld biomes), no sky, a bedrock
  roof, and monsters in the dark.
- **Resets:** once `resetDays` days (default 7, in `config/deepmines-common.toml`) have passed, the Mines are wiped
  the next time the server starts. Players are warned when they arrive and every 5 minutes after, and anyone who logs
  back in to reset Mines is sent to their spawn point. Portals elsewhere keep working and make a fresh portal in the
  new Mines.

## Commands

- `/deepmines when`: when the Mines reset.
- `/deepmines reset` (operators): reset the Mines the next time the server starts.

## Requirements

- NeoForge 21.1.259 or later for Minecraft 1.21.1
- [Custom Portal API Reforged](https://modrinth.com/mod/custom-portal-api-reforged) (the portal)

## Building

`./gradlew build` (Java 21). `./gradlew runClient` to play, `./gradlew runGameTestServer` for the tests.
