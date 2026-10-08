/*
 * TameSkillAura.java
 *
 * A second way for a tame to carry a look, for the affinities the abnormal visual
 * effect bitmask could not carry.
 *
 * The bitmask route has a hard ceiling. It can only set AbnormalVisualEffect values,
 * and that enum is a fixed list of sixty or so client effects - so if the look that
 * actually reads as holy on a given creature model is not one of them, no amount of
 * configuration finds it. That is exactly what happened: INVINCIBILITY was configured
 * for holy on the strength of Set Hero using it, and it drew nothing on screen. The
 * enum says which effect a skill uses; it does not promise the effect renders, and
 * that is only knowable with the client up.
 *
 * So this class takes the other route. Rather than setting a bit, it has the beast
 * cast a chosen skill on a timer. The client already knows what skill 453 looks like
 * because it ships the animation, so a real cast is a real, correct visual - no
 * guessing about enum values involved.
 *
 * The catch is that a real cast is a real cast. Escape Shackle would actually dispel
 * roots and Body To Mind would actually hand out mana on a loop, which is not a
 * cosmetic and would quietly change how the beast plays. So the skill used here is a
 * rebuilt copy that carries the identity and the shape of the original and none of
 * its behaviour:
 *
 *   - the id and level are the real ones, because that is what the client keys its
 *     animation off, so it draws the same thing;
 *   - the hit time is zero, so the core launches immediately and the beast is never
 *     locked in a cast - it looks like it is casting without ever casting;
 *   - every cost is zero, so a loop cannot drain the beast;
 *   - it has no effects at all.
 *
 * That last one is free rather than clever. Skill's effect lists are built empty by
 * its constructor and only ever populated by addEffect, which is called by the XML
 * loader afterwards. A Skill built directly therefore has no effects by construction;
 * there is nothing to strip and nothing that can be forgotten.
 */
package taming;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.network.serverpackets.MagicSkillLaunched;

public final class TameSkillAura
{
	private static final Logger LOGGER = Logger.getLogger(TameSkillAura.class.getName());

	/** Affinities this class can dress, in module.ini order. */
	private static final String[] AFFINITIES =
	{
		"FIRE", "EARTH", "WATER", "WIND", "HOLY", "DARK"
	};

	/** The configured skill per affinity, or null when that affinity has none. */
	private static final Map<String, Skill> BY_AFFINITY = new ConcurrentHashMap<>();

	/**
	 * Rebuilt visual-only copies, keyed by the skill id they came from.
	 *
	 * <p>Built once per id at startup and shared, because they are immutable once
	 * constructed and every beast casting id 453 should cast the same one.
	 */
	private static final Map<Long, Skill> VISUAL = new ConcurrentHashMap<>();

	/**
	 * Sentinel for "take {@code isMagic} from the real skill", as opposed to a forced 0 or
	 * 1. It is a value rather than a null so the cache key can tell the three apart.
	 */
	private static final int COPY_IS_MAGIC = -1;

	/**
	 * Cache key for one rebuild: the id, plus which of the three {@code isMagic} settings it
	 * was built under.
	 *
	 * <p>Two bits for the setting, so a skill asked for both ways is two entries rather than
	 * one entry that whichever caller got there first decided for.
	 */
	private static Long cacheKey(int id, int magicOverride)
	{
		return Long.valueOf((((long) id) << 2) | (magicOverride + 1L));
	}

	/** Which skill each dressed beast is looping, by summon object id. */
	private static final Map<Integer, Skill> CASTING = new ConcurrentHashMap<>();

	private static volatile ScheduledExecutorService executor;

	/** Null until any affinity has a skill, which is what stops the ticker. */
	private static long gapMs = 4000;

