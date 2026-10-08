/*
 * TameSkillPolicy.java
 *
 * Selects safe inherited skills from the real NPC template while preserving
 * species identity and the level 1/10/20/30 progression plan.
 * Runtime script source; Java 8 compatible.
 */
package taming;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.data.holders.PetData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.model.actor.enums.npc.AISkillScope;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.skill.Skill;

public final class TameSkillPolicy
{
	private static final Logger LOGGER = Logger.getLogger(TameSkillPolicy.class.getName());

	private static final int NATIVE_POWER_STRIKE = 3;
	private static final int PET_POWER_STRIKE = 9301;

	public static int petSkillId(int skillId)
	{
		return skillId == NATIVE_POWER_STRIKE ? PET_POWER_STRIKE : skillId;
	}

	public static final class Selection
	{
		private final Skill _skill;
		private final String _slot;
		private final String _quality;
		private final int _unlockLevel;
		private final boolean _awakened;
		
		Selection(Skill skill, String slot, String quality, int unlockLevel, boolean awakened)
		{
			_skill = skill;
			_slot = slot;
			_quality = quality;
			_unlockLevel = unlockLevel;
			_awakened = awakened;
		}
		
		public Skill getSkill() { return _skill; }
		public String getSlot() { return _slot; }
		public String getQuality() { return _quality; }
		public int getUnlockLevel() { return _unlockLevel; }
		public boolean isAwakened() { return _awakened; }
	}
	
	private TameSkillPolicy()
	{
	}
	
	public static List<Selection> select(NpcTemplate template, TameProfile profile)
	{
		if (template == null)
		{
			return Collections.emptyList();
		}
		final Map<String, Skill> unique = new LinkedHashMap<>();
		addScope(unique, template, AISkillScope.ATTACK);
		addScope(unique, template, AISkillScope.DEBUFF);
		addScope(unique, template, AISkillScope.BUFF);
		addScope(unique, template, AISkillScope.HEAL);
		addScope(unique, template, AISkillScope.IMMOBILIZE);
		addScope(unique, template, AISkillScope.UNIVERSAL);
		final List<Skill> candidates = new ArrayList<>(unique.values());
		Collections.sort(candidates, new Comparator<Skill>()
		{
			@Override
			public int compare(Skill left, Skill right)
			{
				return Integer.compare(left.getId(), right.getId());
			}
		});
		final boolean boss = (profile != null) && ("BOSS".equals(profile.getFamily()) || "RaidBoss".equalsIgnoreCase(profile.getSourceType()) || "GrandBoss".equalsIgnoreCase(profile.getSourceType()));
final List<Skill> safe = new ArrayList<>();
		for (Skill skill : candidates)
		{
			if (!valid(skill))
			{
				continue;
			}
			if (boss && (skill.isAOE() || skill.isSuicideAttack() || skill.getPower(false, true) > 1200.0))
			{
				continue;
			}
			safe.add(skill);
		}
			if (safe.isEmpty())
			{
				final List<Selection> traits = new ArrayList<>();
				for (Skill skill : template.getSkills().values())
				{
					if ((skill != null) && skill.isPassive() && (skill.getId() > 0) && (skill.getLevel() > 0) && (SkillData.getInstance().getSkill(skill.getId(), skill.getLevel()) != null))
					{
						traits.add(new Selection(skill, "TRAIT", "NATURAL", 1, true));
						if (traits.size() >= 4)
						{
							break;
						}
					}
				}
					addClassAwakeningSelections(traits, profile);
					return traits;
			}
		orderForRole(safe, (profile != null) ? profile.getRole() : null);
		final List<Selection> result = new ArrayList<>();
		final boolean rare = !boss && (profile != null) && ("RARE".equals(profile.getRarity()) || "EPIC".equals(profile.getRarity()) || "LEGENDARY".equals(profile.getRarity()));
		// A real player damage skill takes the signature slot when one is available. This is
		// additive rather than a replacement: the beast's own inherited techniques still fill
		// the other slots, so a species still looks like its species, it simply fights with
		// one borrowed player technique as well.
		final Skill playerSkill = playerSignature(profile, boss);
		final Skill signature = (playerSkill != null) ? playerSkill : firstDamage(safe);
		if (signature != null)
		{
			result.add(new Selection(signature, "SIGNATURE_1", quality(profile, 0), 1, false));
		}
		if (rare)
		{
			final Skill secondSignature = nextDifferentDamage(safe, signature);
			if (secondSignature != null)
			{
				result.add(new Selection(secondSignature, "SIGNATURE_2", "REFINED", 1, false));
			}
		}
		final Skill utility = firstUtility(safe, signature);
		if (utility != null)
		{
			result.add(new Selection(utility, "UTILITY", quality(profile, 1), 10, false));
		}
			if (boss)
			{
				final Skill bossAdvanced = nextDifferent(safe, signature, utility);
				if (bossAdvanced != null)
				{
					result.add(new Selection(bossAdvanced, "ADVANCED", "REFINED", 20, false));
				}
					final Skill bossAwakening = nextDifferent(safe, signature, utility, bossAdvanced);
					if (bossAwakening != null)
					{
						result.add(new Selection(bossAwakening, "AWAKENING", classQuality(profile), 30, false));
					}
					else
					{
						addClassAwakeningSelections(result, profile);
					}
					return result;
			}
			final Skill advanced = nextDifferent(safe, signature, utility);
		if (advanced != null)
		{
			result.add(new Selection(advanced, rare ? "RARE_INHERITANCE" : "ADVANCED", rare ? "REFINED" : quality(profile, 2), 20, false));
		}
		final Skill awakening = nextDifferent(safe, signature, utility, advanced);
		if (awakening != null)
		{
			result.add(new Selection(awakening, "AWAKENING", "STABLE", 30, false));
		}
			else
			{
					addClassAwakeningSelections(result, profile);
				}
				return result;
	}

