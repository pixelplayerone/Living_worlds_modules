/*
 * TameAwakenedProc.java
 *
 * Second awakening: an on-hit proc tied to the beast's affinity.
 */

package taming;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.options.OptionSkillHolder;
import org.l2jmobius.gameserver.model.options.OptionSkillType;
import org.l2jmobius.gameserver.model.skill.Skill;

/**
 * What a second-awakened beast does when it lands a normal hit.
 *
 * <p>First awakening is numbers: {@code PetProfileGenerator.awakeningFactors}
 * multiplies the six stats a pet actually has. Second awakening is behaviour,
 * and the two are deliberately different in kind so the second one still feels
 * like an event.
 *
 * <p>The proc is registered through {@link Summon#addTriggerSkill}, which is
 * the same server-side mechanism armour options use. That matters: the
 * {@code Item Skill:} family in the stock skill data looks passive but is not -
 * each entry is an active {@code A2} cast with {@code nextActionAttack}, driven
 * by the <em>client</em> asking for it. A module that merely grants one of
 * those skills gets nothing, because nothing asks. Trigger skills are cast by
 * the core when the creature attacks, so they work without the client knowing
 * anything at all - the same wall that makes custom ids undrawable on the
 * shortcut bar does not apply here, because no button is involved.
 *
 * <p>The proc follows affinity rather than the first awakening path on purpose.
 * Five paths would have produced five variations on "a bad thing happens when
 * you hit something", and the elemental set below lets a FIRE beast burn and a
 * DARK beast poison, so the pet's element carries past its aura into a fight.
 */
public final class TameAwakenedProc
{
	/** The awakening stage this proc unlocks at. */
	public static final int STAGE = 2;

	private static final int DEFAULT_LEVEL = 60;

	/**
	 * A percentage, not a fraction. See {@code _chance}.
	 */
	private static final double DEFAULT_CHANCE = 25.0;

	/** One affinity's proc: a stock skill id and the name shown on the collar page. */
	public static final class Proc
	{
		private final int _skillId;
		private final String _label;

		private Proc(int skillId, String label)
		{
			_skillId = skillId;
			_label = label;
		}

		public int getSkillId()
		{
			return _skillId;
		}

		public String getLabel()
		{
			return _label;
		}
	}

	/*
	 * Chosen from the Item Skill family because those are the procs the game
	 * already balanced as on-hit effects: single target, 20 seconds, and no
	 * damage of their own beyond the debuff. There is no elemental debuff to
	 * borrow instead - FIRE_DOT, WATER_DOT and WIND_DOT exist in the
	 * AbnormalType enum but are referenced by almost no stock skill, so
	 * "fire proc" had to become "burn" by way of a bleeding DoT.
	 */
	private static final Map<String, Proc> PROCS;

	static
	{
		final Map<String, Proc> map = new LinkedHashMap<>();
		map.put("FIRE", new Proc(3092, "Bleed"));
		map.put("WATER", new Proc(3083, "Slow"));
		map.put("WIND", new Proc(3093, "Silence"));
		map.put("EARTH", new Proc(3085, "Stun"));
		map.put("HOLY", new Proc(3137, "Weakness"));
		map.put("DARK", new Proc(3091, "Poison"));
		PROCS = Collections.unmodifiableMap(map);
	}

	private static boolean _enabled = true;

	private static int _level = DEFAULT_LEVEL;

	/**
	 * The trigger chance as {@code OptionSkillHolder} wants it: percentage
	 * points, 0 to 100.
	 *
	 * <p>Creature's on-attack loop reads this holder and tests
	 * {@code Rnd.get(100) < getChance()}. {@code Rnd.get(100)} returns an int from
	 * 0 to 99, so the comparison is against percentage points and not against a
	 * 0-to-1 fraction. A fraction therefore does not scale - 0.25 only beats the
	 * single roll that returned 0, which is a 1% proc, not a 25% one. The stock
	 * skills' own activateRate is a separate roll applied afterwards and is not
	 * what made an earlier build feel rare; this unit was.
	 *
	 * <p>Out-of-range values fall back rather than throwing: a number between 0 and
	 * 1 is the one worth rescuing, since that is exactly what a fraction looks
	 * like and it would otherwise read as "far too rare" with no complaint.
	 */
	private static double _chance = DEFAULT_CHANCE;