	/**
	 * Whether an affinity skill is shown without its casting gesture.
	 *
	 * <p>The reason this exists is that a real cast and a bare effect are visibly different
	 * on a tame even when the visual itself is identical. Casting goes out as
	 * {@code MagicSkillUse}, and the client answers that with the caster's casting animation.
	 * {@code MagicSkillLaunched} on its own says "this skill landed on these targets", so the
	 * client plays the skill's effect animation and there is no cast to begin and therefore
	 * no gesture - the same packet the hit-effects page already sends, and the reason that
	 * page can show an effect without anyone gesturing. For a cosmetic that repeats every
	 * few seconds the difference is the single loudest thing about the look: a beast that
	 * appears to be casting forever rather than one standing there calmly.
	 *
	 * <p>This used to be gated on {@code skill.isMagic()}, which was wrong in both
	 * directions. The gate assumed the casting animation was a consequence of the skill being
	 * magic-type, and it is not - it is a consequence of the packet being {@code MagicSkillUse}
	 * at all. So the affinities that were actually stuck gesturing were the ones bound to
	 * non-magic skills: Holy on {@code Escape Shackle} (453) carries no {@code isMagic} at
	 * all, as do {@code Double Shot} (19) and {@code Sonic Storm} (7). All three looped the
	 * loud path and read as permanently busy. The gate was conservatism, not a technical
	 * limit, and it was the only thing preventing the quiet packet from being used where it
	 * works.
	 *
	 * <p>Setting {@code isMagic} to false server-side never helped either way, because the
	 * client decides from its own copy of the skill data - the flag has to be kept off the
	 * wire, which the quiet path does by never sending a cast at all.
	 *
	 * <p>On by default, and now applies to every affinity skill rather than only the
	 * magic-type ones. {@code FALSE} puts every affinity back on the normal
	 * {@code useMagic} path, which is the old behaviour for the non-magic affinities and is
	 * kept as an escape hatch.
	 */
	private static volatile boolean silentMagic = true;

	/**
	 * The floor on the gap.
	 *
	 * <p>Each tick is a real packet to everyone who can see the beast, so this is not
	 * free the way the bitmask self-heal is. A loop tighter than this spends bandwidth
	 * on a cosmetic and, past a point, just re-triggers the same animation before the
	 * previous one has finished drawing.
	 */
	private static final long GAP_FLOOR_MS = 1000;

	private TameSkillAura()
	{
	}

	/**
	 * Every key the Skill constructor reads.
	 *
	 * <p>All of them are required. StatSet's single-argument getters throw on a missing
	 * key rather than defaulting, so a key that is absent takes the whole construction
	 * down, and one absent key is indistinguishable from a typo at a glance.
	 *
	 * <p>The values are strings on purpose. StatSet's numeric getters accept a string
	 * and parse it, so "0" satisfies getInt, getByte, getFloat and getBoolean alike,
	 * which is why this does not need to carry a type per key.
	 */
	private static final String[] REQUIRED_KEYS =
	{
		"skill_id", "level", "displayId", "displayLevel", "name", "isMagic", "staticReuse",
		"mpConsume", "mpInitialConsume", "mpPerChanneling", "hpConsume", "itemConsumeCount",
		"itemConsumeId", "castRange", "effectRange", "abnormalLevel", "abnormalTime",
		"abnormalInstant", "attribute", "stayAfterDeath", "stayOnSubclassChange",
		"hitTime", "coolTime", "isDebuff", "isRecoveryHerb", "feed", "reuseDelay", "affectRange",
		"targetType", "power", "pvpPower", "pvePower", "magicLevel", "lvlBonusRate",
		"activateRate", "minChance", "maxChance", "ignoreShld", "nextActionAttack",
		"removedOnAnyActionExceptMove", "removedOnDamage", "blockedInOlympiad", "element", "elementPower",
		"overHit", "isSuicideAttack", "minPledgeClass", "chargeConsume", "blowChance",
		"baseCritRate", "dmgDirectlyToHp", "effectPoint", "canBeDispeled", "excludedFromCheck",
		"simultaneousCast", "icon", "channelingSkillId", "channelingTickInterval",
		"channelingTickInitialDelay", "isPvPOnly"
	};

/**
 * The keys the constructor reads that are enums rather than plain values.
 *
 * <p>StatSet's numeric getters accept a string and parse it, so "0" satisfies getInt,
 * getByte, getFloat and getBoolean alike. It does not satisfy getEnum, which insists on a
 * constant name. These six are the complete set, confirmed against the shipped jar rather
 * than guessed: each was read out of the constructor's bytecode and then proved by
 * building a Skill with the finished key set.
 *
 * <p>A first attempt paired these up the other way round, concluded isMagic and power were
 * enums, left trait unmentioned, and every build died on "Enum value of type TraitType
 * required, but found: 0". Guessing here is not a shortcut that saves time later.
 */
	private static final String[] ENUM_KEYS =
	{
		"operateType", "trait", "abnormalType", "targetType", "basicProperty", "abnormalVisualEffect"
	};

