/*
 * PetProfileGenerator.java
 *
 * Builds the PetData used by one collar-bound creature. The generated
 * profile keeps the captured species identity, starts from the captured
 * creature's combat values, and applies the configured growth curve.
 */
package taming;

import org.l2jmobius.gameserver.data.holders.PetData;
import org.l2jmobius.gameserver.data.holders.PetLevelData;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.skill.Skill;

public class PetProfileGenerator
{
	// Flat percentage growth applied per level above the wild mob's own
	// base stats. Tune this to make leveling feel faster/slower.
	private static final double GROWTH_PER_LEVEL = 0.02; // +2% per level; tune after playtesting.

	/**
	 * Ceiling on the total growth multiplier, as a multiple of the captured mob's own
	 * stats. Set from {@code PetGrowthCeiling} in module.ini.
	 *
	 * <p>This exists because the growth term is raised to a power of the level, and
	 * {@code growthPercent} rolls between 1.25 and 8.00 percent per level. Raised against a
	 * level of 80 that is 437x; against 62 it is 109x. Multiplied on top of the stats of
	 * whatever the beast happened to eat, a raid-boss tame landed at 218x a raid boss's own
	 * PAtk even after the 0.50 boss conversion multiplier. Weak tames were unaffected in
	 * proportion only because their captured base was tiny - a wolf at 437x is still a wolf -
	 * which is exactly the asymmetry that made low-level pets fine and raid pets absurd.
	 *
	 * <p>Capping the result rather than the rate is deliberate. Capping the rate would make
	 * every roll above the cap identical and would flatten the difference a potential roll
	 * is supposed to make. Capping the result keeps each beast's own curve shape and keeps
	 * the roll visible, and only bounds the top.
	 *
	 * <p>The honest cost: a beast that rolled the maximum is fully grown by about level 15
	 * and gains nothing after that, while the minimum roll never reaches the ceiling at all
	 * and is still growing at the level cap. So "high level" stops being a power spike, and
	 * what separates one tame from another becomes the things that are individually bounded
	 * and deliberately so - potentials, temperament, affinity, imprint, equipment, awakening.
	 */
	private static volatile double growthCeiling = 3.0;

	/** Config-bound settings are read at module enable so a typo is reported once at boot. */
	private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger(PetProfileGenerator.class.getName());

	public static double getGrowthCeiling()
	{
		return growthCeiling;
	}

	/**
	 * Sets the ceiling, ignoring values that are not a usable multiplier.
	 *
	 * <p>Anything at or below 1 would freeze a beast at its captured stats and anything
	 * absurd would reopen the problem this exists to close, so the accepted band is narrow
	 * and a bad value is reported rather than silently clamped to something playable.
	 */
	public static void setGrowthCeiling(double value)
	{
		if ((value < 1.0) || (value > 20.0))
		{
			LOGGER.warning("PetGrowthCeiling " + value + " is outside 1.0..20.0 and was ignored; staying at " + growthCeiling);
			return;
		}
		growthCeiling = value;
	}

	private PetProfileGenerator()
	{
	}

	/**
	 * Builds a fresh PetData profile from explicit base stat numbers
	 * (rather than a live Monster), so the EXACT SAME profile can be
	 * rebuilt later purely from saved database values - no live wild
	 * mob instance needed. This is what makes resummoning work after a
	 * server restart, when the original wild mob no longer exists.
	 */
	public static PetData generate(int npcId, double baseHp, double baseMp, double basePAtk, double basePDef, double baseMAtk, double baseMDef, int maxLevel)
	{
		return generate(npcId, baseHp, baseMp, basePAtk, basePDef, baseMAtk, baseMDef, 1.0, maxLevel, GROWTH_PER_LEVEL, false);
	}
	
	/**
	 * Generates a profile after applying the creature-tier conversion
	 * coefficient. The coefficient is persisted with the collar so a restart
	 * reproduces the same individual instead of recalculating a new balance.
	 */
	public static PetData generate(int npcId, double baseHp, double baseMp, double basePAtk, double basePDef, double baseMAtk, double baseMDef, double conversionMultiplier, int maxLevel)
	{
		return generate(npcId, baseHp, baseMp, basePAtk, basePDef, baseMAtk, baseMDef, conversionMultiplier, maxLevel, GROWTH_PER_LEVEL, false);
	}
	
