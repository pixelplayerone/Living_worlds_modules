package taming;

import java.util.Locale;

import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;

/**
 * Derived identity rules for a collar-bound individual.
 *
 * No extra database column is required: the imprint is deterministic from
 * the profile fields already persisted in tamed_pet, while history records
 * can describe when the identity was first created or reached a milestone.
 */
public final class TameIdentity
{
	private static final String FIRE = "FIRE";
	private static final String WATER = "WATER";
	private static final String WIND = "WIND";
	private static final String EARTH = "EARTH";
	private static final String HOLY = "HOLY";
	private static final String DARK = "DARK";

	/**
	 * Affinity is the single stat a beast is named for, and this is how much of it
	 * gets. It is applied once, when the pet's profile is generated, so it changes
	 * the beast's numbers permanently rather than swinging a fight.
	 *
	 * <p>The old value was 2%, which was too small to notice on a pet whose stats
	 * are then multiplied by level growth, imprint, awakening and equipment. It was
	 * effectively a rounding error wearing a percentage sign.
	 */
	private static final double AFFINITY_BONUS = 0.06;

	/**
	 * Indexes into the factor array {@code PetProfileGenerator} builds, in the order
	 * that generator hands them to the template: hp, mp, physical attack, physical
	 * defence, magical attack, magical defence.
	 */
	private static final int STAT_HP = 0;
	private static final int STAT_MP = 1;
	private static final int STAT_PATK = 2;
	private static final int STAT_PDEF = 3;
	private static final int STAT_MATK = 4;
	private static final int STAT_MDEF = 5;

	private TameIdentity()
	{
	}

	/**
	 * Returns a stable imprint grade. It deliberately uses existing persisted
	 * rolls, so a relog or resummon cannot reroll the individual.
	 */
	public static String imprintGrade(TameProfile profile)
	{
		final int score = imprintScore(profile);
		if (score >= 90)
		{
			return "PERFECT";
		}
		if (score >= 75)
		{
			return "RESONANT";
		}
		if (score >= 60)
		{
			return "DEEP";
		}
		if (score >= 45)
		{
			return "STABLE";
		}
		return "FAINT";
	}

	public static int imprintScore(TameProfile profile)
	{
		if (profile == null)
		{
			return 0;
		}
		int rarityBonus = 0;
		final String rarity = normalize(profile.getRarity());
		if ("UNCOMMON".equals(rarity))
		{
			rarityBonus = 3;
		}
		else if ("RARE".equals(rarity))
		{
			rarityBonus = 6;
		}
		else if ("EPIC".equals(rarity))
		{
			rarityBonus = 9;
		}
		else if ("LEGENDARY".equals(rarity))
		{
			rarityBonus = 12;
		}
		final double growthContribution = Math.min(24.0, Math.max(0.0, profile.getGrowthPercent() * 3.0));
		final int score = (int) Math.round((profile.getPotential() * 0.55) + (profile.getSkillPotential() * 0.15) + growthContribution + rarityBonus);
		return Math.max(0, Math.min(100, score));
	}

	/**
	 * All-stat resonance from imprint quality.
	 * <p>
	 * Imprint is derived from the rolled profile itself (potential, growth,
	 * rarity, skill potential) rather than stored, so it stays consistent for the
	 * life of the collar and cannot drift.
	 * <p>
	 * The ceiling is deliberately twelve percent: enough that a PERFECT imprint is
	 * the clearest stat difference between two collars of the same species, which
	 * is the point of rolling one per collar, but small enough that potential,
	 * growth and equipment still dominate where a player spends their money. It
	 * layers on top of those rather than replacing them.
	 */
	public static double imprintStatFactor(TameProfile profile)
	{
		final String grade = imprintGrade(profile);
		if ("PERFECT".equals(grade))
		{
			return 0.12;
		}
		if ("RESONANT".equals(grade))
		{
			return 0.08;
		}
		if ("DEEP".equals(grade))
		{
			return 0.05;
		}
		if ("STABLE".equals(grade))
		{
			return 0.025;
		}
		return 0.0;
	}

	public static String imprintDescription(String grade)
	{
		if ("PERFECT".equalsIgnoreCase(grade))
		{
			return "A complete imprint: the collar and creature answer as one.";
		}
		if ("RESONANT".equalsIgnoreCase(grade))
		{
			return "The creature's wild signature strongly resonates through the collar.";
		}
		if ("DEEP".equalsIgnoreCase(grade))
		{
			return "The collar holds a deep and reliable memory of this individual.";
		}
		if ("STABLE".equalsIgnoreCase(grade))
		{
			return "The creature's identity is stable and should endure ordinary strain.";
		}
		return "A faint imprint: the wild nature is present, but not fully settled.";
	}

	public static String affinityFromTemplate(NpcTemplate template)
	{
		if (template == null)
		{
			return "NEUTRAL";
		}
		int value = template.getBaseFire();
		String result = FIRE;
		if (template.getBaseWater() > value)
		{
			value = template.getBaseWater();
			result = WATER;
		}
		if (template.getBaseWind() > value)
		{
			value = template.getBaseWind();
			result = WIND;
		}
		if (template.getBaseEarth() > value)
		{
			value = template.getBaseEarth();
			result = EARTH;
		}
		if (template.getBaseHoly() > value)
		{
			value = template.getBaseHoly();
			result = HOLY;
		}
		if (template.getBaseDark() > value)
		{
			value = template.getBaseDark();
			result = DARK;
		}
		return value > 0 ? result : "NEUTRAL";
	}

