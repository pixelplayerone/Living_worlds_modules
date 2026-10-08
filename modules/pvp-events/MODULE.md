# PvP Events

Start a PvP event with bots from the Community Board (Alt+B, **PvP Events**) or with `.pvpevent`.

## How an event runs

1. Pick a scenario and press **Start**. You are moved to your side of the arena (your spot is saved), the bots appear, and you get `PrepSeconds` to buff. The team circles show straight away. The bots buff too, but stand frozen and invulnerable until the timer ends.
2. The fight starts. Both teams get the team circle (blue / red) and each side is put in its own party for the event (broken up at the end). Team members cannot hurt each other; enemies can. There is no death penalty. You are given the same buffs the bots arrive with, at the start and after each respawn. In a free-for-all there are no teams or parties: everyone is everyone's enemy.
3. The bots buff, hunt the nearest enemy in sight and walk toward the enemy start when none is in sight. They never flee.
4. With respawn on the dead come back after `RespawnSeconds` at their start spot (a random spot in a free-for-all), and the first team (or fighter) to the kill limit wins. With `respawn=off` nobody comes back and the last team (or fighter) standing wins. When the clock runs out the side or fighter with more kills wins.
5. A results table shows kills, deaths, damage dealt, damage taken and HP healed for every fighter. **Last result** on the front page and `.pvpevent last` bring it back.
6. You are taken out of the event and put back where you started, with the HP, MP and CP you came with (the arena is not a free heal). The bots are removed.

One event runs at a time. Only the player who started it (or a GM) can stop it. Logging out ends it.

**You cannot start an event while flagged, a PK or in combat.** The place you stood, and your HP, MP and CP, are written to a player variable when the event takes you. If you log out, crash or the server restarts in the arena, you are put back there at your next login. Watching works the same: walk out of the arena (to town, to shop) and the event ends where you stand, without a teleport back.

## Scenarios

Up to 12 lines in `config/module.ini`:

    Name | side vs side | options

A side is a comma list. `you` is you (one side only; leave it out to watch two bot teams). Anything else is `[N x] role-or-class`.

- Roles: `tank warrior archer dagger monk singer dancer nuker healer buffer bounty any`
- Classes: any class name, such as `Gladiator` or `Phoenix Knight`
- Options: `kills=N`, `minutes=N`, `respawn=on|off`, `queue=on|off|both`

Leave out the `vs` and list one side for a free-for-all (up to `MaxFfa` fighters, you included).

Examples:

    FFA Deathmatch | you, 29xany | kills=15, minutes=8
    Korean Deathmatch | you, 4xany vs 5xany | respawn=off, queue=both
    Mage hunt | you, 2xwarrior vs 3xnuker
    Ten of a kind | you, 4xGladiator vs 5xDuelist | kills=20
    Watch: bot war | 5xany vs 5xany | kills=25, minutes=6

`queue=on` (with `respawn=off`) makes the bots facing you wait frozen and come one at a time: the next is let loose `QueueSeconds` after the one in play falls. `queue=both` does it for both sides: each side has one fighter in play (you first on yours), the rest wait frozen, and the next comes `QueueSeconds` after the one in play falls. The side that runs out of fighters loses. The default Korean Deathmatch uses it.

Default scenarios: FFA Deathmatch (30 fighters, first to 15 kills), King of the Hill (30 fighters, no respawns), Team Deathmatch (5v5, no respawns), Korean Deathmatch (5v5, one at a time), 1v1 Duel, 9v9 Team Deathmatch (no respawns).

A bad line is skipped, logged and shown in red on the front page.

## Needs

- FakePlayers on and `PhantomPvpEnabled = True` in `config/Custom/FakePlayers.ini`.
- The team engine in the core (`context.teams()`, framework docs section 3.10) and the damage hook (3.9). Rebuild the jar first.

## Notes

- The arena is the middle of the Coliseum (`colosseum_battle3`) by default; you are only sent home for leaving all the arenas listed.
- Team fights keep healer classes to one per four fighters on a side (two at the most); a free-for-all has none. \'any\' picks from every third-profession class, favouring ones its side lacks; Sword Muses, Spectral Dancers and dwarves are in the mix but half as likely, and the Dominator (the attacking orc caster) is in as a nuker.
- Healer classes (Bishop, Cardinal, Elder...) heal any teammate below 90% HP and otherwise stay behind their team, out of reach; they fight only if cornered or alone. Summoners and other classes do not heal. Melee fighters walk up to their target before using skills.
- You are held in place, with the bots, until the timer ends. Teammates cannot hurt each other, by attack, area skill or debuff.
- A fallen summoner's servitor goes with it.
- Bots are your level (`LevelSpread` varies it) with `EnchantMin`..`EnchantMax` gear.
- Healers and buffers fight with the platform's class combat. Whether a healer heals its team in the fight is not verified yet.
- Rewards (`KillAdena`, `WinAdena`) are off by default.
