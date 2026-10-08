/*
 * TameCollarSummon.java
 *
 * Dedicated owner-validated bypass for the Creature Collar Passport.
 */
package taming;

import org.l2jmobius.gameserver.handler.IBypassHandler;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.instance.Item;

/*
 * Owner-validated bypasses for the Creature Collar Passport: bring a beast out,
 * and wear or remove its collar.
 *
 * Wearing is this module's own state. The core has no worn flag for a pet collar
 * and nothing to draw differently for one, so the whole of it is a column on the
 * profile plus this handler. What it buys is that a player with several collars
 * says which beast they mean: recall answers the worn one, and login brings the
 * worn one back on its own.
 */


public class TameCollarSummon implements IBypassHandler
{
	private static final String[] COMMANDS =
	{
			"tamecollar_summon",
			"tamecollar_wear"
	};

	@Override
	public boolean onCommand(String command, Player player, Creature bypassOrigin)
	{
		if (player == null)
		{
			return false;
		}
		final int separator = command.indexOf(' ');
		final String parameter = separator > 0 ? command.substring(separator + 1).trim() : "";
		try
		{
			final int collarObjectId = Integer.parseInt(parameter);
			final Item collar = player.getInventory().getItemByObjectId(collarObjectId);
			if ((collar == null) || (collar.getId() != TamingManager.getCollarItemId()))
			{
				player.sendMessage("That collar is not in your inventory.");
				return false;
			}
			if (command.startsWith("tamecollar_wear"))
			{
				return wear(player, collar);
			}
			return DynamicPetSummon.summon(player, collar);
		}
		catch (NumberFormatException e)
		{
			player.sendMessage("Invalid collar reference.");
			return false;
		}
	}

	/**
	 * Wears a collar, or takes it off if it was the one already worn.
	 *
	 * <p>Pressing the same collar twice is how a wearer takes it off, which is why
	 * there is no separate remove action: one button and its current state is the
	 * whole of the interaction.
	 *
	 * <p>Wearing brings the beast out. A worn collar means its beast is the one the
	 * player wants, so leaving them to find it in a list of collars would be a step
	 * with no purpose. Taking a collar off puts a beast that is already out away,
	 * because leaving it standing there while its collar is no longer worn would
	 * mean two worn collars' worth of state and one summon slot.
	 */
	private static boolean wear(Player player, Item collar)
	{
		final int collarObjectId = collar.getObjectId();
		final TameProfile profile = TameProfileRepository.load(player.getObjectId(), collarObjectId);
		if (profile == null)
		{
			player.sendMessage("That collar has no beast recorded on it yet.");
			return false;
		}
		if (profile.isWorn())
		{
			if (!TameProfileRepository.unwear(player.getObjectId()))
			{
				player.sendMessage("That collar could not be removed.");
				return false;
			}
			TameProfileRepository.logEvent(profile.getUuid(), "WEAR", "collar=" + collarObjectId + ";removed");
			// Only the beast belonging to this very collar is put away, never
			// whatever happens to be summoned.
			if (player.hasSummon() && (player.getSummon().getControlObjectId() == collarObjectId))
			{
				player.getSummon().unSummon(player);
			}
			player.sendMessage("You take off " + (profile.getPetName().isEmpty() ? "the collar" : profile.getPetName()) + ".");
			return true;
		}
		if (profile.getWoundFlags() > 0)
		{
			player.sendMessage(profile.getPetName() + " is wounded and cannot be worn until it is healed. Use .tameheal, or feed it.");
			return false;
		}
		// Captured before the write, because wear() moves the flag with a single
		// UPDATE over every row this owner owns - worn=(collar_object_id=?). There is
		// no way to put it back afterwards without knowing where it was.
		final int previouslyWorn = TameProfileRepository.findWornCollar(player.getObjectId());
		if (!TameProfileRepository.wear(player.getObjectId(), collarObjectId))
		{
			player.sendMessage("That collar could not be worn.");
			return false;
		}
		// The database write above happens before the summon, so a summon that fails
		// has to be undone. Otherwise the row claims a beast is out that never came
		// out, and the next login tries to restore a summon the player never asked
		// for. summon() has already told the player why it refused - summon slot taken,
		// mounted, wounded - so this only has to put the state back and stay quiet.
		if (!DynamicPetSummon.summon(player, collar))
		{
			if (previouslyWorn > 0)
			{
				TameProfileRepository.wear(player.getObjectId(), previouslyWorn);
			}
			else
			{
				TameProfileRepository.unwear(player.getObjectId());
			}
			TameProfileRepository.logEvent(profile.getUuid(), "WEAR", "collar=" + collarObjectId + ";summon-failed;rolled-back");
			return false;
		}
		TameProfileRepository.logEvent(profile.getUuid(), "WEAR", "collar=" + collarObjectId + ";worn");
		player.sendMessage("You wear " + profile.getPetName() + ".");
		return true;
	}

	@Override
	public String[] getCommandList()
	{
		return COMMANDS;
	}
}