	/**
	 * The enum keys that are safe to drop entirely if a build still trips over them.
	 *
	 * <p>Safe because StatSet has no single-argument getEnum - every variant carries a
	 * default - so a missing key cannot throw, it just takes the default. All six have a
	 * meaningful default: SkillOperateType and TargetType default to the self-cast the
	 * rebuild wants anyway, and the other four default to NONE.
	 *
	 * <p>basicProperty and abnormalVisualEffect go first because they are the two most
	 * likely to gain constants in a future datapack, and neither is load-bearing for a
	 * cosmetic.
	 */
	private static final String[] DROPPABLE_ENUMS =
	{
		"basicProperty", "abnormalVisualEffect", "trait", "abnormalType"
	};

	/**
	 * Points an affinity at a skill id, or {@code NONE} to give it none.
	 *
	 * @param ids     affinity name to skill id, as a decimal string
	 * @param gapMs   gap between casts, floored at {@link #GAP_FLOOR_MS}
	 */
	public static void configure(Map<String, String> ids, long gapMs)
	{
		configure(ids, gapMs, silentMagic);
	}

	/**
	 * As {@link #configure(Map, long)}, and additionally chooses whether magic-type affinity
	 * skills skip the casting gesture.
	 *
	 * @param silentMagic see {@link #silentMagic}
	 */
	public static void configure(Map<String, String> ids, long gapMs, boolean silentMagic)
	{
		TameSkillAura.silentMagic = silentMagic;
		BY_AFFINITY.clear();
		boolean any = false;
		for (String affinity : AFFINITIES)
		{
			final Skill skill = resolve(affinity, (ids == null) ? null : ids.get(affinity));
			if (skill != null)
			{
				BY_AFFINITY.put(affinity, skill);
				any = true;
				LOGGER.info(affinity.toLowerCase(Locale.ROOT) + " aura is skill " + skill.getId() + " (" + skill.getName() + "), visual only");
			}
		}
		if (!any)
		{
			stop();
			CASTING.clear();
			return;
		}
		start(Math.max(GAP_FLOOR_MS, gapMs));
	}

	/**
	 * Turns a configured id into a visual-only copy of that skill.
	 *
	 * @return the rebuilt skill, or null if the id is unusable
	 */
	private static Skill resolve(String label, String configured)
	{
		if ((configured == null) || configured.trim().isEmpty() || "NONE".equalsIgnoreCase(configured.trim()))
		{
			return null;
		}
		int id;
		try
		{
			id = Integer.parseInt(configured.trim());
		}
		catch (NumberFormatException e)
		{
			LOGGER.warning("TameSkillAura: \"" + configured.trim() + "\" for " + label + " is not a skill id, so it gets no skill aura");
			return null;
		}
		return visualOnly(id, label);
	}

	/**
	 * A visual-only copy of any skill in the data, for the aura lab to cast on demand.
	 *
	 * <p>Same rebuild, same guarantee - the real id and level so the client draws the real
	 * animation, and no effects at all so nothing happens when it does. Exposed rather than
	 * reimplemented in the lab: the sixty-odd keys this needs are the part that is easy to
	 * get wrong, and there is one copy of that knowledge worth having.
	 *
	 * <p>Shares the cache with the affinity auras. The rebuilt skill is immutable once built,
	 * so a skill the lab has already built is the same object a beast would cast.
	 *
	 * @return the rebuilt skill, or null if it could not be built
	 */
	public static Skill visualOnly(int id)
	{
		return visualOnly(id, "the aura lab", COPY_IS_MAGIC);
	}

