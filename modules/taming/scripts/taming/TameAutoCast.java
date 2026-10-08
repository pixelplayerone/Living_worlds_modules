/*
 * TameAutoCast.java
 *
 * Makes a tame use its own techniques by itself, so nothing has to be pressed.
 *
 * Why this exists: every route to a visible pet skill button turned out to be a
 * dead end on this build. Binding the client's own pet skill bar is the supported
 * route and is still tried on every summon (see TameSkillBar), but a button there
 * is cast by the *player*, so the id has to be one the client already knows, which
 * means the client's own name and icon cannot be changed and every usable trigger
 * id is targetType SELF, so pressing one overwrites the player's selection. A
 * shortcut-bar version of the same idea was built, measured and then deleted: it
 * forced real stock skills onto the character and still left the player pressing
 * buttons to operate their own pet, which is backwards.
 *
 * Two rules keep it from being obnoxious, and both were added after the first test,
 * where a tame holding one damage skill, two debuffs and one buff fired the buff
 * once a second indefinitely:
 *
 *   - A minimum gap between casts ({@code AutoCastMinGapMs}, default 2000). Beast
 *     techniques rarely carry a reuse delay, so nothing else throttles them.
 *   - Never re-apply a skill the target is already carrying. A live buff is simply
 *     refreshed by useMagic and does nothing, so casting it again burns the tame's
 *     MP and makes the technique look broken.
 *
 * A third was added after the gap proved not to be enough on its own: a per-technique
 * cooldown ({@code AutoCastSkillCooldownMs}, default 5000). The gap stops the tame
 * casting fast, but not casting the <em>same</em> skill every gap - so a beast with a
 * big mana pool leaned on one damage skill, and from range that looked like a stutter,
 * cast then close to melee then cast again. The cooldown makes the deck rotate.
 *
 * The second rule is what keeps a debuff honest. A debuff does no damage by design,
 * so a deck of two debuffs and one damage skill spends most of its casts on effects
 * that are already up; without this check that reads as "the skills do nothing" when
 * in fact every one of them had already landed.
 *
 * None of that survives here. Nothing is drawn and nothing is pressed: the module
 * asks the tame to cast directly through the same Summon.useMagic path the collar
 * and the bar both use, so the technique deck resolves from the profile exactly as
 * it does everywhere else and no client-side trick is involved at all.
 *
 * Threading, stated plainly because it is the one real caveat. This build has no
 * ThreadPoolManager and no CastSkill AI to hang a think-tick off, so the only way
 * to get a periodic callback is a private scheduled executor. That makes it a
 * second thread touching live game objects, which is not how the rest of the
 * server runs. It is kept as safe as it can be without core changes: the player
 * list is copied before iterating so a concurrent logout cannot throw, every
 * single tame is wrapped individually so one bad beast cannot stop the rest, and
 * the work is a decision plus one useMagic call - the same call the game thread
 * makes for a player-initiated cast. If a future build exposes a scheduler or an
 * AI hook, this whole class should become that instead.
 */
package taming;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.ai.Action;
import org.l2jmobius.gameserver.ai.CreatureAI;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.effects.EffectType;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.Skill;

public final class TameAutoCast
{
	private static final Logger LOGGER_LOG = Logger.getLogger(TameAutoCast.class.getName());

	private static volatile boolean enabled;
	private static volatile ScheduledExecutorService executor;

	/**
	 * Which deck slot gets first refusal on the next tick.
	 *
	 * <p>Round-robin rather than always-slot-one, so a tame with a slow technique in
	 * slot 1 still fires the rest. A single counter is enough: it is a fairness hint,
	 * not state, and it cannot leak the way a per-summon map would.
	 */
	private static final AtomicInteger ROTATION = new AtomicInteger();

	/**
	 * When each tame last cast anything, by summon object id.
	 *
	 * <p>A gap between casts is needed and it cannot come from the skills: monster
	 * and boss techniques almost never carry a reuse delay, so with a deck of one
	 * damage skill, two debuffs and a buff the tame fired the buff once a second
	 * forever. Pruned against the live summons on every tick, so a despawned or
	 * dismissed beast cannot leave an entry behind.
	 */
	private static final Map<Integer, Long> LAST_CAST = new ConcurrentHashMap<>();

