// SPDX-License-Identifier: GPL-3.0-or-later
package modules.adventurerbuffer;

import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.handler.IBypassHandler;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

public final class BufferService implements IBypassHandler
{
    public static final int NPC_ID = 59850;
    public static final String COMMAND = "adventurerbuffer";
    private static final int[] IDS = {1045,1048,1204,1040,1036,1085,1059,1086,1068,1268,1078,1044};
    private static final String[] NAMES = {"Bless the Body", "Bless the Soul", "Wind Walk", "Shield", "Magic Barrier", "Acumen", "Empower", "Haste", "Might", "Vampiric Rage", "Concentration", "Regeneration"};
    private static final String[] EFFECTS = {"HP +10%", "MP +10%", "Speed +20", "P.Def +8%", "M.Def +23%", "Cast speed +15%", "M.Atk +55%", "Atk speed +15%", "P.Atk +8%", "HP absorption 6%", "Cancel stat -18", "HP regen +10%"};
    private final LevelRange levels;
    private final int durationSeconds;
    private final BuffCatalog catalog;
    private final Set<Npc> owned;
    volatile boolean active;

    BufferService(LevelRange range, Set<Npc> ownedNpcs) { this(range,ownedNpcs,3600); }
    BufferService(LevelRange range, Set<Npc> ownedNpcs, int seconds) {
        this(range,ownedNpcs,seconds,null);
    }
    BufferService(LevelRange range, Set<Npc> ownedNpcs, int seconds, BuffCatalog profile) {
        durationSeconds=BuffDuration.parse(Integer.toString(seconds)); levels=range; owned=ownedNpcs; catalog=profile;
    }
    private Skill[] selectedSkills() { return catalog==null ? resolveSkills() : catalog.resolve(); }
    private boolean basic() { return catalog==null || "Basic".equals(catalog.profile); }
    private boolean bulk() { return catalog==null || catalog.bulk; }
    private static String escape(String s) { return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }


    static Skill[] resolveSkills()
    {
        Skill[] skills=new Skill[IDS.length];
        for (int i=0;i<IDS.length;i++)
        {
            skills[i]=SkillData.getInstance().getSkill(IDS[i],1);
            if ((skills[i]==null) || (skills[i].getAbnormalTime()!=1200)
                || !skills[i].isContinuous() || skills[i].isPassive() || skills[i].isToggle()
                || skills[i].isDebuff() || skills[i].isAbnormalInstant())
            {
                throw new IllegalStateException("Adventurer Buffer requires ordinary level-one skill " + IDS[i] + " with 1200-second duration; inspect conflicting skill modifiers.");
            }
        }
        return skills;
    }

    private boolean canInteract(Player player, Creature target)
    {
        if (!active || (player==null) || !(target instanceof Npc)) { return false; }
        Npc npc=(Npc)target;
        return owned.contains(npc)
            && (npc.getId()==NPC_ID) && !npc.isDead() && !player.isDead() && !player.isCursedWeaponEquipped()
            && (player.getInstanceId()==npc.getInstanceId())
            && (player.calculateDistance3D(npc)<=Npc.INTERACTION_DISTANCE)
            && GeoEngine.getInstance().canSeeTarget(player,npc);
    }

