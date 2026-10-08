/*
 * TameVisual.java
 *
 * Gives a collar-bound beast a look that belongs to it, without touching the client.
 *
 * The question this answers is "can the module change how a tame looks without a
 * patch", and the answer is yes, within one limit. The client's name colour for an
 * NPC is not in any packet this server sends: PetInfo carries the name as a plain
 * string, so there is no server-side colour field to set. What the client does take
 * from the server is an abnormal visual effect, a bitmask on the creature that the
 * core puts into the summon info packet and the client plays as a particle effect on
 * the model. That is a real, visible, persistent change and it needs no client work.
 *
 * So the look of a tame is carried by an aura rather than by a coloured name, and
 * the collar page carries the colour where HTML can do it.
 *
 * Which effect is which is configuration, not code, because it cannot be chosen by
 * reasoning. The enum is named for what the skill data uses each effect for, not for
 * how it renders, and only someone with the client on screen can say which ones look
 * right on a given beast. The first attempt at guessing proved the point: FIRE,
 * WATER and WIND came out fine, while HOLY drew a large circle pinned to the ground
 * that the beast walked out of and EARTH drew nothing at all. So the names live in
 * module.ini and changing one is an edit and a restart, not a recompile.
 */
package taming;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.Summon;
import org.l2jmobius.gameserver.model.skill.AbnormalVisualEffect;

/**
 * Chooses and applies the visual identity of a tame.
 */
public final class TameVisual
{
	private static final Logger LOGGER = Logger.getLogger(TameVisual.class.getName());

	/**
	 * Every affinity this module has an opinion about, in the order the defaults are
	 * listed in module.ini.
	 */
	private static final String[] AFFINITIES = { "FIRE", "EARTH", "WATER", "WIND", "HOLY", "DARK" };

	/**
	 * A deliberately conservative default: the effects in the DOT family are drawn
	 * attached to the creature rather than anchored to a spot in the world, which is
	 * what the first live test confirmed for DOT_FIRE_AREA, SEIZURE1 and SEIZURE2.
	 */
	private static final Map<String, AbnormalVisualEffect> DEFAULTS = defaults();

	private static final AbnormalVisualEffect DEFAULT_AWAKENED = AbnormalVisualEffect.VP_UP;

	/**
	 * Set once at startup from {@code TameVisual} in module.ini. Off leaves every
	 * beast with no aura at all, which is the escape hatch for a creature model that
	 * draws badly under one of these effects.
	 */
	private static boolean _enabled = true;

	private static Map<String, AbnormalVisualEffect> _effects = DEFAULTS;

	private static AbnormalVisualEffect _awakened = DEFAULT_AWAKENED;

	/**
	 * Added on top once a beast has reached the second awakening, replacing nothing.
	 *
	 * <p>Null until configured. BIG_BODY is the first awakening's overlay and SLEEP is the
	 * second's, chosen on the live client, and a beast at stage two wears both because
	 * each stage is a thing that happened rather than a state that replaced the last.
	 */
	private static AbnormalVisualEffect _awakenedSecond;

	/**
	 * Added for a RARE or EPIC beast, and for a LEGENDARY one. Null until configured,
	 * which is the shipped state.
	 */
	private static AbnormalVisualEffect _rarityRare;

	private static AbnormalVisualEffect _rarityLegendary;

	/**
	 * A one-off effect layered on for auditioning, set by the admin command and
	 * cleared by dismissing the beast, dismissing the player, or rebooting.
	 *
	 * <p>It exists because the only honest way to choose an aura is to look at it,
	 * and the config route costs a reboot per candidate. With only 57 possible values
	 * and six affinities to fill, auditioning is the difference between one coffee
	 * break and an evening of reboots.
	 *
	 * <p>Global rather than per admin on purpose: the candidate list is short, one
	 * person auditioning at a time is the realistic case, and a per-player map would
	 * have to be cleaned up on logout and on module reload to avoid leaking a static
	 * reference to a Player. It is a diagnostic, not gameplay state.
	 */
	private static AbnormalVisualEffect _preview;