	/**
	 * A visual-only copy with {@code isMagic} forced, so the aura lab can play one skill
	 * both ways without touching the data.
	 *
	 * <p>This exists because a magic skill and a non-magic one are cast in visibly different
	 * ways by the client - one gets the casting gesture, the other is a bare effect - and
	 * whether the gesture can be dropped while the effect survives is not answerable from
	 * here. It is answerable by pressing the button twice.
	 *
	 * <p>Cached separately per setting, so asking for the non-magic copy cannot hand back
	 * the magic one that happens to share its id.
	 *
	 * @param nonMagic true to force {@code isMagic=0}, false to force {@code isMagic=1}
	 * @return the rebuilt skill, or null if it could not be built
	 */
	public static Skill visualOnly(int id, boolean nonMagic)
	{
		return visualOnly(id, "the aura lab", nonMagic ? 0 : 1);
	}

	/**
	 * Builds a copy of a skill that keeps its face and none of its behaviour.
	 *
	 * <p>Retries with the enum-shaped keys dropped one at a time. That is not blind
	 * guesswork: a key read through getEnum cannot be satisfied by the string "0", and
	 * it cannot be missing either, so a build that trips over one is telling us it is
	 * one of those. Dropping it hands the key back to StatSet's own default, which is
	 * the correct outcome for a cosmetic.
	 *
	 * @return the rebuilt skill, or null if it could not be built
	 */
	private static Skill visualOnly(int id, String label)
	{
		return visualOnly(id, label, COPY_IS_MAGIC);
	}

	private static Skill visualOnly(int id, String label, int magicOverride)
	{
		final Long cacheKey = cacheKey(id, magicOverride);
		final Skill cached = VISUAL.get(cacheKey);
		if (cached != null)
		{
			return cached;
		}

		final SkillData data = SkillData.getInstance();
		if (data == null)
		{
			LOGGER.warning("TameSkillAura: skill data is not loaded, so " + label + " gets no skill aura");
			return null;
		}

		// The highest level is the one to copy: the client draws the animation off the id
		// and level, so a level the client has never seen is a coin flip.
		Skill base = null;
		for (int level = data.getMaxLevel(id); (level >= 1) && (base == null); level--)
		{
			base = data.getSkill(id, level);
		}
		if (base == null)
		{
			LOGGER.warning("TameSkillAura: no skill " + id + " in the data, so " + label + " gets no skill aura");
			return null;
		}

		final Map<String, Object> values = values(base, magicOverride);
		for (int drop = 0; drop <= DROPPABLE_ENUMS.length; drop++)
		{
			final Map<String, Object> attempt = new LinkedHashMap<>(values);
			for (int i = 0; i < drop; i++)
			{
				attempt.remove(DROPPABLE_ENUMS[i]);
			}
			try
			{
				final StatSet statSet = new StatSet();
				statSet.getSet().putAll(attempt);
				final Skill built = new Skill(statSet);
				VISUAL.put(cacheKey, built);
				return built;
			}
			catch (RuntimeException e)
			{
				// Carried to the end of the loop rather than logged per attempt, so a
				// build that succeeds on the third try does not print two scary warnings
				// on the way there.
				if (drop == DROPPABLE_ENUMS.length)
				{
					LOGGER.log(Level.WARNING, "TameSkillAura: could not build a visual-only copy of skill " + id + ", so " + label + " gets no skill aura", e);
				}
			}
		}
		return null;
	}

