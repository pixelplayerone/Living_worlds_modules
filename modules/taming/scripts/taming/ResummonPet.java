/*
 * ResummonPet.java
 *
 * Runtime effect for Beast Recall (9303). It restores the Pet from the
 * owner's collar by using the same validated summon path as the collar UI.
 */
package taming;

import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.conditions.Condition;
import org.l2jmobius.gameserver.model.effects.AbstractEffect;
import org.l2jmobius.gameserver.model.effects.EffectType;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.skill.Skill;


public class ResummonPet extends AbstractEffect
{
	public ResummonPet(Condition attachCond, Condition applyCond, StatSet set, StatSet params)
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
		if (!(effector instanceof Player))
		{
			return;
		}

		final Player player = (Player) effector;

		// This effect is instant and is re-applied when the skill is granted, which
		// happens on every login. Acting here would then fight the login restore for
		// the summon slot and hand back whichever beast happened to sort first.
		if (player.hasSummon())
		{
			return;
		}

		// A player may own several collars, so "the beast" is only meaningful once
		// something says which one. The worn collar answers that, and the most
		// recently used one is the fallback so the crystal still does something for a
		// player who has never worn a collar at all.
		final Item collar = findWornCollar(player);
		if (collar == null)
		{
			player.sendMessage("No beast collar in your inventory is ready to answer. Wear one from its passport, or heal a wounded beast with .tameheal first.");
			return;
		}

		DynamicPetSummon.summon(player, collar);
	}

	/**
	 * The collar this recall should answer with.
	 *
	 * <p>Recall falls back to the most recently used collar where the login restore
	 * deliberately does not. The difference is who asked: this is a request the
	 * player just made, so answering it with any beast of theirs beats answering it
	 * with nothing, while login spawns without a request and must not. Both halves of
	 * that split live here, rather than inside the repository lookup, so that every
	 * other caller of "the worn collar" gets a collar that is actually worn.
	 */
	private static Item findWornCollar(Player player)
	{
		final Item worn = DynamicPetSummon.findWornCollar(player);
		if (worn != null)
		{
			return worn;
		}
		// Nothing worn, or the worn collar is no longer in this inventory. Either way
		// recall still has to be worth something to a player who has never worn a
		// collar, so it falls back to the old behaviour.
		return DynamicPetSummon.findLastSummonedCollar(player);
	}

	@Override
	public EffectType getEffectType()
	{
		return EffectType.NONE;
	}
}
