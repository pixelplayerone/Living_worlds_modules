/*
 * TameCommandManager.java
 *
 * Server-only command state for one active collar-bound Pet.
 * Runtime script source; Java 8 compatible.
 */
package taming;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.item.instance.Item;

public final class TameCommandManager
{
	public static final String FOLLOW = "FOLLOW";
	public static final String GUARD = "GUARD";
	public static final String ATTACK = "ATTACK";
	public static final String HOLD = "HOLD";
	public static final String RETURN = "RETURN";

	private static final Map<Long, String> COMMANDS = new ConcurrentHashMap<>();

	private TameCommandManager()
	{
	}

	private static long key(Player player, int collarObjectId)
	{
		return (((long) player.getObjectId()) << 32) ^ (collarObjectId & 0xFFFFFFFFL);
	}

	public static String getCommand(Player player, int collarObjectId)
	{
		final String command = COMMANDS.get(key(player, collarObjectId));
		return command == null ? FOLLOW : command;
	}

	public static boolean execute(Player player, Item collar, String command)
	{
		if ((player == null) || (collar == null) || !player.hasSummon() || !player.getSummon().isPet() || (player.getSummon().getControlObjectId() != collar.getObjectId()))
		{
			if (player != null)
			{
				player.sendMessage("Summon this tame before issuing commands.");
			}
			return false;
		}

		final Summon pet = player.getSummon();
		final String normalized = command == null ? "" : command.toUpperCase();
		if (FOLLOW.equals(normalized))
		{
			pet.setTarget(null);
			pet.setFollowStatus(true);
			store(player, collar, FOLLOW);
			player.sendMessage("Tame command: FOLLOW.");
			return true;
		}
		if (GUARD.equals(normalized))
		{
			pet.setTarget(null);
			pet.setFollowStatus(true);
			store(player, collar, GUARD);
			player.sendMessage("Tame command: GUARD OWNER.");
			return true;
		}
		if (HOLD.equals(normalized))
		{
			pet.stopMove(null);
			pet.setTarget(null);
			pet.setFollowStatus(false);
			pet.getAI().setIntention(Intention.IDLE);
			store(player, collar, HOLD);
			player.sendMessage("Tame command: HOLD POSITION.");
			return true;
		}
		if (RETURN.equals(normalized))
		{
			pet.setTarget(null);
			pet.setFollowStatus(true);
			store(player, collar, RETURN);
			player.sendMessage("Tame command: RETURN.");
			return true;
		}
		if (ATTACK.equals(normalized))
		{
			final WorldObject target = player.getTarget();
			if ((target == null) || (target == player) || (target == pet))
			{
				player.sendMessage("Select a valid creature target first.");
				return true;
			}
			if (target.isPlayer())
			{
				player.sendMessage("Tames cannot be ordered to attack another player.");
				return true;
			}
			pet.setFollowStatus(false);
			pet.setTarget(target);
			pet.getAI().setIntention(Intention.ATTACK, target);
			store(player, collar, ATTACK);
			player.sendMessage("Tame command: ATTACK TARGET.");
			return true;
		}
		player.sendMessage("Unknown tame command.");
		return false;
	}

	private static void store(Player player, Item collar, String command)
	{
		COMMANDS.put(key(player, collar.getObjectId()), command);
	}
}
