// SPDX-License-Identifier: GPL-3.0-or-later
package modules.adventurerbuffer;

import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.PhantomPartyManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.network.ConnectionState;
import org.l2jmobius.gameserver.network.GameClient;

/** Action-time guards, not synchronization with native departure/effect threads. */
final class PartyTargets
{
    static final int NEARBY_DISTANCE=300;

    static boolean currentRequester(Player owner, GameClient client)
    {
        return (owner!=null) && (client!=null) && (owner.getClient()==client)
            && owner.isOnline() && client.isConnected() && !client.isDetached()
            && (client.getConnectionState()==ConnectionState.IN_GAME) && (client.getPlayer()==owner)
            && (World.getInstance().getPlayer(owner.getObjectId())==owner);
    }

    private static boolean member(Party party, Player player)
    {
        for (Player entry:party.getMembers()) { if (entry==player) { return true; } }
        return false;
    }

    static boolean eligible(Player owner, Player target, Party party, LevelRange levels)
    {
        if ((target==null) || target.isDead() || target.isCursedWeaponEquipped()
            || !levels.includes(target.getLevel())
            || (World.getInstance().getPlayer(target.getObjectId())!=target)) { return false; }
        if (target==owner) { return true; }
        // Native recruiter ownership covers saved-character and recruited companions.
        // No same-account, leader-only or ModuleCompanions-only restriction.
        if ((party==null) || (PhantomPartyManager.getInstance().getRecruitOwner(target)!=owner)) { return false; }
        return (owner.getParty()==party) && (target.getParty()==party)
            && member(party,owner) && member(party,target)
            && (target.getClient()==null) // reject human sessions; native clientless types require acceptance
            && (World.getInstance().getPlayer(target.getObjectId())==target)
            && !target.isDead() && !target.isCursedWeaponEquipped() && levels.includes(target.getLevel())
            && (target.getInstanceId()==owner.getInstanceId())
            && (owner.calculateDistance3D(target)<=NEARBY_DISTANCE)
            && GeoEngine.getInstance().canSeeTarget(owner,target);
    }
}
