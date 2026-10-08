/*
 * TameMonster.java
 *
 * Runtime custom effect for the Beast Binding skill.
 */
package taming;

import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.conditions.Condition;
import org.l2jmobius.gameserver.model.effects.AbstractEffect;
import org.l2jmobius.gameserver.model.effects.EffectType;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.skill.Skill;


public class TameMonster extends AbstractEffect
{
	private static final Logger LOGGER = Logger.getLogger(TameMonster.class.getName());

	public TameMonster(Condition attachCond, Condition applyCond, StatSet set, StatSet params)
	{
		super(attachCond, applyCond, set, params);
	}

	@Override
	public boolean isInstant()
	{
		return true;
	}

	@Override
	public void onStart(Creature effector, Creature effected, Skill skill)
	{
		if (!(effector instanceof Player) || !(effected instanceof Monster))
		{
			return;
		}

		final Player player = (Player) effector;
		final Monster target = (Monster) effected;

		// Phantom combat bodies use Monster templates for AI/combat, but are not
		// valid Pet species. Keep them blocked for every player, including GMs.
		if (target.getTemplate().isFakePlayer())
		{
			player.sendMessage("Phantoms cannot be tamed.");
			return;
		}

		// Grand bosses - Valakas, Antharas and anything else the worldserver marks as one -
		// are on a fixed schedule with server-wide state behind them (the gate opens, the
		// raid despawns, the raid status flag flips). None of that is reachable from a pet,
		// so a capture here could only ever leave the encounter broken. This was not
		// blocked anywhere before, despite the module already classifying GrandBoss
		// separately from RaidBoss for food and for skill selection.
		//
		// Ordinary BOSSES and RAIDBOSSES stay capturable: those are per-spawn creatures with
		// an ordinary respawn timer, so they come back normally (see the respawn restore in
		// TamingManager).
		if (target.getTemplate().isType("GrandBoss"))
		{
			player.sendMessage("Grand bosses cannot be tamed.");
			return;
		}

		final TamingEntry entry = TamingData.getInstance().getEntry(target);
		if (entry == null)
		{
			player.sendMessage("This creature cannot be tamed.");
			return;
		}

		// Prevent double requests from consuming two reagents or converting the
		// same target twice. The lock is per player, not global to the server.
		synchronized (player)
		{
			if (player.hasSummon() || player.isMounted())
			{
				player.sendMessage("You already have a pet/summon out. Dismiss it first.");
				return;
			}
			if (target.isDead() || target.isDecayed() || !target.isSpawned())
			{
				player.sendMessage("That creature is no longer available for taming.");
				return;
			}
			if (player.getInventory().getInventoryItemCount(entry.getRequiredItem(), -1) < 1)
			{
				player.sendMessage("You need the correct taming item for this creature.");
				return;
			}

			final TamingData rules = TamingData.getInstance();
			double chance = entry.getBaseChance();
			if (entry.isRaid())
			{
				// A raid keeps the wounded and level-gap modifiers, because "weaken it first"
				// and "it outlevels you" are both things that should matter against a
				// boss. It just cannot climb past its own low cap, so beating a raid down to
				// a sliver never turns it into an ordinary capture.
				final double maxHp = target.getMaxHp();
				final double hpPercent = maxHp > 0 ? (target.getCurrentHp() / maxHp * 100.0) : 0.0;
				chance += (100.0 - hpPercent) * rules.getHpWeight();
				final int levelGap = target.getLevel() - player.getLevel();
				if (levelGap > 0)
				{
					chance -= levelGap * rules.getLevelGapPenalty();
				}
				chance = Math.max(rules.getChanceFloor(), Math.min(rules.getRaidChanceCap(), chance));
			}
			else
			{
				// An ordinary creature is a flat roll. No wounded bonus, no level-gap
				// penalty - so the number in module.ini is exactly the number that happens,
				// and there is no combination of conditions that quietly moves it.
				chance = Math.max(0.0, Math.min(rules.getNormalChanceCap(), chance));
			}

			if (ThreadLocalRandom.current().nextDouble(100.0) > chance)
			{
				if (player.destroyItemByItemId(ItemProcessType.DESTROY, entry.getRequiredItem(), 1, target, false))
				{
					player.sendMessage("The taming attempt failed! " + target.getName() + " breaks free, enraged.");
					target.addDamageHate(player, 0, 500);
				}
				return;
			}

			// Consume only after all validation has passed. TamingManager returns
			// false on pet creation failure, in which case the reagent is refunded.
			if (!player.destroyItemByItemId(ItemProcessType.DESTROY, entry.getRequiredItem(), 1, target, false))
			{
				player.sendMessage("The taming item could not be consumed. Try again.");
				return;
			}

			if (!TamingManager.getInstance().convertToPet(player, target, entry))
			{
				player.getInventory().addItem(ItemProcessType.REWARD, entry.getRequiredItem(), 1, player, target);
				player.sendMessage("Taming failed safely; your taming item was refunded.");
			}
		}
	}

	@Override
	public EffectType getEffectType()
	{
		return EffectType.NONE;
	}
}
