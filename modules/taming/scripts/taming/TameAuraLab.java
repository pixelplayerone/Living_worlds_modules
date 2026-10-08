/*
 * TameAuraLab.java
 *
 * A test bench for every AbnormalVisualEffect value, as a page of buttons.
 * Opened with .tameaura.
 *
 * WHY THIS EXISTS AS A PAGE AND NOT JUST A COMMAND
 *
 * Picking an aura was being done by editing config/module.ini and rebooting, once per
 * candidate, and the enum has 60 values. That is a reboot per value and it is how a
 * config ends up holding whatever was tried last rather than what was chosen. The
 * .tameaura command already existed to audition a single value by name, but it was
 * registered as an ADMIN command, so it answered to //tameaura and not to .tameaura,
 * and the in-game message that told the player to use ".tameaura" was wrong. A page
 * was the answer anyway: a name typed from memory is a name mistyped, and the values
 * that matter are the ones nobody would guess.
 *
 * So every value gets a button. All 60, including the ones believed to draw nothing.
 * That belief is worth testing rather than trusting - it comes from a struct recovered
 * out of Engine.dll, and a wrong entry there would quietly hide a working effect.
 *
 * WHY IT TARGETS
 *
 * The effect is applied to whatever the player has selected, falling back to their own
 * summoned pet. Being able to point it at a plain unmodified monster, a rival player or
 * a boss matters, because "does this render at all" and "does this render on a collared
 * beast" are different questions and the second one can confound the first.
 *
 * ONE AT A TIME, NOT STACKED
 *
 * The earlier command layered a candidate on top of the existing auras so it could be
 * judged against what it would displace. That is right when auditioning a replacement
 * and wrong for a bench: with 60 buttons, whatever is left over from the previous click
 * is noise, and a value that only looks good stacked is not what will ship. Each click
 * clears the previous audition first, so exactly one lab effect is ever set.
 *
 * THE PAGE IS PAGINATED BECAUSE THE CLIENT HAS A HARD LIMIT
 *
 * AbstractHtmlPacket.setHtml compares the string against 8192 and, when it is longer,
 * logs "Html is too long! this will crash the client!" and silently truncates with
 * substring(0, 8192). It does not refuse and it does not tell the player; it hands the
 * client half a document. The first version of this page put all 60 buttons in one grid,
 * came to roughly 10500 characters, and was cut off partway down - which is why it drew
 * nothing usable and logged the warning on every single click.
 *
 * So the value list is chunked to fit, and the page count is worked out from the real
 * length of the markup rather than from an estimate. The budget is kept well under 8192
 * because the limit is on characters and nothing here is measured in anything else.
 *
 * THE BITMASK REMEMBERS NOTHING
 *
 * Creature.startAbnormalVisualEffect just ORs a bit into an int and broadcasts; the
 * stop counterpart ANDs it back out. There is no owner, no reference count and no
 * record of who set a bit. So OFF cannot ask the creature what to undo - the lab has to
 * remember, and that memory is the audition map below. It is per player and it is not
 * persisted; a reload or a restart drops it, which is the right default for a tool that
 * exists to be used and abandoned.
 *
 * Because there is no ownership, a lab effect and a real buff using the same value are
 * the same bit. If a creature is carrying Capture Penalty while DOT_BLEEDING is being
 * auditioned on it, OFF will clear the penalty's visual too. That is a reason to audition
 * on something quiet, not a bug to fix here.
 *
 * THE MARKUP IS COPIED FROM PAGES ALREADY CONFIRMED TO RENDER
 *
 * Two button shapes are in use in this module and both are known good on this client:
 * the 65 by 21 passport grid button, and the 100 by 22 shop button. ON and OFF use the
 * shop shape because it is the one whose width was fought over - asked for 140 the
 * client tore it into a 100 pixel body plus a stray square at the far end, and closing
 * the button tag put a black square underneath. The grid uses the passport shape.
 *
 * The page keeps every other rule the reagent store learned the hard way: no bgcolor
 * (it crashes this client), no header or description rows, rows as direct children of
 * the table rather than wrapped in a <tr><td> pair, and no valign. Add one of those
 * back on its own if the page ever misbehaves. Do not add two at a time.
 */
package taming;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.data.xml.SkillTreeData;
import org.l2jmobius.gameserver.handler.IBypassHandler;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.skill.AbnormalVisualEffect;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.holders.SkillLearn;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillLaunched;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

public class TameAuraLab implements IVoicedCommandHandler
{
	private static final String[] VOICED_COMMANDS =
	{
			"tameaura",
			"tameaurlab"
	};

	/**
	 * Bypass names, deliberately not sharing the voiced ones. The lab is entered through
	 * a dot command and then driven entirely by button, so only the buttons need to be
	 * reachable as bypass. Nothing here collides with the collar or reagent handlers.
	 */
	private static final String[] BYPASS_COMMANDS =
	{
			"tame_auralab",
			"tame_aura_on",
			"tame_aura_off",
			"tame_aura_reapply",
"tame_aura_page",
			"tame_aura_skills",
			"tame_aura_skill",
			"tame_aura_cat",
"tame_aura_hits",
		"tame_aura_hit",
		"tame_aura_hit_off",
		"tame_aura_gear",
		"tame_aura_give"
	};

	/**
	 * The 100 by 22 button, copied from the reagent store's OPEN SHOP.
	 *
	 * <p>Inner font, unclosed tag, line break straight after. The caption is the button.
	 */
	private static final String WIDE_BUTTON = " width=100 height=22 back=sek.cbui94 fore=sek.cbui92";

	/**
	 * The 65 by 21 grid button, copied from the collar passport's navigation grid.
	 *
	 * <p>Closed td, unclosed button, which is how the passport has always drawn it.
	 */
	private static final String GRID_CELL = "<td width=67 align=center><button value=";
	private static final String GRID_BUTTON = " width=65 height=21 back=L2UI_ch3.smallbutton2_over fore=L2UI_ch3.smallbutton2></td>";

	/**
	 * The small shop button used for the two gear actions, copied from the collar
	 * equipment page's EQUIP button. Two of these and a name fit one row of the dialog
	 * window, where the 100 wide {@link #WIDE_BUTTON} fits one button alone.
	 */
	private static final String SMALL_BUTTON = " width=52 height=20 back=sek.cbui94 fore=sek.cbui92";

	/** Grid geometry: four across, matching the passport navigation block. */
	private static final int COLUMNS = 4;
	private static final int GRID_WIDTH = 270;

	/**
	 * The client's ceiling is 8192 characters and anything longer is truncated rather
	 * than refused. This is the ceiling this page aims under.
	 */
	private static final int HTML_CEILING = 8192;

	/**
	 * What the page spends before any value button: the heading, the status line, ON and
	 * OFF, the navigation block and the footer. Held back from the budget so a full last
	 * page still has room for the parts that are not buttons.
	 */
	private static final int HTML_RESERVED = 1800;

	/** Ceiling for the button markup alone, after the reserved chrome is subtracted. */
	private static final int HTML_BUDGET = HTML_CEILING - HTML_RESERVED;

	/**
	 * Stand-in page index used only for measuring a button.
	 *
	 * <p>Two digits, so every measurement is at least as wide as the widest index the
	 * paginator can hand out. Measuring with a real index would let the page count drift
	 * as the index grows from one digit to two.
	 */
	private static final int PAGE_PROBE = 99;

	/**
	 * Prefixes stripped from a button caption.
	 *
	 * <p>Labels are clipped rather than wrapped by this client's button, so the long
	 * names have to be shortened to fit or they are unreadable. Stripping is done in
	 * code against the real enum rather than from a written list, so a value that stops
	 * being real cannot leave a caption behind, and a value that becomes real gets a
	 * button without anyone editing this.
	 */
	private static final String[] STRIPPED_PREFIXES =
	{
			"DOT_",
			"CHANGE_",
			"AIR_BATTLE_",
			"BR_"
	};

	/**
	 * Words that make a skill name worth trying early.
	 *
	 * <p>Purely an ordering hint, and only because fire was the open question when this
	 * was written. Nothing is filtered out - the other ~875 skills are all still there,
	 * alphabetically after these - so a wrong guess costs a page of scrolling and not a
	 * missing button. Lowercase, and matched as substrings so "Flame Chant",
	 * "Greater Seal of Flame" and "Freezing Flame" all land near each other.
	 */
	private static final String[] ELEMENT_WORDS =
	{
			"fire",
			"flame",
			"flaminar",
			"burn",
			"blaze",
			"ember",
			"pyro",
			"ignit",
			"heat",
			"torch",
			"inferno",
			"meteor",
			"lava"
	};

	/** One audition in progress, per player. */
	private static final class Audition
	{
		private final int creatureObjectId;
		private final AbnormalVisualEffect effect;

