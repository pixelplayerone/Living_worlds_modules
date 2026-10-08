# Testing

Pure checks, no server needed (Java 8+):

    javac -d out scripts/PvpScenario.java scripts/ArenaGeometry.java scripts/PvpScore.java scripts/PvpPages.java tests/PvpTest.java
    java -cp out modules.pvpevents.PvpTest

In game (rebuild the jar first; FakePlayers and PhantomPvpEnabled on; set `Enabled = True` in `config/module.ini`, restart):

1. Open Alt+B and press **PvP Events**. Six scenarios show with their sizes.
2. **FFA Deathmatch:** you and 29 bots, spread out across the arena, no team circles. Bots buff and wait for the 15-second timer. Then everyone fights everyone, the dead respawn at random spots after 3 seconds, and the first to 15 kills wins. The results table shows one list and the winner.
3. **King of the Hill:** the same, with no respawns: last one standing wins (or the top killer when 8 minutes pass).
4. **Team Deathmatch:** check your party window shows your 4 bots, and the other side is a party too. Nobody respawns; a side that is all dead loses.
5. **Korean Deathmatch:** only you and one red bot fight. The other bots on both sides stand frozen. When your fighter falls the next of yours comes 5 seconds later (you are told), and the same for red. Frozen bots must not be attacked or attack.
6. **1v1 Duel** and **9v9 Team Deathmatch:** the 9v9 should show two full parties of nine.
7. Your buffs: right after you arrive you should have the bots' buffs (check the buff bar). After you die and respawn (FFA) they should be back.
8. After any event: parties are gone, you are back where you started with the same HP, MP and CP as before (no heal), bots are removed.
9. **Stop** mid-event and log out mid-event: no bots or parties left behind.
10. Tell me: do the bots on the far side of the arena find each other, do any stand still, do healers heal in the team fights, and does the 30-bot FFA lag the server.
10. Start at half HP: you return at half HP. Attack a mob or flag yourself, then start: refused. Same for a PK.
11. Watch scenario (`Watch: bot war`): walk out of the arena to town and shop; within about 6 s the event ends and you
    stay in town. Pressing Stop afterwards does not teleport you anywhere.
12. Start an event, then kill the client (or restart the server) mid-event: at your next login you are in your old spot
    with your old HP, MP and CP, and no bots remain.