	/**
	 * When each tame last cast each technique, by summon object id then skill id.
	 *
	 * <p>The gap above stops a tame casting faster than once every couple of seconds, but
	 * it does not stop it casting the <em>same</em> technique over and over. Monster and
	 * boss techniques carry no reuse delay, so a tame with a big mana pool picked its
	 * damage skill every gap forever - which read as a stutter: cast a ranged technique,
	 * close to melee, cast it again. This puts a cooldown on each technique in the deck so
	 * the beast actually rotates through its kit instead of leaning on one skill. Pruned
	 * alongside the gap map, and keyed the same way.
	 */
	private static final Map<Integer, Map<Integer, Long>> LAST_SKILL_CAST = new ConcurrentHashMap<>();

	/**
	 * Players who asked for their own auto-cast to stop. Keyed by player object id, like the
	 * audition maps elsewhere in the module, and deliberately not persisted.
	 */
	private static final java.util.Set<Integer> MUTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/**
	 * Milliseconds any one tame must leave between two casts.
	 */
	private static volatile long minGapMs = 2000;

	/**
	 * Milliseconds any one tame must leave before it uses the <em>same</em> technique
	 * again, from {@code AutoCastSkillCooldownMs}. Applied on top of the technique's own
	 * reuse delay, which monster and boss skills rarely have. Zero disables it. Set high
	 * enough that a deck reads as a rotation rather than a loop.
	 */
	private static volatile long skillCooldownMs = 5000;

	/**
	 * Sets the per-technique cooldown, from {@code AutoCastSkillCooldownMs}. Negative
	 * values are treated as zero (disabled) rather than refused, because zero is a
	 * meaningful setting - it turns the throttle off - and there is nothing to protect
	 * against.
	 */
	public static void setSkillCooldown(long ms)
	{
		skillCooldownMs = Math.max(0, ms);
	}

	public static long getSkillCooldownMs()
	{
		return skillCooldownMs;
	}

	/**
	 * The health fraction at or below which a healing technique is worth a cast.
	 *
	 * <p>A heal that fires early is a wasted cast that could have been damage or a
	 * debuff, and a heal that fires too late is wasted entirely. Fraction of maximum
	 * HP rather than a flat number so the same rule reads the same on a 300 HP wolf
	 * and a 30000 HP raid tame. Set from {@code AutoCastHealPercent} in module.ini.
	 */
	private static volatile double healThreshold = 0.70;

	/**
	 * Sets the ally-health threshold a heal has to beat to be cast, from
	 * {@code AutoCastHealPercent}. Out-of-range values are refused rather than clamped, so a
	 * typo cannot silently make every tame either immortal or never-healing.
	 */
	public static void setHealThreshold(double value)
	{
		if ((value > 0.0) && (value <= 1.0))
		{
			healThreshold = value;
		}
	}

	/**
	 * Whether an offensive technique waits until the beast has stopped at its target,
	 * from {@code AutoCastMeleeFirst}. On by default: the beast closes and fights, and a
	 * technique is a punctuation between swings rather than a stand-off at maximum range.
	 * Turn it off for a beast meant to fight at range, accepting that it will then cast
	 * while walking and look like it stutters.
	 */
	private static volatile boolean meleeFirst = true;

	public static void setMeleeFirst(boolean value)
	{
		meleeFirst = value;
	}

	private TameAutoCast()
	{
	}

	public static void start(int intervalMs)
	{
		start(intervalMs, minGapMs);
	}

	/**
	 * Starts the scheduler with an explicit minimum gap between casts.
	 */
	public static void start(int intervalMs, long gapMs)
	{
		if (executor != null)
		{
			return;
		}
		final int period = Math.max(250, intervalMs);
		minGapMs = Math.max(0, gapMs);
		enabled = true;
		executor = Executors.newSingleThreadScheduledExecutor(runnable ->
		{
			// Daemon: a lingering module thread must never hold the JVM open.
			final Thread thread = new Thread(runnable, "taming-autocast");
			thread.setDaemon(true);
			return thread;
		});
		executor.scheduleWithFixedDelay(TameAutoCast::tick, period, period, TimeUnit.MILLISECONDS);
		LOGGER_LOG.info("auto-cast of tame techniques is on, tick " + period + "ms, minimum gap " + minGapMs + "ms");
	}