	/** Builds PetData from the persisted individual profile without rerolling it. */
	public static PetData generate(TameProfile profile, NpcTemplate template)
	{
		if (profile == null)
		{
			return null;
		}
		final double growthPerLevel = Math.max(0.005, Math.min(0.12, profile.getGrowthPercent() / 100.0));
		final PetData data = generateForProfile(profile);
		TameSkillPolicy.register(data, template, profile);
		return data;
	}
	
	/** Rebuilds a persisted profile using the stored skill rows rather than rerolling skill slots. */
	public static PetData generatePersisted(TameProfile profile, NpcTemplate template)
	{
		if (profile == null)
		{
			return null;
		}
		final double growthPerLevel = Math.max(0.005, Math.min(0.12, profile.getGrowthPercent() / 100.0));
		final PetData data = generateForProfile(profile);
		if (!TameProfileRepository.applyStoredSkills(data, profile.getUuid()))
		{
			TameSkillPolicy.register(data, template, profile);
		}
		return data;
	}
	
		private static PetData generateForProfile(TameProfile profile)
		{
			final double[] factors = individualFactors(profile);
			final double growthPerLevel = Math.max(0.005, Math.min(0.12, profile.getGrowthPercent() / 100.0));
// The profile is filed under the collar's own synthetic npc id, not the
		// shared species id. Stock pet lookups ask for the id on the template the
		// pet was spawned from, which is that same synthetic id, so this is the
		// single line that gives every collar a private profile.
		final boolean boss = TameFood.isBoss(profile);
		// A boss tame reads the CURRENT raid coefficient rather than the one stored on it at
		// capture time, so that weakening raid tames reaches beasts that already exist.
		//
		// The stored value is a snapshot of a balance decision, and a balance decision that
		// only applies to beasts captured after it was made is not much of a balance decision.
		// Every raid and boss tier maps to the same branch of the coefficient table, so
		// re-reading it reproduces exactly what a boss captured today would get - which means
		// this cannot drift a stored tame away from its own tier.
		//
		// Non-boss tames still use their stored coefficient. Those tiers have not been under
		// review, and the same freshness argument would matter less for a coefficient nobody
		// has changed.
		final double conversion = boss ? getConversionMultiplier("RAID") : profile.getConversionMultiplier();
		return generate(profile.getSyntheticNpcId(), profile.getBaseHp() * factors[0], profile.getBaseMp() * factors[1], profile.getBasePAtk() * factors[2], profile.getBasePDef() * factors[3], profile.getBaseMAtk() * factors[4], profile.getBaseMDef() * factors[5], conversion, profile.getMaxPetLevel(), growthPerLevel, boss);
		}

