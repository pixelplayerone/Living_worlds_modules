/*
 * TameSkillBar.java
 *
 * Puts a tame's inherited techniques on the client's pet skill bar.
 */
package taming;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.holders.SkillHolder;

/**
 * Binds a tame's technique deck to the pet skill bar of the client.
 *
 * <p>This is the supported route for this, and it took a while to find. The
 * client's own pet skill bar is not the player's shortcut bar: pressing one of
 * those buttons sends {@code RequestActionUse} with an action id in the
 * 1000-1040 range, and the server answers it like this.
 *
 * <pre>
 * private void useSkill(Player player, String parameter, WorldObject target, boolean isPet)
 * {
 *     Summon summon = player.getSummon();
 *     validateSummon(player, summon, isPet);
 *     canControl(player, summon);
 *     SkillHolder holder = summon.getTemplate().getParameters().getSkillHolder(parameter);
 *     if (holder == null) return;                 // silent no-op
 *     Skill skill = holder.getSkill();
 *     summon.setTarget(target);
 *     summon.useMagic(skill, ctrl, shift);        // the PET casts
 * }
 * </pre>
 *
 * <p>So the skill a button casts is not sent by the client at all - it is read
 * off the pet's own NPC template. Everything a tame needs here is already
 * module-owned: {@link TameForge} hands out a synthetic template per collar, so
 * the parameters on it are ours to set. The client supplies the buttons, the
 * target, and the pet as the caster.
 *
 * <p>Deliberately not done instead:
 * <ul>
 * <li>Putting the skills in the player's skill tab. Those are dispatched by the
 * player's own branch and cast as the player - the pet is never involved.</li>
 * <li>Technique tokens in the pet inventory. The inventory packet has no
 * displayId field, so the client resolves the raw item id and an id of our own
 * draws a black icon with no use action. Re-using stock Echo Crystal ids does
 * give a usable icon, but a module item file cannot override a stock item id in
 * this build - see the note in data/items/9300-9399.xml - so the token would
 * need {@code for_npc} on a stock template that stays untouched.</li>
 * </ul>
 *
* <p>One caveat worth knowing: this path goes straight to {@code useMagic}, so
	 * the gating is applied here, when the bar is built, rather than at press time -
	 * a locked or un-awakened technique is simply not bound and its button stays
	 * dead. The bar is rebuilt on every summon, so a tame that has just levelled up
	 * fills its slots without being re-forged.
 */
public final class TameSkillBar
{
	/**
	 * Template parameters the core reads, in the order the buttons are filled.
	 *
	 * <p>These names are the whole contract with {@code RequestActionUse}; the
	 * button a player presses is chosen by the client, not by us, and each one
	 * lands on exactly one of these. Which visible button corresponds to which
	 * entry is client-side layout, so the first pass deliberately uses four
	 * different techniques rather than guessing - that way the first test also
	 * reveals the mapping.
	 */
	public static final String[] PARAMETERS =
	{
		"DDMagic",
		"HealMagic",
		"Buff",
		"Buff1"
	};

	private TameSkillBar()
	{
	}

	/**
	 * Clears the bar and refills it from the profile's deck.
	 *
	 * <p>Clearing first matters: a re-forged or re-bound collar can end up with a
	 * shorter deck, and a leftover parameter would keep a button casting a
	 * technique this tame no longer has.
	 *
	 * @return how many techniques were bound
	 */
	public static int bind(NpcTemplate template, TameProfile profile)
	{
		return bind(template, profile, (profile == null) ? 0 : profile.getCurrentLevel());
	}