	/**
	 * The StatSet contents for a visual-only copy of the given skill.
	 *
	 * <p>Every key the constructor reads is present, then the ones that decide what the
	 * client draws are replaced with values copied from the real skill.
	 */
	private static Map<String, Object> values(Skill base, int magicOverride)
	{
		final Map<String, Object> values = new LinkedHashMap<>();
		for (String key : REQUIRED_KEYS)
		{
			values.put(key, "0");
		}

		// affectLimit is deliberately left out rather than set to "0". It is the one key
		// here that is not a plain getter: read as a string, then split on "-" and parsed
		// as an integer on both halves - so it wants "0-0", and "0" throws. Omitting it
		// skips that block and leaves the field as the empty int[2] the constructor
		// already created, which is the same answer without the trap.
		//
		// The six enum keys follow for the same reason: "0" satisfies every other getter in
		// this map and is exactly what breaks these. Each has a NONE, each has been
		// checked against the shipped jar, and the finished set builds.
		values.put("operateType", "A1");
		values.put("targetType", "SELF");
		values.put("trait", "NONE");
		values.put("abnormalType", "NONE");
		values.put("basicProperty", "NONE");
		values.put("abnormalVisualEffect", "NONE");

		// Identity. The client keys its animation off these two, so they have to be the
		// real ones - this is the whole reason for rebuilding rather than inventing an id.
		final int id = base.getId();
		final int level = base.getLevel();
		values.put("skill_id", Integer.valueOf(id));
		values.put("level", Integer.valueOf(level));
		values.put("displayId", Integer.valueOf(id));
		values.put("displayLevel", Integer.valueOf(level));
		values.put("name", base.getName());

		// isMagic is read as a number, not an enum, so "1" is a valid value - and it is
		// copied from the real skill unless a caller forces it, because it decides whether
		// the core treats this as a magic skill and the two are visibly different to draw.
		// Body To Mind is a magic skill and Escape Shackle is not, and forcing both to
		// magic would have drawn the wrong one of the two.
		//
		// Forcing it to 0 is the lab's experiment: whether the post-cast effect survives
		// without the casting gesture cannot be settled from the server side, because the
		// client also has its own copy of what skill 1157 looks like. Two presses answer it.
		values.put("isMagic", (magicOverride == COPY_IS_MAGIC) ? (base.isMagic() ? "1" : "0") : Integer.toString(magicOverride));

		// Shape. An instant self-cast is what makes this a cosmetic: the core launches it
		// on the spot, so the beast never enters a cast, never shows a bar, and never
		// stands still while it happens.
		values.put("hitTime", "0");
		values.put("castRange", "-1");
		values.put("effectRange", "-1");
		values.put("effectPoint", "0");

		// Cost. A loop that costs anything is a loop that empties the beast's bar and then
		// silently stops, which looks exactly like the feature not working.
		values.put("mpConsume", "0");
		values.put("mpInitialConsume", "0");
		values.put("hpConsume", "0");
		values.put("chargeConsume", "0");
		values.put("itemConsumeCount", "0");
		values.put("itemConsumeId", "0");

		// Cadence. The original's reuse delay is dropped so the module's own gap is the
		// only thing deciding how often this fires.
		values.put("reuseDelay", "0");
		values.put("coolTime", "0");
		values.put("channelingSkillId", "0");

		// Nothing abnormal, nothing drawn from the bitmask route either.
		values.put("abnormalLevel", "0");
		values.put("abnormalTime", "0");
		return values;
	}

	/**
	 * Points a beast at the skill its affinity should loop, or removes it.
	 *
	 * <p>Called from the same place the bitmask auras are applied, so a beast gets both
	 * routes at once if both are configured.
	 */
	public static void apply(Summon summon, TameProfile profile)
	{
		if (summon == null)
		{
			return;
		}
		final Skill skill = (profile == null) ? null : BY_AFFINITY.get(key(TameIdentity.effectiveAffinity(profile)));
		if (skill == null)
		{
			CASTING.remove(Integer.valueOf(summon.getObjectId()));
			return;
		}
		CASTING.put(Integer.valueOf(summon.getObjectId()), skill);
	}

	private static String key(String affinity)
	{
		return (affinity == null) ? "" : affinity.trim().toUpperCase(Locale.ROOT);
	}

	/**
	 * @return the skill this affinity is looping, or null
	 */
	public static Skill skillFor(String affinity)
	{
		return BY_AFFINITY.get(key(affinity));
	}

