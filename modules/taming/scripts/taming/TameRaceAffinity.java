/*
 * TameRaceAffinity.java
 *
 * The thirteen-race matchup circle: every beast is strong against one race,
 * weak against one other, and even against the remaining eleven.
 */

package taming;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.stats.Calculator;
import org.l2jmobius.gameserver.model.stats.Stat;
import org.l2jmobius.gameserver.model.stats.functions.AbstractFunction;

/**
 * What a beast does to the thing it is currently hitting, based on both creatures' races.
 *
 * <p><b>The circle.</b> Thirteen races, each strong against the next one and weak against the
 * previous one, so no race is ever strictly better and every beast has a readable answer to
 * "what is this thing good against". Animal is strong against Bug and weak against Elemental;
 * Dragon is strong against Elemental and weak against Construct; and so on around the loop.
 *
 * <p><b>Why these thirteen and not a chosen list.</b> Because the core already has exactly
 * them. {@code org.l2jmobius.gameserver.model.actor.enums.creature.Race} declares ANIMAL, BUG,
 * PLANT, BEAST, HUMANOID, UNDEAD, DIVINE, DEMONIC, FAIRY, CONSTRUCT, GIANT, DRAGON and
 * ELEMENTAL, and {@code CreatureTemplate.getRace()} reports one for every creature in the world.
 * The circle below is that enum, in a chosen order. Nothing had to be invented and no template
 * had to be edited, which is the only reason a matchup table is possible at all under the
 * read-only-datapack rule.
 *
 * <p><b>How the bonus is applied, and why it is not a buff.</b> This was originally going to be
 * a permanent self-buff, the way a player would expect a racial bonus to work. That is not
 * possible here, and it is worth being precise about why, because the reason shaped the design.
 *
 * <p>A buff is unconditional: an effect on the beast applies to every target for as long as it
 * lasts, so a permanent "strong against insects" buff would simply be a flat damage bonus for
 * owning an animal, and would fire just as hard against a dragon. Worse, this engine has no
 * effect type that keys damage off the target's race at all - all 41 {@code EffectType} values
 * were checked and none of them does. There is no such buff to find.
 *
 * <p>There is, however, a hook that is exactly right. {@code CreatureStat.calcStat} walks a
 * per-stat {@code Calculator} owned by the creature being asked, and every function in that
 * calculator is handed the creature and the value computed so far. {@code Formulas.calcPhysDam}
 * calls {@code getPAtk} during the damage calculation itself, so a function installed on the
 * beast's own {@code POWER_ATTACK} calculator sees the swing it is actually part of, and can
 * read the beast's current target at that instant. {@code AbstractFunction} is public with a
 * single abstract {@code calc}, which makes this a supported extension point rather than a
 * reflection hack.
 *
 * <p>So the advantage is evaluated live, per hit, with no duration to expire, no icon to
 * flicker, no recast, and no way for it to be wrong after the beast retargets. It also cannot
 * leak: {@code Creature}'s constructor gives every non-NPC its own private copies of the shared
 * {@code NPC_STD_CALCULATOR} entries, so a function added to one beast's calculator is invisible
 * to every other creature in the world.
 *
 * <p>Two limits worth stating plainly. The bonus follows the beast's <em>current</em> target,
 * which is what the core's own AI uses to decide what it is attacking; if a swing were ever
 * aimed at something other than the current target the bonus would be read against the current
 * one. And the multiplier is applied before the server's own attack cap is taken, so it scales a
 * capped value rather than pushing past the cap.
 */
public final class TameRaceAffinity
{
	/**
	 * The circle, in order. Each entry is strong against the one after it and weak against the
	 * one before it, wrapping at both ends.
	 */
	private static final Race[] CYCLE =
	{
		Race.ANIMAL, Race.BUG, Race.PLANT, Race.BEAST, Race.HUMANOID, Race.UNDEAD, Race.DIVINE, Race.DEMONIC, Race.FAIRY, Race.CONSTRUCT, Race.GIANT, Race.DRAGON, Race.ELEMENTAL
	};

	/**
	 * Damage multiplier against the favourable race.
	 *
	 * <p>Twelve percent is a real difference between two equally rolled beasts and is invisible
	 * against a raid boss, which is the intended shape: the circle is a reason to pick a beast
	 * for a fight, not a reason to skip a boss. It is deliberately not scaled by rarity - a rarer
	 * beast is already better on every other axis, and letting rarity reach this number too
	 * would make the circle unreadable exactly where it matters most.
	 */
	private static double ADVANTAGE = 1.12;

	/**
	 * Damage multiplier against the unfavourable race.
	 *
	 * <p>Asymmetric on purpose. Being outmatched should sting without making the fight unwinnable,
	 * so the penalty is smaller than the reward and the eleven neutral matchups are the common
	 * case in any real zone.
	 */
	private static double DISADVANTAGE = 0.92;

