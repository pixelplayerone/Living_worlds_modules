/*
 * TameProfileFactory.java
 *
 * Deterministic individual profile generation from real NPC template data.
 * Runtime script source; Java 8 compatible.
 */
package taming;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.actor.enums.npc.AISkillScope;
import org.l2jmobius.gameserver.model.actor.enums.npc.AIType;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.skill.Skill;

public final class TameProfileFactory
{
	public static final int PROFILE_VERSION = 1;
	
	private TameProfileFactory()
	{
	}
	
	public static TameProfile create(int collarObjectId, int ownerId, int npcId, int sourceLevel, String sourceType, String petName, double conversionMultiplier, int maxPetLevel, double baseHp, double baseMp, double basePAtk, double basePDef, double baseMAtk, double baseMDef, NpcTemplate template, String tier)
	{
		final long seed = seed(collarObjectId, ownerId, npcId);
		final Random random = new Random(seed);
		final String family = classifyFamily(template);
		final String role = classifyRole(template);
			final String affinity = classifyAffinity(template, seed);
		final int difficulty = difficulty(sourceType, tier, sourceLevel);
		final int potential = clamp(35 + random.nextInt(46) + Math.min(15, difficulty / 2), 1, 100);
		int remaining = potential;
		final int offense = 1 + random.nextInt(Math.max(1, remaining - 3));
		remaining -= offense;
		final int defense = 1 + random.nextInt(Math.max(1, remaining - 2));
		remaining -= defense;
		final int vitality = 1 + random.nextInt(Math.max(1, remaining - 1));
		remaining -= vitality;
		final int skill = Math.max(1, remaining);
		final double growth = round2(1.25 + (random.nextDouble() * 5.75) + (potential / 100.0));
		final String rarity = rarity(potential, difficulty);
		final String temperament = temperament(random, role, family);
			final String uuid = UUID.nameUUIDFromBytes((collarObjectId + ":" + ownerId + ":" + npcId + ":" + seed).getBytes(StandardCharsets.UTF_8)).toString();
			final int rolledMaxPetLevel = rollMaxPetLevel(sourceLevel, maxPetLevel, random);
			return new TameProfile(uuid, collarObjectId, ownerId, npcId, sourceLevel, safe(sourceType, "Monster"), safe(template.getRace().name(), "NONE"), family, role, rarity, potential, offense, defense, vitality, skill, growth, temperament, affinity, 0, 0, "", 1, rolledMaxPetLevel, seed, PROFILE_VERSION, clampName(petName, template.getName()), (int) Math.round(baseHp), (int) Math.round(baseMp), (int) Math.round(basePAtk), (int) Math.round(basePDef), (int) Math.round(baseMAtk), (int) Math.round(baseMDef), clampMultiplier(conversionMultiplier), 0);
	}
	
	/**
	 * Rolls an individual ceiling without allowing a low-level source to jump
	 * into the level-80 range. The configured/source baseline is guaranteed;
	 * the additional ceiling is weighted toward small bonuses. Any creature
	 * whose source level is below 30 can rarely reach the level-30 awakening
	 * threshold, while higher-level creatures receive a source-scaled ceiling.
	 */
	private static int rollMaxPetLevel(int sourceLevel, int configuredMaxPetLevel, Random random)
	{
		final int source = Math.max(1, sourceLevel);
		final int baseline = clamp(Math.max(configuredMaxPetLevel, source + 5), 1, 80);
		final int awakeningCeiling = source < 30 ? 30 : source + 17;
		final int upper = clamp(Math.max(baseline, awakeningCeiling), baseline, 80);
		final int range = upper - baseline;
		if ((range <= 0) || (random == null))
		{
			return baseline;
		}

		final int band = random.nextInt(100);
		final int bonus;
		if (band < 70)
		{
			bonus = random.nextInt(Math.min(2, range) + 1);
		}
		else if (band < 92)
		{
			bonus = random.nextInt(Math.min(6, range) + 1);
		}
		else
		{
			bonus = random.nextInt(range + 1);
		}
		return clamp(baseline + bonus, baseline, upper);
	}

	private static String classifyFamily(NpcTemplate template)
	{
		if (template == null)
		{
			return "UNKNOWN";
		}
		if (template.isType("GrandBoss") || template.isType("RaidBoss"))
		{
			return "BOSS";
		}
		final Race race = template.getRace();
		if (race == Race.DRAGON)
		{
			return "DRAGON";
		}
		if ((race == Race.UNDEAD) || (race == Race.DEMONIC))
		{
			return "CURSE";
		}
		if ((race == Race.CONSTRUCT) || (race == Race.SIEGE_WEAPON))
		{
			return "GUARDIAN";
		}
		if ((race == Race.FAIRY) || (race == Race.ELEMENTAL) || (race == Race.DIVINE))
		{
			return "MYSTIC";
		}
		if ((race == Race.BEAST) || (race == Race.ANIMAL))
		{
			return "BEAST";
		}
		if (race == Race.BUG)
		{
			return "INSECT";
		}
		if ((race == Race.HUMANOID) || (race == Race.HUMAN) || (race == Race.ORC) || (race == Race.ELF) || (race == Race.DARK_ELF) || (race == Race.DWARF) || (race == Race.GIANT))
		{
			return "HUMANOID";
		}
		if (race == Race.PLANT)
		{
			return "PLANT";
		}
		return "OTHER";
	}
	