	/**
	 * Puts the beast's own inherited techniques in role order before the slots are
	 * filled from them.
	 *
	 * <p>Every pick below this point - the signature fallback, the utility slot, the
	 * advanced and awakening slots - walks {@code safe} in list order and takes the
	 * first entry that fits. That order used to be skill id, which is arbitrary as far
	 * as the beast is concerned: a SUPPORT drew whatever non-damage technique its
	 * species happened to define under the lowest id, and a MAGE could land on a
	 * physical tap for its advanced slot purely because that id came first.
	 *
	 * <p>This reorders rather than filters. Nothing is ever removed, so a species whose
	 * whole pool is the "wrong" flavour still fills every slot it can rather than coming
	 * out short - the same rule the reach floor follows. Roles that are not named here
	 * rank 0 across the board, and the sort is stable, so a FIGHTER or a BALANCED beast
	 * keeps exactly the skill-id order it had before.
	 *
	 * <p>Existing decks are untouched: this only decides the order {@code select} hands
	 * out, {@link TameCollarView} skips any slot a profile already has, and
	 * {@link #refreshSignature} rewrites SIGNATURE_1 only.
	 *
	 * @param safe the role-appropriate candidates, in skill id order
	 * @param role the beast's role, or null for a profileless read
	 */
	private static void orderForRole(List<Skill> safe, String role)
	{
		if (safe.size() < 2)
		{
			return;
		}
		Collections.sort(safe, (left, right) -> Integer.compare(roleRank(left, role), roleRank(right, role)));
	}