		/**
		 * Applies the individual roll as a bounded, deterministic stat profile.
		 * The conversion coefficient remains the broad balance control; these
		 * factors are deliberately small so potential is meaningful without
		 * turning a lucky common tame into a raid boss.
		 */
	private static double[] individualFactors(TameProfile profile)
	{
			final double[] factors = awakeningFactors(profile);
			factors[0] *= 0.94 + ((profile.getVitalityPotential() / 100.0) * 0.12);
			factors[2] *= 0.94 + ((profile.getOffensePotential() / 100.0) * 0.12);
			factors[3] *= 0.94 + ((profile.getDefensePotential() / 100.0) * 0.12);
			factors[1] *= 0.94 + ((profile.getSkillPotential() / 100.0) * 0.12);
			factors[4] *= 0.94 + ((profile.getSkillPotential() / 100.0) * 0.12);
			factors[5] *= 0.94 + ((profile.getDefensePotential() / 100.0) * 0.12);

			// Bond grants up to four percent at the current maximum bond.
			final double bondFactor = 1.0 + (Math.max(0, Math.min(10000, profile.getBond())) / 10000.0 * 0.04);
			for (int i = 0; i < factors.length; i++)
			{
				factors[i] *= bondFactor;
			}

			final String temperament = profile.getTemperament() == null ? "" : profile.getTemperament().toUpperCase();
			if ("AGGRESSIVE".equals(temperament))
			{
				factors[2] *= 1.03;
				factors[3] *= 0.98;
			}
			else if ("BERSERKER".equals(temperament))
			{
				factors[2] *= 1.05;
				factors[3] *= 0.95;
			}
			else if ("GUARDIAN".equals(temperament) || "DEFENSIVE".equals(temperament))
			{
				factors[0] *= 1.02;
				factors[3] *= 1.04;
				factors[2] *= 0.98;
			}
			else if ("COWARDLY".equals(temperament))
			{
				factors[0] *= 1.02;
				factors[2] *= 0.97;
			}
			else if ("PATIENT".equals(temperament))
			{
				factors[5] *= 1.03;
			}
			else if ("LOYAL".equals(temperament))
			{
				factors[0] *= 1.02;
			}

			// Affinity sharpens one fixed stat on the pet itself. It deliberately does
			// not vary by opponent: this build offers no hook that can rescale an
			// individual hit, since the damage events fire after the damage has already
			// been subtracted and cannot return a replacement value.
			//
			// Which stat, and by how much, is TameIdentity's decision, so the number the
			// collar page quotes and the number applied here cannot drift apart.
			final String affinity = TameIdentity.effectiveAffinity(profile);
			final int affinityStat = TameIdentity.statIndex(affinity);
			if (affinityStat >= 0 && affinityStat < factors.length)
			{
				factors[affinityStat] *= TameIdentity.statFactor(affinity);
			}

			// Imprint is a derived, deterministic identity bonus. It is the clearest
			// stat difference between two collars of the same species and is layered on
			// top of potential, growth and equipment rather than replacing them.
			final double imprintFactor = 1.0 + TameIdentity.imprintStatFactor(profile);
			for (int i = 0; i < factors.length; i++)
			{
				factors[i] *= imprintFactor;
			}

					// Collar-bound custom equipment is applied after individual profile,
				// temperament, affinity, and awakening factors, but before wounds and
				// per-level growth. This makes gear persistent without replacing the
				// creature's species identity.
				TameEquipmentCatalog.apply(TameProfileRepository.loadCustomEquipment(profile.getUuid()), factors);

// Wounds are a severity, not a switch. A beast hurt in a fight carries a
			// smaller penalty than one that dropped from a bad fight, and food walks
			// the severity back down over several feedings.
			final int woundSeverity = Math.max(0, Math.min(TameProfileRepository.WOUND_MAX, profile.getWoundFlags()));
			if (woundSeverity > 0)
			{
				// A fully wounded beast keeps the original eight percent loss; every
				// step of nursing gives a proportional part of it back.
				final double woundFactor = 1.0 - (0.08 * ((double) woundSeverity / TameProfileRepository.WOUND_MAX));
				for (int i = 0; i < factors.length; i++)
				{
					factors[i] *= woundFactor;
				}
			}
			return factors;
		}
	
	private static double[] awakeningFactors(TameProfile profile)
	{
		final double[] factors = { 1.0, 1.0, 1.0, 1.0, 1.0, 1.0 };
		if ((profile == null) || (profile.getAwakeningStage() <= 0))
		{
			return factors;
		}
		final String path = profile.getAwakeningPath() == null ? "" : profile.getAwakeningPath();
		if ("FANG".equals(path))
		{
			factors[2] = 1.08;
		}
		else if ("PACK".equals(path))
		{
			factors[0] = 1.05;
			factors[3] = 1.03;
		}
		else if ("SHADOW".equals(path))
		{
			factors[2] = 1.04;
			factors[3] = 1.02;
		}
		else if ("ARCANE".equals(path))
		{
			factors[4] = 1.08;
			factors[1] = 1.03;
		}
		else if ("GUARDIAN".equals(path))
		{
			factors[0] = 1.06;
			factors[3] = 1.08;
		}
		return factors;
	}
	
