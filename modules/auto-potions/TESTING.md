# Testing

Pure checks, no server needed (Java 8+):

    javac -d out scripts/PotionLogic.java tests/PotionLogicTest.java
    java -cp out modules.autopotions.PotionLogicTest

In game (set `Enabled = True` in `config/module.ini`, restart):

1. Put Greater CP, Greater Healing and Mana potions in your inventory.
2. `.pots`: the chat line says ON with the three lines.
3. Take damage until CP is under 90%: CP potions are drunk about every half second until it is over the line.
4. Let HP drop under 70%: a healing potion is drunk. With Quick and Greater Healing Potions in your bag, both are drunk, Quick about every 0.5 s and Greater every 10 s. With neither, the `HpItemIds` fallback is used.
5. Cast spells until MP is under 70%: a mana potion is drunk.
6. `.pots hp 40` then `.pots status` shows the new line. `.pots cp off` stops CP potions.
7. Drop all potions of one kind: one chat line says you are out of them. It does not repeat.
8. `.pots` again turns it off. Log out and in with it on: it comes back on.

9. Stand next to a Quick Healing Potion stack at low HP: it is drunk about twice a second, not four times. A Greater Healing Potion is drunk once, then not again for 10 seconds, and no re-use lines scroll in chat.
10. Get stunned or slept at low HP with it on: nothing is drunk until you can move again. Open a private store: nothing is drunk.
11. `.pots hp 40`, `.pots cp off`, log out and in: `.pots status` shows the same lines.
