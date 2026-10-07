// SPDX-License-Identifier: GPL-3.0-or-later
package modules.adventurerbuffer;
import java.util.*;

/** Owner recipes only. No client-supplied skill IDs or automatic class inference. */
final class StrengthConfig {
    static final int[] BASIC_IDS={1045,1048,1204,1040,1036,1085,1059,1086,1068,1268,1078,1044};
    static final String MID_MUSIC="264:1,265:1,266:1,267:1,268:1,269:1,271:1,272:1,273:1,274:1,275:1,276:1";
    static final String DEFAULT_CUSTOM="1045:6,1048:6,1204:2,1040:3,1036:2,1085:3,1059:3,1086:2,1068:3,1268:4,1078:6,1044:3,1388:3,1363:1,4699:13,4703:13,"+MID_MUSIC;
    static int strength(String raw) {
        if (raw==null || !raw.trim().matches("[1-3]")) { throw new IllegalArgumentException("Strength must be 1 (Basic), 2 (Mid), or 3 (Custom); Strength 4 is not supported."); }
        return Integer.parseInt(raw.trim());
    }
    static List<BuffCatalog.Entry> parseList(String raw) {
        if (raw==null || raw.trim().isEmpty()) { throw new IllegalArgumentException("CustomBuffs must contain at least one ID:rank pair."); }
        List<BuffCatalog.Entry> out=new ArrayList<BuffCatalog.Entry>();
        for(String item:raw.split(",",-1)) {
            String[] pair=item.trim().split(":",-1);
            if(pair.length!=2 || !pair[0].trim().matches("[0-9]+") || !pair[1].trim().matches("[0-9]+")) { throw new IllegalArgumentException("Expected comma-separated ID:rank pairs; invalid entry: "+item); }
            int id,rank;try{id=Integer.parseInt(pair[0].trim());rank=Integer.parseInt(pair[1].trim());}catch(NumberFormatException e){throw new IllegalArgumentException("Skill ID/rank is too large: "+item,e);}
            SupportedBuffs.Spec spec=SupportedBuffs.get(id);
            if(spec==null || rank<1 || rank>spec.durations.length) { throw new IllegalArgumentException("Unsupported buff ID/rank: "+item); }
            out.add(new BuffCatalog.Entry(id,rank));
        }
        validate(out);return out;
    }
    static void validate(List<BuffCatalog.Entry> entries) {
        Set<Integer> ids=new HashSet<Integer>();Map<String,Integer> groups=new HashMap<String,Integer>();int buffs=0,music=0;
        for(BuffCatalog.Entry e:entries) {
            if(!ids.add(e.id)) { throw new IllegalArgumentException("Duplicate buff ID "+e.id+"."); }
            Integer previous=groups.put(e.spec.type,e.id);
            if(previous!=null) { throw new IllegalArgumentException("Conflicting buff family "+e.spec.type+": "+previous+" and "+e.id+". Choose only one."); }
            if(e.spec.dance)music++;else buffs++;
        }
        if(buffs>20 || music>12) { throw new IllegalArgumentException("Recipe exceeds supported budget: "+buffs+" regular buffs / "+music+" songs-dances; maximum 20 / 12."); }
    }
    static BuffCatalog select(String raw,String custom) {
        int tier=strength(raw);List<BuffCatalog.Entry> list=new ArrayList<BuffCatalog.Entry>();
        if(tier==3)list.addAll(parseList(custom));
        else {
            for(int id:BASIC_IDS) {
                SupportedBuffs.Spec spec=SupportedBuffs.get(id);
                list.add(new BuffCatalog.Entry(id,tier==1?1:Math.min(2,spec.durations.length)));
            }
            if(tier==2)list.addAll(parseList(MID_MUSIC));
        }
        validate(list);
        return BuffCatalog.strength(tier==1?"Basic":tier==2?"Mid":"Custom",list);
    }
}