	/**
	 * How well a candidate suits a role, lower fitting better.
	 *
	 * @param skill a candidate technique
	 * @param role  the beast's role, or null
	 * @return the rank, 0 meaning no preference
	 */
	private static int roleRank(Skill skill, String role)
	{
		if ("MAGE".equals(role))
		{
			if (skill.isMagic() && skill.isDamage())
			{
				return 0;
			}
			return skill.isMagic() ? 1 : 2;
		}
		if ("ARCHER".equals(role))
		{
			if (skill.isPhysical() && (skill.getCastRange() > 150))
			{
				return 0;
			}
			if (skill.isPhysical() && skill.isDamage())
			{
				return 1;
			}
			return skill.isDamage() ? 2 : 3;
		}
		if ("SUPPORT".equals(role))
		{
			if (!skill.isDamage())
			{
				return 0;
			}
			return skill.isMagic() ? 1 : 2;
		}
		return 0;
	}

	public static void register(PetData data, NpcTemplate template, TameProfile profile)
	{
		if ((data == null) || (template == null))
		{
			return;
		}
		for (Selection selection : select(template, profile))
		{
				final Skill skill = selection.getSkill();
				data.addNewSkill(petSkillId(skill.getId()), skill.getLevel(), selection.getUnlockLevel());
		}
	}
	
	private static void addScope(Map<String, Skill> target, NpcTemplate template, AISkillScope scope)
	{
		for (Skill skill : template.getAISkills(scope))
		{
			if (skill != null)
			{
				target.put(skill.getId() + ":" + skill.getLevel(), skill);
			}
		}
	}
	
	private static boolean valid(Skill skill)
	{
		return (skill != null) && skill.isActive() && !skill.isPassive() && !skill.isToggle() && !skill.isDance() && !skill.isSuicideAttack() && (skill.getId() > 0) && (skill.getLevel() > 0) && (SkillData.getInstance().getSkill(skill.getId(), skill.getLevel()) != null);
	}
	
	private static Skill firstDamage(List<Skill> skills)
	{
		for (Skill skill : skills)
		{
			if (skill.isDamage())
			{
				return skill;
			}
		}
		return skills.get(0);
	}
	
	private static Skill nextDifferentDamage(List<Skill> skills, Skill first)
	{
		for (Skill skill : skills)
		{
			if ((skill != first) && skill.isDamage())
			{
				return skill;
			}
		}
		return null;
	}
	
	private static Skill firstUtility(List<Skill> skills, Skill... excluded)
	{
		for (Skill skill : skills)
		{
			if (!contains(excluded, skill) && !skill.isDamage())
			{
				return skill;
			}
		}
		return null;
	}
	
	private static Skill nextDifferent(List<Skill> skills, Skill... excluded)
	{
		for (Skill skill : skills)
		{
			if (!contains(excluded, skill))
			{
				return skill;
			}
		}
		return null;
	}
	
	private static boolean contains(Skill[] skills, Skill candidate)
	{
		for (Skill skill : skills)
		{
			if (skill == candidate)
			{
				return true;
			}
		}
		return false;
	}
	
	private static Skill classAwakening(TameProfile profile)
	{
		final List<Skill> pool = classAwakeningPool(profile);
		return pool.isEmpty() ? null : pool.get(0);
	}

	/**
	 * Whether a tame's signature may be a real player technique. Set from
	 * {@code PlayerDamageSkills} in module.ini.
	 */
	private static volatile boolean playerDamageSkills = true;

	/**
	 * Turns the player-technique signature on or off, from {@code PlayerDamageSkills}.
	 *
	 * <p>Turning it off restores purely inherited techniques. Already-forged decks keep
	 * whatever they were last written with until {@link #refreshSignature} next runs - and it
	 * does not run when this is false, so nothing is quietly rewritten behind the setting.
	 */
	public static void setPlayerDamageSkills(boolean value)
	{
		playerDamageSkills = value;
	}

	/**
	 * How far a tame's signature technique has to reach, from {@code SignatureMinRange}.
	 *
	 * <p>Zero disables the floor, which restores the old behaviour of accepting whatever the
	 * pool happens to offer.
	 */
	private static volatile int signatureMinRange = 900;

	/**
	 * How many of the longest-reaching candidates stay in the running, from
	 * {@code SignatureReachBand}.
	 *
	 * <p>Taking only the single longest would give every archer in the game the same
	 * technique; taking the whole pool would leave the reach complaint exactly where it is.
	 * A band biases the roll towards distance while still letting two beasts of the same role
	 * come out different.
	 */
	private static volatile int reachBand = 12;

