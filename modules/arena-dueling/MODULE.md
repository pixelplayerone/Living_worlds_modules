# Arena Dueling module

Phantoms hang out at the town PvP arenas and duel. Walk into an arena and a phantom of your own class comes over and
challenges you. Or call a duel yourself, optionally for an adena stake.

It needs fake players, phantom PvP and phantom duels on (`FakePlayers`, `PhantomPvpEnabled = True` and
`PhantomPvpDuels = True` in `config/Custom/FakePlayers.ini`). The platform supplies the duelists and the duel system
(`context.duels()`); this module is the whole feature: which arenas, who stands there, when they challenge, and what a
stake pays. Set `Enabled = True` in `config/module.ini` and restart.

## What you see

- **Regulars.** While you are in a configured arena, a few geared phantoms (`RegularsPerArena`, near your level) stand
  around it. Every minute or so two of them duel each other.
- **Class challenge.** About 15 seconds after you walk in, a phantom of your class (a Titan for a Titan, and so on) walks
  up and challenges you with the normal duel dialog. Accept or decline. After one, none comes for 10 minutes.
- **Called duel.** `.duel titan` brings a Titan, `.duel any` a random class. `.duel evastemplar 100k` adds a stake.
- **Duels are the real thing.** Countdown, nobody dies, HP/MP/CP restored when it ends, surrender works. Duelists leave
  when the last player has left the arena for a minute.

## Stakes

`.stake 50k` puts that much adena on your next arena duel with a phantom; `.stake` shows it. Only the amounts in
`Stakes` are allowed and you must hold the amount. A stake nobody duelled for expires after `StakeExpireSeconds`, and
until then `.stake off` clears it.

The stake is taken from you the moment the phantom sets off to duel you, and is then locked: `.stake off` and new stakes
are refused until the duel is over. What happens next:

- **Win:** your stake comes back plus the stake less `HouseFeePercent` (100k at 10% pays +90k). The phantom puts nothing
  up, so winnings are new adena; `MaxWinPerHour` limits what one player can win per hour (0 = no cap).
- **Lose or surrender:** the stake is gone.
- **Walk away** (cancel the duel, hit a mob, log out) while the phantom is still there: you forfeit the stake.
- **Time-out,** the phantom vanished, or the duel never started (challenge declined, 90 s): the stake is refunded.

Arena state is shared by the 5-second tick and the `.duel` command; both take one lock, and a failing arena no longer
stops the others.

## Platform changes this module needs

- `ModuleDuels` (`context.duels()`): spawn a duelist that stays put, send it to challenge a player, and a duel-result
  hook. A duelist accepts any duel.
- The stock rules refuse duels inside PvP zones. `ModuleDuels.openArena(zone)` opens the named arenas only; the check is
  one line in `Player.canDuel()`. Nothing changes outside those zones, or with the module off.

## Notes

- Duelists are ordinary phantoms with gear from the same roster the party recruits use.
- If an arena overlaps a peace zone the duel is still refused there (stock rule). The three defaults are plain PvP
  zones.
- A phantom killed outside a duel (you attack it in the arena) is replaced after a moment.