	public static long getMinGapMs()
	{
		return minGapMs;
	}

	public static void stop()
	{
		enabled = false;
		LAST_CAST.clear();
		LAST_SKILL_CAST.clear();
		final ScheduledExecutorService running = executor;
		executor = null;
		if (running != null)
		{
			running.shutdownNow();
		}
	}

	public static boolean isEnabled()
	{
		return enabled;
	}

	/**
	 * Whether auto-cast runs for one player: the server-wide switch, minus that player's own
	 * opt-out.
	 *
	 * <p>These are two separate questions. {@code .tameautocast off} used to answer "off" for
	 * everybody - it wrote the single global flag below - so one player muting their own beast
	 * silenced every tame on the server and the rest never got a message explaining why their
	 * pets had gone quiet. The mute is kept per player instead, and the global flag is left to
	 * the config, where it belongs.
	 */
	public static boolean isEnabledFor(int playerObjectId)
	{
		return enabled && !MUTED.contains(Integer.valueOf(playerObjectId));
	}

	/**
	 * Mutes or unmutes one player's auto-cast. Turning auto-cast back on also clears the mute,
	 * so "off" then "on" lands where the player expects rather than leaving a hidden mute
	 * behind.
	 */
	public static void setMutedFor(int playerObjectId, boolean muted)
	{
		if (muted)
		{
			MUTED.add(Integer.valueOf(playerObjectId));
		}
		else
		{
			MUTED.remove(Integer.valueOf(playerObjectId));
		}
	}

	/** Clears a player's mute, so a relog does not inherit the previous session's choice. */
	public static void forget(int playerObjectId)
	{
		MUTED.remove(Integer.valueOf(playerObjectId));
	}

	/**
	 * The server-wide switch. Configuration only - a player asking for auto-cast to stop goes
	 * through {@link #setMutedFor(int, boolean)} so it stays their own business.
	 */
	public static void setEnabled(boolean value)
	{
		if (value && (executor == null))
		{
			// Enabled in the config but the scheduler is not running (module reloaded,
			// or onEnable bailed early): start it rather than pretending.
			start(1000);
			return;
		}
		enabled = value;
	}

	private static void tick()
	{
		if (!enabled)
		{
			return;
		}
		final List<Player> players;
		try
		{
			// Copied, not iterated live: the collection changes under us when somebody
			// logs out mid-tick.
			players = new ArrayList<>(World.getInstance().getPlayers());
		}
		catch (RuntimeException e)
		{
			LOGGER_LOG.log(Level.FINE, "auto-cast could not read the player list", e);
			return;
		}
		for (Player player : players)
		{
			try
			{
				castFor(player);
			}
			catch (RuntimeException e)
			{
				// One misbehaving tame must not silence every other one.
				LOGGER_LOG.log(Level.FINE, "auto-cast skipped a tame", e);
			}
		}
		prune();
	}

	/**
	 * Drops gap timestamps for summons that are gone.
	 *
	 * <p>Cheap enough to do every tick and worth doing there rather than on logout:
	 * a player who disconnects mid-fight never fires the logout hook this class has
	 * no access to, and an unbounded map keyed by object id is a slow leak on a
	 * server that runs for weeks.
	 */
	private static void prune()
	{
		if (LAST_CAST.isEmpty() && LAST_SKILL_CAST.isEmpty())
		{
			return;
		}
		final Set<Integer> live = new HashSet<>();
		for (Player player : World.getInstance().getPlayers())
		{
			final Summon summon = player.getSummon();
			if ((summon != null) && summon.isPet())
			{
				live.add(Integer.valueOf(summon.getObjectId()));
			}
		}
		LAST_CAST.keySet().removeIf(id -> !live.contains(id));
		LAST_SKILL_CAST.keySet().removeIf(id -> !live.contains(id));
	}