	/**
	 * Points the signature selection at longer-reach techniques.
	 *
	 * <p>Reach cannot be widened on an existing skill. {@code SkillData} hands out one shared
	 * instance per id, so raising {@code castRange} on it would change every creature in the
	 * game that uses that technique, and rebuilding a private copy for tames only is not an
	 * option either: the rebuild that {@code TameSkillAura} uses works precisely because a
	 * directly constructed Skill has an empty effect list, and a signature technique has to
	 * keep its effects. So the only lever left is which technique gets granted, which is what
	 * this does.
	 *
	 * @param minRange reach floor in units, 0 for none
	 * @param band     how many of the longest-reaching candidates stay eligible
	 */
	public static void setSignatureReach(int minRange, int band)
	{
		signatureMinRange = Math.max(0, minRange);
		reachBand = Math.max(1, band);
	}

	/**
	 * Brings an already-forged tame up to date with the current selection rules, by
	 * pointing its SIGNATURE_1 at the player technique it should now be using.
	 *
	 * <p>The deck is written once, at capture, so a beast forged before real player
	 * techniques existed would otherwise keep its old signature forever. Comparing the stored
	 * row against what {@link #playerSignature} returns makes this self-healing and idempotent:
	 * the first summon after the change rewrites the one row, and every summon after that
	 * finds nothing to do and writes nothing.
	 *
	 * <p>Self-healing also means a future change to the rules needs no migration - the deck
	 * corrects itself the next time each beast is called out.
	 *
	 * <p>Only ever touches SIGNATURE_1, and only when the current rule actually names a player
	 * technique. A beast whose stored signature is already correct, or whose deck has no
	 * signature row at all, is left untouched.
	 *
	 * @return true when the deck was rewritten
	 */
	public static boolean refreshSignature(TameProfile profile)
	{
		if ((profile == null) || (profile.getUuid() == null))
		{
			return false;
		}
		final Skill expected = playerSignature(profile, TameFood.isBoss(profile));
		if (expected == null)
		{
			return false;
		}
		if (TameProfileRepository.currentSignatureId(profile.getUuid()) == expected.getId())
		{
			return false;
		}
		return TameProfileRepository.rebindSignature(profile.getUuid(), expected.getId(), expected.getLevel());
	}

	/**
	 * A real player damage technique for this individual, or null to leave the
	 * signature slot to the beast's own inherited skills.
	 *
	 * <p>Unlike {@link #classAwakeningPool} this is not hand-whitelisted. The pool is the 95
	 * active player damage skills {@link TamePlayerDamageSkills} identifies, and every one of
	 * them was checked for the two things that make a player skill unsafe on a dynamic Pet:
	 * a {@code <condition>} it could never satisfy, and a weapon requirement. This datapack
	 * has no condition tags at all and none of the 95 mention a weapon, so all 95 are
	 * eligible and there is no need to curate.
	 *
	 * <p>The pick is seeded from the individual's profile seed, exactly as the awakening pool
	 * is, so two wolves of the same role keep the same technique for life rather than
	 * rerolling on every summon.
	 *
	 * <p>Role decides the flavour the same way the awakening pool uses it: an ARCHER gets
	 * physical ranged, a MAGE gets magic, a SUPPORT gets magic so its damage is incidental
	 * to its real job, and anything else gets physical. Bosses are held to the same AOE,
	 * suicide and power limits as their inherited skills.
	 */
	private static Skill playerSignature(TameProfile profile, boolean boss)
	{
		if (profile == null)
		{
			return null;
		}
		if (!playerDamageSkills)
		{
			return null;
		}
		final String role = profile.getRole();
		final boolean wantMagic = "MAGE".equals(role) || "SUPPORT".equals(role);
		final boolean wantRanged = "ARCHER".equals(role);
		final List<Skill> pool = new ArrayList<>();
		for (Skill skill : TamePlayerDamageSkills.playerDamageSkills())
		{
			if (skill.isMagic() != wantMagic)
			{
				continue;
			}
			if (wantRanged && (skill.getCastRange() <= 150))
			{
				continue;
			}
			if (boss && (skill.isAOE() || skill.isSuicideAttack() || skill.getPower(false, true) > 1200.0))
			{
				continue;
			}
			pool.add(skill);
		}
		if (pool.isEmpty())
		{
			return null;
		}
		// The reach preference only applies to a role that is supposed to stand off and
		// shoot. Applied to every role it forces a melee pet onto bow skills - 900 is the
		// furthest anything in the catalogue reaches, so "prefer the longest reach" and
		// "must reach at all" are the same instruction here, and a WARRIOR ends up with the
		// identical technique list an ARCHER gets.
		return wantRanged ? preferReach(pool, profile) : pool.get(new Random(profile.getProfileSeed() ^ 0x3F9A17C5L).nextInt(pool.size()));
	}

