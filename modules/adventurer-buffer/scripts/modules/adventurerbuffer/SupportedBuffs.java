// SPDX-License-Identifier: GPL-3.0-only
// Catalog IDs adapted from Sateriok Preset Buffer 1.0.0; metadata checked against native XML.
package modules.adventurerbuffer;
import java.util.*;
final class SupportedBuffs {
    static final class Spec {
        final int id; final String category,type,target; final boolean dance; final int[] durations;
        Spec(int i,String c,String t,String target,boolean d,int[] times){id=i;category=c;type=t;this.target=target;dance=d;durations=times;}
    }
    private static final Map<Integer,Spec> DATA=new LinkedHashMap<Integer,Spec>();
    private static void add(int id,String c,String t,String target,boolean d,int[] times){DATA.put(id,new Spec(id,c,t,target,d,times));}
    static Spec get(int id){return DATA.get(id);}
    static {
        add(264, "Songs", "SONG_OF_EARTH", "PARTY", true, new int[]{120});
        add(265, "Songs", "SONG_OF_LIFE", "PARTY", true, new int[]{120});
        add(266, "Songs", "SONG_OF_WATER", "PARTY", true, new int[]{120});
        add(267, "Songs", "SONG_OF_WARDING", "PARTY", true, new int[]{120});
        add(268, "Songs", "SONG_OF_WIND", "PARTY", true, new int[]{120});
        add(269, "Songs", "SONG_OF_HUNTER", "PARTY", true, new int[]{120});
        add(270, "Songs", "SONG_OF_INVOCATION", "PARTY", true, new int[]{120});
        add(271, "Dances", "DANCE_OF_WARRIOR", "PARTY", true, new int[]{120});
        add(272, "Dances", "DANCE_OF_INSPIRATION", "PARTY", true, new int[]{120});
        add(273, "Dances", "DANCE_OF_MYSTIC", "PARTY", true, new int[]{120});
        add(274, "Dances", "DANCE_OF_FIRE", "PARTY", true, new int[]{120});
        add(275, "Dances", "DANCE_OF_FURY", "PARTY", true, new int[]{120});
        add(276, "Dances", "DANCE_OF_CONCENTRATION", "PARTY", true, new int[]{120});
        add(277, "Dances", "DANCE_OF_LIGHT", "PARTY", true, new int[]{120});
        add(304, "Songs", "SONG_OF_VITALITY", "PARTY", true, new int[]{120});
        add(305, "Songs", "SONG_OF_VENGEANCE", "PARTY", true, new int[]{120});
        add(306, "Songs", "SONG_OF_FLAME_GUARD", "PARTY", true, new int[]{120});
        add(307, "Dances", "DANCE_OF_AQUA_GUARD", "PARTY", true, new int[]{120});
        add(308, "Songs", "SONG_OF_STORM_GUARD", "PARTY", true, new int[]{120});
        add(309, "Dances", "DANCE_OF_EARTH_GUARD", "PARTY", true, new int[]{120});
        add(310, "Dances", "DANCE_OF_VAMPIRE", "PARTY", true, new int[]{120});
        add(311, "Dances", "DANCE_OF_PROTECTION", "PARTY", true, new int[]{120});
        add(349, "Songs", "SONG_OF_RENEWAL", "PARTY", true, new int[]{120});
        add(363, "Songs", "SONG_OF_MEDITATION", "PARTY", true, new int[]{120});
        add(364, "Songs", "SONG_OF_CHAMPION", "PARTY", true, new int[]{120});
        add(365, "Dances", "DANCE_OF_SIREN", "PARTY", true, new int[]{120});
        add(1002, "Chants", "CASTING_TIME_DOWN", "PARTY", false, new int[]{1200,1200,1200});
        add(1006, "Chants", "MD_UP", "PARTY", false, new int[]{1200,1200,1200});
        add(1007, "Chants", "PA_UP", "PARTY", false, new int[]{1200,1200,1200});
        add(1009, "Chants", "PD_UP", "PARTY", false, new int[]{1200,1200,1200});
        add(1032, "Resist", "RESIST_BLEEDING", "ONE", false, new int[]{1200,1200,1200});
        add(1033, "Resist", "RESIST_POISON", "ONE", false, new int[]{1200,1200,1200});
        add(1035, "Buffs", "RESIST_DERANGEMENT", "ONE", false, new int[]{1200,1200,1200,1200});
        add(1036, "Buffs", "MD_UP", "ONE", false, new int[]{1200,1200});
        add(1040, "Buffs", "PD_UP", "ONE", false, new int[]{1200,1200,1200});
        add(1043, "Buffs", "HOLY_ATTACK", "PARTY_MEMBER", false, new int[]{1200});
        add(1044, "Buffs", "HP_REGEN_UP", "ONE", false, new int[]{1200,1200,1200});
        add(1045, "Buffs", "MAX_HP_UP", "ONE", false, new int[]{1200,1200,1200,1200,1200,1200});
        add(1048, "Buffs", "MAX_MP_UP", "ONE", false, new int[]{1200,1200,1200,1200,1200,1200});
        add(1059, "Buffs", "MA_UP", "ONE", false, new int[]{1200,1200,1200});
        add(1062, "Buffs", "BERSERKER", "PARTY_MEMBER", false, new int[]{1200,1200});
        add(1068, "Buffs", "PA_UP", "ONE", false, new int[]{1200,1200,1200});
        add(1077, "Buffs", "CRITICAL_PROB_UP", "ONE", false, new int[]{1200,1200,1200});
        add(1078, "Buffs", "CANCEL_PROB_DOWN", "ONE", false, new int[]{1200,1200,1200,1200,1200,1200});
        add(1085, "Buffs", "CASTING_TIME_DOWN", "ONE", false, new int[]{1200,1200,1200});
        add(1086, "Buffs", "ATTACK_TIME_DOWN", "ONE", false, new int[]{1200,1200});
        add(1087, "Buffs", "AVOID_UP", "ONE", false, new int[]{1200,1200,1200});
        add(1182, "Resist", "ARMOR_WATER", "ONE", false, new int[]{1200,1200,1200});
        add(1189, "Resist", "ARMOR_WIND", "ONE", false, new int[]{1200,1200,1200});
        add(1191, "Resist", "ARMOR_FIRE", "ONE", false, new int[]{1200,1200,1200});
        add(1204, "Buffs", "SPEED_UP", "ONE", false, new int[]{1200,1200});
        add(1240, "Buffs", "HIT_UP", "ONE", false, new int[]{1200,1200,1200});
        add(1242, "Buffs", "CRITICAL_DMG_UP", "ONE", false, new int[]{1200,1200,1200});
        add(1243, "Buffs", "SHIELD_PROB_UP", "ONE", false, new int[]{1200,1200,1200,1200,1200,1200});
        add(1251, "Chants", "ATTACK_TIME_DOWN", "PARTY", false, new int[]{1200,1200});
        add(1252, "Chants", "AVOID_UP", "PARTY", false, new int[]{1200,1200,1200});
        add(1253, "Chants", "CRITICAL_DMG_UP", "PARTY", false, new int[]{1200,1200,1200});
        add(1259, "Resist", "RESIST_SHOCK", "ONE", false, new int[]{1200,1200,1200,1200});
        add(1268, "Buffs", "VAMPIRIC_ATTACK", "ONE", false, new int[]{1200,1200,1200,1200});
        add(1284, "Chants", "DMG_SHIELD", "PARTY", false, new int[]{1200,1200,1200});
        add(1303, "Buffs", "MAGIC_CRITICAL_UP", "ONE", false, new int[]{1200,1200});
        add(1304, "Buffs", "SHIELD_DEFENCE_UP", "ONE", false, new int[]{1200,1200,1200});
        add(1308, "Chants", "CRITICAL_PROB_UP", "PARTY", false, new int[]{1200,1200,1200});
        add(1309, "Chants", "HIT_UP", "PARTY", false, new int[]{1200,1200,1200});
        add(1310, "Chants", "VAMPIRIC_ATTACK", "PARTY", false, new int[]{1200,1200,1200,1200});
        add(1352, "Resist", "MD_UP_ATTR", "ONE", false, new int[]{1200});
        add(1353, "Resist", "RESIST_HOLY_UNHOLY", "ONE", false, new int[]{1200});
        add(1354, "Resist", "RESIST_DEBUFF_DISPEL", "ONE", false, new int[]{1200});
        add(1355, "Special", "MULTI_BUFF", "PARTY_MEMBER", false, new int[]{300});
        add(1356, "Special", "MULTI_BUFF", "PARTY_MEMBER", false, new int[]{300});
        add(1357, "Special", "MULTI_BUFF", "PARTY_MEMBER", false, new int[]{300});
        add(1362, "Chants", "RESIST_DEBUFF_DISPEL", "PARTY", false, new int[]{1200});
        add(1363, "Special", "MULTI_BUFF", "PARTY", false, new int[]{300});
        add(1388, "Buffs", "PA_PD_UP", "ONE", false, new int[]{1200,1200,1200});
        add(1389, "Buffs", "PA_PD_UP", "ONE", false, new int[]{1200,1200,1200});
        add(1390, "Buffs", "PA_PD_UP", "PARTY", false, new int[]{1200,1200,1200});
        add(1391, "Buffs", "PA_PD_UP", "PARTY", false, new int[]{1200,1200,1200});
        add(1392, "Resist", "ARMOR_HOLY", "ONE", false, new int[]{1200,1200,1200});
        add(1393, "Resist", "ARMOR_UNHOLY", "ONE", false, new int[]{1200,1200,1200});
        add(1397, "Buffs", "CHEAP_MAGIC", "ONE", false, new int[]{1200,1200,1200});
        add(1413, "Special", "MULTI_BUFF", "PARTY", false, new int[]{300});
        add(4699, "Special", "BUFF_QUEEN_OF_CAT", "PARTY", false, new int[]{120,120,120,150,165,180,195,210,225,240,255,270,285});
        add(4700, "Special", "BUFF_QUEEN_OF_CAT", "PARTY", false, new int[]{120,120,120,150,165,180,195,210,225,240,255,270,285});
        add(4702, "Special", "BUFF_UNICORN_SERAPHIM", "PARTY", false, new int[]{120,120,120,150,165,180,195,210,225,240,255,270,285});
        add(4703, "Special", "BUFF_UNICORN_SERAPHIM", "PARTY", false, new int[]{120,120,120,150,165,180,195,210,225,240,255,270,285});
    }
}