	private static PetData generate(int npcId, double baseHp, double baseMp, double basePAtk, double basePDef, double baseMAtk, double baseMDef, double conversionMultiplier, int maxLevel, double growthPerLevel, boolean boss)
	{
		PetData data = new PetData(npcId, TamingManager.getCollarItemId());
		final TameFood food = boss ? TameFood.hatchling() : TameFood.wolf();
		// Without this the generated pet has no hunger threshold at all: stock
		// reads the gauge against it, and a missing one leaves the pet permanently
		// fed no matter how much it burns.
		data.setHungryLimit(TameFood.HUNGRY_LIMIT);
		data.addFood(food.getItemId());
		// NOT using setSyncLevel(true) - that syncs the pet to the PLAYER's
		// level, which isn't what we want. We want the pet to always show
		// level 1, with its real power baked into level 1's own stats
		// (forced explicitly in TamingManager right after spawn, since
		// Pet's constructor sets level directly and ignores this curve's
		// level 1 entry as a "starting point" on its own).
		// Lower bound moved down from 0.35 to 0.20. That floor used to sit exactly on the
		// RAID/BOSS coefficient, which silently made any attempt to weaken a raid tame
		// below 1.05x a no-op - the clamp undid the change before it reached a stat line.
		// It is a sanity floor against a zero or negative multiplier, not a balance control.
		final double safeMultiplier = Math.max(0.20, Math.min(1.0, conversionMultiplier));
		// The highest level the beast may actually reach.
		final int safeMaxLevel = Math.max(1, Math.min(maxLevel, ExperienceData.getInstance().getMaxLevel() - 1));
		// The table is generated one row PAST that on purpose, and the row carries no
		// stat of its own worth - it is a threshold, not a level.
		//
		// The core clamps a pet's experience against getExpForLevel(getMaxLevel()),
		// and for a pet getMaxLevel() is ExperienceData.getMaxPetLevel(), which counts
		// up from the attribute, so it asks for the row one past the real ceiling. It
		// reads that row out of PetDataTable.getPetLevelData, which quietly clamps the
		// requested level down to PetData._maxLevel. With the table stopping at the cap,
		// that lookup lands back on the cap's own row and hands the core the threshold
		// for the cap itself.
		//
		// The effect is a beast that stops one experience point short of its own last
		// level and can never cross: capped at 80, the core is handed 4200000000 - 1,
		// so it sits on 4199999999 showing a level 79 bar pinned at 99.99% forever.
		// Nothing about that looks like a table problem from the outside.
		//
		// The row past the cap is only ever read for its threshold. Nothing can reach it
		// as a level: the core's own level walk refuses to pass getMaxLevel() - 1, and
		// TameLevelCap holds the beast at its cap anyway.
		final int tableTopLevel = safeMaxLevel + 1;
		// The summoned Pet automatically applies these generated values.
		// to the OWNER's level (capped by maxLevel below) every time it's
		// summoned - this is the actual mechanism spawnPet() checks, and
		// is why our earlier setExp() attempts never worked (Pet's
		// constructor sets level directly from the wild mob's own
		// template level, before setExp() ever runs).

		for (int level = 1; level <= tableTopLevel; level++)
		{
			// Compounding growth, bounded. See the growthCeiling field for why the cap is on
			// the result and not on the rate.
			double growth = Math.min(Math.pow(1.0 + growthPerLevel, level - 1), growthCeiling);

			StatSet set = new StatSet();
			// Every one of these is capped in absolute terms, separately from growth.
			//
			// A growth ceiling bounds the MULTIPLE, which is enough when the number it
			// multiplies is ordinary. It is not enough when the base is not: a raid boss
			// starts from six figures of HP, so 3x a boss is still a number no single
			// character can put down, and the same argument applies to its PAtk and PDef.
			// Two tame pets of the same species with the same growth can otherwise land
			// tens of thousands of points apart purely because of what they ate.
			//
			// So the multiple stays as the shape of the curve and these decide where each
			// individual stat stops. Applied after growth and conversion, at the last point
			// before the number reaches the client.
			//
			// Every one is a ceiling and not a target. A wolf's few hundred HP and its
			// hundred-odd PAtk never reach any of these, so weak and mid creatures are
			// bit-identical to before and only the genuinely broken high end is held down.
			set.set("org_hp", cap(hpCap, baseHp * safeMultiplier * growth));
			set.set("org_mp", (int) Math.round(baseMp * safeMultiplier * growth));
			set.set("org_pattack", cap(pAtkCap, basePAtk * safeMultiplier * growth));
			set.set("org_pdefend", cap(pDefCap, basePDef * safeMultiplier * growth));
			set.set("org_mattack", cap(mAtkCap, baseMAtk * safeMultiplier * growth));
			set.set("org_mdefend", cap(mDefCap, baseMDef * safeMultiplier * growth));

			// Reasonable safe defaults for fields not tied to combat power.
			// get_exp_type is the share of the kill's experience the OWNER keeps.
			// Stock splits it, so the pet only ever gets 100 - this. This module
			// sets it to 100 so the owner keeps the whole kill, and the tame's own
			// share is granted separately at the same value by
			// TameExperienceShare, which makes both sides earn the full amount
			// instead of trading a share back and forth.
			set.set("get_exp_type", TameExperienceShare.ownerExpType());
			// Clamped to the top of the experience table rather than one short of it.
			// getMaxLevel() is one past the last reachable level, so clamping at
			// getMaxLevel() - 1 would hand the sentinel row the cap's own threshold
			// again and reintroduce the stall this table exists to avoid.
			set.set("exp", ExperienceData.getInstance().getExpForLevel(Math.min(level, ExperienceData.getInstance().getMaxLevel())));
			// A flat 100000 here is what made tames look unfeedable: the gauge was
			// a hundred thousand units tall while one item of food put back about
			// a hundred, so a tame needed thousands of items to move it and never
			// crossed the hunger line. Capacity now grows with the level the same
			// way a stock pet's does, and the burn rates are tied to that capacity
			// so a tame at level drains on a stock-like schedule.
			set.set("max_meal", food.maxMeal(level));
			set.set("consume_meal_in_battle", food.consumeInBattle(level));
			set.set("consume_meal_in_normal", food.consumeWhileNormal(level));
			set.set("org_hp_regen", (int) Math.round(1 + (level * 0.2)));
			set.set("org_mp_regen", (int) Math.round(1 + (level * 0.1)));
			set.set("soulshot_count", 1);
			set.set("spiritshot_count", 1);

			data.addNewStat(level, new PetLevelData(set));
		}

		return data;
	}
	