	/**
	 * Keeps a tame that is already fighting from going idle after a couple of swings.
	 *
	 * <p>A tamed beast stops attacking after two or three hits and has to be ordered to
	 * attack again. The cause is in the core's SummonAI, not here: its think loop is
	 * only re-armed by a ready-to-act notification that a melee attack schedules for
	 * itself, and {@code thinkAttack} returns without scheduling the next one whenever
	 * the beast has to step to its target. Once that one notification is missed the
	 * intention is still ATTACK but nothing drives it any more, so the beast stands
	 * there until the player presses attack again - which is what re-arms it.
	 *
	 * <p>The server already has a fix for exactly this shape of problem: its phantom
	 * players re-assert an attack every tick instead of trusting the loop to survive
	 * (PhantomCombatActions.maintainAttack). This does the same, on the ticker this
	 * class already runs. It is deliberately conservative about what it will touch:
	 *
	 * <ul>
	 *   <li>It only acts while the beast has an actual AI attack target. An explicit
	 *   stop clears that target (SummonAI.onIntentionIdle), so this can never
	 *   resurrect a fight the player called off, and it never starts a new one.</li>
	 *   <li>It does nothing while the beast is casting, attacking or disabled, so it
	 *   cannot interrupt one of the module's own casts.</li>
	 * </ul>
	 */
	private static void maintainAttack(Player player, Summon summon)
	{
		if (summon.isDead() || summon.isDisabled() || summon.isCastingNow() || summon.isAttackingNow())
		{
			return;
		}
		final CreatureAI ai = summon.getAI();
		final Creature target = ai.getAttackTarget();
		if ((target == null) || target.isAlikeDead() || (target == summon) || (target == player) || !target.canBeAttacked())
		{
			return;
		}
		if (ai.getIntention() == Intention.ATTACK)
		{
			// Still pointed at the target but the loop stalled: nudge the think step.
			ai.notifyAction(Action.THINK);
		}
		else
		{
			// The intention drifted off while the target survived: re-assert it.
			ai.setIntention(Intention.ATTACK, target);
		}
	}