	private TameVisual()
	{
	}

	private static Map<String, AbnormalVisualEffect> defaults()
	{
		final Map<String, AbnormalVisualEffect> map = new LinkedHashMap<>();
		// ROOT, not DOT_FIRE_AREA. The fire aura was switched to the rooted effect
		// because it reads as a hold rather than a burn, and it is one of the few
		// effects known to sit on the creature instead of pinning a shape to the floor
		// and staying behind as the beast walks off. HOLY already uses DANCE_ROOT, so
		// ROOT is proven to render here - and if it ever stops rendering, DOT_FIRE_AREA
		// is the fallback a dot is known to keep.
		map.put("FIRE", AbnormalVisualEffect.ROOT);
		// FROZEN_PILLAR was the first guess here and drew nothing at all.
		map.put("EARTH", AbnormalVisualEffect.DOT_SOIL);
		map.put("WATER", AbnormalVisualEffect.SEIZURE1);
		map.put("WIND", AbnormalVisualEffect.SEIZURE2);
		// MAGIC_SQUARE and GHOST_STUN were the first guesses for these two and both
		// drew a shape pinned to the ground that the beast walked away from.
		//
		// The live test then ruled out two more, and both for the same reason the
		// earlier ones failed - ULTIMATE_DEFENCE drew nothing at all on an arbitrary
		// beast model, and DOT_BLEEDING was confirmed to render as bleeding, which
		// is not what a dark beast should look like it is doing to you. A name in the
		// enum only says which skill data uses the effect, not what it draws.
		//
		// So these are picked by what the effect is FOR rather than by what it is
		// called. MP_SHIELD is the protective-glow visual and is the closest thing
		// to a buff that does not also draw a promise - INVINCIBILITY and VP_KEEP
		// were excluded for exactly that reason, and module.ini says so. SPEED_DOWN
		// is the one the weakness family uses, which is what dark affinity wants,
		// and DOT_BLEEDING went because it genuinely rendered as bleeding.
		//
		// Neither is confirmed on screen. The only family known to follow the
		// creature is DOT_* and SEIZURE, so if the shield glow also turns out to be
		// pinned to the ground, DOT_FIRE is the fallback that is known to travel.
		map.put("HOLY", AbnormalVisualEffect.INVINCIBILITY);
		// FLESH_STUN was tried for DARK and rejected on the live client: it draws a
		// petrified figure and freezes the beast's animation, so the model appears to
		// slide along the ground instead of walking. That is the effect doing what it
		// is for - FLESH_STONE is the flesh-to-stone line, and a stun pose on a moving
		// creature will always read as sliding - so there is no way to keep the look
		// and drop the stutter.
		//
		// POISON is chosen instead because it is in the DOT family, which is the only
		// family confirmed to be drawn attached to the creature rather than pinned to a
		// spot in the world, and because a venom overlay reads as dark without drawing a
		// wound the way BLEEDING was found to. Not confirmed on screen yet.
		map.put("DARK", AbnormalVisualEffect.DOT_POISON);
		return map;
	}

	/**
	 * Affinities whose effect is re-anchored on a timer so it follows the beast.
	 *
	 * <p>Empty unless module.ini asks for it. The reason this exists at all is that the
	 * abnormal visual effects ride out in the summon info packet as a bitmask, and the
	 * client draws the ground-anchored family at the coordinates the creature was
	 * standing when the bit arrived. The beast then walks off and the picture stays put.
	 * No amount of setting the same bit again moves it, because the core compares the
	 * mask before and after and skips the packet entirely when nothing changed - so
	 * re-applying on a timer is not merely wasteful, it provably does nothing.
	 *
	 * <p>The only thing that does work is clearing the bit and setting it again, which
	 * makes the client tear the old picture down and build a new one at wherever the
	 * creature is standing now. That is a stop followed by a start, and it costs two
	 * packets to everyone who can see the beast, so the interval is a real setting
	 * rather than something to make as small as possible - see {@link #startFollow}.
	 */
	private static final Set<String> FOLLOW = Collections.synchronizedSet(new HashSet<>());