	/**
	 * How the two stats are described on the collar page. Both are covered so the beast is not
	 * rewarded twice for one matchup - physical and magical damage are separate lines of defence
	 * and a caster should not also benefit from a physical reading of the same circle.
	 */
	private static final Map<Stat, String> ATTACK_STATS;

	static
	{
		final Map<Stat, String> map = new LinkedHashMap<>();
		map.put(Stat.POWER_ATTACK, "physical attack");
		map.put(Stat.MAGIC_ATTACK, "magical attack");
		ATTACK_STATS = Collections.unmodifiableMap(map);
	}

	private static boolean _enabled = true;

	private TameRaceAffinity()
	{
	}

	/**
	 * Reads the settings out of {@code module.ini}. Bad values fall back rather than throwing,
	 * matching the rest of the module: a typo in a config file must not stop the server booting.
	 */
	public static void configure(String enabled, String advantagePercent, String disadvantagePercent)
	{
		_enabled = !"false".equalsIgnoreCase(enabled == null ? "" : enabled.trim());
		ADVANTAGE = 1.0 + percent(advantagePercent, 12.0) / 100.0;
		DISADVANTAGE = 1.0 - percent(disadvantagePercent, 8.0) / 100.0;
	}

	private static double percent(String raw, double fallback)
	{
		try
		{
			final double value = (raw == null) || raw.trim().isEmpty() ? fallback : Double.parseDouble(raw.trim());
			if ((value < 0.0) || (value > 100.0))
			{
				return fallback;
			}
			return value;
		}
		catch (NumberFormatException e)
		{
			return fallback;
		}
	}

	public static double getAdvantagePercent()
	{
		return (ADVANTAGE - 1.0) * 100.0;
	}

	public static double getDisadvantagePercent()
	{
		return (1.0 - DISADVANTAGE) * 100.0;
	}

	/**
	 * Resolves a profile's stored race string to the core enum, or null when it is not one of
	 * the thirteen.
	 *
	 * <p>Not every creature in this game is in the circle. Players' races, mercenaries, castle
	 * guards, siege weapons and the {@code NONE} placeholder all exist in the enum and can be
	 * rolled onto a profile, and none of them has a place in a thirteen-entry loop. They are
	 * simply outside it, and a beast outside the circle fights on even terms.
	 */
	public static Race raceOf(TameProfile profile)
	{
		return (profile == null) ? null : raceOf(profile.getRace());
	}

	public static Race raceOf(String name)
	{
		if (name == null)
		{
			return null;
		}
		final String value = name.trim().toUpperCase(Locale.ROOT);
		if (value.isEmpty())
		{
			return null;
		}
		for (Race race : CYCLE)
		{
			if (race.name().equals(value))
			{
				return race;
			}
		}
		return null;
	}

	/** The race this one is strong against, or null if it is outside the circle. */
	public static Race preyOf(Race self)
	{
		final int index = indexOf(self);
		return (index < 0) ? null : CYCLE[(index + 1) % CYCLE.length];
	}

	/** The race this one is weak against, or null if it is outside the circle. */
	public static Race nemesisOf(Race self)
	{
		final int index = indexOf(self);
		if (index < 0)
		{
			return null;
		}
		return CYCLE[(index + (CYCLE.length - 1)) % CYCLE.length];
	}

