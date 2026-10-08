# Testing the Arena Dueling module

Fake players, `PhantomPvpEnabled`, `PhantomPvpDuels` and `PhantomPvpBetweenPhantoms` must be on. Set `Enabled = True`
in `config/module.ini` and restart. For a quicker loop set `ChallengeDelaySeconds = 3`, `ClassChallengePercent = 100`.

1. Look for `Arena Dueling module enabled.` at startup, and `Arena Dueling: 3 arena(s) open for duels.` about 45
   seconds later.
2. Teleport to an arena (Gludin PvP arena, Dion monster arena, Giran battle arena). Within a few seconds three
   phantoms appear around you. After about a minute two of them duel each other.
3. After the delay, a phantom of your class walks over and the duel challenge dialog opens. Accept: countdown, fight,
   nobody dies, HP restored at the end.
4. `.duel titan` brings a Titan; `.duel banana` says there is no such class; `.duel` alone prints help; outside an
   arena it says to go to one.
5. Stakes: `.stake 50k` then `.duel any`. The 50k leaves your inventory when the phantom sets off. While locked,
   `.stake off` and `.stake 100k` are refused. Win: stake back plus 45000 (at the default 10% fee). Lose or surrender:
   stake gone. Cancel (walk off or hit a mob) with the phantom still there: forfeit. Decline the challenge dialog: the
   stake is back after about 90 s. `.stake 60k` is refused; so is a stake larger than your adena.
5b. Win several 1m stakes in a row: after `MaxWinPerHour` (default 2m) wins only return the stake.
5c. Race: spam `.duel any` while standing in an arena for a minute; no errors in the log, no stuck phantoms.
6. Walk out of the arena and wait a minute: the regulars leave.
7. Hit a regular outside a duel until it dies: it is replaced.
8. Stand in a town (peace zone) or the open field: nothing happens, and duels there work as before.

Edge cases: two players in one arena (each gets their own class challenge); `MaxDuelists` caps the total;
`Wagers = False` ignores stakes.
