/*
 * TamePlayerDamageSkills.java
 *
 * Answers one question: which skill ids are damage skills?
 *
 * WHY THIS READS THE XML INSTE OF ASKING THE RUNTIME
 *
 * The obvious way to ask is Skill.isDamage(), and on this build it returns false for all
 * 622 player skills - not because none of them are damage skills, but because the flag is
 * not populated by this datapack. The second obvious way is Skill.hasEffectType, or
 * walking Skill.getEffects, and both go through the effect handler registry - which on
 * this engine build contains no concrete handlers at all. Every instantiation logs
 * "Requested unexistent effect handler: PhysicalDamage" and returns nothing. So the
 * runtime cannot answer this question here, and a page that filtered on it would come up
 * empty and look like a bug in the page rather than a property of the build.
 *
 * The datapack is the authority that does know. A damage skill is one whose block
 * declares a damage effect - PhysicalDamage, MagicalDamage, EnergyDamage, MagicalDamageMp,
 * StaticDamage or PhysicalDamageHpLink. That is read straight out of
 * data/stats/skills, which is where the core reads it from too (the core resolves that
 * directory as new File(".", "data/stats/skills"), relative to the working directory the
 * server is started in). One regex pass over the chunked files, once, then cached.
 *
 * WHY THE FALLBACK EXISTS
 *
 * If the directory ever moves, or the regex stops matching a future datapack layout, the
 * page would silently lose every row. So a failed scan falls back to the runtime check
 * anyway and reports which source was used. On a build with working effect handlers that
 * fallback is the better source; on this one it returns nothing, and an empty page with an
 * explanation beats a page that quietly lies about what it filtered.
 *
 * SCOPE
 *
 * This only classifies. It does not decide what may be cast, does not touch the affinities
 * and does not cast anything.
 */
package taming;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.data.xml.SkillTreeData;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.effects.EffectType;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.holders.SkillLearn;

public final class TamePlayerDamageSkills
{
	private static final Logger LOGGER = Logger.getLogger(TamePlayerDamageSkills.class.getName());

	/**
	 * One skill block, captured whole. Non-greedy to the first closing tag, which is
	 * correct because these blocks do not nest: a skill's tables and effects are its only
	 * children.
	 */
	private static final Pattern SKILL_BLOCK = Pattern.compile("<skill\\s+id=\"(\\d+)\"[^>]*>(.*?)</skill>", Pattern.DOTALL);

	/**
	 * A damage effect declaration. These are the six names that appear in the datapack;
	 * TriggerSkillByDamageReceived is deliberately not here, since it reacts to being hit
	 * rather than dealing the hit.
	 */
	private static final Pattern DAMAGE_EFFECT = Pattern.compile(
			"<effect\\s+name=\"(?:PhysicalDamage|MagicalDamage|EnergyDamage|MagicalDamageMp|StaticDamage|PhysicalDamageHpLink)\"");

	/** Where the core keeps the chunked skill definitions. */
	private static final File SKILL_DIR = new File(".", "data/stats/skills");

	/**
	 * Null until the first question is asked, then the answer for every id in the data.
	 *
	 * <p>Held in a volatile rather than built under a lock: two players opening the page at
	 * once may both scan, and scanning twice is harmless for a set that is only read.
	 */
	private static volatile Set<Integer> damageIds;

	/** The resolved catalogue behind {@link #playerDamageSkills()}, or null until asked. */
	private static volatile List<Skill> catalogue;

	/** Null while the XML scan has not been attempted; "" means it was tried and failed. */
	private static volatile String source;

	private TamePlayerDamageSkills()
	{
	}

	/**
	 * Whether this skill deals damage, and so is worth putting on a bench as an attack
	 * animation rather than as a buff.
	 *
	 * @param skill the skill to classify; null is not damage, so it is never offered
	 */
	public static boolean dealsDamage(Skill skill)
	{
		if ((skill == null) || (skill.getId() <= 0))
		{
			return false;
		}
		final Set<Integer> ids = damageIds();
		if (!ids.isEmpty())
		{
			return ids.contains(Integer.valueOf(skill.getId()));
		}
		// The scan produced nothing usable. Fall back to the runtime check so the page
		// degrades to "fewer rows" rather than to "no rows", and the log says why.
		return runtimeDamage(skill);
	}

	/**
	 * Whether the classification came from the datapack or from the runtime, for the page
	 * to report. Empty until something has asked.
	 */
	public static String source()
	{
		damageIds();
		return source;
	}

	/** How many damage skills were found, for the page's footer. */
	public static int damageCount()
	{
		return damageIds().size();
	}