	private static void castFor(Player player)
	{
		if ((player == null) || !player.isOnline() || !player.hasSummon())
		{
			return;
		}
		final Summon summon = player.getSummon();
		if ((summon == null) || !summon.isPet() || summon.isDead())
		{
			return;
		}
		maintainAttack(player, summon);
		if (!isEnabledFor(player.getObjectId()) || summon.isCastingNow())
		{
			return;
		}
		final TameProfile profile = TameProfileRepository.load(player.getObjectId(), summon.getControlObjectId());
		if (profile == null)
		{
			return;
		}
		// The gap. A beast technique deck is usually one damage skill, a couple of
		// debuffs and a buff, and none of them carry a reuse delay, so without this
		// the buff went off once a tick and never stopped - which is exactly what it
		// did on the first test.
		final long now = System.currentTimeMillis();
		final Integer tameId = Integer.valueOf(summon.getObjectId());
		final Long last = LAST_CAST.get(tameId);
		if ((last != null) && ((now - last.longValue()) < minGapMs))
		{
			return;
		}
		// The live level, so a technique that unlocked while the tame was already out
		// is usable immediately.
		final List<TameProfileRepository.DeckSkill> deck = TameSkillBar.resolveAll(profile, summon.getLevel());
		if (deck.isEmpty())
		{
			return;
		}
		final int size = deck.size();
		// One rotation step per tick, not per candidate.
		//
		// This used to call getAndIncrement() inside the collection loop, which
		// advanced the counter once for every deck row examined - so the "rotation"
		// moved by size each tick, and by a different amount each tick as skills
		// became available or not. Every row is still visited exactly once either
		// way, so the set of candidates was never wrong, but the offset it applied
		// was not the thing the name claimed.
		//
		// It is a tie-breaker and nothing more. ready.sort below is stable, so when
		// two candidates share a priority band and a cast time the one that entered
		// the list first wins; rotating the scan is what stops the same technique
		// from taking every exact tie and starving the other.
		final int offset = Math.floorMod(ROTATION.getAndIncrement(), size);
		// Collect everything castable right now, each with the creature it would be
		// aimed at, then try them fastest-first. Order matters more than it looks: a
		// beast debuff with a long cast time lands on a moving target or not at all,
		// so the instant techniques get first refusal while the target is still there.
		final List<Candidate> ready = new ArrayList<>(size);
		for (int i = 0; i < size; i++)
		{
			final TameProfileRepository.DeckSkill row = deck.get((offset + i) % size);
			final Skill skill = SkillData.getInstance().getSkill(TameSkillPolicy.petSkillId(row.getSkillId()), row.getSkillLevel());
			if ((skill == null) || skill.isPassive() || summon.isSkillDisabled(skill))
			{
				continue;
			}
			// The beast's own throttle for this technique. isSkillDisabled above only
			// covers a real reuse delay, which beast skills almost never have, so without
			// this the same skill is eligible every single gap. Healing is exempt: an
			// emergency heal must never be held back by a rotation timer.
			if (!isHealing(skill) && onSkillCooldown(tameId, skill.getId(), now))
			{
				continue;
			}
			// A melee beast should close on its target and fight, not stand at the edge
			// of a technique's reach and fire while walking - which is exactly what made
			// it look like it stuttered: cast, step in, cast, step in. An offensive
			// technique therefore waits until the beast has stopped at its target.
			// Support (a buff or a heal) is never gated on movement.
			if (meleeFirst && isOffensive(skill) && summon.isMoving())
			{
				continue;
			}
			if (!(summon.getCurrentHp() > skill.getHpConsume()))
			{
				continue;
			}
			if (summon.getCurrentMp() < (summon.getStat().getMpConsume(skill) + summon.getStat().getMpInitialConsume(skill)))
			{
				continue;
			}
			final Creature target = chooseTarget(player, summon, skill);
			if (target == null)
			{
				continue;
			}
			// Never re-apply something already up. A live buff is refreshed by
			// useMagic and does nothing, so casting it again only burns the tame's MP
			// and makes the technique look broken.
			if (alreadyApplied(player, summon, target, skill))
			{
				continue;
			}
			ready.add(new Candidate(skill, target));
		}
		ready.sort(Comparator.<Candidate>comparingInt(TameAutoCast::priority).thenComparingInt(candidate -> candidate.skill.getHitTime()));
		for (Candidate candidate : ready)
		{
			if (!inCastRange(summon, candidate.target, candidate.skill))
			{
				continue;
			}
			// chooseTarget() returns the owner for anything that is not damage or a debuff,
			// which is right for aiming a cast but must never be left in summon.setTarget():
			// an owner is not something a pet can attack, so the AI would drop the ATTACK
			// intention and fall back to following. That was the "walks up to a mob, attacks,
			// then walks straight back" bug. The offensive target is left in place (it is the
			// mark the beast is already fighting); the support target is set only for the
			// duration of the cast and restored afterwards.
			final boolean offensive = isOffensive(candidate.skill);
			// Summon.useMagic (and the Creature.doCast it drives in the same call) resolves the
			// real cast target from the skill's TargetType, and for the common ONE/TARGET kinds
			// from summon.getTarget() - synchronously, before useMagic returns. An offensive
			// technique wants exactly that. Support used to be left alone on the theory its
			// TargetType picks the owner by itself, which is only true for SELF/OWNER_PET/PARTY:
			// a plain ONE buff resolved from getTarget() instead, so a fighting tame buffed the
			// mob it was hitting. Aim the support cast at its intended target for the call, then
			// put the mark back so the beast's attack target is unchanged.
			final WorldObject previousTarget = summon.getTarget();
			summon.setTarget(candidate.target);
			final boolean cast;
			try
			{
				cast = summon.useMagic(candidate.skill, false, false);
			}
			finally
			{
				if (!offensive)
				{
					summon.setTarget(previousTarget);
				}
			}
			if (cast)
			{
				LAST_CAST.put(tameId, Long.valueOf(now));
				markSkillCast(tameId, candidate.skill.getId(), now);
				// One cast per tick, so a cheap deck cannot machine-gun.
				return;
			}
		}
	}

	/**
	 * Whether this tame used this technique too recently for it to be cast again. A
	 * disabled cooldown ({@code <= 0}) or a tame that has never cast it both answer no.
	 */
	private static boolean onSkillCooldown(Integer tameId, int skillId, long now)
	{
		if (skillCooldownMs <= 0)
		{
			return false;
		}
		final Map<Integer, Long> perSkill = LAST_SKILL_CAST.get(tameId);
		if (perSkill == null)
		{
			return false;
		}
		final Long seen = perSkill.get(Integer.valueOf(skillId));
		return (seen != null) && ((now - seen.longValue()) < skillCooldownMs);
	}

