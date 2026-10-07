# Testing

Pure checks, no server needed (Java 8+):

    javac -d out scripts/HuntingRules.java scripts/HuntingState.java scripts/GroundIndex.java scripts/GroundData.java scripts/HuntingPages.java scripts/PlayerLocks.java tests/HuntingTest.java
    java -cp out modules.hunting.HuntingTest

In game (set `Enabled = True` in `config/module.ini`, restart):

1. Alt+B, click **Hunting**, then **Extermination**. Pick a level range and click a ground's name to mark it.
2. Kill monsters inside that ground. Its progress fills in by itself; kills outside it do not count.
3. Restart the server mid-way: progress is still there.
4. At the target, press Claim: adena arrives and the ground shows "Claimed" for good. Claim every ground of a level range for the section bonus.
5. **Bounties**: kill a raid boss (a partied phantom's kill counts for you). It shows "Slain"; claim it once. Clear a whole range and claim the section bonus.
6. **My Progress** lists the grounds you have started and the bosses waiting to be claimed.
