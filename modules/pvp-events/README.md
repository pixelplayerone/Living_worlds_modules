# PvP Events

Start a PvP event with bots from the Community Board (Alt+B, **PvP Events**) or with `.pvpevent`: free-for-all,
King of the Hill, team deathmatch, Korean-style one-at-a-time, duels and 9v9, or just watch two bot teams fight.

## How an event runs
1. Pick a scenario and press Start. You are moved to your side of the arena (your spot is saved), the bots appear,
   and you get a prep timer to buff. Bots buff too but stand frozen and invulnerable until the timer ends.
2. The fight starts. Each side gets its team circle and its own event party (teammates cannot hurt each other).
   No death penalty. You get the same buffs the bots arrive with, at the start and after each respawn. A
   free-for-all has no teams.
3. Bots buff, hunt the nearest enemy in sight, and push toward the enemy start otherwise. They never flee.
4. With respawn on, the dead return after a few seconds and the first side to the kill limit wins. With respawn off,
   the last side standing wins. On a timeout, most kills wins.
5. A results table shows kills, deaths, damage dealt and taken, and HP healed per fighter. "Last result" and
   `.pvpevent last` bring it back.
6. You are put back exactly where you started, with the HP/MP/CP you came with. The bots are removed.

One event at a time. Only the starter (or a GM) can stop it. You cannot start while flagged, a PK, or in combat.
Your spot and vitals are saved, so a logout, crash, or restart in the arena returns you at next login.

## Scenarios
Up to 12 lines in `config/module.ini`: `Name | side vs side | options`. A side is a comma list; `you` is you.
Entries are `[N x] role-or-class`. Roles: tank, warrior, archer, dagger, monk, singer, dancer, nuker, healer,
buffer, bounty, any. Options: `kills=N`, `minutes=N`, `respawn=on|off`, `queue=on|off|both`. One side alone is a
free-for-all.

```
FFA Deathmatch       | you, 29xany            | kills=15, minutes=8
Korean Deathmatch    | you, 4xany vs 5xany    | respawn=off, queue=both
Ten of a kind        | you, 4xGladiator vs 5xDuelist | kills=20
Watch: bot war       | 5xany vs 5xany         | kills=25, minutes=6
```

Defaults: FFA Deathmatch, King of the Hill, Team Deathmatch (5v5), Korean Deathmatch (one at a time), 1v1 Duel,
9v9 Team Deathmatch.

## Requirements
Needs FakePlayers on and `PhantomPvpEnabled = True` in `config/Custom/FakePlayers.ini`.

## Install
1. Copy the `pvp-events` folder into `game/modules/`.
2. Set `Enabled = True` in `config/module.ini`.
3. Restart.

## Configuration (`config/module.ini`)
- `TabLabel`, `Arenas` (zone names), `MinPlayerLevel`, `LevelSpread`, `EnchantMin`/`EnchantMax`.
- `MaxPerSide`, `MaxFfa`, `PrepSeconds`, `Respawn`, `RespawnSeconds`, `QueueSeconds`, `DefaultKills`, `DefaultMinutes`.
- Rewards (off by default): `KillAdena`, `WinAdena`. `ShowResults`. `Scenario1`..`Scenario12`.

## Remove
Disable it, stop the server, delete the folder. No ids or database tables.

## License
GNU General Public License v3.0.
