// SPDX-License-Identifier: GPL-3.0-or-later
package modules.adventurerbuffer;

import java.util.*;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.model.skill.Skill;

/** Owner-edited selection, never an arbitrary client-provided skill ID. Immutable until restart. */
final class BuffCatalog {
    static final class Entry {
        final int id,rank; final SupportedBuffs.Spec spec;
        Entry(int i,int r){id=i;rank=r;spec=SupportedBuffs.get(i);}
        Skill resolve() {
            Skill s=SkillData.getInstance().getSkill(id,rank);
            if(s==null || s.getId()!=id || s.getLevel()!=rank || !s.isActive() || s.isPassive() || s.isToggle()
                || !s.isContinuous() || s.getAbnormalTime()==Integer.MAX_VALUE || s.isDebuff() || s.isDamage() || s.isAbnormalInstant() || s.isHeroSkill() || s.isGMSkill()
                || s.getAbnormalTime()!=spec.durations[rank-1] || s.getAbnormalTime()<=0
                || !spec.type.equals(s.getAbnormalType().name()) || !spec.target.equals(s.getTargetType().name())
                || s.isDance()!=spec.dance) {
                throw new IllegalStateException("Unsupported or modified support skill "+id+":"+rank+"; inspect native skill data/modifiers.");
            }
            return s;
        }
    }
    static BuffCatalog strength(String label,List<Entry> entries) { return new BuffCatalog(label,entries); }
    final String profile;
    final List<Entry> entries;
    final boolean bulk;
    private BuffCatalog(String p,List<Entry> e){profile=p;entries=Collections.unmodifiableList(new ArrayList<Entry>(e));bulk=!"Full".equals(p);}
    static BuffCatalog parse(List<String> lines,String schema,String selected) {
        if(!"1".equals(schema.trim()))throw new IllegalArgumentException("CatalogSchema must be 1.");
        if(!Arrays.asList("Basic","Advanced","Full").contains(selected))throw new IllegalArgumentException("Profile must be Basic, Advanced or Full.");
        Map<String,List<Entry>> profiles=new LinkedHashMap<String,List<Entry>>();
        for(String p:Arrays.asList("Basic","Advanced","Full"))profiles.put(p,new ArrayList<Entry>());
        Set<String> seen=new HashSet<String>();
        for(String raw:lines){
            String line=raw.trim();if(line.isEmpty()||line.startsWith("#"))continue;
            String[] f=line.split(",",-1);
            if(f.length!=3)throw new IllegalArgumentException("Expected Profile,SkillId,Rank: "+line);
            String p=f[0].trim();if(!profiles.containsKey(p))throw new IllegalArgumentException("Unknown profile: "+p);
            if(!f[1].trim().matches("[0-9]+")||!f[2].trim().matches("[0-9]+"))throw new IllegalArgumentException("IDs and ranks must be positive decimal integers.");
            int id=Integer.parseInt(f[1].trim()),rank=Integer.parseInt(f[2].trim());
            SupportedBuffs.Spec spec=SupportedBuffs.get(id);
            if(spec==null||rank<1||rank>spec.durations.length)throw new IllegalArgumentException("Unsupported ID/rank: "+id+":"+rank);
            if(!seen.add(p+":"+id))throw new IllegalArgumentException("Duplicate skill ID in "+p+": "+id);
            if("Basic".equals(p)&&(spec.dance||spec.durations[rank-1]!=1200))throw new IllegalArgumentException("Basic accepts ordinary 1200-second buffs only.");
            profiles.get(p).add(new Entry(id,rank));
        }
        for(Map.Entry<String,List<Entry>> p:profiles.entrySet()){
            if(p.getValue().isEmpty())throw new IllegalArgumentException("Empty profile: "+p.getKey());
            if(!"Full".equals(p.getKey())){
                Set<String> groups=new HashSet<String>();
                for(Entry e:p.getValue())if(!groups.add(e.spec.type))throw new IllegalArgumentException("Conflicting stacking group in "+p.getKey()+": "+e.spec.type+"; offer alternatives in Full instead.");
            }
        }
        return new BuffCatalog(selected,profiles.get(selected));
    }
    Skill[] resolve() {
        Skill[] result=new Skill[entries.size()];
        for(int i=0;i<result.length;i++)result[i]=entries.get(i).resolve();
        return result;
    }
}