	/**
	 * Stable fallback for ordinary templates with no elemental power configured.
	 * Neutral remains possible, but it is no longer the default for every such
	 * creature. The profile seed makes the result individual and persistent.
	 */
	public static String seededAffinity(long seed)
	{
		switch (Math.floorMod(seed, 7))
		{
			case 1:
				return FIRE;
			case 2:
				return WATER;
			case 3:
				return WIND;
			case 4:
				return EARTH;
			case 5:
				return HOLY;
			case 6:
				return DARK;
			default:
				return "NEUTRAL";
		}
	}

	/**
	 * Returns the stored real affinity when present, repairs legacy neutral
	 * profiles from their source template, and otherwise uses the stable seed.
	 */
	public static String effectiveAffinity(TameProfile profile)
	{
		if (profile == null)
		{
			return "NEUTRAL";
		}
		final String stored = normalize(profile.getAffinity());
		if (!stored.isEmpty() && !"NEUTRAL".equals(stored))
		{
			return stored;
		}
		final NpcTemplate template = NpcData.getInstance().getTemplate(profile.getSourceNpcId());
		final String templateAffinity = affinityFromTemplate(template);
		return !"NEUTRAL".equals(templateAffinity) ? templateAffinity : seededAffinity(profile.getProfileSeed());
	}


	/**
	 * Describes what affinity actually does to the beast.
	 * <p>
	 * This is deliberately a stat description rather than a matchup one. Affinity
	 * sharpens a single stat on the pet itself, which is applied once when the
	 * pet's profile is generated. It does not inspect the opponent, because this
	 * build has no hook that lets a module rescale an individual hit: the damage
	 * events are dispatched after the damage has already been subtracted from the
	 * target and cannot return a replacement value.
	 * <p>
	 * The old text here promised strong/vulnerable matchups against named
	 * elements. No matchup table was ever consumed by anything, so that text
	 * described a mechanic that did not exist.
	 */
	public static String affinityDescription(String affinity)
	{
		final int stat = statIndex(affinity);
		if (stat < 0)
		{
			return "This beast carries no elemental affinity and gains no affinity bonus.";
		}
		return "This beast's nature sharpens its " + statLabel(stat) + " by " + percent() + ". It is a fixed trait of the collar, not a situational bonus.";
	}

	/**
	 * The factor array index this affinity sharpens.
	 *
	 * @return the index, or -1 for a beast with no affinity
	 */
	public static int statIndex(String affinity)
	{
		final String value = normalize(affinity);
		if (FIRE.equals(value))
		{
			return STAT_PATK;
		}
		if (EARTH.equals(value))
		{
			return STAT_PDEF;
		}
		if (WATER.equals(value))
		{
			return STAT_MDEF;
		}
		if (WIND.equals(value))
		{
			return STAT_MATK;
		}
		if (HOLY.equals(value))
		{
			return STAT_HP;
		}
		if (DARK.equals(value))
		{
			return STAT_MP;
		}
		return -1;
	}

	/**
	 * Multiplier to apply to {@link #statIndex(String)}.
	 *
	 * @return 1.0 for a beast with no affinity, so the caller needs no special case
	 */
	public static double statFactor(String affinity)
	{
		return (statIndex(affinity) < 0) ? 1.0 : (1.0 + AFFINITY_BONUS);
	}

	/**
	 * The short stat name an affinity points at, for the collar page.
	 */
	public static String statLabel(String affinity)
	{
		return statLabel(statIndex(affinity));
	}

	private static String statLabel(int stat)
	{
		switch (stat)
		{
			case STAT_HP:
				return "maximum health";
			case STAT_MP:
				return "maximum mana";
			case STAT_PATK:
				return "physical attack";
			case STAT_PDEF:
				return "physical defence";
			case STAT_MATK:
				return "magical attack";
			case STAT_MDEF:
				return "magical defence";
			default:
				return "";
		}
	}

	private static String percent()
	{
		return String.format(Locale.ROOT, "%.0f%%", AFFINITY_BONUS * 100.0);
	}

	public static String affinityColor(String affinity)
	{
		final String value = normalize(affinity);
		if (FIRE.equals(value))
		{
			return "FF7B72";
		}
		if (WATER.equals(value))
		{
			return "58A6FF";
		}
		if (WIND.equals(value))
		{
			return "7EE787";
		}
		if (EARTH.equals(value))
		{
			return "D2A679";
		}
		if (HOLY.equals(value))
		{
			return "F6C453";
		}
		if (DARK.equals(value))
		{
			return "C084FC";
		}
		return "A8B0BA";
	}

	public static String imprintColor(String grade)
	{
		if ("PERFECT".equalsIgnoreCase(grade))
		{
			return "F6C453";
		}
		if ("RESONANT".equalsIgnoreCase(grade))
		{
			return "C084FC";
		}
		if ("DEEP".equalsIgnoreCase(grade))
		{
			return "58A6FF";
		}
		if ("STABLE".equalsIgnoreCase(grade))
		{
			return "7EE787";
		}
		return "A8B0BA";
	}

	private static String normalize(String value)
	{
		return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
	}
}