	/**
	 * Picks from a pool with a bias towards the techniques that reach furthest.
	 *
	 * <p>Two filters, in order, and the second is the one that does the work. The floor drops
	 * anything that is not genuinely long-range, which is what actually answers "my pet's
	 * skills do not reach"; the band then sorts what survives by {@code castRange} and keeps
	 * only the top slice, so the seeded roll is made from the longest-reaching candidates
	 * rather than from the pool as a whole.
	 *
	 * <p>A floor that empties the pool is not fatal: it falls back to the unfiltered pool
	 * rather than leaving the tame with no signature at all. A species whose whole pool is
	 * short-range still gets a technique, and the log says so, because a tame that silently
	 * loses its signature is far worse than one that keeps a melee-range technique.
	 *
	 * <p>The sort is stable and the roll is seeded from the profile, so a given beast picks
	 * the same technique for life and two beasts of the same role can differ - the same
	 * guarantee {@link #playerSignature} already made before this ran.
	 *
	 * @param pool the role-appropriate candidates
	 * @param profile the beast, for the seed and for the log line
	 * @return the chosen technique, or null if the pool was empty to begin with
	 */
	private static Skill preferReach(List<Skill> pool, TameProfile profile)
	{
		List<Skill> candidates = pool;
		if (signatureMinRange > 0)
		{
			final List<Skill> reached = new ArrayList<>();
			for (Skill skill : pool)
			{
				if (skill.getCastRange() >= signatureMinRange)
				{
					reached.add(skill);
				}
			}
			if (reached.isEmpty())
			{
				LOGGER.warning("no technique reaches " + signatureMinRange + " for this pool, so its signature keeps the old reach rather than being dropped");
			}
			else
			{
				candidates = reached;
			}
		}

		// Only worth sorting when the band is actually narrower than the pool, AND the pool
		// has a spread of ranges to sort on. With every candidate tied there is nothing to
		// prefer, and truncating anyway would cut the pool to an arbitrary slice in
		// catalogue order while looking as though it had chosen on reach.
		if ((candidates.size() > reachBand) && hasSpread(candidates))
		{
			final List<Skill> sorted = new ArrayList<>(candidates);
			sorted.sort(Comparator.comparingInt(Skill::getCastRange).reversed());
			candidates = sorted.subList(0, reachBand);
		}
		return candidates.get(new Random(profile.getProfileSeed() ^ 0x3F9A17C5L).nextInt(candidates.size()));
	}