	private static String classifyRole(NpcTemplate template)
	{
		if (template == null)
		{
			return "FIGHTER";
		}
		final AIType aiType = template.getAIType();
		if (aiType == AIType.ARCHER)
		{
			return "ARCHER";
		}
		if (aiType == AIType.MAGE)
		{
			return "MAGE";
		}
		if ((aiType == AIType.HEALER) || !template.getAISkills(AISkillScope.HEAL).isEmpty() || !template.getAISkills(AISkillScope.BUFF).isEmpty())
		{
			return "SUPPORT";
		}
		if (aiType == AIType.BALANCED)
		{
			for (Skill skill : template.getAISkills(AISkillScope.ATTACK))
			{
				if ((skill != null) && skill.isMagic())
				{
					return "MAGE";
				}
			}
			return "BALANCED";
		}
		return "FIGHTER";
	}
	
	private static String classifyAffinity(NpcTemplate template, long seed)
	{
		if (template == null)
		{
			return TameIdentity.seededAffinity(seed);
		}
		final int fire = template.getBaseFire();
		final int water = template.getBaseWater();
		final int wind = template.getBaseWind();
		final int earth = template.getBaseEarth();
		final int holy = template.getBaseHoly();
		final int dark = template.getBaseDark();
		int value = fire;
		String result = "FIRE";
		if (water > value)
		{
			value = water;
			result = "WATER";
		}
		if (wind > value)
		{
			value = wind;
			result = "WIND";
		}
		if (earth > value)
		{
			value = earth;
			result = "EARTH";
		}
		if (holy > value)
		{
			value = holy;
			result = "HOLY";
		}
		if (dark > value)
		{
			value = dark;
			result = "DARK";
		}
		return value > 0 ? result : TameIdentity.seededAffinity(seed);
	}
	
	private static int difficulty(String sourceType, String tier, int sourceLevel)
	{
		int value = Math.min(40, Math.max(1, sourceLevel / 2));
		final String normalized = safe(tier, "NORMAL").toUpperCase(Locale.ROOT);
		if (normalized.contains("RAID") || normalized.contains("BOSS"))
		{
			value += 45;
		}
		else if (normalized.contains("CHAMPION") || normalized.contains("HARD"))
		{
			value += 25;
		}
		else if (normalized.contains("ELITE") || normalized.contains("MEDIUM"))
		{
			value += 12;
		}
		if (safe(sourceType, "Monster").toLowerCase(Locale.ROOT).contains("raid"))
		{
			value += 20;
		}
		return value;
	}
	
	private static String rarity(int potential, int difficulty)
	{
		final int score = potential + (difficulty / 3);
		if (score >= 125)
		{
			return "LEGENDARY";
		}
		if (score >= 108)
		{
			return "EPIC";
		}
		if (score >= 92)
		{
			return "RARE";
		}
		if (score >= 70)
		{
			return "UNCOMMON";
		}
		return "COMMON";
	}
	
	private static String temperament(Random random, String role, String family)
	{
		if ("GUARDIAN".equals(family))
		{
			return "DEFENSIVE";
		}
		if ("BOSS".equals(family))
		{
			return "AGGRESSIVE";
		}
		if ("SUPPORT".equals(role))
		{
			return random.nextBoolean() ? "LOYAL" : "PATIENT";
		}
		final String[] values = { "AGGRESSIVE", "GUARDIAN", "DEFENSIVE", "COWARDLY", "BERSERKER", "PATIENT", "LOYAL" };
		return values[random.nextInt(values.length)];
	}
		
	private static long seed(int collarObjectId, int ownerId, int npcId)
	{
		long value = 1469598103934665603L;
		value = (value ^ collarObjectId) * 1099511628211L;
		value = (value ^ ownerId) * 1099511628211L;
		value = (value ^ npcId) * 1099511628211L;
		return value;
	}
	
	private static int clamp(int value, int min, int max)
	{
		return Math.max(min, Math.min(max, value));
	}
	
	private static double clampMultiplier(double value)
	{
		return Math.max(0.35, Math.min(1.0, value));
	}
	
	private static double round2(double value)
	{
		return Math.round(value * 100.0) / 100.0;
	}
	
	private static String safe(String value, String fallback)
	{
		return (value == null) || value.trim().isEmpty() ? fallback : value.trim();
	}
	
	private static String clampName(String value, String fallback)
	{
		// An empty name is deliberate, not a missing one. A capture is created unnamed
		// so the client's own rename box is offered, and falling back to the species
		// name here would name it before the owner ever gets the choice. The species
		// is already kept in the profile's source type, so nothing is lost.
		if ((value != null) && !value.trim().isEmpty())
		{
			final String result = value.trim();
			return (result.length() > 16) ? result.substring(0, 16) : result;
		}
		return "";
	}
}