		private Audition(int creatureObjectId, AbnormalVisualEffect effect)
		{
			this.creatureObjectId = creatureObjectId;
			this.effect = effect;
		}
	}

	/**
	 * What each player currently has auditioned.
	 *
	 * <p>Keyed by player object id. Not persisted: this is scratch state for a test
	 * tool, and losing it on reload is preferable to a lab effect outliving the session
	 * that set it.
	 */
	private static final Map<Integer, Audition> AUDITIONS = new ConcurrentHashMap<>();

	// ==========================================================================
	// Entry points
	// ==========================================================================

	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		return open(player, 0);
	}

	/**
	 * Whether this player may use the bench at all.
	 *
	 * <p>Everything the bench does was always reachable by any player, because the command was
	 * registered as a voiced one and nothing here asked who was asking. The page casts a
	 * rebuilt copy of any skill in the table at whatever the caster has selected - which may
	 * be another player - and never consulted the skill's own requirements, so it bypassed
	 * the MP cost, the cooldown, the range and the level gate. That is a GM tool and it is now
	 * gated like one.
	 *
	 * <p>Deliberately {@link Player#isGM()} rather than the module's own access list: this is
	 * the server's staff flag, and a tame should not be able to escalate into it.
	 */
	public static boolean isAllowed(Player player)
	{
		return (player != null) && player.isGM();
	}

	private static boolean refuse(Player player)
	{
		player.sendMessage("The aura lab is a GM tool.");
		return false;
	}

	/**
	 * The bypass half of the bench.
	 *
	 * <p>This is a separate handler rather than a second command list on this class
	 * because IBypassHandler and IVoicedCommandHandler both declare
	 * {@code String[] getCommandList()}. One class implementing both can only have one
	 * such method, so one of the two registries would have been handed the other's names
	 * and the buttons would have been registered under the wrong dispatcher - the page
	 * would draw fine and every button on it would do nothing.
	 */
	public static final class Bypass implements IBypassHandler
	{
		@Override
		public boolean onCommand(String command, Player player, Creature bypassOrigin)
		{
			if (player == null)
			{
				return false;
			}
			if (!isAllowed(player))
			{
				return refuse(player);
			}
			// Every action carries the page it was clicked on, so the bench redraws where
			// the player was rather than resetting them to page one. It used to open
			// page zero after each click, which meant reaching a value on the third page
			// threw away the navigation and the player had to page back to it to reach
			// the value next to it. The page is in the link rather than remembered here
			// on purpose: a remembered page is wrong the moment a reload drops the map,
			// and it is wrong for two players sharing a browser profile.
			if (command.startsWith("tame_aura_on"))
			{
				final String[] words = words(command);
				// A value click is "<NAME> <page>"; ON alone is now its own command, so
				// there is no longer an empty first word to mean "the current one".
				return audition(player, (words.length > 0) ? words[0] : "", (words.length > 1) ? parsePage(words[1]) : 0);
			}
			if (command.startsWith("tame_aura_reapply"))
			{
				return reapply(player, pageArgument(command));
			}
			if (command.startsWith("tame_aura_off"))
			{
				final boolean cleared = clear(player);
				open(player, pageArgument(command));
				return cleared;
			}
			if (command.startsWith("tame_aura_page"))
			{
				return open(player, pageArgument(command));
			}
// Order matters and it is not alphabetical. "tame_aura_skills" is a prefix of
		// "tame_aura_skill", so testing the singular first would swallow the plural and
		// the SKILL VISUALS button would try to play a skill whose id was the word
		// "s". Each prefix that is a prefix of another is tested first here.
// Tested before "tame_aura_skills" for the same prefix reason: "tame_aura_cat"
		// shares nothing with it, but it is a page toggle and costs nothing to keep the
		// toggles together at the top of the list rather than scattered.
		if (command.startsWith("tame_aura_cat"))
		{
			final String[] parts = words(command);
			return setCategory(player, parseId((parts.length > 0) ? parts[0] : null));
		}
		if (command.startsWith("tame_aura_skills"))
		{
			return openSkills(player, pageArgument(command));
		}
if (command.startsWith("tame_aura_skill"))
		{
			final String[] parts = words(command);
			return castSkill(player, parseId((parts.length > 0) ? parts[0] : null), (parts.length > 1) ? parsePage(parts[1]) : 0);
		}
		// Same prefix trap as above, one pair over: "tame_aura_hits" starts with
		// "tame_aura_hit", so the plural has to be tested first or the HIT FX page would
		// try to burst skill "s".
		if (command.startsWith("tame_aura_hit_off"))
		{
			return stopBurst(player);
		}
		if (command.startsWith("tame_aura_hits"))
		{
			return openHits(player, pageArgument(command));
		}
		if (command.startsWith("tame_aura_hit"))
		{
			final String[] parts = words(command);
			return startBurst(player, parseId((parts.length > 0) ? parts[0] : null), (parts.length > 1) ? parsePage(parts[1]) : 0);
		}
		if (command.startsWith("tame_aura_gear"))
		{
			return openGear(player, pageArgument(command));
		}
		if (command.startsWith("tame_aura_give"))
		{
			final String[] parts = words(command);
			return grantGear(player, parseId((parts.length > 0) ? parts[0] : null), (parts.length > 1) ? parsePage(parts[1]) : 0);
		}
		return open(player, 0);
		}

		@Override
		public String[] getCommandList()
		{
			return BYPASS_COMMANDS;
		}
	}

	// ==========================================================================
	// Skill visuals: every skill in the data, animation only
	// ==========================================================================

	/**
	 * The last visual played, per player.
	 *
	 * <p>Just enough to write "you played this on that" back onto the page, which is what
	 * makes a name you half-remember reportable. There is no buff handle here any more and
	 * nothing to remove - a visual-only skill applies nothing by construction, which is
	 * precisely why the page can offer all of them.
	 */
	private static final class SkillAudition
	{
		private final int creatureObjectId;
		private final String skillName;

		private SkillAudition(int creatureObjectId, String skillName)
		{
			this.creatureObjectId = creatureObjectId;
			this.skillName = skillName;
		}
	}

	/** What each player has cast, per player object id. Not persisted, like AUDITIONS. */
	private static final Map<Integer, SkillAudition> SKILL_AUDITIONS = new ConcurrentHashMap<>();

	/**
	 * Every active player damage skill, built once.
	 *
	 * <p>Null until first use. Held in a volatile rather than built under a lock because
	 * two players opening the page at once may both build it, and building it twice is
	 * harmless - it is a read-only list of skills the server already has in memory.
	 */
	private static volatile List<Skill> SKILL_CATALOGUE;

	/**
	 * Skills the bench is allowed to cast.
	 *
	 * <p>The pool is player skills, and only the attacking ones. Three filters, each for a
	 * stated reason:
	 *
	 * <ul>
	 * <li><b>Player skills</b>, from every class skill tree rather than
	 * {@code getBaseGameSkillIds}. The base-game list is 2682 named skills, most of them
	 * NPC and pet abilities nobody would put on a collared beast. The 89 class trees union
	 * to 622 ids, and those are skills a player actually learns - the pool whose animations
	 * were designed to be looked at.</li>
	 * <li><b>Not passive.</b> A passive draws no cast animation at all, so it would be a
	 * row whose button does nothing visible.</li>
	 * <li><b>Damage only.</b> Buffs, heals and pure debuffs are excluded. An aura is an
	 * attacking animation, and a Stun button sitting in the same list as a Flame is a row
	 * that cannot answer the question the page exists to answer. A damage skill that also
	 * happens to debuff is kept - it still draws the attack.</li>
	 * </ul>
	 *
	 * <p>That leaves 95, which is the point of the change: a list short enough to read
	 * rather than to search. "Deals damage" is decided by {@link TamePlayerDamageSkills},
	 * because on this build neither {@code isDamage} nor the effect list can answer it.
	 */
	private static final List<Skill> skillCatalogue()
	{
		List<Skill> cached = SKILL_CATALOGUE;
		if (cached != null)
		{
			return cached;
		}
		final List<Skill> found = new ArrayList<>();
		final SkillData data = SkillData.getInstance();
		final Set<Integer> seen = new java.util.HashSet<>();
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
				// The same id sits in a dozen trees - a base class skill plus every subclass
				// that keeps it - so without this the page would list it a dozen times.
				if ((id <= 0) || !seen.add(Integer.valueOf(id)))
				{
					continue;
				}
				// The highest level, for the same reason the rebuild uses it: some skills do not
				// exist at level 1, so asking for level 1 would silently drop them from a page
				// whose whole purpose is to have everything in it.
				final int max = data.getMaxLevel(id);
				Skill skill = null;
				for (int level = max; (level >= 1) && (skill == null); level--)
				{
					skill = data.getSkill(id, level);
				}
				if (isAuditionable(skill) && TamePlayerDamageSkills.dealsDamage(skill))
				{
					found.add(skill);
				}
			}
		}
		// Category first, then name, so the default page reads as blocks rather than as one
		// alphabetical smear. Range then id breaks every remaining tie, which makes the
		// ordering total and therefore identical between reloads - a list that reshuffles
		// itself makes "I clicked the one next to X" useless.
		found.sort((left, right) ->
		{
			final int byKind = Integer.compare(categoryOf(left), categoryOf(right));
			if (byKind != 0)
			{
				return byKind;
			}
			final int byName = left.getName().compareToIgnoreCase(right.getName());
			if (byName != 0)
			{
				return byName;
			}
			final int byRange = Integer.compare(left.getCastRange(), right.getCastRange());
			return (byRange != 0) ? byRange : Integer.compare(left.getId(), right.getId());
		});
		SKILL_CATALOGUE = found;
		return found;
	}

	/**
	 * The four categories, as tab ids. Magic and physical are one axis and short and long
	 * are another, so these are overlapping tabs rather than a partition: the two halves
	 * each account for every skill in the catalogue, and a skill can be reached either way.
	 */
	private static final int CAT_ALL = 0;
	private static final int CAT_MAGIC = 1;
	private static final int CAT_PHYSICAL = 2;
	private static final int CAT_SHORT = 3;
	private static final int CAT_LONG = 4;

	/** The cast range at or below which a skill counts as short. */
	private static final int SHORT_RANGE = 150;

	/**
	 * Cast ranges worth knowing about before trusting the split.
	 *
	 * <p>A negative cast range is not a bug and not "very long": it means the skill names no
	 * range at all, which is what the self-centred area attacks use - Whirlwind, Thunder
	 * Storm, Sword Symphony and the blade dances all report -1. They are the most promising
	 * rows on the page for an aura, because they are already drawn around the caster rather
	 * than at something in front of it. They count as short, since they are the opposite of
	 * long range.
	 */
	private static boolean isShortRange(Skill skill)
	{
		return skill.getCastRange() <= SHORT_RANGE;
	}

	/** Whether this player is looking at this tab. */
	private static boolean inCategory(Skill skill, int category)
	{
		switch (category)
		{
			case CAT_MAGIC:
				return skill.isMagic();
			case CAT_PHYSICAL:
				return !skill.isMagic();
			case CAT_SHORT:
				return isShortRange(skill);
			case CAT_LONG:
				return !isShortRange(skill);
			default:
				return true;
		}
	}

	/**
	 * Which block of the default page a skill lands in, used only for ordering: magic first
	 * because those are the affinities with an animation worth hunting, then physical.
	 */
	private static int categoryOf(Skill skill)
	{
		return skill.isMagic() ? 0 : 1;
	}

	/**
	 * Which tab each player is on. Absent means CAT_ALL, which is the safe default: the
	 * first page someone opens shows everything.
	 */
	private static final Map<Integer, Integer> SKILL_CATEGORY = new ConcurrentHashMap<>();

	private static int category(Player player)
	{
		if (player == null)
		{
			return CAT_ALL;
		}
		final Integer chosen = SKILL_CATEGORY.get(Integer.valueOf(player.getObjectId()));
		if ((chosen == null) || (chosen.intValue() < CAT_ALL) || (chosen.intValue() > CAT_LONG))
		{
			return CAT_ALL;
		}
		return chosen.intValue();
	}

	/**
	 * Switches tab and redraws, returning to page 0 because the tab is a different list and
	 * keeping page 4 of a tab that has one page shows an empty grid.
	 */
	private static boolean setCategory(Player player, int which)
	{
		SKILL_CATEGORY.put(Integer.valueOf(player.getObjectId()), Integer.valueOf(which));
		return openSkills(player, 0);
	}

	/** The catalogue narrowed to the player's tab, in catalogue order. */
	private static List<Skill> visibleSkills(Player player)
	{
		final int which = category(player);
		final List<Skill> all = skillCatalogue();
		if (which == CAT_ALL)
		{
			return all;
		}
		final List<Skill> kept = new ArrayList<>();
		for (final Skill skill : all)
		{
			if (inCategory(skill, which))
			{
				kept.add(skill);
			}
		}
		return kept;
	}

	/** How many catalogue skills each tab holds, for the captions. */
	private static int categorySize(int which)
	{
		int count = 0;
		for (final Skill skill : skillCatalogue())
		{
			if (inCategory(skill, which))
			{
				count++;
			}
		}
		return count;
	}

	/** Whether a skill name sounds like it is worth trying for an elemental aura. */
	private static boolean looksElemental(String name)
	{
		if (name == null)
		{
			return false;
		}
		final String lower = name.toLowerCase(java.util.Locale.ROOT);
		for (final String word : ELEMENT_WORDS)
		{
			if (lower.contains(word))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a skill belongs on the page at all.
	 *
	 * <p>One test now, and it is only about being reportable: a button with no caption
	 * cannot be described afterwards, so unnamed skills are left out. Everything else is
	 * allowed in - passives, toggles, dances, damage skills, instant effects, the lot.
	 *
	 * <p>The previous filter threw most of that away on the grounds that they draw nothing
	 * useful on a target. That reasoning was sound for what the page was doing then, and it
	 * is wrong for what it does now: this is a list of things to look at, and the only
	 * reliable way to find out whether one of them draws anything is to press it.
	 */
	private static boolean isAuditionable(Skill skill)
	{
		if (skill == null)
		{
			return false;
		}
		final String name = skill.getName();
		return (name != null) && !name.isEmpty();
	}

	/**
	 * The SKILL VISUALS page: the same grid, driven by the skill table instead of the
	 * enum.
	 *
	 * <p>This is the only route to a visual the 24-value struct cannot name. It is a
	 * separate page rather than more rows on the first one for a boring reason: the two
	 * lists are different lengths by orders of magnitude, so sharing a page means either
	 * one enormous page or a confusing index space.
	 *
	 * <p>Every button plays the skill's animation and nothing else, so the whole table can
	 * be listed without any of it being dangerous to press. That is the reason this page can
	 * hold the catalogue at all - there is nothing on it to be careful with.
	 */
	private static boolean openSkills(Player player, int page)
	{
		if (player == null)
		{
			return false;
		}
		final List<Skill> catalogue = visibleSkills(player);
		if (catalogue.isEmpty())
		{
			player.sendMessage("No skills found. The player damage index returned nothing usable "
					+ "(source: " + TamePlayerDamageSkills.source() + ").");
			return open(player, 0);
		}
		final List<List<Skill>> pages = paginateSkills(catalogue);
		final int index = Math.max(0, Math.min(page, pages.size() - 1));
		final List<Skill> shown = pages.get(index);
		final int tab = category(player);

		final SkillAudition current = SKILL_AUDITIONS.get(player.getObjectId());
		final StringBuilder html = new StringBuilder(HTML_CEILING);
		html.append("<html><body><center>");
		html.append("<font color=F2CC60>SKILL VISUALS</font><br>");

		// Nav above the fold for the same reason as the main page: the grid is long, the page
		// is rebuilt on every click, and a rebuilt page lands the client back at the top.
		// Three across in two rows so the five tabs and BACK stay one compact block.
		html.append("<table width=310 border=0><tr>");
		html.append(tabCell(CAT_ALL, "ALL", tab));
		html.append(tabCell(CAT_MAGIC, "MAGIC", tab));
		html.append(tabCell(CAT_PHYSICAL, "PHYSICAL", tab));
		html.append("</tr><tr>");
		html.append(tabCell(CAT_SHORT, "SHORT", tab));
		html.append(tabCell(CAT_LONG, "LONG", tab));
		html.append("<td width=100 align=center><button value=\"BACK\" action=\"bypass -h tame_auralab 0\"")
				.append(WIDE_BUTTON).append("><font color=D7DCE2>value effects</font></td>");
		html.append("</tr></table><br>");

		html.append("<font color=6E7681>Active player damage skills - no buffs, passives or debuffs.</font><br>");
		html.append("<font color=6E7681>Plays a skill's animation on your target and nothing else.</font><br>");
		html.append("<font color=6E7681>No damage, no effects, no cost. Safe to press anything.</font><br>");
		html.append("<font color=6E7681>SHORT is cast range 150 or less. A range of -1 means the</font><br>");
		html.append("<font color=6E7681>skill names no range and draws around the caster.</font><br><br>");

		if (current == null)
		{
			html.append("<font color=6E7681>Nothing played.</font><br>");
		}
		else
		{
			html.append("<font color=E4E1DB>").append(current.skillName).append("</font>");
			html.append("<font color=6E7681> on ").append(escape(describe(current.creatureObjectId))).append("</font><br>");
		}

		html.append("<br>");
		html.append("<table width=").append(GRID_WIDTH).append(" border=0>");
		for (int i = 0; i < shown.size(); i++)
		{
			if ((i % COLUMNS) == 0)
			{
				html.append("<tr>");
			}
			// Built with the real page index, not the probe the paginator measured with.
			html.append(skillCell(shown.get(i), index));
			if (((i % COLUMNS) == (COLUMNS - 1)) || (i == (shown.size() - 1)))
			{
				html.append("</tr>");
			}
		}
		html.append("</table><br>");

		if (pages.size() > 1)
		{
			html.append("<table width=210 border=0><tr>");
			if (index > 0)
			{
				html.append("<td width=105 align=center><button value=\"PREV\" action=\"bypass -h tame_aura_skills ").append(index - 1).append('"').append(WIDE_BUTTON).append("></td>");
			}
			if (index < (pages.size() - 1))
			{
				html.append("<td width=105 align=center><button value=\"NEXT\" action=\"bypass -h tame_aura_skills ").append(index + 1).append('"').append(WIDE_BUTTON).append("></td>");
			}
			html.append("</tr></table><br>");
			html.append("<font color=6E7681>page ").append(index + 1).append(" of ").append(pages.size()).append("</font><br>");
		}
		html.append("<font color=6E7681>").append(catalogue.size()).append(" of ").append(skillCatalogue().size())
				.append(" shown - ").append(categorySize(CAT_MAGIC)).append(" magic, ")
				.append(categorySize(CAT_PHYSICAL)).append(" physical, ")
				.append(categorySize(CAT_SHORT)).append(" short, ")
				.append(categorySize(CAT_LONG)).append(" long.</font>");
		html.append("</center></body></html>");

		if (html.length() > HTML_CEILING)
		{
			player.sendMessage("Skill page is " + html.length() + " characters, over the " + HTML_CEILING + " the client allows. Report this.");
		}
		player.sendPacket(new NpcHtmlMessage(0, html.toString()));
		return true;
	}

	/**
	 * One tab button. The active tab is captioned with a bullet rather than a different
	 * colour, because this client has been shown to ignore font colour inside a button.
	 */
	private static String tabCell(int which, String caption, int active)
	{
		final boolean on = (which == active);
		final StringBuilder cell = new StringBuilder("<td width=100 align=center><button value=\"");
		cell.append(on ? "*" : "").append(caption).append(' ').append(categorySize(which));
		cell.append("\" action=\"bypass -h tame_aura_cat ").append(which).append(" 0\"").append(WIDE_BUTTON);
		cell.append("><font color=D7DCE2>").append(caption).append("</font></td>");
		return cell.toString();
	}

	/** One skill's button markup. Same shape as a value button, different action. */
	private static String skillCell(Skill skill, int page)
	{
		final StringBuilder cell = new StringBuilder(GRID_CELL);
		cell.append(caption(skill.getName())).append(" action=\"bypass -h tame_aura_skill ").append(skill.getId()).append(' ').append(page).append('"');
		cell.append(GRID_BUTTON);
		return cell.toString();
	}

	/**
	 * Splits the skills into pages that each fit the ceiling.
	 *
	 * <p>The same measurement-then-rebuild arrangement as {@link #paginate}, over skills
	 * instead of enum values, and for the same reason: the action carries the page index,
	 * so the cell has to be measured at a width no real index can exceed.
	 */
	private static List<List<Skill>> paginateSkills(List<Skill> catalogue)
	{
		final List<List<Skill>> pages = new ArrayList<>();
		List<Skill> current = new ArrayList<>();
		int used = HTML_RESERVED;
		for (final Skill skill : catalogue)
		{
			final int cost = skillCell(skill, PAGE_PROBE).length();
			if (((used + cost) > HTML_BUDGET) && !current.isEmpty())
			{
				pages.add(current);
				current = new ArrayList<>();
				used = HTML_RESERVED;
			}
			current.add(skill);
			used += cost;
		}
		pages.add(current);
		return pages;
	}

	/**
	 * Plays a skill's animation on the target, with none of the skill.
	 *
	 * <p>This is the same rebuilt skill the holy and dark auras cast, asked for by id
	 * instead of by configuration. That reuse is the point: it is already known to build
	 * cleanly and to carry no effects, so a button can offer every skill in the data
	 * without a page of them being dangerous to press. There is nothing to disable, nothing
	 * to clean up afterwards, and no way for a stray button press to do anything at all.
	 *
	 * <p>{@code useMagic} on the target rather than on the player, so the creature is the
	 * thing seen doing it - the same call the affinity auras make. It checks passive,
	 * already-casting, and reuse, but it does <em>not</em> check whether the caster knows
	 * the skill, which is what makes it usable here: the skill belongs to nobody.
	 *
	 * <p>The creature casts, not the player, so no requirement is consulted anywhere - magic
	 * level, weapon, precondition, none of it.
	 */
	private static boolean castSkill(Player player, int skillId, int page)
	{
		if (skillId <= 0)
		{
			player.sendMessage("That link had no skill id in it.");
			return openSkills(player, page);
		}
		final Skill visual = TameSkillAura.visualOnly(skillId);
		if (visual == null)
		{
			player.sendMessage("Skill " + skillId + " could not be rebuilt as a visual, so nothing was played.");
			return openSkills(player, page);
		}
		final Creature target = selected(player);
		if (target == null)
		{
			player.sendMessage("Select a creature first, or summon your tame.");
			return openSkills(player, page);
		}
		player.setTarget(target);
		playVisual(target, visual);
		SKILL_AUDITIONS.put(player.getObjectId(), new SkillAudition(target.getObjectId(), visual.getName()));
		player.sendMessage("Played " + visual.getName() + " (" + skillId + ") on " + describe(target.getObjectId()) + ". Visual only - nothing was applied to it.");
		return openSkills(player, page);
	}

	/**
	 * Puts the animation on a creature without asking it anything.
	 *
	 * <p>A summon - the tame, and the thing this is nearly always pointed at - gets the
	 * quiet route: a bare {@code MagicSkillLaunched} naming the real id and level. That is
	 * exactly what the affinity auras do when AffinitySilentMagic is on, which is the point
	 * of a bench - what plays here is what plays in game. Sending {@code MagicSkillUse}
	 * instead would make a magic-type skill raise the casting gesture, and the client decides
	 * that from its own tables, so a button labelled with a skill that gestures would be
	 * auditioning something the player will never actually see on their beast.
	 *
	 * <p>There used to be a NOT magic toggle here for exactly that problem. It is gone: it
	 * set isMagic=0 on the rebuilt skill, and a live test showed the client gesturing anyway,
	 * because the server's copy of that flag is not the client's. The button promised
	 * something it could not deliver, so it is better absent than present and wrong.
	 *
	 * <p>Anything that is not a summon falls back to {@code doCast}, which is
	 * {@code beginCast} and nothing more. That path cannot be made quiet without becoming a
	 * different method, and auditioning on a plain monster is the diagnostic case rather than
	 * the shipping one.
	 */
	private static void playVisual(Creature target, Skill visual)
	{
		if (target instanceof Summon)
		{
			final Summon summon = (Summon) target;
			summon.broadcastPacket(new MagicSkillLaunched(summon, visual.getId(), visual.getLevel(),
					Collections.singletonList((WorldObject) summon)));
			return;
		}
		target.doCast(visual);
	}

	/**
	 * A skill id from a client-supplied string, or 0.
	 */
	private static int parseId(String text)
	{
		if (text == null)
		{
			return 0;
		}
		try
		{
			return Integer.parseInt(text.trim());
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	// ==========================================================================
	// Hit effects: the pretty thing a damage skill does on impact
	// ==========================================================================

	/**
	 * One repeating impact burst in progress, per player.
	 *
	 * <p>Just the two ids. There is deliberately no handle here, because nothing is
	 * applied to the creature to be removed later - see {@link #burstTick}.
	 */
	private static final class Burst
	{
		private final int creatureObjectId;
		private final int skillId;

		private Burst(int creatureObjectId, int skillId)
		{
			this.creatureObjectId = creatureObjectId;
			this.skillId = skillId;
		}
	}

	/** What each player is bursting, per player object id. Not persisted, like the rest. */
	private static final Map<Integer, Burst> BURSTS = new ConcurrentHashMap<>();

	/** Null until the first burst is started. */
	private static volatile ScheduledExecutorService burstExecutor;

	/**
	 * Gap between two impact effects.
	 *
	 * <p>Prominence's blast is roughly a second of animation, so much below this and the
	 * bursts overlap into a strobe rather than reading as separate hits.
	 */
	private static final long BURST_PERIOD_MS = 1500;

	/**
	 * Skills whose impact is worth looking at.
	 *
	 * <p>This page sends the launch packet only and never executes the skill, so the skills
	 * that would be too dangerous to audition properly are exactly the ones worth auditioning
	 * - which is why {@code isDamage} is a reason to be <em>in</em> this list.
	 *
	 * <p>This is where the fire answer came from. "NPC Prominence" (id 4100) is a plain
	 * {@code MagicalDamage} area skill with nothing persistent about it, and its blast is
	 * the handsomest thing found in the whole sweep. A bit in the abnormal mask can never
	 * produce it, because the client draws it from the skill's own launch data.
	 *
	 * <p>Note this is the packet route, which is why HIT FX still draws nothing where the
	 * SKILL VISUALS page works: a bare launch packet is not the same thing as a cast. It is
	 * kept for the damage skills that have no cast animation worth watching, and the two
	 * pages are not expected to agree with each other.
	 */
	private static volatile List<Skill> HIT_CATALOGUE;

	private static List<Skill> hitCatalogue()
	{
		List<Skill> cached = HIT_CATALOGUE;
		if (cached != null)
		{
			return cached;
		}
		final List<Skill> found = new ArrayList<>();
		final SkillData data = SkillData.getInstance();
		for (final Integer id : data.getBaseGameSkillIds())
		{
			final Skill skill = data.getSkill(id.intValue(), 1);
			if (isWatchableHit(skill))
			{
				found.add(skill);
			}
		}
		// Same ordering rule as the skills page, for the same reason.
		found.sort((left, right) ->
		{
			final int rank = Boolean.compare(!looksElemental(left.getName()), !looksElemental(right.getName()));
			return (rank != 0) ? rank : left.getName().compareToIgnoreCase(right.getName());
		});
		HIT_CATALOGUE = found;
		return found;
	}

	/**
	 * A named skill that hits something, and so has an impact effect to steal.
	 *
	 * <p>No passive, dance or self-kill, for the same reason as before: they never land on
	 * a target. Continuous ones are kept here even though they were excluded from the
	 * skills page, because a skill can both hit and leave something behind, and its launch
	 * packet is what this page is about.
	 */
	private static boolean isWatchableHit(Skill skill)
	{
		if (skill == null)
		{
			return false;
		}
		final String name = skill.getName();
		if ((name == null) || name.isEmpty())
		{
			return false;
		}
		return skill.isDamage() && !skill.isPassive() && !skill.isDance() && !skill.isSuicideAttack();
	}

	/**
	 * The HIT FX page.
	 *
	 * <p>Same grid, same pagination, same page-in-the-link handling as the other two
	 * pages. The description says what actually happens, because "visual only" is the whole
	 * point and a player who assumes this page hurts things will not click it.
	 */
	private static boolean openHits(Player player, int page)
	{
		if (player == null)
		{
			return false;
		}
		final List<Skill> catalogue = hitCatalogue();
		if (catalogue.isEmpty())
		{
			player.sendMessage("No impact effects found. The skill table returned nothing usable.");
			return open(player, 0);
		}
		final List<List<Skill>> pages = paginateHits(catalogue);
		final int index = Math.max(0, Math.min(page, pages.size() - 1));
		final List<Skill> shown = pages.get(index);

		final Burst current = BURSTS.get(player.getObjectId());
		final StringBuilder html = new StringBuilder(HTML_CEILING);
		html.append("<html><body><center>");
		html.append("<font color=F2CC60>HIT FX</font><br>");
		html.append("<font color=6E7681>Repeats a skill's impact effect on your target.</font><br>");
		html.append("<font color=FF6666>Visual only - the skill is never run, so nothing takes damage.</font><br><br>");

		if (current == null)
		{
			html.append("<font color=6E7681>Nothing bursting.</font><br>");
		}
		else
		{
			final Skill skill = SkillData.getInstance().getSkill(current.skillId, 1);
			html.append("<font color=E4E1DB>").append((skill != null) ? escape(skill.getName()) : ("skill " + current.skillId)).append("</font>");
			html.append("<font color=6E7681> on ").append(escape(describe(current.creatureObjectId))).append("</font><br>");
		}

		html.append("<br>");
		html.append("<button value=\"BACK\" action=\"bypass -h tame_auralab 0\"").append(WIDE_BUTTON).append("><font color=D7DCE2>value effects</font>");
		html.append("<br>");
		html.append("<button value=\"OFF\" action=\"bypass -h tame_aura_hit_off\"").append(WIDE_BUTTON).append("><font color=D7DCE2>stop burst</font>");
		html.append("<br><br>");

		html.append("<table width=").append(GRID_WIDTH).append(" border=0>");
		for (int i = 0; i < shown.size(); i++)
		{
			if ((i % COLUMNS) == 0)
			{
				html.append("<tr>");
			}
			html.append(hitCell(shown.get(i), index));
			if (((i % COLUMNS) == (COLUMNS - 1)) || (i == (shown.size() - 1)))
			{
				html.append("</tr>");
			}
		}
		html.append("</table><br>");

		if (pages.size() > 1)
		{
			html.append("<table width=210 border=0><tr>");
			if (index > 0)
			{
				html.append("<td width=105 align=center><button value=\"PREV\" action=\"bypass -h tame_aura_hits ").append(index - 1).append('"').append(WIDE_BUTTON).append("></td>");
			}
			if (index < (pages.size() - 1))
			{
				html.append("<td width=105 align=center><button value=\"NEXT\" action=\"bypass -h tame_aura_hits ").append(index + 1).append('"').append(WIDE_BUTTON).append("></td>");
			}
			html.append("</tr></table><br>");
			html.append("<font color=6E7681>page ").append(index + 1).append(" of ").append(pages.size()).append("</font><br>");
		}
		html.append("<font color=6E7681>").append(catalogue.size()).append(" impact effects. Repeats every ").append(BURST_PERIOD_MS / 1000).append("s until OFF.</font>");
		html.append("</center></body></html>");

		if (html.length() > HTML_CEILING)
		{
			player.sendMessage("Hit page is " + html.length() + " characters, over the " + HTML_CEILING + " the client allows. Report this.");
		}
		player.sendPacket(new NpcHtmlMessage(0, html.toString()));
		return true;
	}

	/** One impact skill's button markup. */
	private static String hitCell(Skill skill, int page)
	{
		final StringBuilder cell = new StringBuilder(GRID_CELL);
		cell.append(caption(skill.getName())).append(" action=\"bypass -h tame_aura_hit ").append(skill.getId()).append(' ').append(page).append('"');
		cell.append(GRID_BUTTON);
		return cell.toString();
	}

	private static List<List<Skill>> paginateHits(List<Skill> catalogue)
	{
		final List<List<Skill>> pages = new ArrayList<>();
		List<Skill> current = new ArrayList<>();
		int used = HTML_RESERVED;
		for (final Skill skill : catalogue)
		{
			final int cost = hitCell(skill, PAGE_PROBE).length();
			if (((used + cost) > HTML_BUDGET) && !current.isEmpty())
			{
				pages.add(current);
				current = new ArrayList<>();
				used = HTML_RESERVED;
			}
			current.add(skill);
			used += cost;
		}
		pages.add(current);
		return pages;
	}

	/**
	 * Starts repeating a skill's impact effect on the target.
	 *
	 * <p>Only the target is resolved here. Nothing is cast, no buff is applied and no
	 * scheduler work happens until the first tick, so a bad id costs one message rather
	 * than a repeating job that does nothing.
	 */
	private static boolean startBurst(Player player, int skillId, int page)
	{
		if (player == null)
		{
			return false;
		}
		final Skill skill = SkillData.getInstance().getSkill(skillId, 1);
		if (skill == null)
		{
			player.sendMessage("No skill " + skillId + " level 1 is loaded.");
			return openHits(player, page);
		}
		final Creature target = selected(player);
		if (target == null)
		{
			player.sendMessage("Select a creature first, or summon your tame.");
			return openHits(player, page);
		}
		player.setTarget(target);
		// Replacing rather than refusing, so auditioning a second effect is one click and
		// never needs an OFF first.
		BURSTS.put(player.getObjectId(), new Burst(target.getObjectId(), skillId));
		ensureBurstScheduler();
		player.sendMessage("Bursting " + skill.getName() + " on " + describe(target.getObjectId()) + ". Visual only, OFF to stop.");
		return openHits(player, page);
	}

	/** Stops the burst, and says so whether or not there was one. */
	private static boolean stopBurst(Player player)
	{
		if (player == null)
		{
			return false;
		}
		final Burst stopped = BURSTS.remove(player.getObjectId());
		player.sendMessage((stopped != null) ? "Burst stopped." : "Nothing was bursting.");
		return openHits(player, 0);
	}

	private static void ensureBurstScheduler()
	{
		if (burstExecutor != null)
		{
			return;
		}
		synchronized (Burst.class)
		{
			if (burstExecutor != null)
			{
				return;
			}
			final ScheduledExecutorService running = Executors.newSingleThreadScheduledExecutor(runnable ->
			{
				// Daemon, same as the auto-cast scheduler: a lingering bench thread must
				// never hold the JVM open.
				final Thread thread = new Thread(runnable, "taming-aura-burst");
				thread.setDaemon(true);
				return thread;
			});
			running.scheduleWithFixedDelay(TameAuraLab::burstTick, BURST_PERIOD_MS, BURST_PERIOD_MS, TimeUnit.MILLISECONDS);
			burstExecutor = running;
		}
	}

	/**
	 * Sends one launch packet per active burst.
	 *
	 * <p>This is the whole mechanism, and it is worth being explicit about why no damage
	 * happens. {@code MagicSkillLaunched} is a presentation packet: it carries a caster, a
	 * skill id, a level and a target list, and the client looks the id up in its own
	 * {@code skillgrp} and plays the matching effect at the target. The core never calls
	 * the skill, so there is no damage calculation, no MP, no buff and no effect list
	 * entry. The blast is the client's own drawing of a skill it already knows how to draw.
	 *
	 * <p>The target is re-resolved from its object id on every tick rather than captured,
	 * so the effect follows the beast as it walks. It is sent at the creature's current
	 * position each time, which is also why it behaves better than the ground-anchored
	 * visuals in the enum: those are placed once and stay where they were put.
	 */
	private static void burstTick()
	{
		for (final Map.Entry<Integer, Burst> entry : BURSTS.entrySet())
		{
			final Burst burst = entry.getValue();
			final Player owner = World.getInstance().getPlayer(entry.getKey());
			if (owner == null)
			{
				// Logged out. Dropping it here is what stops the map from keeping a job
				// alive for a player who is no longer here.
				BURSTS.remove(entry.getKey());
				continue;
			}
			final WorldObject object = World.getInstance().findObject(burst.creatureObjectId);
			if (!(object instanceof Creature))
			{
				// The tame was dismissed, died or changed. Nothing left to draw on.
				BURSTS.remove(entry.getKey());
				owner.sendMessage("Burst stopped: your target is gone.");
				continue;
			}
			final Creature creature = (Creature) object;
			// Caster and target are the same creature on purpose: the effect is drawn at
			// the target's position, and the caster is only used for the animation, so
			// pointing both at the beast keeps the blast on the beast.
			creature.broadcastPacket(new MagicSkillLaunched(creature, burst.skillId, 1, Collections.singletonList((WorldObject) creature)));
		}
	}

	// ==========================================================================
	// Monster Only gear: the datapack's NPC-only weapons, given out for the collar bench
	// ==========================================================================

	/**
	 * Every item the datapack names {@code Monster Only(...)}.
	 *
	 * <p>These are the weapons and shields the stock NPCs carry, and they are the only
	 * items in this datapack whose models nothing a player can normally reach is made of.
	 * They are not sold, not dropped and not craftable, so without this list there is no
	 * route to holding one. The finding they rest on is recorded in MODLOG: every one is a
	 * real weapon or armour with real stats, a real icon and {@code for_npc="true"}, and
	 * {@code ItemTemplate.isForNpc()} is consulted only by the fake-player gear filter and
	 * the pet-use path, so a player can hold and wear them.
	 *
	 * <p>Written out rather than discovered by scanning names, because a scan would have to
	 * walk the whole item table on every page draw, and because "starts with Monster Only"
	 * would silently change its answer if the client's name table changed. The ids are
	 * stable, so a literal list is the cheaper and the more honest form.
	 */
	private static final int[] MONSTER_GEAR_IDS =
	{
			6715, 6716, 6717, 6718, 6719, 6720, 6721, 6722, 6723,
			6917, 6918, 6919, 7014, 7560,
			8203, 8204, 8205, 8206, 8207, 8208, 8209, 8210, 8211, 8212,
			8213, 8214, 8215, 8216, 8217, 8218, 8219, 8220, 8221, 8222
	};

	/** The last gear action this player took, for the status line. Not persisted, like the rest. */
	private static final Map<Integer, String> GEAR_STATUS = new ConcurrentHashMap<>();

	/** Whether an id is one of the {@link #MONSTER_GEAR_IDS}. */
	private static boolean isMonsterGear(int itemId)
	{
		for (final int known : MONSTER_GEAR_IDS)
		{
			if (known == itemId)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * A short name for a gear item.
	 *
	 * <p>The names are all of the form {@code Monster Only(Name)} and the wrapper is the part
	 * that carries no information, so it is stripped and what is inside the brackets - the part
	 * that tells two of them apart - is what is shown. Shorter than a row only because the two
	 * action buttons share the line with it, and this client cuts a line rather than wrapping
	 * it. The full name is never lost: every chat message carries it.
	 */
	private static String gearName(int itemId)
	{
		final ItemTemplate template = ItemData.getInstance().getTemplate(itemId);
		final String name = (template == null) ? null : template.getName();
		if (name == null)
		{
			return "?";
		}
		final int open = name.indexOf('(');
		final int close = name.lastIndexOf(')');
		final String inner = ((open >= 0) && (close > open)) ? name.substring(open + 1, close).trim() : name;
		return (inner.length() > 26) ? inner.substring(0, 26) : inner;
	}

	/**
	 * The MONSTER GEAR page.
	 *
	 * <p>One button per item, giving one to the player's inventory. The point is the model:
	 * these are the stock NPC weapons, and a tame can be made to hold one through the collar's
	 * TAME GEAR page, which is the only appearance lever this server has. The page gives and
	 * does not install - installing stays on the collar page, behind its own dismiss-first
	 * rule - and the gift is deliberate: the collar reads the item off the owner's bag when it
	 * equips it, so the copy has to pass through the player first.
	 */
	private static boolean openGear(Player player, int page)
	{
		if (player == null)
		{
			return false;
		}
		final List<List<Integer>> pages = paginateGear();
		final int index = Math.max(0, Math.min(page, pages.size() - 1));
		final List<Integer> shown = pages.get(index);

		final String given = GEAR_STATUS.get(player.getObjectId());
		final StringBuilder html = new StringBuilder(HTML_CEILING);
		html.append("<html><body><center>");
		html.append("<font color=F2CC60>MONSTER GEAR</font><br>");
		html.append("<font color=6E7681>The datapack's NPC-only weapons and shields, the</font><br>");
		html.append("<font color=6E7681>models no normal item uses. GIVE one to your bag,</font><br>");
		html.append("<font color=6E7681>then install it on a tame from that collar's own</font><br>");
		html.append("<font color=6E7681>TAME GEAR page.</font><br><br>");

		if (given == null)
		{
			html.append("<font color=6E7681>Nothing given yet.</font><br>");
		}
		else
		{
			html.append("<font color=6E7681>Last given: </font><font color=E4E1DB>").append(escape(given)).append("</font><br>");
		}

		html.append("<br>");
		html.append("<button value=\"BACK\" action=\"bypass -h tame_auralab 0\"").append(WIDE_BUTTON).append("><font color=D7DCE2>value effects</font>");
		html.append("<br><br>");

		for (final Integer itemId : shown)
		{
			html.append(gearRow(itemId.intValue(), index));
		}
		html.append("<br>");

		if (pages.size() > 1)
		{
			html.append("<table width=210 border=0><tr>");
			if (index > 0)
			{
				html.append("<td width=105 align=center><button value=\"PREV\" action=\"bypass -h tame_aura_gear ").append(index - 1).append('"').append(WIDE_BUTTON).append("></td>");
			}
			if (index < (pages.size() - 1))
			{
				html.append("<td width=105 align=center><button value=\"NEXT\" action=\"bypass -h tame_aura_gear ").append(index + 1).append('"').append(WIDE_BUTTON).append("></td>");
			}
			html.append("</tr></table><br>");
			html.append("<font color=6E7681>page ").append(index + 1).append(" of ").append(pages.size()).append("</font><br>");
		}
		html.append("<font color=6E7681>").append(MONSTER_GEAR_IDS.length).append(" NPC-only items. GIVE sends a copy to your bag; it is an ordinary item and can be dropped or destroyed.</font>");
		html.append("</center></body></html>");

		if (html.length() > HTML_CEILING)
		{
			player.sendMessage("Gear page is " + html.length() + " characters, over the " + HTML_CEILING + " the client allows. Report this.");
		}
		player.sendPacket(new NpcHtmlMessage(0, html.toString()));
		return true;
	}

	/** One gear item's row: its name and the GIVE button, as a plain line like the collar page. */
	private static String gearRow(int itemId, int page)
	{
		final StringBuilder row = new StringBuilder("<font color=D7DCE2>");
		row.append(escape(gearName(itemId))).append("</font> ");
		row.append("<button value=GIVE action=\"bypass -h tame_aura_give ").append(itemId).append(' ').append(page).append('"').append(SMALL_BUTTON).append('>');
		row.append("<br>");
		return row.toString();
	}

	/** Splits the list into pages that each fit the ceiling, measured the same way as the rest. */
	private static List<List<Integer>> paginateGear()
	{
		final List<List<Integer>> pages = new ArrayList<>();
		List<Integer> current = new ArrayList<>();
		int used = HTML_RESERVED;
		for (final int itemId : MONSTER_GEAR_IDS)
		{
			final int cost = gearRow(itemId, PAGE_PROBE).length();
			if (((used + cost) > HTML_BUDGET) && !current.isEmpty())
			{
				pages.add(current);
				current = new ArrayList<>();
				used = HTML_RESERVED;
			}
			current.add(Integer.valueOf(itemId));
			used += cost;
		}
		pages.add(current);
		return pages;
	}

	/**
	 * Gives one copy of a Monster Only item to the player's bag.
	 *
	 * <p>Validated against the same list the page was drawn from, so a hand-written link
	 * cannot be used to pull any other item through this command. The gift goes through the
	 * ordinary reward path, so it is a real item with a real inventory row and can be
	 * dropped, traded or destroyed like any other.
	 */
	private static boolean grantGear(Player player, int itemId, int page)
	{
		if (!isMonsterGear(itemId))
		{
			player.sendMessage("That is not a Monster Only item.");
			return openGear(player, page);
		}
		final ItemTemplate template = ItemData.getInstance().getTemplate(itemId);
		if (template == null)
		{
			player.sendMessage("Item " + itemId + " is not loaded, so nothing was given.");
			return openGear(player, page);
		}
		final Item given = player.addItem(ItemProcessType.REWARD, itemId, 1, player, false);
		if (given == null)
		{
			player.sendMessage("No inventory room for " + template.getName() + ". Make space and try again.");
			return openGear(player, page);
		}
		GEAR_STATUS.put(player.getObjectId(), template.getName());
		player.sendMessage("Gave " + template.getName() + ". Install it on a tame from that collar's TAME GEAR page.");
		return openGear(player, page);
	}

	// ==========================================================================
	// The page
	// ==========================================================================

	/** Opens the bench on the first page. */
	public static boolean open(Player player)
	{
		return open(player, 0);
	}

	/**
	 * Draws one page of the bench.
	 *
	 * <p>Reopened after every button rather than left stale, because the interesting
	 * thing after a click is which value is now set and on what.
	 *
	 * @param page zero-based page index, clamped into range because it arrives from a
	 *             client that will happily replay an old link
	 */
	public static boolean open(Player player, int page)
	{
		if (player == null)
		{
			return false;
		}
		// The single choke point: the voiced command, the bypass buttons and TameDiag all
		// redraw through here, so gating here is what closes the page for good.
		if (!isAllowed(player))
		{
			return refuse(player);
		}
		final List<List<AbnormalVisualEffect>> pages = paginate(AbnormalVisualEffect.values());
		final int index = Math.max(0, Math.min(page, pages.size() - 1));
		final List<AbnormalVisualEffect> shown = pages.get(index);

		final Audition current = AUDITIONS.get(player.getObjectId());
		final StringBuilder html = new StringBuilder(HTML_CEILING);
		html.append("<html><body><center>");
		html.append("<font color=F2CC60>AURA LAB</font><br>");

		// Navigation sits above the fold, not below the grid, and that placement is the whole
		// reason it is here. Every one of these buttons rebuilds this html, and a rebuilt page
		// lands the client back at the top of the document - so anything placed under a full
		// grid of values can only be reached by scrolling down, clicking, and then scrolling
		// down again to get back to where the next page put you. Two rows of two keeps the
		// whole set visible without scrolling, at 210 wide against WIDE_BUTTON's 100.
		html.append("<table width=210 border=0><tr>");
		html.append("<td width=105 align=center><button value=\"SKILLS\" action=\"bypass -h tame_aura_skills 0\"").append(WIDE_BUTTON).append("><font color=D7DCE2>skill visuals &gt;</font></td>");
		html.append("<td width=105 align=center><button value=\"HIT FX\" action=\"bypass -h tame_aura_hits 0\"").append(WIDE_BUTTON).append("><font color=D7DCE2>hit effects &gt;</font></td>");
		html.append("</tr><tr>");
		// ON re-applies whatever is set, OFF takes it off. ON with nothing set says so
		// rather than silently doing nothing. Both carry the current page so they redraw
		// in place.
		html.append("<td width=105 align=center><button value=\"ON\" action=\"bypass -h tame_aura_reapply ").append(index).append('"').append(WIDE_BUTTON).append("><font color=D7DCE2>re-apply</font></td>");
		html.append("<td width=105 align=center><button value=\"OFF\" action=\"bypass -h tame_aura_off ").append(index).append('"').append(WIDE_BUTTON).append("><font color=D7DCE2>clear</font></td>");
		html.append("</tr><tr>");
		// Third row for the gear bench: the two buttons above are the value bench's own
		// verbs, and adding a third column would have pushed them off the 210 wide block
		// they were sized for. This page grants the datapack's NPC-only weapons so they
		// can be installed on a tame through the collar's own TAME GEAR page.
		html.append("<td width=105 align=center><button value=\"GEAR\" action=\"bypass -h tame_aura_gear 0\"").append(WIDE_BUTTON).append("><font color=D7DCE2>monster gear &gt;</font></td>");
		html.append("<td width=105></td>");
		html.append("</tr></table><br>");

		html.append("<font color=6E7681>Target a creature, then press a value. One at a time.</font><br>");
		html.append("<font color=6E7681>Everything is listed, including values believed to draw nothing.</font><br><br>");

		if (current == null)
		{
			html.append("<font color=6E7681>Nothing auditioned.</font><br>");
		}
		else
		{
			html.append("<font color=E4E1DB>").append(current.effect.name()).append("</font>");
			html.append("<font color=6E7681> on ").append(escape(describe(current.creatureObjectId))).append("</font><br>");
		}

		html.append("<br>");
		html.append("<table width=").append(GRID_WIDTH).append(" border=0>");
		for (int i = 0; i < shown.size(); i++)
		{
			// A row tag opens and closes per group of COLUMNS. The previous version also
			// opened a row before the loop and then opened one again on the first
			// iteration, which nested a row inside a row.
			if ((i % COLUMNS) == 0)
			{
				html.append("<tr>");
			}
			html.append(cell(shown.get(i), index));
			if (((i % COLUMNS) == (COLUMNS - 1)) || (i == (shown.size() - 1)))
			{
				html.append("</tr>");
			}
		}
		html.append("</table><br>");

		if (pages.size() > 1)
		{
			html.append("<table width=210 border=0><tr>");
			if (index > 0)
			{
				html.append("<td width=105 align=center><button value=\"PREV\" action=\"bypass -h tame_aura_page ").append(index - 1).append('"').append(WIDE_BUTTON).append("></td>");
			}
			if (index < (pages.size() - 1))
			{
				html.append("<td width=105 align=center><button value=\"NEXT\" action=\"bypass -h tame_aura_page ").append(index + 1).append('"').append(WIDE_BUTTON).append("></td>");
			}
			html.append("</tr></table><br>");
			html.append("<font color=6E7681>page ").append(index + 1).append(" of ").append(pages.size()).append("</font><br>");
		}
		html.append("<font color=6E7681>").append(AbnormalVisualEffect.values().length).append(" values. OFF clears only what this page set.</font>");
		html.append("</center></body></html>");

		// Belt and braces. The chunking above is meant to keep this under the ceiling,
		// and the core would only truncate it silently, so it is worth knowing.
		if (html.length() > HTML_CEILING)
		{
			player.sendMessage("Aura lab page is " + html.length() + " characters, over the " + HTML_CEILING + " the client allows. Report this.");
		}
		player.sendPacket(new NpcHtmlMessage(0, html.toString()));
		return true;
	}

	/**
	 * One value's button markup.
	 *
	 * <p>Built as a string first so the pagination pass can measure the real thing rather
	 * than guess at it. The page index is baked into the action so the click comes back to
	 * the page it was made on.
	 */
	private static String cell(AbnormalVisualEffect effect, int page)
	{
		final StringBuilder cell = new StringBuilder(GRID_CELL);
		cell.append(caption(effect)).append(" action=\"bypass -h tame_aura_on ").append(effect.name()).append(' ').append(page).append('"');
		cell.append(GRID_BUTTON);
		return cell.toString();
	}

	/**
	 * Splits the values into pages that each fit the ceiling.
	 *
	 * <p>Chunked by measured length, so the page count follows the enum instead of a
	 * constant that goes stale the day a value is added. RESERVED is held back on every
	 * page for the chrome, which is why a page can hold fewer buttons than its raw
	 * budget suggests.
	 *
	 * <p>Measured with {@link #PAGE_PROBE} rather than with any real index, because the
	 * action embeds the page number and page 10 is one character longer than page 9.
	 * Measuring with the widest possible index makes every page an upper bound, so a
	 * two-digit page cannot tip a page that was measured with one digit over the ceiling.
	 * The buttons are then built again with the real index when the page is drawn.
	 */
	private static List<List<AbnormalVisualEffect>> paginate(AbnormalVisualEffect[] values)
	{
		final List<List<AbnormalVisualEffect>> pages = new ArrayList<>();
		List<AbnormalVisualEffect> current = new ArrayList<>();
		int used = HTML_RESERVED;
		for (final AbnormalVisualEffect effect : values)
		{
			// Row tags are not counted per cell; the reserve absorbs them.
			final int cost = cell(effect, PAGE_PROBE).length();
			if (((used + cost) > HTML_BUDGET) && !current.isEmpty())
			{
				pages.add(current);
				current = new ArrayList<>();
				used = HTML_RESERVED;
			}
			current.add(effect);
			used += cost;
		}
		pages.add(current);
		return pages;
	}

	/** A page index from a client-supplied string, or 0. */
	private static int parsePage(String text)
	{
		try
		{
			return Integer.parseInt(text.trim());
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	/**
	 * The page index a bypass action carries, or 0.
	 *
	 * <p>Read as the last word so a command with any number of leading words still yields
	 * its page, and so a command with no page at all still works rather than throwing.
	 */
	private static int pageArgument(String command)
	{
		final String[] parts = words(command);
		return (parts.length > 0) ? parsePage(parts[parts.length - 1]) : 0;
	}

	// ==========================================================================
	// Doing the thing
	// ==========================================================================

	/**
	 * Sets one value on the selected creature, clearing whatever this page set before.
	 *
	 * <p>The clear-first is the whole difference between a bench and the older audition
	 * command, and it is why ON is worth having at all rather than just clicking a value.
	 *
	 * <p>The page is redrawn where the click happened, not at the start, so working down a
	 * page of candidates does not throw away the navigation each time.
	 */
	private static boolean audition(Player player, String name, int page)
	{
		final AbnormalVisualEffect effect;
		if ((name == null) || name.isEmpty())
		{
			// No value in the link. ON is its own command now, so this is a malformed
			// action rather than a deliberate one; re-open where they were and say so.
			player.sendMessage("Pick a value from the grid first.");
			return open(player, page);
		}
		try
		{
			effect = AbnormalVisualEffect.valueOf(name.toUpperCase(java.util.Locale.ROOT));
		}
		catch (IllegalArgumentException e)
		{
			player.sendMessage("\"" + name + "\" is not an AbnormalVisualEffect.");
			return open(player, page);
		}
		final Creature target = selected(player);
		if (target == null)
		{
			player.sendMessage("Select a creature first, or summon your tame.");
			return open(player, page);
		}
		// Clear first so the bench never stacks, then set. updateAbnormalEffect is
		// abstract per subclass, so this reaches a Player as UserInfo and a Summon as
		// PetInfo without either being special-cased here.
		clear(player);
		target.startAbnormalVisualEffect(true, effect);
		AUDITIONS.put(player.getObjectId(), new Audition(target.getObjectId(), effect));
		player.sendMessage("Auditioning " + effect.name() + " on " + describe(target.getObjectId()) + ".");
		return open(player, page);
	}

	/**
	 * ON: sets the recorded value again, without changing which one it is.
	 *
	 * <p>Re-applying is not a no-op. The bit is already set in the field the bitmask lives
	 * in, but the client only re-reads that field when the creature is re-broadcast, so
	 * this is how a value is made to reappear on a client that has drifted out of range or
	 * missed the update - and it is the only way to audition a second creature while
	 * keeping the first one picked.
	 */
	private static boolean reapply(Player player, int page)
	{
		final Audition current = AUDITIONS.get(player.getObjectId());
		if (current == null)
		{
			player.sendMessage("Nothing is auditioned yet. Pick a value first.");
			return open(player, page);
		}
		final WorldObject object = World.getInstance().findObject(current.creatureObjectId);
		if (!(object instanceof Creature))
		{
			player.sendMessage(current.effect.name() + " was set on a creature that is no longer there. Pick a value to set it again.");
			AUDITIONS.remove(player.getObjectId());
			return open(player, page);
		}
		final Creature target = (Creature) object;
		// Deliberately not cleared first: the point is to re-broadcast the same bit.
		target.startAbnormalVisualEffect(true, current.effect);
		player.sendMessage("Re-applied " + current.effect.name() + " on " + describe(current.creatureObjectId) + ".");
		return open(player, page);
	}

	/**
	 * Takes off whatever this page put on.
	 *
	 * <p>Only the recorded value, only on the recorded creature. The bitmask cannot be
	 * interrogated for what is set, so anything else left on the beast - its affinity
	 * aura, an awakening overlay - is deliberately left alone.
	 */
	private static boolean clear(Player player)
	{
		final Audition current = AUDITIONS.remove(player.getObjectId());
		if (current == null)
		{
			return false;
		}
		final WorldObject object = World.getInstance().findObject(current.creatureObjectId);
		if (object instanceof Creature)
		{
			((Creature) object).stopAbnormalVisualEffect(true, current.effect);
			player.sendMessage("Cleared " + current.effect.name() + ".");
		}
		else
		{
			// A creature that is gone needs no clearing, and saying so is more use than
			// reporting a success that did nothing.
			player.sendMessage("Cleared " + current.effect.name() + " from a creature that is no longer there.");
		}
		return true;
	}

	// ==========================================================================
	// Helpers
	// ==========================================================================

	/**
	 * Who the effect goes on: whatever is selected, else the player's own pet.
	 *
	 * <p>Falling back to the pet is what makes the bench usable without a target, but
	 * the target is the interesting case and is listed first so it wins.
	 */
	private static Creature selected(Player player)
	{
		final WorldObject target = player.getTarget();
		if (target instanceof Creature)
		{
			return (Creature) target;
		}
		final Summon summon = player.getSummon();
		if ((summon != null) && summon.isPet())
		{
			return summon;
		}
		return null;
	}

	/** A name for a creature object id, for the status line and the messages. */
	private static String describe(int objectId)
	{
		final WorldObject object = World.getInstance().findObject(objectId);
		if (object == null)
		{
			return "a creature that is gone";
		}
		final String name = object.getName();
		return (name == null) ? ("object " + objectId) : name;
	}

	/**
	 * The button caption for a value.
	 *
	 * <p>This client clips button text rather than wrapping it, so a raw
	 * STIGMA_OF_SILEN at 65 pixels is unreadable. Known prefixes go first, then
	 * underscores become spaces, then anything still over the limit is cut. The full name
	 * is never lost - it is what the status line and the chat message show after the
	 * click, which is the only place it has to be legible.
	 */
	private static String caption(AbnormalVisualEffect effect)
	{
		String name = effect.name();
		for (final String prefix : STRIPPED_PREFIXES)
		{
			if (name.startsWith(prefix))
			{
				name = name.substring(prefix.length());
				break;
			}
		}
		return clip(name);
	}

	/**
	 * A button caption from an already-stripped name.
	 *
	 * <p>Skill names are far longer than the enum's, so they need the same clipping. They
	 * do not get the prefix stripping: there are no prefixes to strip off "Fire Shield",
	 * and mangling a readable word to save a few characters helps nobody.
	 */
	private static String caption(String name)
	{
		return clip(name);
	}

	/** Underscores to spaces, then clipped to what a 65 pixel button can show. */
	private static String clip(String name)
	{
		final String spaced = name.replace('_', ' ');
		return (spaced.length() > 12) ? spaced.substring(0, 12) : spaced;
	}

	/**
	 * The words of a bypass command, after its name.
	 *
	 * <p>Not the nth-word helper the reagent shop uses. That one walks spaces and gives
	 * up when it runs off the end, so it returns "" for the last word - which is fine
	 * when the last word is never the one being read, and silently wrong here, where
	 * "tame_aura_on STUN 1" has the value and the page and the helper only ever handed
	 * back one of them. That bug made every button on the page report that no value had
	 * been picked.
	 *
	 * <p>Splitting into all words rather than a fixed pair also means an action that gains
	 * a second argument later does not silently misread its first.
	 */
	private static String[] words(String command)
	{
		final int space = command.indexOf(' ');
		if (space < 0)
		{
			return new String[0];
		}
		final List<String> parts = new ArrayList<>();
		for (final String part : command.substring(space + 1).trim().split("\\s+"))
		{
			if (!part.isEmpty())
			{
				parts.add(part);
			}
		}
		return parts.toArray(new String[0]);
	}

	/** Client text is HTML. */
	private static String escape(String text)
	{
		if (text == null)
		{
			return "";
		}
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	@Override
	public String[] getCommandList()
	{
		return VOICED_COMMANDS;
	}
}
