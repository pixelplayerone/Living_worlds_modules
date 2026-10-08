# Phantom Encounters module

PvP danger for a solo player. While you play in the open field, phantoms occasionally walk up and fight you once.
Each actor exists only to PvP you: it fights until you die or it dies, then it leaves. Nothing keeps roaming.

It needs fake players and phantom PvP on (`FakePlayers` and `PhantomPvpEnabled = True` in
`config/Custom/FakePlayers.ini`). The platform does the mechanics (`context.encounters()`, see
`docs/MODULE_FRAMEWORK.md` section 3.7); this module is the whole feature: when, who, how strong, what they say and
what a win pays.

## The five kinds

| Kind | Level vs. you | Gear | Group size | Behaviour |
|---|---|---|---|---|
| Wimp | 2-3 lower | +0 | your party size | Walks up and attacks on arrival. |
| Normie | same | +0 to +3 | your party size | Walks up and attacks on arrival. |
| Hard | +3 | +3 to +4 | your party size | Waits until you stand still or fight a monster, then attacks. |
| 4Horsemen | +5 | +5 to +7 | your party size, minimum 4 | Attacks the moment it reaches you. |
| AssMuncher | +11 | full +16 | always 1 | The extinction event: one named phantom. |

Group size is capped by `MaxActors` (default 9). Roles (warrior, archer, dagger, nuker, monk) are random.

## When they happen

Each player has an independent timer per kind: after one of that kind, a random wait between `MinMinutes` and
`MaxMinutes`. Defaults:

| Kind | Wait | Unlocks at your level |
|---|---|---|
| Wimp | 30-40 min | 10 |
| Normie | 30-40 min | 10 |
| Hard | 2-3 h | 20 |
| 4Horsemen | 4.5-5.5 h | 20 |
| AssMuncher | 7-9 h | 20 |

- Wimp and Normie together land roughly every 17 minutes.
- If two kinds are due together, the rarer one goes first.
- After any encounter starts, nothing else starts for `GapMinutes` (default 5).
- At most `MaxActive` encounters (default 2) run server-wide; a whole group counts as one.
- A kind's clock starts the first time you are eligible, so there is no ambush on login.
- You are skipped in towns and peace zones, no-PvP and siege zones, duels, stores, instances, the Olympiad, and while dead.

## How an encounter plays

1. **Spawn:** each actor appears 650-900 units away, from one general direction, on ground it can walk up (geodata
   checked). An actor that lands in a peace zone is removed.
2. **Approach:** it walks to you (up to `ApproachSeconds`). Wimp, Normie, Horsemen and AssMuncher attack on arrival; Hard waits for you to
   stand still (`StillSeconds`) or be mid-fight with a monster.
3. **Fight:** one fight, up to `FightSeconds`. Actors never flee.
4. **End:** if they all die you are paid; if you die the survivors say a win line and leave; if you escape or time runs
   out they leave. The same actor never comes back for a second round.

Actors are not red-named, so nobody drops items on death: phantoms killed by players never drop, and you keep your
gear under the normal PK rules.

## Rewards

Adena only, paid once when the whole group is down (`<Kind>AdenaReward`):

| Wimp | Normie | Hard | 4Horsemen | AssMuncher |
|---|---|---|---|---|
| 50,000 | 100,000 | 200,000 | 350,000 | 1,000,000 |

## Config (`config/module.ini`)

Global: `Enabled`, `MinPlayerLevel`, `GapMinutes`, `MaxActive`, `MaxActors`, `HorsemenMinSize`, `PkerName`, `RedNameKarma` (Horsemen and Pker spawn red; 0 = off), `RedEscapePercent`, `OtherEscapePercent` (Blessed Scroll of Escape chances),
`ApproachSeconds`, `FightSeconds`, `WarnSeconds`, `StillSeconds`.

Per kind (`Wimp`, `Normie`, `Hard`, `Horsemen`, `Pker` + ...): `MinMinutes`, `MaxMinutes` (0 = kind off),
`MinPlayerLevel`, `LevelMin`, `LevelMax`, `EnchantMin`, `EnchantMax`, `AdenaReward`.

## Enable it

