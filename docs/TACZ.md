# Optional TaCZ NPC integration

The integration is discovered through reflection when TaCZ is installed. Ordinary Sentinel
servers keep their existing weapon handling. It targets Citizens `PLAYER` NPCs on Youer
1.21.1 with TaCZ 1.2.0; no TaCZ or NeoForge implementation is bundled in the plugin.

Sentinel owns targeting, line of sight and movement. TaCZ owns draw timing, RPM, projectiles,
reload/bolt scripts and damage. The fallback watchdog advances the native shooter reload
and bolt components only when the fake player's normal tick has stalled. This retains
TaCZ 1.2.0 reload-completion events and tracking packets.

`tacz.*` config values control the optional integration. The four `sentinel defaults.tacz ...`
settings initialize movement policy; existing NPC persist keys are unchanged. The normal
`needammo` setting still selects real ammunition or the temporary NPC ammo ability.
With DeuteriumMMO, use 0.1.11 or later: it separates Citizens NPCs from player weapon ownership
and connects that ammo ability to TaCZ without changing the NPC's full game mode.

This main-branch integration imports only the required gun controller, reflection bridge,
aim/result/watchdog components, wiring and their tests. It does not import pending local GUI,
translation, command presentation or metrics edits. Main's upstream version and dependency
defaults are retained; the deployed Deuterium 2.9.1-deuterium.4 artifact is a separate build.
Two existing upstream API differences are handled for the 1.21.1 target: potion entity names
resolve through the existing Bukkit compatibility lookup, and pathfinding selects the available
Citizens constructor while retaining NavigatorParameters on newer Citizens.

For the current Youer compatibility target, validate with Java 21:

```text
mvn -Dbukkit.version=1.21.1-R0.1-SNAPSHOT -Dcitizens.version=2.0.35-SNAPSHOT clean test package
```

This source merge does not redeploy the running server or publish a new client resource version.

The Java 21 build above passes 9 tests (aiming, shoot results, stalled ticks and path-node construction).
The constructor adapter has its own API-compatibility responsibility and is tested without a live server;
the existing TaCZ components are copied as a coherent integration rather than splitting host classes for size.