    String page(Player player, Npc npc) { return page(player,npc,0); }
    private String page(Player player, Npc npc, int pageNumber)
    {
        if (!canInteract(player,npc)) { return null; }
        final Skill[] displayed;
        try { displayed=selectedSkills(); } catch (IllegalStateException error) { return null; }
        if (pageNumber<0 || pageNumber>=(displayed.length+11)/12) { return null; }
        String prefix="npc_"+npc.getObjectId()+"_"+COMMAND;
        StringBuilder html=new StringBuilder("<html><body><center><font color=\"LEVEL\">Adventurer Buffer</font><br>");
        html.append(basic() ? "Free basic support: levels " : catalog.profile+" support: levels ").append(levels.min()).append(" through ").append(levels.max()).append(".<br>");
        if (basic()) { boolean rankOne=true;for (Skill s:displayed) { rankOne&=s.getLevel()==1; }html.append(rankOne ? "Ordinary skill level 1.<br>" : "Owner-configured skill ranks.<br>"); }
        String[] labels={"Buff Me","Buff Party","Remove My Buffs","Remove Party Buffs"};
        String[] actions={"all","party","remove","remove-party"};
        for (int i=0;i<labels.length;i++)
        {
            html.append("<button value=\"").append(labels[i])
                .append("\" action=\"bypass -h ").append(prefix).append(" ").append(actions[i])
                .append("\" width=134 height=21 back=\"L2UI_ch3.BigButton3_over\" fore=\"L2UI_ch3.BigButton3\"><br>");
        }
        html.append("<br>Party actions: you and eligible<br1>controlled companions.<br>Removal also clears debuffs, toggles,<br1>and indefinite active effects.<br1>Includes your summon or pet.<br1>Party removal also includes companions' current summons.<br>Or select only what you need:<br><table width=280>");
        for (int i=pageNumber*12;i<Math.min(displayed.length,pageNumber*12+12);i++)
        {
            Skill skill=displayed[i];
            int canonical=-1;
            if (basic() && skill.getLevel()==1) { for (int k=0;k<IDS.length;k++) { if (IDS[k]==skill.getId()) { canonical=k;break; } } }
            html.append("<tr><td><a action=\"bypass -h ").append(prefix).append(" one ").append(i).append("\">");
            if (canonical>=0) { html.append(NAMES[canonical]).append("</a></td><td>").append(EFFECTS[canonical]); }
            else { html.append(escape(skill.getName())).append(" Lv ").append(skill.getLevel()).append("</a>"); }
            html.append("</td></tr>");
        }
        html.append("</table>");
        if (catalog!=null) { html.append("<br>").append(catalog.profile).append(" profile."); }
        if (!bulk()) { html.append("<br>Full: select individual effects.<br1>Buff Me and Buff Party sets are unavailable."); }
        if (pageNumber>0) { html.append("<br><a action=\"bypass -h ").append(prefix).append(" page ").append(pageNumber-1).append("\">Previous</a>"); }
        if ((pageNumber+1)*12<displayed.length) { html.append("<br><a action=\"bypass -h ").append(prefix).append(" page ").append(pageNumber+1).append("\">Next</a>"); }
        html.append("<br><font color=\"D8C078\">Customize buffer: config/module.ini<br1>Restart the game server to apply.</font>");
        return html.append("</center></body></html>").toString();
    }

    private static SlotPolicy.Effect effect(Skill skill){return new SlotPolicy.Effect(skill.getId(),skill.getLevel(),skill.getAbnormalLevel(),skill.getAbnormalType().name(),skill.isDance());}
    private String refusal(Player player,java.util.List<Skill> selected){
        java.util.List<SlotPolicy.Effect> old=new java.util.ArrayList<SlotPolicy.Effect>();
        java.util.List<SlotPolicy.Effect> next=new java.util.ArrayList<SlotPolicy.Effect>();
        for(org.l2jmobius.gameserver.model.skill.BuffInfo b:player.getEffectList().getBuffs())old.add(effect(b.getSkill()));
        for(org.l2jmobius.gameserver.model.skill.BuffInfo b:player.getEffectList().getDances())old.add(effect(b.getSkill()));
        for(Skill s:selected){
            if(player.getEffectList().getBlockedAbnormalTypes().contains(s.getAbnormalType()))return "This effect is currently blocked.";
            next.add(effect(s));
        }
        return SlotPolicy.refusal(old,next,player.getEffectList().getBuffCount(),player.getEffectList().getDanceCount(),player.getStat().getMaxBuffCount(),org.l2jmobius.gameserver.config.PlayerConfig.DANCES_MAX_AMOUNT);
    }
    @Override
    public boolean onCommand(String command, Player player, Creature target)
    {
        if (!canInteract(player,target) || (command==null)) { return false; }
        final GameClient client=player.getClient();
        if (!PartyTargets.currentRequester(player,client)) { return false; }
        String[] words=command.trim().split("\\s+");
        if ((words.length<2) || !COMMAND.equals(words[0])) { return false; }
        boolean all=(words.length==2) && "all".equals(words[1]);
        boolean partyAction=(words.length==2) && ("party".equals(words[1]) || "remove-party".equals(words[1]));
        boolean removal=(words.length==2) && ("remove".equals(words[1]) || "remove-party".equals(words[1]));
        if ((words.length==3) && "page".equals(words[1])) {
            int pageNumber;try { pageNumber=Integer.parseInt(words[2]); } catch (NumberFormatException error) { return false; }
            String html=page(player,(Npc)target,pageNumber);if (html==null) { return false; }
            NpcHtmlMessage packet=new NpcHtmlMessage(target.getObjectId());packet.setHtml(html);player.sendPacket(packet);return true;
        }
        if (!removal && (all || partyAction) && !bulk()) { player.sendMessage("Full profile offers individual effects; choose one from the list.");return false; }
        int choice=-1;
        if ((words.length==3) && "one".equals(words[1]))
        {
            try { choice=Integer.parseInt(words[2]); } catch (NumberFormatException error) { return false; }
        }
        if (!removal && !all && !partyAction && ((choice<0) || (choice>=(catalog==null ? IDS.length : catalog.entries.size())))) { return false; }
        if (!partyAction && !levels.includes(player.getLevel()))
        {
            player.sendMessage("Adventurer Buffer is available at levels " + levels.min() + " through " + levels.max() + ".");
            return false;
        }
        final Skill[] skills;
        try { skills=removal ? new Skill[0] : selectedSkills(); }
        catch (IllegalStateException error) { player.sendMessage("Adventurer Buffer is unavailable: skill data conflicts. Ask the operator to check the module."); return false; }
        final Party party=partyAction ? player.getParty() : null;
        final List<Player> recipients=new ArrayList<Player>();
        recipients.add(player);
        if (party!=null)
        {
            for (Player member:new ArrayList<Player>(party.getMembers()))
            {
                if ((member!=player) && !recipients.contains(member)) { recipients.add(member); }
            }
        }
        if (!removal) { return grant(player,target,client,party,partyAction,recipients,skills,all || partyAction,choice); }
        for (Player recipient:recipients)
        {
            if (removal)
            {
                if (!canInteract(player,target) || !PartyTargets.currentRequester(player,client)
                    || (partyAction && (player.getParty()!=party))) { return false; }
                if (!PartyTargets.eligible(player,recipient,party,levels)) { continue; }
                if (!canInteract(player,target) || !PartyTargets.currentRequester(player,client)
                    || (partyAction && (player.getParty()!=party))) { return false; }
                // Same native cancellation operation as installed PresetBufferUi cleanup.
                // Clears debuffs/toggles too; deliberately not the discarded selective helper.
                recipient.stopAllEffects();
                final Summon summon=recipient.getSummon();
                if ((summon!=null) && canInteract(player,target)
                    && PartyTargets.currentRequester(player,client)
                    && (!partyAction || (player.getParty()==party))
                    && PartyTargets.eligible(player,recipient,party,levels)
                    && (recipient.getSummon()==summon))
                {
                    summon.stopAllEffects();
                }
                continue;
            }
        }
        String html=page(player,(Npc)target);
        if (html!=null) { NpcHtmlMessage packet=new NpcHtmlMessage(target.getObjectId());packet.setHtml(html);player.sendPacket(packet); }
        return true;
    }

