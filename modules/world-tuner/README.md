# World Tuner

Scales how fast monsters respawn and how many monsters each spawn point holds, for the whole world or just part
of it. Everything is set in `config/module.ini`; edit it and restart. With it off (or at stock values) the server
behaves exactly as stock.

## What it does
- **Faster or slower respawns**, server-wide or for a chosen slice.
- **More or fewer monsters per spawn point**, including fractional multipliers that work by chance.
- Targeting by **level range**, by **region**, or **per monster**.

Raid bosses, minions, instance monsters, town NPCs, and spawns made by quests or scripts are never touched.

## Install
1. Copy the `world-tuner` folder into `game/modules/`.
2. Set `Enabled = True` in `config/module.ini` and adjust the settings.
3. Restart.

## Configuration (`config/module.ini`)
- `Enabled` - master switch. False means the server is stock.
- `RespawnSpeedPercent` - how much faster monsters respawn. 50 = 50% faster, 100 = twice as fast, 0 = stock,
  negative = slower.
- `MinRespawnSeconds` - floor so a scaled respawn never drops below this many seconds.
- `SpawnCountMultiplier` - 1.0 = stock, 2.0 = one extra monster per spawn point. Fractions work by chance (1.5
  gives about half the spawn points an extra; 0.5 switches off about half).
- `ExtraSpawnSpread` - how far (game units) extra monsters are scattered around their spawn point. 0 stacks them.
- `MaxExtraPerSpawn` - safety cap on extra monsters per spawn point.
- `MinLevel` / `MaxLevel` - only change monsters in this level range.
- `Regions` - only change these regions (the folder names under `data/spawns`, e.g. `Gludio, Dion`). Empty =
  everywhere.
- `ExcludeNpcIds` - monster ids that are never changed.
- `Overrides` - per-monster `id:countMultiplier:respawnSpeedPercent`, separated by `;` (e.g.
  `20001:2.0:50;20002:1.0:100`). These win over the level range and regions.

## How it works
Modules start before the spawn lists load, so World Tuner waits for the server's "started" event, then makes one
pass over the spawn table. For each eligible spawn point it scales the respawn delay and, for a count above 1,
adds extra spawn points of the same kind near the original (within `ExtraSpawnSpread`), each with the original's
territory, name, and AI settings. The log prints a summary when the pass finishes. Skipped: raid and grand bosses,
instance monsters, anything that never respawns, day/night spawns not out at startup, and quest or script spawns.

## Notes
- Denser or sparser zones change how fights play out, including how your phantoms handle groups and aggro. Try it
  on a test server first.
- Quest monsters become more plentiful or scarcer too.

## Remove
Disable it, stop the server, delete the folder. No ids or database tables.

## License
GNU General Public License v3.0.
