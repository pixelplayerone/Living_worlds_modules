# Arena Dueling

Phantoms hang out at the town PvP arenas and spar. Walk into an arena and a phantom of your own class comes over
and challenges you to a duel, or call a duel yourself by class, optionally for an adena stake.

## What you see
- **Regulars.** While you are in a configured arena, a few geared phantoms near your level stand around it and duel
  each other every minute or so.
- **Class challenge.** A little after you walk in, a phantom of your class (a Titan for a Titan, and so on) walks up
  and challenges you with the normal duel dialog. Accept or decline. After one, none comes for a cooldown.
- **Called duel.** `.duel titan` brings a Titan, `.duel any` a random class. `.duel evastemplar 100k` adds a stake.
- **Real duels.** Countdown, nobody dies, HP/MP/CP restored when it ends, surrender works.

## Stakes
`.stake 50k` puts adena on your next arena duel with a phantom; `.stake` shows it, `.stake off` clears it (before a
duel starts). Only the configured amounts are allowed and you must hold it. The stake is taken when the phantom
sets off to duel you, then locked. Win pays your stake back plus the stake less a house fee (the phantom's winnings
are new adena, with an optional per-hour cap). Lose, surrender, or walk away and the stake is forfeit. A timed-out
or declined duel refunds it.

## Requirements
Needs FakePlayers on, with `PhantomPvpEnabled = True` and `PhantomPvpDuels = True` in
`config/Custom/FakePlayers.ini` (and `PhantomPvpBetweenPhantoms = True`, the default, for the regulars to spar).

## Install
1. Copy the `arena-dueling` folder into `game/modules/`.
2. Set `Enabled = True` in `config/module.ini`.
3. Restart.

## Configuration (`config/module.ini`)
- `Enabled`, `Arenas` (zone names), `MinPlayerLevel`.
- Regulars: `RegularsPerArena`, `MaxDuelists`, `LevelSpread`, `EnchantMin`/`EnchantMax`, `LeaveGraceSeconds`.
- Sparring: `Spar`, `SparMinSeconds`, `SparMaxSeconds`.
- Challenges: `ClassChallengePercent`, `ChallengeDelaySeconds`, `ChallengeCooldownMinutes`.
- Stakes: allowed `Stakes`, `StakeExpireSeconds`, `HouseFeePercent`, `MaxWinPerHour`.

## Remove
Disable it, stop the server, delete the folder. No ids or database tables.

## License
GNU General Public License v3.0.
