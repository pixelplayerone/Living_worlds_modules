# Testing the Phantom Encounters module

Fake players and phantom PvP must be on. Set `Enabled = True` in `config/module.ini` and restart. To see something
quickly, shorten a kind's wait, for example `WimpMinMinutes = 1` and `WimpMaxMinutes = 1`, and put the other kinds at 0.

1. Start the server and look for `Phantom Encounters module enabled.` in the console.
2. Log in with a character of level 10 or more, stand in the open field (not a town or peace zone) and wait.
   About a minute after the first check you should see `Phantom Encounters: WIMP encounter for <you> ...` in the log.
3. A phantom appears at a distance and walks to you. The Wimp says "are you a bot?" and attacks after a few seconds, or
   at once if you hit it first.
4. Kill it. You should receive the adena reward and the log shows `reward 50000 adena`.
5. Let it kill you instead. The survivors say a line such as "gg" and leave. You lose nothing beyond a normal death.

Per kind, one at a time (set the others to 0):

- Normie and Hard: stand still for 4 seconds, or fight a monster; they strike at that moment.
- Horsemen: with a party of 3 you should see at least 4 actors, all striking when they arrive.
- AssMuncher: one actor named `AssMuncher`, level 11 above you with +16 gear. It does not flee.

Edge cases to check:

- Go into a town while an encounter is on its way: the actors leave.
- Run far away (2,500+ units) or recall: the actors leave.
- Log out during a fight: no actor stays behind.
- Be in a duel, a store, an instance, a siege zone or the Olympiad: nothing is sent.
- Party of 9: a Normie encounter sends 9 actors (the `MaxActors` cap).
- Turn `PhantomPvpEnabled` off: the module stays quiet.

## Contested Farming Zones

Set `ContestedZones = True`, `ContestedChancePercent = 100`, `ContestedCooldownMinutes = 1`.

- Kill a monster in a hunting ground (for example the Cruma Tower or Ant Nest areas): a phantom walks up and says a "my spot" line.
- Kill monsters in an area not in a hunting ground: nothing comes.
- Kill a raid boss or minion: nothing comes.
- Right after one contest, kill more: nothing until the cooldown ends, and no scheduled encounter during `GapMinutes`.
- With `ContestedZones = False`: no contests at all.

## Quick test with commands

Set `Enabled = True` and `TestCommands = True`, restart once. Then in game: `.enc wimp`, `.enc normie`, `.enc hard`,
`.enc horsemen`, `.enc pker`, `.enc contest`. For the kill chance: stand in a hunting ground, `.enc chance 100`, kill a
monster; `.enc status` shows whether you are in a farming area and your cooldown.