	/**
	 * Copies the wild NPC template's usable active skills into one generated
	 * profile. Passive and toggle skills are deliberately excluded because
	 * ordinary Pet action handling cannot safely invoke them as commands.
	 *
	 * <p>Backward-compatible entry point: old callers now receive the bounded staged
	 * policy rather than every active wild skill.
	 */
	@Deprecated
		public static void addWildSkills(PetData data, NpcTemplate template)
		{
			TameSkillPolicy.register(data, template, null);
		}
	
	/**
	 * Absolute ceilings on the stats a tame reads from its own level table, in points.
	 * Set from {@code PetHpCap}, {@code PetPAtkCap}, {@code PetMAtkCap},
	 * {@code PetPDefCap} and {@code PetMDefCap} in module.ini.
	 *
	 * <p>The growth ceiling bounds the multiple, and for a stat whose base is ordinary that
	 * is sufficient on its own. It stops being sufficient the moment the base is not. A raid
	 * boss begins an order of magnitude above a normal mob, so 3x that base is still a value
	 * the game cannot answer - for HP it is a boss no one can kill, and for PAtk it is a boss
	 * that deletes a character in one hit. The asymmetry that made this a bug in the first
	 * place was never really about the multiplier at all; it was that a wolf at 437x is still
	 * a wolf while a raid boss at 3x is not.
	 *
	 * <p>So the multiple keeps its job of deciding the SHAPE of a beast's progression, and
	 * these decide where each individual stat stops. They are applied after growth and after
	 * conversion, at the last point before the number is handed to the client.
	 *
	 * <p>Deliberately absolute and deliberately blunt. A soft curve would be prettier and
	 * would also make it impossible to say what any given tame ends up at, which is the
	 * opposite of what this needs to do. Every one is a ceiling and not a target, so weak
	 * and mid creatures never reach any of them and are bit-identical to before; only the
	 * high end, which is where the breakage was, is held down.
	 *
	 * <p>0 disables any single cap without touching the others. Out-of-range values are
	 * refused and logged rather than clamped, so a typo cannot silently halve every beast in
	 * the game - see {@link #setHpCap(int)} for the reasoning, which these share.
	 */
	private static volatile int hpCap = 30000;
	private static volatile int pAtkCap = 22000;
	private static volatile int mAtkCap = 22000;
	private static volatile int pDefCap = 3000;
	private static volatile int mDefCap = 3000;

	/**
	 * The HP ceiling in force, in absolute points, or 0 when uncapped.
	 */
	public static int getHpCap()
	{
		return hpCap;
	}

	/**
	 * The PAtk ceiling in force, in absolute points, or 0 when uncapped.
	 */
	public static int getPAtkCap()
	{
		return pAtkCap;
	}

	/**
	 * The MAtk ceiling in force, in absolute points, or 0 when uncapped.
	 */
	public static int getMAtkCap()
	{
		return mAtkCap;
	}

	/**
	 * The PDef ceiling in force, in absolute points, or 0 when uncapped.
	 */
	public static int getPDefCap()
	{
		return pDefCap;
	}