	private static int indexOf(Race race)
	{
		if (race == null)
		{
			return -1;
		}
		for (int i = 0; i < CYCLE.length; i++)
		{
			if (CYCLE[i] == race)
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * The damage multiplier for a beast of {@code self}'s race against a target of
	 * {@code target}'s race. Returns 1.0 whenever either side is outside the circle, so a
	 * caller never needs a special case.
	 */
	public static double multiplier(Race self, Race target)
	{
		if (!inCircle(self) || !inCircle(target))
		{
			return 1.0;
		}
		if (self == target)
		{
			// A beast does not gain an advantage over its own kind. Without this the
			// wrap-around would hand an animal a bonus against animals, since its
			// nemesis is the entry before it and that is never itself - but the check
			// is kept because it costs nothing and states the intent.
			return 1.0;
		}
		if (target == preyOf(self))
		{
			return ADVANTAGE;
		}
		if (target == nemesisOf(self))
		{
			return DISADVANTAGE;
		}
		return 1.0;
	}

	public static boolean inCircle(Race race)
	{
		return indexOf(race) >= 0;
	}

	/**
	 * The multiplier a live beast would apply to whatever it is hitting right now.
	 *
	 * <p>Exposed for the diagnostic command so the number on screen is the number the server is
	 * using, read through the same path rather than recomputed from a second copy of the rules.
	 */
	public static double currentMultiplier(Summon summon, TameProfile profile)
	{
		final Race self = raceOf(profile);
		if (summon == null)
		{
			return 1.0;
		}
		final Creature victim = victimOf(summon);
		return (victim == null) ? 1.0 : multiplier(self, victim.getTemplate().getRace());
	}

	private static Creature victimOf(Creature creature)
	{
		final Object target = creature.getTarget();
		return (target instanceof Creature) ? (Creature) target : null;
	}

	/**
	 * Puts the circle on a beast that has just entered the world.
	 *
	 * <p>Safe to call repeatedly for the same beast: any function this class installed before is
	 * removed first, so a resummon or a module reload replaces it rather than stacking a second
	 * copy and doubling the bonus.
	 *
	 * @param summon the beast, already broadcast
	 * @param profile its profile, or null if this summon is not a collar tame
	 */
	public static void apply(Summon summon, TameProfile profile)
	{
		if (summon == null)
		{
			return;
		}
		unapply(summon);
		if (!_enabled)
		{
			return;
		}
		final Race self = raceOf(profile);
		if (!inCircle(self))
		{
			return;
		}
		for (Stat stat : ATTACK_STATS.keySet())
		{
			calculatorFor(summon, stat).addFunc(new RaceFunction(stat, summon, self));
		}
	}

	/**
	 * Takes the circle back off a beast.
	 *
	 * <p>Called from {@link #apply} before reinstalling, and available on its own so a beast
	 * leaving the world does not keep a function pointing at it. Every function this class can
	 * install is identified by its class, so nothing else in the calculator is touched.
	 */
	public static void unapply(Summon summon)
	{
		if (summon == null)
		{
			return;
		}
		for (Stat stat : ATTACK_STATS.keySet())
		{
			final Calculator calculator = calculatorFor(summon, stat);
			// Copy first: removeFunc rewrites the very array getFunctions() hands back, and
			// removing while walking it would skip an entry and leave a second copy installed.
			for (AbstractFunction function : calculator.getFunctions().clone())
			{
				if (function instanceof RaceFunction)
				{
					calculator.removeFunc(function);
				}
			}
		}
	}

	/**
	 * The beast's own calculator for one stat, allocating the slot if the core left it empty.
	 *
	 * <p>{@code Creature}'s constructor does the same thing when it meets a stat no function has
	 * claimed yet. Mirroring it here rather than assuming a slot exists is the difference between
	 * a missing bonus and an exception on the first swing of the first beast.
	 */
	private static Calculator calculatorFor(Summon summon, Stat stat)
	{
		final Calculator[] calculators = summon.getCalculators();
		final int index = stat.ordinal();
		if (calculators[index] == null)
		{
			calculators[index] = new Calculator();
		}
		return calculators[index];
	}

	/**
	 * One stat on one beast, resolved against whatever that beast is hitting at the moment the
	 * server asks for the number.
	 *
	 * <p>Deliberately does nothing for any creature other than the one it was installed on. The
	 * calculator belongs to the beast, so the guard is belt and braces - but a function that
	 * quietly scaled something it was not installed on would be the worst kind of bug to find
	 * later.
	 */
	private static final class RaceFunction extends AbstractFunction
	{
		private final Summon _owner;
		private final Race _self;

		RaceFunction(Stat stat, Summon owner, Race self)
		{
			// Order is irrelevant to correctness here - nothing else reorders the array - but a
			// high one documents the intent that this multiplies the finished value rather than
			// taking part in building it.
			super(stat, Integer.MAX_VALUE, owner, 1.0, null);
			_owner = owner;
			_self = self;
		}

		@Override
		public double calc(Creature creature, Creature skillOwner, org.l2jmobius.gameserver.model.skill.Skill skill, double value)
		{
			if (creature != _owner)
			{
				return value;
			}
			final Creature victim = victimOf(creature);
			if (victim == null)
			{
				return value;
			}
			return value * multiplier(_self, victim.getTemplate().getRace());
		}
	}

	/**
	 * One line for the collar page, e.g.
	 * {@code ANIMAL - strong against BUG, weak against ELEMENTAL}.
	 */
	public static String describe(TameProfile profile)
	{
		final Race self = raceOf(profile);
		if (!inCircle(self))
		{
			return "";
		}
		return self.name() + " - strong against " + preyOf(self).name() + ", weak against " + nemesisOf(self).name();
	}

	/**
	 * What the circle is currently doing for this beast, for the diagnostic command.
	 * Returns an empty string when there is no matchup in effect, which is the common case.
	 */
	public static String describeCurrent(Summon summon, TameProfile profile)
	{
		final Creature victim = (summon == null) ? null : victimOf(summon);
		if (victim == null)
		{
			return "";
		}
		final Race targetRace = victim.getTemplate().getRace();
		final double multiplier = currentMultiplier(summon, profile);
		if (multiplier >= ADVANTAGE)
		{
			return "advantage over " + targetRace.name() + " (" + percent(getAdvantagePercent()) + " damage)";
		}
		if (multiplier <= DISADVANTAGE)
		{
			return "outmatched by " + targetRace.name() + " (" + percent(getDisadvantagePercent()) + " less damage)";
		}
		return "even against " + targetRace.name();
	}

	private static String percent(double value)
	{
		return String.format(Locale.ROOT, "%.0f%%", value);
	}
}