	/**
	 * Whether the candidates actually differ in reach.
	 *
	 * @return false when every candidate has the same {@code castRange}
	 */
	private static boolean hasSpread(List<Skill> candidates)
	{
		final int first = candidates.get(0).getCastRange();
		for (int i = 1; i < candidates.size(); i++)
		{
			if (candidates.get(i).getCastRange() != first)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Hand-whitelisted class skills deliberately chosen from condition-free,
	 * weapon-independent Interlude records. They are safe for dynamic Pets,
	 * which do not have a player equipment inventory.
	 *
	 * <p>A beast's class is its role's defining technique plus a race-flavoured set. The
	 * role skill is added first and never shuffled, so it always wins the first awakening
	 * slot - a support tame keeps its Heal, a mage keeps Acumen, and no beast loses its
	 * defining technique to a dice roll. The race then decides the rest, so two beasts of
	 * the same role but different races field visibly different kits.
	 */
	private static List<Skill> classAwakeningPool(TameProfile profile)
	{
		final List<Skill> pool = new ArrayList<>();
		if (profile == null)
		{
			return pool;
		}
		addClassSkill(pool, roleClassSkill(profile.getRole()));
		final List<Skill> flavour = new ArrayList<>();
		for (int skillId : raceClassKit(profile.getRace()))
		{
			addClassSkill(flavour, skillId);
		}
		// Drop anything the role skill already supplied so the same technique cannot
		// occupy two awakening slots.
		flavour.removeIf(skill -> containsSkillId(pool, skill.getId()));
		if (flavour.size() > 1)
		{
			Collections.shuffle(flavour, new Random(profile.getProfileSeed() ^ 0x5A17A9E3L));
		}
		pool.addAll(flavour);
		return pool;
	}

	/**
	 * The one condition-free technique that defines a role, added to every beast of that
	 * role before any race flavour. Physical is the default so an unknown role still gets
	 * a real attack buff rather than an empty pool.
	 */
	private static int roleClassSkill(String role)
	{
		if ("SUPPORT".equals(role))
		{
			return 1011; // Heal
		}
		if ("MAGE".equals(role))
		{
			return 1085; // Acumen
		}
		if ("ARCHER".equals(role))
		{
			return 1240; // Guidance
		}
		return 1068; // Might
	}

	/**
	 * The race's flavour kit: two or three condition-free, weapon-independent techniques
	 * drawn from the whitelisted Interlude records. None of these are guaranteed to be
	 * granted - rarity decides how many of the pool a beast actually awakens - so a race
	 * reads as a tendency, not a fixed loadout. Unknown races contribute nothing.
	 */
	private static int[] raceClassKit(String race)
	{
		if (race == null)
		{
			return new int[0];
		}
		switch (race)
		{
			case "ANIMAL": // feral and relentless
				return new int[]
				{
					1204, // Wind Walk
					1068, // Might
					1044  // Regeneration
				};
			case "BUG": // chitinous, hard to crack
				return new int[]
				{
					1040, // Shield
					1035, // Mental Shield
					1045  // Blessed Body
				};
			case "PLANT": // rooted, slow to die
				return new int[]
				{
					1045, // Blessed Body
					1044, // Regeneration
					1048  // Blessed Soul
				};
			case "BEAST": // raw physical power
				return new int[]
				{
					1068, // Might
					1045, // Blessed Body
					1040  // Shield
				};
			case "HUMANOID": // drilled and disciplined
				return new int[]
				{
					1240, // Guidance
					1068, // Might
					1040  // Shield
				};
			case "UNDEAD": // already dead, hard to kill twice
				return new int[]
				{
					1035, // Mental Shield
					1048, // Blessed Soul
					1044  // Regeneration
				};
			case "DIVINE": // radiant and restorative
				return new int[]
				{
					1011, // Heal
					1045, // Blessed Body
					1048  // Blessed Soul
				};
			case "DEMONIC": // cruel and arcane
				return new int[]
				{
					1085, // Acumen
					1068, // Might
					1035  // Mental Shield
				};
			case "FAIRY": // elusive and swift
				return new int[]
				{
					1204, // Wind Walk
					1085, // Acumen
					1035  // Mental Shield
				};
			case "CONSTRUCT": // built to endure
				return new int[]
				{
					1040, // Shield
					1045, // Blessed Body
					1035  // Mental Shield
				};
			case "GIANT": // unshakable bulk
				return new int[]
				{
					1068, // Might
					1040, // Shield
					1045  // Blessed Body
				};
			case "DRAGON": // ancient and balanced
				return new int[]
				{
					1068, // Might
					1085, // Acumen
					1045  // Blessed Body
				};
			case "ELEMENTAL": // volatile arcane energy
				return new int[]
				{
					1085, // Acumen
					1035, // Mental Shield
					1204  // Wind Walk
				};
			default:
				return new int[0];
		}
	}

	private static void addClassSkill(List<Skill> pool, int skillId)
	{
		final Skill skill = SkillData.getInstance().getSkill(skillId, 1);
		if ((skill != null) && skill.isActive() && !skill.isPassive() && !skill.isToggle() && !skill.isDance() && !skill.isSuicideAttack())
		{
			pool.add(skill);
		}
	}

	private static boolean containsSkillId(List<Skill> pool, int skillId)
	{
		for (Skill skill : pool)
		{
			if (skill.getId() == skillId)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Adds the first awakening's technique set, and the technique the second
	 * awakening will open.
	 *
	 * <p>Rarity decides how many techniques the first awakening offers - one for a
	 * common beast, two for RARE and above, three for a legendary one with skill
	 * potential 85 or more - and those are numbered AWAKENING, AWAKENING_2,
	 * AWAKENING_3. All of them are granted together when the beast first awakens:
	 * the player picks a path, not a technique.
	 *
	 * <p>AWAKENING2 is a different thing entirely and is always exactly one, whatever
	 * the rarity. It is the technique the <em>second</em> awakening opens, so it is
	 * rolled into the deck now and left locked until the beast has been through
	 * that second awakening. It is spelled without the underscore on purpose - the
	 * SQL that grants the first awakening's set matches the family with
	 * LIKE 'AWAKENING%', and AWAKENING2 is inside that family, so the underscore
	 * would be the only thing telling the two apart in a query nobody reads twice.
	 */
	private static void addClassAwakeningSelections(List<Selection> result, TameProfile profile)
	{
		final List<Skill> pool = classAwakeningPool(profile);
		if (pool.isEmpty())
		{
			return;
		}
		int count = 1;
		final String rarity = profile.getRarity();
		if ("RARE".equals(rarity) || "EPIC".equals(rarity) || "LEGENDARY".equals(rarity))
		{
			count = 2;
		}
		if ("LEGENDARY".equals(rarity) && (profile.getSkillPotential() >= 85))
		{
			count = 3;
		}
		count = Math.min(count, pool.size());
		for (int i = 0; i < count; i++)
		{
			final String slot = i == 0 ? "AWAKENING" : "AWAKENING_" + (i + 1);
			final String quality = i == 0 ? classQuality(profile) : (i == 1 ? "REFINED" : "RARE");
			result.add(new Selection(pool.get(i), slot, quality, 30, false));
		}
		// The second awakening's own technique, taken from the same seeded pool so the
		// roll is stable for this individual across every relog and resummon. It needs
		// one entry past the first awakening's, so a beast whose rarity took the whole
		// pool simply gets no second technique rather than a duplicate of its first.
		if (pool.size() > count)
		{
			result.add(new Selection(pool.get(count), TameProfileRepository.SECOND_AWAKENING_SLOT, "RARE", 30, false));
		}
	}

	private static String classQuality(TameProfile profile)
	{
		return (profile != null) && ("RARE".equals(profile.getRarity()) || "EPIC".equals(profile.getRarity()) || "LEGENDARY".equals(profile.getRarity())) ? "REFINED" : "STABLE";
	}

	private static String quality(TameProfile profile, int slot)
	{
		if (profile == null)
		{
			return "STABLE";
		}
		final int score = profile.getSkillPotential() + (slot * 7);
		if (score >= 75)
		{
			return "REFINED";
		}
		if (score <= 25)
		{
			return "FAINT";
		}
		return "STABLE";
	}
}