It ships disabled. Set `Enabled = True` in `config/module.ini` and restart the server.

## Disable it

Set `Enabled = False` and restart. Nothing is registered and the stock server is unchanged.

## Remove it

While the server is stopped or the module is disabled, delete this whole `phantom-encounters` directory. It has no
database tables and stores nothing.

## Moving from the earlier built-in version

The first version of this feature lived in the server core and was set in `FakePlayers.ini` (`PhantomEncounters`,
`PhantomEncounter<Kind>...`). Those keys no longer exist. The same settings are now in this module's
`config/module.ini` under shorter names (drop the `PhantomEncounter` prefix). The gear-drop option was removed.

## Not yet verified in a live game

The rules are unit tested. Not yet checked on a running server: skills landing on an unflagged player through the
hostile rule, gear and enchant on the spawned actors, spawn visibility at 650-900 units, and the AssMuncher name
clashing if a character already uses it. The difficulty is an estimate (rough time-to-kill edge: Hard 15-25%, Horsemen
1.3-1.5x, AssMuncher 2.5-3x), not a measurement.

## Contested Farming Zones (optional)

Set `ContestedZones = True` (and `Enabled = True`). Each monster kill inside one of the 838 farming-area circles (taken
from the server's phantom hunting grounds, quest areas left out) rolls `ContestedChancePercent`. A win sends either a PvE
party that wants the spot (`ContestedPvePercent`, 70 by default) or a PK party. A PvE party asks first
(`PveAskPercent`, 50 by default) and attacks if you refuse, or just attacks; wiping it makes the rest leave. Each kind has its
own adena reward.

Only normal monsters count, not raid bosses or minions, not in instances. The player must be eligible as for any
encounter and meet the unlock level of the party kinds. After a contest the same player is safe from it for
`ContestedCooldownMinutes`, and scheduled encounters wait out `GapMinutes` too. Regenerate the areas with
`tools/gen_farming_grounds.py`.

## Who comes

- **AssMuncher (Pker):** one phantom. By default (`PkerClasses = Bag`) the class is dealt from a shuffled bag per player:
  mage, summoner, archer, dagger, Duelist, Titan and Grand Khavatari are common, tank and Dreadnought less so, and the same
  profile never comes twice in a row. `PkerClasses = TitanDuelist` makes it a Titan or a Duelist, evenly.
- **4Horsemen:** a Cardinal, a Titan, a Storm Screamer and a Duelist; every member of your party beyond four adds one
  more drawn at random, none twice, from Adventurer, Hell Knight, Soultaker, Cardinal and Sagittarius.
- **PkParty:** 3 to 6 phantoms (`PkPartyMinSize`/`PkPartyMaxSize`): archer, mage, melee or mixed (equally likely). One tank
  half the time, and for each full four members a healer half the time. Dreadnoughts and Fortune Seekers never join.
- **PveParty:** a tank, a healer, a Fortune Seeker and one damage dealer per member of your party (you count as one),
  from any damage class including Dreadnoughts. They use no CP potions.
- **Rare all-alike parties:** `NoveltyPartyPercent` (4% by default) of PK and PvE parties are all archers, all mages, all
  melee, all tanks, all Fortune Seekers, or one class repeated, with their own taunts.
- Everyone talks a little trash when the fight begins; the first to fall whines. The Horsemen and the Pker spawn red;
  they and PK parties carry CP potions. Horsemen and Pker run at 10% HP 50% of the time; everyone else 25% (and when their group is
  down to a quarter and outnumbered).

## Test commands (optional)

Set `TestCommands = True` and use `.enc` in game chat: `.enc wimp`, `.enc normie`, `.enc hard`, `.enc pkparty`,
`.enc horsemen`, `.enc pker`, `.enc pveparty [ask|ambush]` force that encounter now (timers and unlock levels ignored); `.enc contest` forces a contested one;
`.enc chance 100` turns contested zones on at 100% per kill with no restart; `.enc reset` and `.enc status`.
Turn it back to False for normal play.

`.enc buffs` gives you the same pre-buff kit recruited phantoms arrive with (`.enc buffs tank` for the tank set, without Berserker).