	private static void markSkillCast(Integer tameId, int skillId, long now)
	{
		LAST_SKILL_CAST.computeIfAbsent(tameId, key -> new ConcurrentHashMap<>()).put(Integer.valueOf(skillId), Long.valueOf(now));
	}

	/**
	 * Whether a technique is aimed at an enemy: damage, a debuff, or anything carrying a
	 * debuff effect. One predicate, shared by the target choice, the priority order, the
	 * movement gate and the target-restore after a cast, so those four can never disagree
	 * about which techniques are offensive.
	 */
	private static boolean isOffensive(Skill skill)
	{
		return skill.isDamage() || skill.isDebuff() || skill.hasEffectType(EffectType.DEBUFF);
	}

	/**
	 * Whether a technique heals. Heals are the one class of skill the rotation treats as
	 * always-urgent: they ignore the per-technique cooldown, though they still resolve
	 * onto whichever of the beast and its owner is hurt worse.
	 */
	private static boolean isHealing(Skill skill)
	{
		return skill.hasEffectType(EffectType.HEAL);
	}

	/**
	 * Which technique gets the tick, when several are ready.
	 *
	 * <p>A heal first, then damage, then debuffs, then everything else. This was
	 * originally ordered purely by cast time, on the theory that the fastest thing to
	 * land was the best thing to cast - and because a beast debuff is usually faster
	 * than a beast attack, a tame reliably opened every fight with the debuff and left
	 * the damage skill sitting on cooldown. Sorting by cast time was optimising the
	 * wrong quantity: the question is not "what lands soonest" but "what is worth doing
	 * first", and that is a property of the skill's role, not its duration.
	 *
	 * <p>Cast time only breaks ties within a band, where it still does the useful job
	 * of getting an instant variant ahead of a slow one of the same kind.
	 *
	 * <p>A damage skill that also carries a negative effect - a boss technique like
	 * 4194, with a positive power and a negative effectPoint - is a damage skill, and
	 * is deliberately checked first so it lands in the damage band.
	 */
	private static int priority(Candidate candidate)
	{
		final Skill skill = candidate.skill;
		// A heal only ever reaches this point when an ally is actually below the
		// threshold (chooseTarget refuses it otherwise), so when one is ready it is the
		// single most valuable thing the beast can do and it goes first - a dying owner
		// outranks any amount of damage.
		if (isHealing(skill))
		{
			return 0;
		}
		if (skill.isDamage())
		{
			return 1;
		}
		if (isOffensive(skill))
		{
			return 2;
		}
		// Everything else is support: a buff, a self-buff, a summon. Last, so the beast
		// fights with its offensive deck first and only spends a tick on upkeep when
		// there is nothing better to do.
		return 3;
	}

	/**
	 * One technique that could be cast this tick, and where at.
	 */
	private static final class Candidate
	{
		private final Skill skill;
		private final Creature target;

		private Candidate(Skill skill, Creature target)
		{
			this.skill = skill;
			this.target = target;
		}
	}

	/**
	 * Whether the target is close enough for the cast to even begin.
	 *
	 * <p>{@code useMagic} resolves and refuses an out-of-range target by itself, but
	 * silently, and a refused cast still costs this tick's one attempt - so a tame
	 * chasing something across the map would spend every gap trying to reach it and
	 * never land anything. Checked up front instead.
	 *
	 * <p>The slack is deliberate. Both are moving between ticks, so a strict
	 * {@code castRange} test rejects a target that is closing in and would have been
	 * in range a moment later.
	 */
	private static boolean inCastRange(Summon summon, Creature target, Skill skill)
	{
		final int castRange = skill.getCastRange();
		if (castRange <= 0)
		{
			return true;
		}
		return summon.calculateDistance3D(target.getX(), target.getY(), target.getZ()) <= (castRange + 300);
	}