    private boolean requestCurrent(Player player, Creature target, GameClient client, Party party, boolean partyAction) {
        return canInteract(player,target) && PartyTargets.currentRequester(player,client)
            && (!partyAction || player.getParty()==party);
    }
    private boolean grant(Player player, Creature target, GameClient client, Party party, boolean partyAction,
        List<Player> recipients, Skill[] skills, boolean all, int choice) {
        java.util.List<Skill> selected=all ? java.util.Arrays.asList(skills) : java.util.Collections.singletonList(skills[choice]);
        int complete=0,skipped=0,interrupted=0,totalCalls=0;
        for (Player recipient:recipients) {
            String reason=null;int calls=0;
            if (!requestCurrent(player,target,client,party,partyAction)) {
                interrupted++;
                reason="requester connection, party or NPC access changed";
            } else if (!PartyTargets.eligible(player,recipient,party,levels)) {
                skipped++;reason="not currently eligible or controlled";
            } else {
                // Strength 1 keeps native per-skill stacking; stronger buffs cannot block the rest.
                if (!basic()) { reason=refusal(recipient,selected); }
                if (reason!=null) { skipped++; }
                else {
                    for (Skill skill:selected) {
                        if (!requestCurrent(player,target,client,party,partyAction)
                            || !PartyTargets.eligible(player,recipient,party,levels)
                            || !requestCurrent(player,target,client,party,partyAction)) {
                            reason="eligibility, control, range or connection changed";break;
                        }
                        if (!basic()) { reason=refusal(recipient,java.util.Collections.singletonList(skill));if(reason!=null)break; }
                        skill.applyEffects(target,recipient,true,durationSeconds);calls++;totalCalls++;
                    }
                    if (calls==selected.size()) { complete++; }
                    else { interrupted++; }
                }
            }
            if (reason!=null && PartyTargets.currentRequester(player,client)) {
                player.sendMessage("Character #"+recipient.getObjectId()+": "+(calls>0 ? "interrupted after "+calls+" buff requests; " : "not fully processed; ")+reason+".");
            }
        }
        if (PartyTargets.currentRequester(player,client)) {
            player.sendMessage("Buff requests: completed "+complete+", skipped "+skipped+", interrupted "+interrupted+"; "+totalCalls+" skill calls submitted. Native stacking determines which effects take hold.");
            String html=page(player,(Npc)target);
            if (html!=null) { NpcHtmlMessage packet=new NpcHtmlMessage(target.getObjectId());packet.setHtml(html);player.sendPacket(packet); }
        }
        // This reports full dispatch, not native effect acceptance. No rollback is promised.
        return skipped==0 && interrupted==0;
    }

    @Override
    public String[] getCommandList() { return new String[]{COMMAND}; }
}