	/** Summon object id to the effect being followed. Filled by {@link #apply}. */
	private static final Map<Integer, AbnormalVisualEffect> FOLLOWING = new ConcurrentHashMap<>();

	/** Null until follow is switched on. */
	private static volatile ScheduledExecutorService followExecutor;

	/**
	 * Whether auras are put back if something clears them.
	 *
	 * <p>Set from {@code AffinityPermanent} in module.ini. There is no version of this
	 * that makes a bit unremovable - see {@link #guardTick} - so the honest claim is
	 * only that a cleared aura comes back within one guard interval.
	 */
	private static boolean _permanent = true;

	/** Null until the guard is started. */
	private static volatile ScheduledExecutorService guardExecutor;

	/**
	 * How often a cleared aura is put back.
	 *
	 * <p>The guard is cheap when nothing has happened: it calls
	 * {@code hasAbnormalVisualEffect}, and the bit is still there, so no packet is built.
	 * The core's dirty check means a no-op tick costs nothing on the wire, which is what
	 * makes a one-second poll affordable rather than a flood.
	 */
	private static long guardMs = 1000;

	/**
	 * Re-applies any configured aura that something cleared.
	 *
	 * <p>What this cannot do is make a bit unremovable, and it is worth being blunt about
	 * why. The mask is a plain OR of bits with no owner recorded against them, so
	 * {@code stopAbnormalVisualEffect} clears a bit whoever set it. A skill that draws
	 * the same effect - SEIZURE1 and ROOT both appear in real skill data - will take the
	 * aura off when it ends, and there is no flag on the call that says "not this one".
	 *
	 * <p>So this is a self-heal, not immunity: the aura is off for up to one interval and
	 * then back. What it does buy is that a dispel, a death, a skill expiring or a
	 * reboot cannot leave a beast permanently naked.
	 *
	 * <p>Deliberately built on {@code effectsFor} rather than on a cached copy, so a
	 * beast that has since awakened gains its new overlays without needing an apply pass
	 * to happen to coincide.
	 */
	private static void guardTick()
	{
		List<Player> players;
		try
		{
			// Copied, not iterated live: the collection changes under us on a logout.
			players = new ArrayList<>(World.getInstance().getPlayers());
		}
		catch (RuntimeException e)
		{
			LOGGER.log(Level.FINE, "aura guard could not read the player list", e);
			return;
		}
		for (final Player player : players)
		{
			try
			{
				guardOne(player);
			}
			catch (RuntimeException e)
			{
				// One misbehaving tame must not silence every other one.
				LOGGER.log(Level.FINE, "aura guard skipped a tame", e);
			}
		}
	}

	private static void guardOne(Player player)
	{
		final Summon summon = player.getSummon();
		if ((summon == null) || !summon.isPet())
		{
			return;
		}
		// effectsFor is a pure function of the profile, so this is where the profile
		// comes from. A database read per player per second is the cost of the self-heal
		// and the reason the interval is not shorter; the alternative is a cached copy
		// that goes stale the moment a beast awakens and nobody notices until it is
		// redressed by hand.
		final TameProfile profile = TameProfileRepository.load(player.getObjectId(), summon.getObjectId());
		if (profile == null)
		{
			// Not a collared tame, or its row is gone. Either way it is not ours to
			// dress, and it was not wearing an aura from us to begin with.
			return;
		}
		final List<AbnormalVisualEffect> wanted = effectsFor(profile);
		if (wanted.isEmpty())
		{
			return;
		}
		for (final AbnormalVisualEffect effect : wanted)
		{
			if (!summon.hasAbnormalVisualEffect(effect))
			{
				// Only the missing one, so a beast that legitimately has some other
				// effect cleared is not restacked with everything it should be wearing.
				summon.startAbnormalVisualEffect(true, effect);
			}
		}
	}