	private static void start(long intervalMs)
	{
		if (executor != null)
		{
			return;
		}
		final ScheduledExecutorService running = Executors.newSingleThreadScheduledExecutor(runnable ->
		{
			final Thread thread = new Thread(runnable, "taming-skill-aura");
			thread.setDaemon(true);
			return thread;
		});
		running.scheduleWithFixedDelay(TameSkillAura::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
		executor = running;
		gapMs = intervalMs;
		LOGGER.info("skill auras are on, casting every " + intervalMs + "ms");
	}

	private static void stop()
	{
		final ScheduledExecutorService running = executor;
		executor = null;
		if (running != null)
		{
			running.shutdownNow();
		}
	}

	/**
	 * Fires one round of casts.
	 */
	private static void tick()
	{
		if (CASTING.isEmpty())
		{
			return;
		}
		for (final Map.Entry<Integer, Skill> entry : CASTING.entrySet())
		{
			final WorldObject object = World.getInstance().findObject(entry.getKey().intValue());
			if (!(object instanceof Summon))
			{
				CASTING.remove(entry.getKey());
				continue;
			}
			try
			{
				cast((Summon) object, entry.getValue());
			}
			catch (RuntimeException e)
			{
				// One beast mid-death must not stop the rest from drawing.
				LOGGER.log(Level.FINE, "skill aura skipped a tame", e);
			}
		}
	}

	/**
	 * Puts one aura tick on the wire.
	 *
	 * <p>Two paths, and which one is taken is the whole reason a beast looks calm or looks
	 * permanently busy. Nothing about {@code isMagic} decides it any more - see
	 * {@link #silentMagic}.
	 *
	 * <p>{@code useMagic} is the honest path - it is what a real cast does, and it is what
	 * applies any server-side effect. It routes through {@code doCast}, which broadcasts
	 * {@code MagicSkillUse}, and the client answers that with the caster's casting animation.
	 * For a cosmetic aura repeating every few seconds that reads as a permanent casting loop.
	 *
	 * <p>{@code MagicSkillLaunched} on its own is the quiet path. It says "this skill landed on
	 * these targets" and the client plays the skill's effect animation from it, but there is no
	 * cast to begin, so there is no casting animation. This is the same packet the hit-effects
	 * page already sends, and it is why that page can show an effect without anyone gesturing.
	 *
	 * <p>The quiet path is taken for every affinity skill when enabled. It used to be limited
	 * to magic-type skills on the belief that the gesture came from the skill's type; it comes
	 * from the packet instead, so restricting it left exactly the wrong affinities gesturing.
	 *
	 * <p>The real level is sent rather than a flat 1, since the client looks the animation up by
	 * skill id *and* level and a mismatched level can resolve to a different animation or none.
	 */
	private static void cast(Summon summon, Skill skill)
	{
		if (summon.isDead() || summon.isCastingNow())
		{
			// Casting now is the pet's own business - a real fight skill, or the pet AI
			// deciding to do something. Interleaving with it is what makes a tame look like
			// it is glitching, so the cosmetic yields.
			return;
		}
		summon.setTarget(summon);
		if (silentMagic)
		{
			summon.broadcastPacket(new MagicSkillLaunched(summon, skill.getId(), skill.getLevel(), Collections.singletonList((WorldObject) summon)));
			return;
		}
		summon.useMagic(skill, false, false);
	}

	/**
	 * Takes the auras off every beast and stops the ticker.
	 *
	 * <p>Called on module unload. The registry has to go with the module rather than
	 * surviving it: on a reload the scheduler is shut down but the map would keep summon
	 * ids from the previous run, and the first tick afterwards would be dressing creatures
	 * that no longer exist.
	 */
	public static void clear()
	{
		stop();
		CASTING.clear();
		BY_AFFINITY.clear();
		VISUAL.clear();
	}

	/**
	 * @return how often the ticker is firing, for the collar page and diagnostics
	 */
	public static long gapMs()
	{
		return gapMs;
	}

	/**
	 * @return how many beasts are looping a skill aura
	 */
	public static int dressed()
	{
		return CASTING.size();
	}

	/**
	 * @param affinity the affinity to describe
	 * @return a short honest description for the collar page
	 */
	public static String describe(String affinity)
	{
		final Skill skill = BY_AFFINITY.get(key(affinity));
		if (skill == null)
		{
			return "none";
		}
		return skill.getName() + " (skill " + skill.getId() + ", visual only)";
	}
}