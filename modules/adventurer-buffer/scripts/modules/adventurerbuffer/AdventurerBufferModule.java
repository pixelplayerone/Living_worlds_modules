// SPDX-License-Identifier: GPL-3.0-or-later
package modules.adventurerbuffer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.handler.BypassHandler;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.npc.OnNpcFirstTalk;
import org.l2jmobius.gameserver.model.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.modules.GameModule;
import org.l2jmobius.gameserver.modules.ModuleContext;

public final class AdventurerBufferModule implements GameModule
{
    private final List<Spawn> spawns=new ArrayList<>();
    private final Set<Npc> owned=ConcurrentHashMap.newKeySet();
    private BufferService service;
    private final List<ConsumerEventListener> dialogues=new ArrayList<>();

    static List<int[]> parsePlacements(List<String> lines)
    {
        List<int[]> result=new ArrayList<>(); Set<String> coordinates=new HashSet<>(); Set<Integer> gatekeepers=new HashSet<>();
        for (String raw:lines)
        {
            String line=raw.trim(); if (line.isEmpty() || line.startsWith("#")) { continue; }
            String[] fields=line.split(",",-1);
            if ((fields.length!=6) || fields[0].isBlank()) { throw new IllegalArgumentException("Invalid Adventurer Buffer placement: "+line); }
            int gk=Integer.parseInt(fields[1].trim()); int[] p=new int[4];
            for (int i=0;i<4;i++) { p[i]=Integer.parseInt(fields[i+2].trim()); }
            if ((Math.abs((long)p[0])>1000000) || (Math.abs((long)p[1])>1000000) || (Math.abs((long)p[2])>100000)
                || (p[3]<0) || (p[3]>65535) || (gk<=0) || !gatekeepers.add(gk)
                || !coordinates.add(p[0]+","+p[1]+","+p[2]))
            { throw new IllegalArgumentException("Duplicate or invalid Adventurer Buffer placement: "+line); }
            result.add(p);
        }
        if (result.isEmpty()) { throw new IllegalArgumentException("Adventurer Buffer placements must not be empty."); }
        return result;
    }

    @Override
    public void onEnable(ModuleContext context)
    {
        if (!context.config().getBoolean("Enabled",false)) { return; }
        if (service!=null) { throw new IllegalStateException("Adventurer Buffer is already enabled; restart required."); }
        // This SDK exposes the exclusive experience-table bound (81 for playable level 80).
        int playableMax=ExperienceData.getInstance().getMaxLevel()-1;
        LevelRange range=LevelRange.parse(context.config().getString("MinLevel","1"),context.config().getString("MaxLevel","32"),playableMax);
        if (BypassHandler.getInstance().getHandler(BufferService.COMMAND)!=null) { throw new IllegalStateException("Adventurer Buffer bypass is already owned; refusing to replace it."); }
        if (NpcData.getInstance().getTemplate(BufferService.NPC_ID)==null) { throw new IllegalStateException("Adventurer Buffer NPC template missing; check module resource validation."); }
        int durationSeconds=BuffDuration.parse(context.config().getString("BuffDurationSeconds","3600"));
        try
        {
            BuffCatalog catalog=StrengthConfig.select(context.config().getString("Strength","1"),
                context.config().getString("CustomBuffs",StrengthConfig.DEFAULT_CUSTOM));
            catalog.resolve();
            List<int[]> locations=parsePlacements(Files.readAllLines(Path.of("modules","adventurer-buffer","config","placements.csv")));
            service=new BufferService(range,owned,durationSeconds,catalog);
            for (int[] p:locations)
            {
                Spawn spawn=new Spawn(BufferService.NPC_ID);
                spawn.setXYZ(p[0],p[1],p[2]);spawn.setHeading(p[3]);spawn.setRandomWalking(false);spawn.stopRespawn();
                spawns.add(spawn);
                Npc npc=spawn.doSpawn(false);
                if (npc==null) { throw new IllegalStateException("Adventurer Buffer spawn failed."); }
                owned.add(npc);
                final BufferService buffer=service;
                ConsumerEventListener listener=new ConsumerEventListener(npc,EventType.ON_NPC_FIRST_TALK,
                    (OnNpcFirstTalk event) -> {
                        String html=buffer.page(event.getPlayer(),event.getNpc());
                        if (html!=null) {
                            NpcHtmlMessage packet=new NpcHtmlMessage(event.getNpc().getObjectId());
                            packet.setHtml(html);event.getPlayer().sendPacket(packet);
                        }
                    },this);
                dialogues.add(listener);
                npc.addListener(listener);
            }
            context.handlers().registerBypass(service);
            service.active=true;
            context.logging().info("Adventurer Buffer: "+owned.size()+" owned town NPCs; levels "+range.min()+"-"+range.max()+"; restart for settings/removal.");
        }
        catch (Exception error)
        {
            onDisable(context);
            throw new IllegalStateException("Adventurer Buffer refused initialization; owned partial spawns cleaned up.",error);
        }
    }

    @Override
    public void onDisable(ModuleContext context)
    {
        if (service!=null)
        {
            service.active=false;
            if (BypassHandler.getInstance().getHandler(BufferService.COMMAND)==service) { BypassHandler.getInstance().removeHandler(service); }
        }
        for (ConsumerEventListener listener:dialogues) { listener.unregisterMe(); }
        dialogues.clear();
        for (Spawn spawn:spawns)
        {
            spawn.stopRespawn();
            for (Npc npc:new ArrayList<>(spawn.getSpawnedNpcs())) { npc.deleteMe(); }
        }
        spawns.clear();owned.clear();service=null;
    }

}