	/**
	 * Every active player damage skill, at its highest level, built once.
	 *
	 * <p>Shared by the SKILL VISUALS page and by {@link TameSkillPolicy}, which hands these
	 * to tames as real techniques. Both want the same list and neither wants to re-walk the
	 * 89 class trees, so it is built here and cached.
	 *
	 * <p>Highest level for the same reason the rebuild uses it: some skills do not exist at
	 * level 1, so asking for level 1 would silently drop them.
	 */
	public static List<Skill> playerDamageSkills()
	{
		List<Skill> cached = catalogue;
		if (cached != null)
		{
			return cached;
		}
		final List<Skill> found = new ArrayList<>();
		final SkillData data = SkillData.getInstance();
		final Set<Integer> seen = new HashSet<>();
		for (final PlayerClass playerClass : PlayerClass.values())
		{
			final Map<Integer, SkillLearn> tree = SkillTreeData.getInstance().getCompleteClassSkillTree(playerClass);
			if (tree == null)
			{
				continue;
			}
			for (final SkillLearn learn : tree.values())
			{
				final int id = learn.getSkillId();
				if ((id <= 0) || !seen.add(Integer.valueOf(id)))
				{
					continue;
				}
				final int max = data.getMaxLevel(id);
				Skill skill = null;
				for (int level = max; (level >= 1) && (skill == null); level--)
				{
					skill = data.getSkill(id, level);
				}
				if ((skill != null) && usable(skill) && dealsDamage(skill))
				{
					found.add(skill);
				}
			}
		}
		found.sort(Comparator.comparingInt(Skill::getId));
		catalogue = found;
		return found;
	}

	/**
	 * Whether a skill is something a dynamic Pet may be handed at all.
	 *
	 * <p>Deliberately the same test {@code TameSkillPolicy.valid} uses on inherited beast
	 * skills. Note what is <em>not</em> here: there is no condition or weapon check, because
	 * this datapack contains no {@code <condition>} tags anywhere and none of the 95 mention
	 * a weapon. That was verified rather than assumed - a filter that cannot match anything
	 * looks exactly like a filter that found nothing to reject.
	 */
	public static boolean usable(Skill skill)
	{
		return (skill != null) && skill.isActive() && !skill.isPassive() && !skill.isToggle() && !skill.isDance()
				&& !skill.isSuicideAttack() && (skill.getId() > 0) && (skill.getLevel() > 0);
	}

	private static Set<Integer> damageIds()
	{
		Set<Integer> cached = damageIds;
		if (cached != null)
		{
			return cached;
		}
		final Set<Integer> found = scan();
		if (found.isEmpty())
		{
			source = "runtime effect scan (datapack scan found nothing at " + SKILL_DIR.getPath() + ")";
			LOGGER.warning("Skill damage index: no datapack scan result, falling back to the runtime effect "
					+ "check. If this build has no effect handlers that fallback returns nothing and the "
					+ "page will list no skills.");
		}
		else
		{
			source = "datapack";
			LOGGER.info("Skill damage index: " + found.size() + " damage skills read from " + SKILL_DIR.getPath());
		}
		final Set<Integer> immutable = Collections.unmodifiableSet(found);
		damageIds = immutable;
		return immutable;
	}

	/**
	 * One pass over the chunked skill files, collecting the ids that declare a damage
	 * effect.
	 */
	private static Set<Integer> scan()
	{
		final Set<Integer> found = new HashSet<>();
		final File[] files = SKILL_DIR.listFiles((dir, name) -> name.endsWith(".xml"));
		if ((files == null) || (files.length == 0))
		{
			return found;
		}
		// Longest name first so the chunk order is stable across runs and a log line from
		// two boots can be compared. The file names are fixed-width, so this is just the id.
		java.util.Arrays.sort(files, (left, right) -> right.getName().compareTo(left.getName()));
		for (final File file : files)
		{
			try
			{
				final String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
						// The comments in these files are per-level and enormous; they carry no
						// effects but they do carry the words, and a skill whose comment mentions
						// a damage effect must not be counted by one.
						.replaceAll("(?s)<!--.*?-->", "");
				final Matcher block = SKILL_BLOCK.matcher(text);
				while (block.find())
				{
					if (DAMAGE_EFFECT.matcher(block.group(2)).find())
					{
						found.add(Integer.valueOf(block.group(1)));
					}
				}
			}
			catch (Exception e)
			{
				// One unreadable chunk must not throw away the chunks that did read.
				LOGGER.log(Level.FINE, "skill damage index could not read " + file.getName(), e);
			}
		}
		return found;
	}

	/** The runtime check, used only when the datapack scan came back empty. */
	private static boolean runtimeDamage(Skill skill)
	{
		try
		{
			// hasEffectType(type, moreTypes...) asks whether the skill carries any of them,
			// so the first argument is one of the types being looked for rather than a scope.
			if (skill.hasEffectType(EffectType.PHYSICAL_ATTACK, EffectType.MAGICAL_ATTACK,
					EffectType.DMG_OVER_TIME, EffectType.DMG_OVER_TIME_PERCENT))
			{
				return true;
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.FINE, "runtime damage check failed for " + skill.getId(), e);
		}
		return false;
	}
}