	/**
	 * Switches the self-heal on or off.
	 *
	 * @param intervalMs gap between checks, floored at {@link #GUARD_FLOOR_MS}
	 */
	public static void configurePermanent(boolean value, long intervalMs)
	{
		_permanent = value;
		if (!value)
		{
			stopGuard();
			return;
		}
		startGuard(Math.max(GUARD_FLOOR_MS, intervalMs));
	}

	/**
	 * The floor on the guard interval, which exists because the guard reads the
	 * database once per player per tick.
	 */
	private static final long GUARD_FLOOR_MS = 500;

	private static void startGuard(long intervalMs)
	{
		if (guardExecutor != null)
		{
			return;
		}
		final ScheduledExecutorService running = Executors.newSingleThreadScheduledExecutor(runnable ->
		{
			// Daemon, as everywhere else in this module.
			final Thread thread = new Thread(runnable, "taming-aura-guard");
			thread.setDaemon(true);
			return thread;
		});
		running.scheduleWithFixedDelay(TameVisual::guardTick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
		guardExecutor = running;
		guardMs = intervalMs;
		LOGGER.info("aura self-heal is on, re-applying cleared auras every " + intervalMs + "ms");
	}

	private static void stopGuard()
	{
		final ScheduledExecutorService running = guardExecutor;
		guardExecutor = null;
		if (running != null)
		{
			running.shutdownNow();
		}
	}

	/**
	 * Points the follow ticker at a set of affinities.
	 *
	 * @param names        comma-separated affinity names, or empty to stop following
	 * @param intervalMs   gap between re-anchors, floored at {@link #FOLLOW_FLOOR_MS}
	 */
	public static void configureFollow(String names, long intervalMs)
	{
		FOLLOW.clear();
		if ((names != null) && !names.trim().isEmpty())
		{
			for (final String token : names.split(","))
			{
				final String affinity = token.trim().toUpperCase(Locale.ROOT);
				if (!affinity.isEmpty())
				{
					FOLLOW.add(affinity);
				}
			}
		}
		if (FOLLOW.isEmpty())
		{
			stopFollow();
			return;
		}
		startFollow(Math.max(FOLLOW_FLOOR_MS, intervalMs));
	}

	/**
	 * The floor on the follow interval, and the reason there is one.
	 *
	 * <p>Every tick clears the bit and sets it again, so the client destroys and rebuilds
	 * the effect each time. Below roughly this rate the rebuild lands on top of the last
	 * one often enough that the effect visibly stutters, and the two packets per viewer
	 * per tick stop being free. There is no setting that makes this free; this is the
	 * point where it stops getting worse.
	 */
	private static final long FOLLOW_FLOOR_MS = 150;

	private static void startFollow(long intervalMs)
	{
		if (followExecutor != null)
		{
			return;
		}
		final ScheduledExecutorService running = Executors.newSingleThreadScheduledExecutor(runnable ->
		{
			// Daemon, as everywhere else in this module.
			final Thread thread = new Thread(runnable, "taming-aura-follow");
			thread.setDaemon(true);
			return thread;
		});
		running.scheduleWithFixedDelay(TameVisual::followTick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
		followExecutor = running;
		LOGGER.info("aura follow is on for " + FOLLOW + ", re-anchoring every " + intervalMs + "ms");
	}

	/**
	 * Stops the ticker. Registered beasts keep whatever bit they currently hold; only the
	 * re-anchoring stops, so a pet already wearing a followed aura keeps it until the
	 * next apply or a restart.
	 */
	public static void stopFollow()
	{
		final ScheduledExecutorService running = followExecutor;
		followExecutor = null;
		if (running != null)
		{
			running.shutdownNow();
		}
	}

	private static void followTick()
	{
		if (FOLLOWING.isEmpty())
		{
			return;
		}
		for (final Map.Entry<Integer, AbnormalVisualEffect> entry : FOLLOWING.entrySet())
		{
			final WorldObject object = World.getInstance().findObject(entry.getKey().intValue());
			if (!(object instanceof Creature))
			{
				// Dismissed, died or rebooted out from under us. Keeping the id would
				// grow the map for the life of the server.
				FOLLOWING.remove(entry.getKey());
				continue;
			}
			final AbnormalVisualEffect effect = entry.getValue();
			// Stop then start, in that order and both unconditional. Clearing first is
			// the whole mechanism: while the bit is already set the core compares the
			// mask, sees no change and sends nothing, so the picture never moves. The
			// client only re-anchors when it has been told to take the effect down first.
			((Creature) object).stopAbnormalVisualEffect(true, effect);
			((Creature) object).startAbnormalVisualEffect(true, effect);
		}
	}

	/**
	 * Turns the aura on or off for the whole module.
	 */
	public static void setEnabled(boolean value)
	{
		_enabled = value;
	}

	/**
	 * @return whether the aura is being applied
	 */
	public static boolean isEnabled()
	{
		return _enabled;
	}

	/**
	 * Points each affinity at an effect by name.
	 *
	 * <p>A name that is not a real {@link AbnormalVisualEffect} is logged and the
	 * default is kept, so a typo in module.ini costs one affinity its aura instead of
	 * stopping the module from loading. An empty name or {@code NONE} means that
	 * affinity deliberately has no aura.
	 *
	 * @param names        affinity name to effect name
	 * @param awakenedName the effect an awakened beast adds, or empty for none
	 */
	public static void configure(Map<String, String> names, String awakenedName, String secondName)
	{
		final Map<String, AbnormalVisualEffect> resolved = new LinkedHashMap<>();
		for (String affinity : AFFINITIES)
		{
			final String configured = (names == null) ? null : names.get(affinity);
			final AbnormalVisualEffect fallback = DEFAULTS.get(affinity);
			if ((configured == null) || configured.trim().isEmpty() || "NONE".equalsIgnoreCase(configured.trim()))
			{
				resolved.put(affinity, null);
				continue;
			}
			final AbnormalVisualEffect effect = parse(configured, affinity);
			resolved.put(affinity, (effect == null) ? fallback : effect);
		}
		_effects = resolved;

		if ((awakenedName == null) || awakenedName.trim().isEmpty() || "NONE".equalsIgnoreCase(awakenedName.trim()))
		{
			_awakened = null;
		}
		else
		{
			final AbnormalVisualEffect effect = parse(awakenedName, "AWAKENED");
			_awakened = (effect == null) ? DEFAULT_AWAKENED : effect;
		}

		// Second awakening is configured apart from the first because it is a different
		// stage, not a bigger version of the same one. A single setting cannot express
		// "one overlay at stage one, a different one at stage two" - it can only pick
		// one look for every awakened beast, which is what it did until now.
		if ((secondName == null) || secondName.trim().isEmpty() || "NONE".equalsIgnoreCase(secondName.trim()))
		{
			_awakenedSecond = null;
		}
		else
		{
			_awakenedSecond = parse(secondName, "AWAKENED_SECOND");
		}
	}

	/**
	 * Configures the rarity auras, separately from the affinities because they are
	 * keyed on a different thing.
	 *
	 * <p>Both default to none. Layering is only worth having once there is more than
	 * one thing to layer, and right now the only second layer is the awakening one.
	 * Shipping a rarity aura nobody has looked at would be the same mistake as
	 * {@code ULTIMATE_DEFENCE} as holy: a name in the enum that draws nothing, or
	 * draws something wrong, on every rare beast in the game.
	 *
	 * @param rare      the effect a RARE or EPIC beast adds, or empty for none
	 * @param legendary the effect a LEGENDARY beast adds, or empty for none
	 */
	public static void configureRarity(String rare, String legendary)
	{
		_rarityRare = resolveOrNull(rare, "RARITY_RARE");
		_rarityLegendary = resolveOrNull(legendary, "RARITY_LEGENDARY");
	}

	/**
	 * Resolves an optional aura name, where absent and {@code NONE} both mean no
	 * aura and there is no sensible default to fall back to.
	 */
	private static AbnormalVisualEffect resolveOrNull(String name, String forWhom)
	{
		if ((name == null) || name.trim().isEmpty() || "NONE".equalsIgnoreCase(name.trim()))
		{
			return null;
		}
		return parse(name, forWhom);
	}

	private static AbnormalVisualEffect parse(String name, String forWhom)
	{
		try
		{
			return AbnormalVisualEffect.valueOf(name.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e)
		{
			LOGGER.warning("TameVisual: \"" + name.trim() + "\" is not an AbnormalVisualEffect, so " + forWhom + " falls back to its default");
			return null;
		}
	}

	/**
	 * The effects a beast with this identity should be showing.
	 *
	 * @param profile the tame's profile
	 * @return the effects to apply, empty for a beast with no affinity that has not
	 *         awakened
	 */
	public static List<AbnormalVisualEffect> effectsFor(TameProfile profile)
	{
		final List<AbnormalVisualEffect> effects = new ArrayList<>();
		if (profile == null)
		{
			return effects;
		}

		final AbnormalVisualEffect affinity = effectFor(TameIdentity.effectiveAffinity(profile));
		if (affinity != null)
		{
			effects.add(affinity);
		}
		final int stage = profile.getAwakeningStage();
		if ((stage > 0) && (_awakened != null))
		{
			effects.add(_awakened);
		}
		// Layered, not swapped. Both awakening overlays stay on at stage two, because the
		// second awakening is something that happened on top of the first rather than a
		// state that undid it, and a beast that loses its first-awakening look the moment
		// it grows stronger reads as a downgrade.
		if ((stage > 1) && (_awakenedSecond != null))
		{
			effects.add(_awakenedSecond);
		}
		final AbnormalVisualEffect rarity = rarityEffect(profile);
		if (rarity != null)
		{
			effects.add(rarity);
		}
		if (_preview != null)
		{
			// Appended rather than replacing, so auditioning a candidate can be judged
			// against what the beast is already wearing instead of in isolation.
			effects.add(_preview);
		}
		return effects;
	}

	/**
	 * The rarity aura this beast earns, or null.
	 *
	 * <p>LEGENDARY is checked first so a legendary beast gets the legendary effect
	 * rather than both. Whether that is right is a taste question - a legendary beast
	 * carrying both would read as more decorated rather than as rarer - but one effect
	 * per source is the rule, and it means a beast never stacks two copies of the
	 * same visual.
	 *
	 * <p>EPIC shares the rare effect on purpose. There are five rarity tiers and only
	 * two settings, and splitting EPIC off would mean three settings for an effect
	 * nobody has watched yet.
	 */
	private static AbnormalVisualEffect rarityEffect(TameProfile profile)
	{
		final String rarity = profile.getRarity();
		if (rarity == null)
		{
			return null;
		}
		if ("LEGENDARY".equalsIgnoreCase(rarity.trim()))
		{
			return _rarityLegendary;
		}
		if ("EPIC".equalsIgnoreCase(rarity.trim()) || "RARE".equalsIgnoreCase(rarity.trim()))
		{
			return _rarityRare;
		}
		// COMMON and UNCOMMON deliberately get nothing. Every beast in the game being
		// slightly decorated is the same as none of them being decorated.
		return null;
	}

	private static AbnormalVisualEffect effectFor(String affinity)
	{
		if (affinity == null)
		{
			return null;
		}
		return _effects.get(affinity.trim().toUpperCase(Locale.ROOT));
	}

	/**
	 * Layers one candidate effect onto a beast and re-applies immediately, so the
	 * admin does not have to dismiss and resummon to see the change.
	 *
	 * <p>Reuses {@link #apply} rather than poking the abnormal-effect list directly,
	 * because apply() is the only thing that knows how to clear what was there first.
	 * Calling startAbnormalVisualEffect twice without the clear would leave the
	 * previous audition stuck on the beast.
	 *
	 * @param summon the beast to dress
	 * @param profile its profile, needed because apply() reads the affinity from it
	 * @param effect the candidate, or null to take the audition off
	 * @return true if the beast was dressed
	 */
	public static boolean preview(Summon summon, TameProfile profile, AbnormalVisualEffect effect)
	{
		_preview = effect;
		if ((summon == null) || (profile == null))
		{
			return false;
		}
		apply(summon, profile);
		return true;
	}

	/**
	 * @return the effect currently being auditioned, or null if none
	 */
	public static AbnormalVisualEffect previewing()
	{
		return _preview;
	}

	/**
	 * Drops the audition without re-dressing anything.
	 *
	 * <p>Called on module unload. Leaving a static pointing at an enum is harmless on
	 * its own, but it would survive a reload as a stale candidate that nobody is
	 * looking at any more.
	 */
	public static void clearPreview()
	{
		_preview = null;
		// The registry has to go with the module, not with the preview: on a reload the
		// scheduler is shut down but the map would otherwise survive holding summon ids
		// from the previous run, and the first tick after the reload would try to dress
		// creatures that no longer exist.
		stopFollow();
		FOLLOWING.clear();
		// The guard goes too. Leaving it running across a reload would have it querying
		// the database for every player on the server against a configuration that is
		// being replaced underneath it.
		stopGuard();
	}

	/**
	 * A short, honest description of what the aura is, for the collar page.
	 *
	 * <p>It names the effect rather than claiming a look, because the effect is drawn
	 * by the client and this module cannot see it. "ULTIMATE_DEFENCE on the model" is
	 * a thing that can be checked; "a shimmering shield" is a promise.
	 *
	 * @param affinity the beast's affinity
	 * @param awakened whether it has awakened
	 */
	public static String effectName(String affinity, boolean awakened)
	{
		final AbnormalVisualEffect effect = effectFor(affinity);
		final String base = (effect == null) ? "none" : effect.name();
		if (!awakened)
		{
			return base;
		}
		final String extra = (_awakened == null) ? "none" : _awakened.name();
		return base + " plus " + extra;
	}

	/**
	 * The second awakening overlay on its own, for the collar page.
	 *
	 * @return the effect name, or none when nothing is configured or the beast has not
	 *         reached that stage
	 */
	public static String secondAwakeningEffectName(TameProfile profile)
	{
		if ((_awakenedSecond == null) || (profile == null) || (profile.getAwakeningStage() < 2))
		{
			return "none";
		}
		return _awakenedSecond.name();
	}

	/**
	 * Applies this tame's look to a live summon.
	 *
	 * <p>Every effect this module could have put on the creature is stopped first, so
	 * awakening a beast does not leave it wearing two auras and a rebuild does not
	 * stack copies of the same one. Anything a real skill put on the pet is left
	 * alone: this only clears the list this module could have used.
	 *
	 * <p>Call this after the creature has been broadcast into the world, otherwise
	 * clients that already know about the summon never receive the change and only
	 * players who happen to walk into range see the aura.
	 *
	 * @param summon the beast to dress
	 * @param profile its profile
	 */
	public static void apply(Summon summon, TameProfile profile)
	{
		if ((summon == null) || !_enabled)
		{
			return;
		}

		// The skill-aura route runs before the bitmask pass, and deliberately so. It is not
		// gated on there being a bitmask to draw: an affinity can be configured for a skill
		// aura and NONE for the effect, which is the entire reason the second route exists.
		// Placing this below the empty check would silently skip exactly the affinities
		// that need it, which is the case this was written for.
		TameSkillAura.apply(summon, profile);

		final List<AbnormalVisualEffect> wanted = effectsFor(profile);
		if (wanted.isEmpty())
		{
			return;
		}

		// Clear only what was previously applied, so a skill's own visual survives.
		final AbnormalVisualEffect[] owned = ownedEffects();
		if (owned.length > 0)
		{
			summon.stopAbnormalVisualEffect(true, owned);
		}

		final AbnormalVisualEffect[] toApply = wanted.toArray(new AbnormalVisualEffect[0]);
		// The flag is what the core uses for effects that outlive their own skill,
		// which is exactly this case: there is no skill and no buff to end it.
		summon.startAbnormalVisualEffect(true, toApply);

		// Remember the one followed effect for this beast, if any, so the ticker does not
		// have to work out the affinity again on every tick. Registered here rather than
		// derived per tick because the profile lives in the database and a table read
		// several times a second per player is not something to do for a cosmetic effect.
		final String affinity = TameIdentity.effectiveAffinity(profile);
		final AbnormalVisualEffect followed = effectFor(affinity);
		if ((followed != null) && FOLLOW.contains(affinity.trim().toUpperCase(Locale.ROOT)))
		{
			FOLLOWING.put(summon.getObjectId(), followed);
		}
		else
		{
			// Not a followed affinity any more, or the config changed under a beast that
			// was dressed earlier. Leaving the old entry would keep re-anchoring an aura
			// the module no longer owns.
			FOLLOWING.remove(summon.getObjectId());
		}
	}

	/**
	 * Every effect this module could have put on a beast, both the configured set and
	 * the shipped defaults, so the apply path can undo itself after a change in
	 * module.ini without needing to know which names were in force earlier.
	 *
	 * <p>Nulls are dropped rather than collected. An affinity configured to NONE
	 * resolves to null, and {@code String.valueOf} turns every one of those into the
	 * same key, so the map would happily end up holding a null value - which then
	 * reaches {@code stopAbnormalVisualEffect} and dies on {@code ave.isEvent()} the
	 * first time any beast is dressed. That is not hypothetical: a case-mismatched
	 * config key made all six affinities resolve to null at once and took the whole
	 * aura pass down with it.
	 */
	private static AbnormalVisualEffect[] ownedEffects()
	{
		final Map<String, AbnormalVisualEffect> all = new LinkedHashMap<>();
		for (AbnormalVisualEffect effect : DEFAULTS.values())
		{
			if (effect != null)
			{
				all.put(String.valueOf(effect), effect);
			}
		}
		for (AbnormalVisualEffect effect : _effects.values())
		{
			if (effect != null)
			{
				all.put(String.valueOf(effect), effect);
			}
		}
		if (_awakened != null)
		{
			all.put(String.valueOf(_awakened), _awakened);
		}
		// Same reasoning as the first awakening's: a beast dressed while a second
		// awakening overlay was configured has to be able to take it off again on a later
		// pass that no longer knows the name.
		if (_awakenedSecond != null)
		{
			all.put(String.valueOf(_awakenedSecond), _awakenedSecond);
		}
		all.put(String.valueOf(DEFAULT_AWAKENED), DEFAULT_AWAKENED);
		// The rarity auras belong in the undo set for the same reason the affinities do.
		// A beast that was dressed while a rarity aura was configured cannot be
		// undressed by a later pass that does not know the name, so the effect would
		// outlive every config change that tried to remove it and no beast would ever
		// come clean.
		if (_rarityRare != null)
		{
			all.put(String.valueOf(_rarityRare), _rarityRare);
		}
		if (_rarityLegendary != null)
		{
			all.put(String.valueOf(_rarityLegendary), _rarityLegendary);
		}
		if (_preview != null)
		{
			// So an audition can be taken off again by the same clear-then-apply pass.
			all.put(String.valueOf(_preview), _preview);
		}
		return all.values().toArray(new AbnormalVisualEffect[0]);
	}
}