	/**
	 * As above, but judged against an explicit level.
	 *
	 * <p>Used by the collar with the level the pet is actually at now, so a
	 * technique that came unlocked while the tame was already out does not have
	 * to wait for the next summon.
	 *
	 * @return how many techniques were bound
	 */
	public static int bind(NpcTemplate template, TameProfile profile, int level)
	{
		if ((template == null) || (profile == null) || (profile.getUuid() == null))
		{
			return 0;
		}
		final StatSet parameters = template.getParameters();
		if (parameters == null)
		{
			return 0;
		}
		for (String parameter : PARAMETERS)
		{
			parameters.remove(parameter);
		}

		int bound = 0;
		for (TameProfileRepository.DeckSkill row : resolve(profile, level))
		{
			// petSkillId, not the deck id: the same substitution the cast path
			// makes, so a bound technique is exactly what the skill page shows.
			parameters.set(PARAMETERS[bound], new SkillHolder(TameSkillPolicy.petSkillId(row.getSkillId()), row.getSkillLevel()));
			bound++;
		}
		return bound;
	}

	/**
	 * The techniques that belong on the bar, in slot order.
	 *
	 * <p>One list, shared by the template bind and auto-cast, so a technique can never
	 * end up visible in one of them and missing from the other.
	 */
	public static List<TameProfileRepository.DeckSkill> resolve(TameProfile profile, int level)
	{
		return resolve(profile, level, PARAMETERS.length);
	}

	/**
	 * The whole usable deck, for the auto-cast rotation.
	 *
	 * <p>Deliberately not bounded by the number of client buttons: the beast casts these
	 * itself, so the only limit is a balance one. A full deck is signature, second
	 * signature, utility, advanced and the awakening and element slots - eight rows - so
	 * eight is enough to let the rotation see everything a tame can actually do, instead
	 * of silently ignoring the advanced and awakening techniques that {@link #resolve}
	 * drops once the four manual slots are full.
	 */
	public static List<TameProfileRepository.DeckSkill> resolveAll(TameProfile profile, int level)
	{
		return resolve(profile, level, AUTO_CAST_DECK_SIZE);
	}

	/**
	 * How many techniques auto-cast will consider at once. See {@link #resolveAll}.
	 */
	public static final int AUTO_CAST_DECK_SIZE = 8;

	/**
	 * The shared body of {@link #resolve} and {@link #resolveAll}: walk the deck in slot
	 * order, take everything usable until {@code cap} is reached.
	 */
	private static List<TameProfileRepository.DeckSkill> resolve(TameProfile profile, int level, int cap)
	{
		final List<TameProfileRepository.DeckSkill> bound = new ArrayList<>();
		if ((profile == null) || (profile.getUuid() == null))
		{
			return bound;
		}
		for (TameProfileRepository.DeckSkill row : TameProfileRepository.getOrderedSkills(profile.getUuid()))
		{
			if (bound.size() >= cap)
			{
				break;
			}
			if (usable(row, level))
			{
				bound.add(row);
			}
		}
		return bound;
	}

	/**
	 * The technique bound to one bar slot, or null when that slot is empty.
	 *
	 * <p>Slot numbering matches {@link #PARAMETERS}: slot 0 is the first button.
	 */
	public static TameProfileRepository.DeckSkill resolveSlot(TameProfile profile, int level, int slot)
	{
		if ((slot < 0) || (slot >= PARAMETERS.length))
		{
			return null;
		}
		final List<TameProfileRepository.DeckSkill> bound = resolve(profile, level);
		return (slot < bound.size()) ? bound.get(slot) : null;
	}

	/**
	 * Whether a deck row is bound to the bar at all.
	 *
	 * <p>The same gates the deck page reports, applied at bind time instead of at
	 * press time: a technique the skill page calls locked has no business sitting on
	 * the bar, where it would offer a button that cannot do anything.
	 */
	private static boolean usable(TameProfileRepository.DeckSkill row, int level)
	{
		if ((row == null) || !row.isEnabled() || !row.isCastable())
		{
			return false;
		}
		// The awakening technique is a choice, not a skill: it stays dark until
		// that choice has actually been made.
		if (TameProfileRepository.isAwakeningSlot(row.getSlot()) && !row.isAwakened())
		{
			return false;
		}
		if (row.getUnlockLevel() > level)
		{
			return false;
		}
		final Skill skill = SkillData.getInstance().getSkill(TameSkillPolicy.petSkillId(row.getSkillId()), row.getSkillLevel());
		return (skill != null) && skill.isActive() && !skill.isPassive();
	}
}
