// SPDX-License-Identifier: GPL-3.0-or-later
package modules.adventurerbuffer;
import java.util.*;

/** Conservative preflight. Never remove effects or expand native slot limits. */
final class SlotPolicy {
    static final class Effect {
        final int id,rank,power;final String group;final boolean dance;
        Effect(int id,int rank,int power,String group,boolean dance){this.id=id;this.rank=rank;this.power=power;this.group=group;this.dance=dance;}
    }
    static String refusal(List<Effect> existing,List<Effect> requested,int buffs,int dances,int maxBuffs,int maxDances){
        Set<String> groups=new HashSet<String>();
        int addBuffs=0,addDances=0;
        for(Effect next:requested){
            if(!groups.add(next.group))return "Selection contains conflicting effects. Choose one at a time.";
            boolean replaces=false;
            for(Effect old:existing){
                if(old.id==next.id || old.group.equals(next.group)){
                    if(old.id!=next.id)return "A different effect in the same stacking group is already active. Let it expire before choosing this alternative.";
                    // Same-ID effects occupy the same slot. Native abnormal power decides replacement.
                    // Equal power can refresh even a lower skill rank (Queen/Seraphim ranks 3-13).
                    // Still dispatch the other skills when changing to a lower strength.
                    replaces=true;
                }
            }
            if(!replaces){if(next.dance)addDances++;else addBuffs++;}
        }
        if(buffs+addBuffs>maxBuffs || dances+addDances>maxDances)return "Not enough free buff or song/dance slots. Select fewer buffs or wait for existing effects to expire.";
        return null;
    }
}