	private TameAwakenedProc()
	{
	}

	/**
	 * Reads the three settings out of {@code module.ini}. Bad values fall back
	 * rather than throwing, because a typo in a config file should not stop the
	 * module from loading.
	 */
	public static void configure(String enabled, String level, String chance)
	{
		_enabled = !"false".equalsIgnoreCase(enabled == null ? "" : enabled.trim());
		try
		{
			_level = (level == null) || level.trim().isEmpty() ? DEFAULT_LEVEL : Integer.parseInt(level.trim());
			if (_level < 1)
			{
				_level = DEFAULT_LEVEL;
			}
		}
		catch (NumberFormatException e)
		{
			_level = DEFAULT_LEVEL;
		}
		try
		{
			_chance = (chance == null) || chance.trim().isEmpty() ? DEFAULT_CHANCE : Double.parseDouble(chance.trim());
			if ((_chance > 0.0) && (_chance <= 1.0))
			{
				// Almost certainly a fraction left over from the unit bug. Take it
				// at face value rather than discarding it, so an old config still
				// means roughly what its author intended.
				_chance *= 100.0;
			}
			else if ((_chance <= 0.0) || (_chance > 100.0))
			{
				_chance = DEFAULT_CHANCE;
			}
		}
		catch (NumberFormatException e)
		{
			_chance = DEFAULT_CHANCE;
		}
	}

	public static int getLevel()
	{
		return _level;
	}

	public static double getChance()
	{
		return _chance;
	}

	public static Proc procFor(String affinity)
	{
		return (affinity == null) ? null : PROCS.get(affinity.trim().toUpperCase());
	}

	/**
	 * Registers the proc on a beast that has just entered the world.
	 *
	 * <p>The level is passed in rather than read from the profile on purpose. The
	 * profile's stored level is only synced when the collar page is opened, so
	 * reading it would judge the beast against a number from whenever the player
	 * last looked at it. The live summon level is always current and costs nothing
	 * to ask for, which removes a database write from the summon path that used to
	 * exist purely to work around this.
	 *
	 * <p>Safe to call more than once for the same summon: trigger skills are held
	 * in a map keyed by skill id, so re-registering replaces rather than stacks.
	 * That is what lets this be called from both the summon and the experience
	 * events without the pet ever carrying two copies.
	 *
	 * @param summon the beast, already broadcast
	 * @param profile its profile, or null if this summon is not a collar tame
	 * @param liveLevel the beast's level right now, not the profile's stored one
	 */
	public static void apply(Summon summon, TameProfile profile, int liveLevel)
	{
		if (!_enabled || (summon == null) || (profile == null) || (profile.getAwakeningStage() < STAGE))
		{
			return;
		}

		if (liveLevel < _level)
		{
			return;
		}

		final Proc proc = procFor(TameIdentity.effectiveAffinity(profile));
		if (proc == null)
		{
			return;
		}

		final Skill skill = SkillData.getInstance().getSkill(proc.getSkillId(), 1);
		if (skill == null)
		{
			return;
		}

		summon.addTriggerSkill(new OptionSkillHolder(skill, _chance, OptionSkillType.ATTACK));
	}

	/**
	 * Whether this profile has earned the proc, judged against the level stored
	 * on the profile. For display only - {@link #apply} deliberately uses the live
	 * level instead.
	 */
	public static boolean isUnlocked(TameProfile profile)
	{
		if (!_enabled || (profile == null) || (profile.getAwakeningStage() < STAGE))
		{
			return false;
		}
		return profile.getCurrentLevel() >= _level;
	}

	/** Description for the collar page, e.g. {@code FIRE: Bleed on hit}. */
	public static String describe(TameProfile profile)
	{
		final Proc proc = procFor(TameIdentity.effectiveAffinity(profile));
		if (proc == null)
		{
			return "";
		}
		return proc.getLabel() + " on hit";
	}
}