	/**
	 * Whether the beast already has this technique running.
	 *
	 * <p>Reads the live effect list rather than tracking what this class cast, so it
	 * is equally correct for a buff the player applied themselves, landed from an
	 * item, or is already there from an earlier cast before this module ran.
	 *
	 * <p>Both the aim and the beast are checked. A support technique is aimed at the
	 * owner, but a pet skill carrying {@code isTargetSelf()} still resolves onto the
	 * creature doing the casting - so the buff visibly landed while the owner, the only
	 * place that was being searched, never showed it. The effect list came back empty
	 * every time, the buff was treated as new, and the beast recast it on every gap for
	 * as long as it had MP. That is the non-stop buff animation.
	 *
	 * @param owner the player, used to reach the summon
	 */
	private static boolean alreadyApplied(Player owner, Summon summon, Creature target, Skill skill)
	{
		if (applied(target, skill))
		{
			return true;
		}
		return (summon != target) && applied(summon, skill);
	}

	private static boolean applied(Creature target, Skill skill)
	{
		if (target == null)
		{
			return false;
		}
		for (BuffInfo applied : target.getEffectList().getEffects())
		{
			final Skill onTarget = applied.getSkill();
			if ((onTarget != null) && (onTarget.getId() == skill.getId()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Where the technique should be aimed.
	 *
	 * <p>Offensive techniques follow whatever the tame is already fighting - that is
	 * its own AI target, so nothing here decides who to attack, and the peace zone
	 * and attackability checks keep a tame in town from swinging at a player. Support
	 * techniques are aimed at the owner, matching the collar's own rule that a
	 * non-damaging technique may not inherit an arbitrary target.
	 */
	private static Creature chooseTarget(Player owner, Summon summon, Skill skill)
	{
		// A heal belongs on whoever is actually hurt - the beast or its owner - and not
		// at all when neither is below the threshold. This is the one case where the
		// target is decided by need rather than by role, and it is what stops a support
		// tame from wasting a heal on a full-health owner while its own HP drains, or
		// from topping up nobody at all.
		if (skill.hasEffectType(EffectType.HEAL))
		{
			return healTarget(owner, summon);
		}
		if (skill.isDamage() || skill.isDebuff() || skill.hasEffectType(EffectType.DEBUFF))
		{
			final WorldObject enemy = summon.getTarget();
			// A summon only ever holds creatures as a target, but the getter is typed
			// WorldObject, so this has to be checked rather than assumed.
			if (!(enemy instanceof Creature))
			{
				return null;
			}
			final Creature mark = (Creature) enemy;
			// The dead check is the one that was missing: after a kill the tame's AI
			// target is still the corpse for a moment, and canBeAttacked() is still true
			// on a dead creature, so the tame kept trying to burn its techniques on a
			// body. canBeAttacked() answers "may I hit this", never "is it still alive".
			if (mark.isDead())
			{
				return null;
			}
			if ((mark == owner) || (mark == summon) || !mark.canBeAttacked())
			{
				return null;
			}
			// The same two-WorldObject form Summon.useMagic uses, so the tame is held
			// to exactly the peace-zone rule the real cast would apply. A refusal there
			// is silent in the core, which is precisely why it is worth refusing here.
			if (summon.isInsidePeaceZone(summon, mark))
			{
				return null;
			}
			return mark;
		}
		return owner;
	}

	/**
	 * The ally a heal should land on: whichever of the beast and its owner is hurt worse,
	 * or null when neither is below {@link #healThreshold}.
	 *
	 * <p>Compared by health <em>fraction</em>, not by points, so a 200-of-300 beast is
	 * treated as more urgent than a 900-of-1000 owner. Ties go to the owner, which is the
	 * creature the player actually watches.
	 */
	private static Creature healTarget(Player owner, Summon summon)
	{
		final double ownerFraction = healthFraction(owner);
		final double summonFraction = healthFraction(summon);
		if (summonFraction < ownerFraction)
		{
			return (summonFraction < healThreshold) ? summon : null;
		}
		return (ownerFraction < healThreshold) ? owner : null;
	}

	private static double healthFraction(Creature creature)
	{
		if ((creature == null) || creature.isDead())
		{
			return 1.0;
		}
		final double max = creature.getMaxHp();
		return (max <= 0.0) ? 1.0 : (creature.getCurrentHp() / max);
	}
}