	/**
	 * The MDef ceiling in force, in absolute points, or 0 when uncapped.
	 */
	public static int getMDefCap()
	{
		return mDefCap;
	}

	/**
	 * Sets the absolute HP ceiling, from {@code PetHpCap} in module.ini.
	 *
	 * <p>Refuses anything outside 1000..1000000 rather than clamping it. A config that
	 * silently became 1000 would quietly remove every boss from the game, and one that
	 * silently became 200000 would leave the original problem in place while looking like it
	 * had been addressed. Either is worse than a refused value and one line in the log.
	 */
	public static void setHpCap(int value)
	{
		hpCap = validatedCap("PetHpCap", value, hpCap);
	}

	/**
	 * Sets the absolute PAtk ceiling, from {@code PetPAtkCap} in module.ini.
	 */
	public static void setPAtkCap(int value)
	{
		pAtkCap = validatedCap("PetPAtkCap", value, pAtkCap);
	}

	/**
	 * Sets the absolute MAtk ceiling, from {@code PetMAtkCap} in module.ini.
	 */
	public static void setMAtkCap(int value)
	{
		mAtkCap = validatedCap("PetMAtkCap", value, mAtkCap);
	}

	/**
	 * Sets the absolute PDef ceiling, from {@code PetPDefCap} in module.ini.
	 */
	public static void setPDefCap(int value)
	{
		pDefCap = validatedCap("PetPDefCap", value, pDefCap);
	}

	/**
	 * Sets the absolute MDef ceiling, from {@code PetMDefCap} in module.ini.
	 */
	public static void setMDefCap(int value)
	{
		mDefCap = validatedCap("PetMDefCap", value, mDefCap);
	}

	/**
	 * Shared range check behind every stat ceiling setter.
	 *
	 * <p>0 means uncapped and is always allowed. Anything else must be a plausible stat, and
	 * anything outside that is refused with the name of the setting that was wrong rather than
	 * quietly clamped - a config that silently became a tiny number would remove content from
	 * the game, and one that silently became enormous would leave the original problem in
	 * place while looking like it had been addressed. The band is deliberately generous so a
	 * server owner setting something extreme on purpose is not blocked by it.
	 */
	private static int validatedCap(String name, int value, int current)
	{
		if (value == 0)
		{
			return 0;
		}
		if ((value < 1) || (value > 1000000))
		{
			LOGGER.warning(name + " " + value + " is outside 1..1000000 and was ignored; staying at " + current);
			return current;
		}
		return value;
	}

	/**
	 * Applies one absolute ceiling to one computed stat.
	 *
	 * <p>A cap of 0 means uncapped, which is why this is not just a
	 * {@code Math.min} against the cap: min against 0 would zero every beast.
	 *
	 * @param ceiling the ceiling in force for this particular stat
	 * @param value the computed value, before any ceiling is applied
	 */
	private static int cap(int ceiling, double value)
	{
		final int rounded = (int) Math.round(value);
		return (ceiling > 0) ? Math.min(rounded, ceiling) : rounded;
	}

	/** Returns the first-pass balance coefficient for a configured taming tier. */
	public static double getConversionMultiplier(String tier)
	{
		if (tier == null)
		{
			return 0.75;
		}
		final String normalizedTier = tier.trim().toUpperCase();
		switch (normalizedTier)
		{
			case "EASY":
			case "NORMAL":
				return 0.80;
			case "MEDIUM":
			case "ELITE":
				return 0.75;
			case "CHAMPION":
			case "HARD":
				return 0.65;
			case "RAID":
			case "BOSS":
				// Was 0.50, chosen while growth was unbounded and could reach 437x: at that
				// coefficient the cap was doing nothing and this number was only a rounding
				// error against the runaway. Then 0.35, which put a max-growth raid tame at
				// about 1.05x the raw boss's own stats. Now 0.2333, which puts it at about
				// 0.70x - a raid tame is finally weaker than the raid it was taken from.
				//
				// Read as "0.2333 x 3.0 = 0.70", not as "0.70". The conversion is a
				// coefficient multiplied by a capped growth term, so the number people
				// actually feel is the product. Raising this to 0.70 would make raid tames
				// 2.10x, which is the bug this whole change was about.
				return 0.2333;
			default:
				return 0.75;
		}
	}
}
