package modules.phantomencounters;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.managers.PhantomEncounterRules.Approach;
import org.l2jmobius.gameserver.managers.PhantomEncounterRules.Style;
import org.l2jmobius.gameserver.managers.PhantomBuffs;
import org.l2jmobius.gameserver.managers.PhantomManager.PartyRole;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.config.PvpConfig;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.npc.attackable.OnAttackableKill;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.modules.GameModule;
import org.l2jmobius.gameserver.modules.ModuleConfig;
import org.l2jmobius.gameserver.modules.ModuleContext;
import org.l2jmobius.gameserver.modules.ModuleEncounters;
import org.l2jmobius.gameserver.modules.ModuleEncounters.Group;

import modules.phantomencounters.EncounterPlanner.Kind;
import modules.phantomencounters.EncounterRoster.Pick;

/**
 * The Phantom Encounters module: PvP danger for a solo player. Every so often a phantom, or a group, comes for the
 * player in the open field and fights them once. This module is the whole feature: the director that decides when,
 * the five kinds, how strong they are, what they say, and what winning is worth. The platform only supplies the
 * mechanics (see {@link ModuleEncounters}). With the switch off, or the directory removed, the server is stock.
 */
public class PhantomEncountersModule implements GameModule
{
	private static final long START_DELAY_MS = 60_000;
	private static final long TICK_MS = 15_000;
	private static final long RETRY_MS = 120_000; // a failed attempt (no room, busy) tries again after this

	private static final PartyRole[] ROLES =
	{
		PartyRole.WARRIOR,
		PartyRole.WARRIOR,
		PartyRole.ARCHER,
		PartyRole.DAGGER,
		PartyRole.NUKER,
		PartyRole.MONK
	};

	private static final String[] WIN_LINES =
	{
		"gg",
		"ez",
		"lol rip",
		"gg wp"
	};

	private static final String[] KIND_KEYS =
	{
		"Wimp",
		"Normie",
		"Hard",
		"PkParty",
		"Horsemen",
		"Pker",
		"PveParty"
	};

	private static final String[] TAUNT_LINES =
	{
		"Wrong place to be alone.",
		"Nice gear. It'll look better on me.",
		"Don't run now, I just got here.",
		"You really thought you were safe out here?",
		"Free loot, boys.",
		"This is gonna be quick."
	};

	private static final Map<EncounterRoster.Theme, String[]> NOVELTY_LINES = new java.util.EnumMap<>(EncounterRoster.Theme.class);

	static
	{
		NOVELTY_LINES.put(EncounterRoster.Theme.ARCHERS, new String[] { "Arrow rain incoming!", "Hope you brought a shield.", "Archer squad, nobody gets close." });
		NOVELTY_LINES.put(EncounterRoster.Theme.MAGES, new String[] { "Fireworks time.", "Nobody out-nukes us.", "Mages only. Try to keep up." });
		NOVELTY_LINES.put(EncounterRoster.Theme.MELEE, new String[] { "Everybody on him!", "Hope you like getting hit.", "No healers, no casters, just fists and steel." });
		NOVELTY_LINES.put(EncounterRoster.Theme.TANKS, new String[] { "You can't kill what can't die.", "This is going to take you a while.", "We brought nothing but shields." });
		NOVELTY_LINES.put(EncounterRoster.Theme.DWARVES, new String[] { "Dwarves, together! Rise up!", "Short, rich and angry.", "We pooled our adena for this." });
		NOVELTY_LINES.put(EncounterRoster.Theme.ONE_CLASS, new String[] { "Class reunion!", "We all picked the same class. Deal with it.", "Matching builds. Very coordinated." });
	}

	private static final String[] ULTIMATUM_LINES =
	{
		"Wrong answer.",
		"We asked nicely. Last chance's gone.",
		"Fine, we'll take it the hard way."
	};

	private static final String[] DEFEAT_LINES =
	{
		"Lag. That was lag.",
		"My healer wasn't even looking.",
		"Whatever, you're probably botting.",
		"Cheap. You buffed before we even came.",
		"Get out of my sight. We'll be back.",
		"gg... I guess. Total garbage party anyway."
	};

	private static final String[] CONTEST_ASK_LINES =
	{
		"hey this is my spot",
		"bro i was farming here",
		"find your own spot",
		"u cant just take my farm",
		"this is my spot.. u a bot?",
		"get out of my farm"
	};

	private final Map<Integer, long[]> _nextAt = new ConcurrentHashMap<>(); // per player, one due-time per kind (0 = not running yet)
	private final Map<Integer, Long> _quietUntil = new ConcurrentHashMap<>(); // per player, no new encounter before this
	private final Map<Integer, Long> _contestReadyAt = new ConcurrentHashMap<>(); // per player, no new contest before this

	private Logger _log;
	private ModuleEncounters _encounters;
	private int _minPlayerLevel;
	private int _gapMinutes;
	private int _maxActive;
	private int _maxActors;
	private int _horsemenMinSize;
	private String _pkerName;
	private final int[] _minMinutes = new int[KIND_KEYS.length];
	private final int[] _maxMinutes = new int[KIND_KEYS.length];
	private final int[] _unlockLevel = new int[KIND_KEYS.length];
	private final int[] _levelMin = new int[KIND_KEYS.length];
	private final int[] _levelMax = new int[KIND_KEYS.length];
	private final int[] _enchantMin = new int[KIND_KEYS.length];
	private final int[] _enchantMax = new int[KIND_KEYS.length];
	private final int[] _adena = new int[KIND_KEYS.length];
	private final Style[] _styles = new Style[KIND_KEYS.length];
	private Style _pveAskStyle;
	private Style _pveAmbushStyle;
	private int _pkPartyMin;
	private int _pkPartyMax;
	private int _contestPvePercent;
	private int _pveAskPercent;
	private double _noveltyPercent;
	private boolean _pkerBag;
	private final Map<Integer, EncounterRoster.SoloBag> _soloBags = new ConcurrentHashMap<>(); // per player, the no-repeat class draw
	private int _redKarma;
	private int _redEscapePercent;
	private int _otherEscapePercent;
	private volatile boolean _contested;
	private volatile double _contestChance;
	private long _contestCooldownMs;

	@Override
	public void onEnable(ModuleContext context)
	{
		final ModuleConfig config = context.config();
		if (!config.getBoolean("Enabled", false))
		{
			return; // Switch off: register nothing, behave as stock.
		}

		_log = context.logging();
		_encounters = context.encounters();
		_minPlayerLevel = Math.max(1, config.getInt("MinPlayerLevel", 10));
		_gapMinutes = Math.max(0, config.getInt("GapMinutes", 5));
		_maxActive = Math.max(1, config.getInt("MaxActive", 2));
		_maxActors = Math.max(1, config.getInt("MaxActors", 12));
		_horsemenMinSize = Math.max(1, config.getInt("HorsemenMinSize", 4));
		_pkPartyMin = Math.max(2, config.getInt("PkPartyMinSize", 3));
		_pkPartyMax = Math.max(_pkPartyMin, config.getInt("PkPartyMaxSize", 6));
		_contestPvePercent = Math.max(0, Math.min(100, config.getInt("ContestedPvePercent", 70)));
		_pveAskPercent = Math.max(0, Math.min(100, config.getInt("PveAskPercent", 50)));
		_noveltyPercent = Math.max(0, Math.min(100, config.getDouble("NoveltyPartyPercent", 4.0)));
		_pkerBag = !config.getString("PkerClasses", "Bag").trim().equalsIgnoreCase("TitanDuelist");
		_pkerName = config.getString("PkerName", "AssMuncher").trim();
		_redKarma = Math.max(0, config.getInt("RedNameKarma", 500));
		_redEscapePercent = Math.max(0, Math.min(100, config.getInt("RedEscapePercent", 50)));
		_otherEscapePercent = Math.max(0, Math.min(100, config.getInt("OtherEscapePercent", 25)));

		final int[] minMinutesDefaults = { 30, 30, 120, 150, 270, 420, 0 };
		final int[] maxMinutesDefaults = { 40, 40, 180, 210, 330, 540, 0 };
		final int[] unlockDefaults = { 10, 10, 20, 20, 20, 20, 20 };
		final int[] levelMinDefaults = { -3, 0, 3, 1, 5, 11, 0 };
		final int[] levelMaxDefaults = { -2, 0, 3, 3, 5, 11, 2 };
		final int[] enchantMinDefaults = { 0, 0, 3, 3, 5, 16, 0 };
		final int[] enchantMaxDefaults = { 0, 3, 4, 5, 7, 16, 3 };
		final int[] adenaDefaults = { 50000, 100000, 200000, 300000, 350000, 1000000, 150000 };
		final int approach = Math.max(10, config.getInt("ApproachSeconds", 60));
		final int fight = Math.max(30, config.getInt("FightSeconds", 240));
		final int warn = Math.max(1, config.getInt("WarnSeconds", 7));
		final int still = Math.max(1, config.getInt("StillSeconds", 4));
		for (int i = 0; i < KIND_KEYS.length; i++)
		{
			final String key = KIND_KEYS[i];
			_minMinutes[i] = Math.max(0, config.getInt(key + "MinMinutes", minMinutesDefaults[i]));
			_maxMinutes[i] = Math.max(_minMinutes[i], config.getInt(key + "MaxMinutes", maxMinutesDefaults[i]));
			_unlockLevel[i] = Math.max(1, config.getInt(key + "MinPlayerLevel", unlockDefaults[i]));
			_levelMin[i] = config.getInt(key + "LevelMin", levelMinDefaults[i]);
			_levelMax[i] = Math.max(_levelMin[i], config.getInt(key + "LevelMax", levelMaxDefaults[i]));
			_enchantMin[i] = Math.max(0, config.getInt(key + "EnchantMin", enchantMinDefaults[i]));
			_enchantMax[i] = Math.max(_enchantMin[i], config.getInt(key + "EnchantMax", enchantMaxDefaults[i]));
			_adena[i] = Math.max(0, config.getInt(key + "AdenaReward", adenaDefaults[i]));
		}
		// How each kind picks its moment: Hard waits for an opening; the rest strike the moment they arrive. A PvE party
		// either asks for the spot first or ambushes. Everyone talks a little trash as the fight starts and whines when the first falls.
		for (int i = 0; i < _styles.length; i++)
		{
			final Approach how = (i == Kind.HARD.ordinal()) ? Approach.WAIT_FOR_MOMENT : Approach.STRIKE_ON_ARRIVAL;
			_styles[i] = ModuleEncounters.style(how, approach, fight, warn, still, null, WIN_LINES, TAUNT_LINES, DEFEAT_LINES);
		}
		_pveAskStyle = ModuleEncounters.style(Approach.ASK_FIRST, approach, fight, warn, still, CONTEST_ASK_LINES, WIN_LINES, ULTIMATUM_LINES, DEFEAT_LINES);
		_pveAmbushStyle = _styles[Kind.PVE_PARTY.ordinal()];

		_contested = config.getBoolean("ContestedZones", false);
		_contestChance = Math.max(0, config.getDouble("ContestedChancePercent", 2.0));
		_contestCooldownMs = Math.max(0, config.getInt("ContestedCooldownMinutes", 15)) * 60_000L;
		final boolean testCommands = config.getBoolean("TestCommands", false);
		if (_contested || testCommands)
		{
			context.events().<OnAttackableKill> onGlobal(EventType.ON_ATTACKABLE_KILL, this::onKill);
		}

		if (testCommands)
		{
			registerTestCommands(context);
		}

		ThreadPool.scheduleAtFixedRate(this::tick, START_DELAY_MS, TICK_MS);
		_log.info("Phantom Encounters module enabled.");
	}

	private void tick()
	{
		if (!_encounters.available())
		{
			_nextAt.clear();
			return;
		}
		try
		{
			final long now = System.currentTimeMillis();
			int active = _encounters.activeCount();
			for (Player player : World.getInstance().getPlayers())
			{
				if (_encounters.isPhantom(player) || !eligible(player))
				{
					continue;
				}
				final long[] due = _nextAt.computeIfAbsent(player.getObjectId(), k -> new long[KIND_KEYS.length]);
				for (int i = 0; i < due.length; i++)
				{
					if ((_maxMinutes[i] <= 0) || (player.getLevel() < _unlockLevel[i]))
					{
						due[i] = 0; // off, or not unlocked yet
					}
					else if (due[i] == 0)
					{
						due[i] = now + nextDelay(i); // first eligibility: start this kind's clock, no instant ambush
					}
				}
				final int pick = EncounterPlanner.pickDue(due, now);
				if ((pick < 0) || _encounters.isTargeted(player))
				{
					continue;
				}
				if (active >= _maxActive)
				{
					due[pick] = now + RETRY_MS;
					continue;
				}
				if (now < _quietUntil.getOrDefault(player.getObjectId(), 0L))
				{
					continue; // a contest just happened: stay quiet until it is over
				}
				if (trigger(player, Kind.values()[pick], null))
				{
					active++;
					final long quietUntil = now + (_gapMinutes * 60_000L);
					_quietUntil.put(player.getObjectId(), quietUntil);
					EncounterPlanner.afterStart(due, pick, now + nextDelay(pick), quietUntil);
				}
				else
				{
					due[pick] = now + RETRY_MS;
				}
			}
		}
		catch (Exception e)
		{
			_log.warning("Phantom Encounters: tick error: " + e.getMessage());
		}
	}

	private long nextDelay(int kind)
	{
		return EncounterPlanner.delayMs(_minMinutes[kind] * 60_000L, _maxMinutes[kind] * 60_000L, Rnd.get(1000));
	}

	/** In the open field and free to be bothered: not in town, a duel, a store, an instance, the Olympiad, or a siege. */
	private boolean eligible(Player player)
	{
		if (!player.isOnline() || player.isDead() || player.isInOlympiadMode() || player.isInDuel() || player.isInStoreMode() || (player.getInstanceId() != 0))
		{
			return false;
		}
		if (player.isInsideZone(ZoneId.PEACE) || player.isInsideZone(ZoneId.NO_PVP) || player.isInsideZone(ZoneId.SIEGE))
		{
			return false;
		}
		return player.getLevel() >= _minPlayerLevel;
	}

	/** Test commands (TestCommands = True): force any encounter now, and tune the contest chance on the fly. */
	private void registerTestCommands(ModuleContext context)
	{
		context.handlers().registerVoicedCommand(new IVoicedCommandHandler()
		{
			@Override
			public boolean onCommand(String command, Player player, String params)
			{
				testCommand(player, (params == null) ? "" : params.trim().toLowerCase());
				return true;
			}

			@Override
			public String[] getCommandList()
			{
				return new String[]
				{
					"enc"
				};
			}
		});
		_log.info("Phantom Encounters: test commands on (.enc).");
	}

	private void testCommand(Player player, String args)
	{
		final String[] part = args.split("\\s+");
		final String word = part[0];
		if (word.equals("buffs"))
		{
			PhantomBuffs.applyFullBuffs(player, (part.length > 1) && part[1].equals("tank")); // the same kit recruited phantoms arrive with
			player.sendMessage("Phantom buff kit applied to you" + (((part.length > 1) && part[1].equals("tank")) ? " (tank set)." : "."));
			return;
		}
		if (!_encounters.available())
		{
			player.sendMessage("Encounters unavailable: needs FakePlayers on and PhantomPvpEnabled = True.");
			return;
		}
		for (int i = 0; i < KIND_KEYS.length; i++)
		{
			if (KIND_KEYS[i].toLowerCase().equals(word))
			{
				force(player, Kind.values()[i], (part.length > 1) ? Boolean.valueOf(part[1].equals("ask")) : null, KIND_KEYS[i]);
				return;
			}
		}
		if (word.equals("contest"))
		{
			final Kind outcome = contestKind();
			force(player, outcome, null, "contested (" + KIND_KEYS[outcome.ordinal()] + ")");
		}
		else if (word.equals("chance") && (part.length > 1))
		{
			try
			{
				_contestChance = Math.max(0, Math.min(100, Double.parseDouble(part[1])));
			}
			catch (NumberFormatException e)
			{
				player.sendMessage("Usage: .enc chance <percent>");
				return;
			}
			_contested = true;
			_contestReadyAt.remove(player.getObjectId());
			_quietUntil.remove(player.getObjectId());
			player.sendMessage("Contested zones ON at " + _contestChance + "% per kill in a farming area (your cooldown was cleared).");
		}
		else if (word.equals("reset"))
		{
			_contestReadyAt.remove(player.getObjectId());
			_quietUntil.remove(player.getObjectId());
			player.sendMessage("Your contest cooldown and quiet time are cleared.");
		}
		else if (word.equals("status"))
		{
			final long now = System.currentTimeMillis();
			final long cool = Math.max(0, _contestReadyAt.getOrDefault(player.getObjectId(), 0L) - now) / 1000;
			player.sendMessage("Contested zones " + (_contested ? "ON" : "OFF") + ", " + _contestChance + "% per kill, " + _contestPvePercent + "% PvE party / rest PK party, PvE asks first " + _pveAskPercent + "% of the time, your cooldown " + cool + "s. In a farming area now: " + FarmingGrounds.contains(player.getX(), player.getY()) + ". Active encounters: " + _encounters.activeCount() + ".");
		}
		else
		{
			player.sendMessage(".enc wimp|normie|hard|pkparty|horsemen|pker|pveparty  force that encounter now");
			player.sendMessage(".enc contest  force a contested-spot encounter");
			player.sendMessage(".enc chance <percent>  turn contested zones on at that chance (clears your cooldown)");
			player.sendMessage(".enc buffs [tank]  give yourself the buffs phantoms spawn with");
			player.sendMessage(".enc reset  clear your cooldown   .enc status  show the settings");
		}
	}

	private void force(Player player, Kind kind, Boolean ask, String label)
	{
		if (player.isDead() || _encounters.isTargeted(player))
		{
			player.sendMessage("Not now: you are dead or already being targeted.");
			return;
		}
		player.sendMessage(trigger(player, kind, ask) ? ("Sent: " + label + ".") : "No room to place the phantoms here; move to open ground.");
	}

	/** A kill inside a farming area may start a "this is my spot" fight with a phantom. */
	private void onKill(OnAttackableKill event)
	{
		try
		{
			if (!_contested || !EncounterPlanner.contestRolled(_contestChance, Rnd.get(10000)))
			{
				return; // cheap exit: nearly every kill ends here
			}
			final Player player = event.getAttacker();
			final Attackable target = event.getTarget();
			if ((player == null) || (target == null) || target.isRaid() || target.isRaidMinion() || (target.getInstanceId() != 0) || _encounters.isPhantom(player))
			{
				return;
			}
			if (!_encounters.available() || !eligible(player) || (player.getLevel() < Math.min(_unlockLevel[Kind.PVE_PARTY.ordinal()], _unlockLevel[Kind.PK_PARTY.ordinal()])) || _encounters.isTargeted(player))
			{
				return;
			}
			if (!FarmingGrounds.contains(target.getX(), target.getY()))
			{
				return;
			}
			final long now = System.currentTimeMillis();
			final int id = player.getObjectId();
			if (!EncounterPlanner.contestReady(now, _contestReadyAt.getOrDefault(id, 0L), _quietUntil.getOrDefault(id, 0L), _encounters.activeCount(), _maxActive))
			{
				return;
			}
			if (!trigger(player, contestKind(), null))
			{
				_contestReadyAt.put(id, now + 60_000L); // no room to place them: try again after a short wait
				return;
			}
			final long quietUntil = now + (_gapMinutes * 60_000L);
			_contestReadyAt.put(id, now + _contestCooldownMs);
			_quietUntil.put(id, quietUntil);
			final long[] due = _nextAt.get(id);
			if (due != null)
			{
				EncounterPlanner.quiet(due, quietUntil);
			}
		}
		catch (Exception e)
		{
			_log.warning("Phantom Encounters: contested zone error: " + e.getMessage());
		}
	}

	/** The same style, with the trash talk of a rare all-alike party. */
	private static Style noveltyStyle(Style base, EncounterRoster.Theme theme)
	{
		return ModuleEncounters.style(base.approach, base.approachSeconds, base.fightSeconds, base.warnSeconds, base.stillSeconds, base.askLines, base.winLines, NOVELTY_LINES.get(theme), base.defeatLines);
	}

	/** What a contested spot sends: a PvE party that wants the spot, or (the rest of the time) a PK party. */
	private Kind contestKind()
	{
		return EncounterRoster.pveRolled(_contestPvePercent, Rnd.get(100)) ? Kind.PVE_PARTY : Kind.PK_PARTY;
	}

	/**
	 * Sends one encounter.
	 * @param ask for a PvE party only: {@code true} asks first, {@code false} ambushes, {@code null} rolls it
	 */
	private boolean trigger(Player player, Kind kind, Boolean ask)
	{
		final int index = kind.ordinal();
		final int partySize = (player.getParty() == null) ? 1 : player.getParty().getMemberCount();
		final java.util.function.IntUnaryOperator rnd = Rnd::get;
		List<Pick> roster = null; // null: the older kinds, which match the party with random roles
		Style style = _styles[index];
		switch (kind)
		{
			case PKER:
			{
				roster = Collections.singletonList(_pkerBag ? EncounterRoster.solo(_soloBags.computeIfAbsent(player.getObjectId(), k -> new EncounterRoster.SoloBag()).next(rnd), rnd) : EncounterRoster.pker(rnd));
				break;
			}
			case HORSEMEN:
			{
				roster = EncounterRoster.horsemen(EncounterPlanner.groupSize(kind, partySize, _horsemenMinSize, _maxActors), rnd);
				break;
			}
			case PK_PARTY:
			{
				final int pkSize = _pkPartyMin + Rnd.get((_pkPartyMax - _pkPartyMin) + 1);
				if (EncounterRoster.noveltyRolled(_noveltyPercent, Rnd.get(10000)))
				{
					final EncounterRoster.Novelty novelty = EncounterRoster.novelty(pkSize, rnd);
					roster = novelty.roster;
					style = noveltyStyle(style, novelty.theme);
				}
				else
				{
					roster = EncounterRoster.pkParty(pkSize, EncounterRoster.pkType(rnd), rnd);
				}
				break;
			}
			case PVE_PARTY:
			{
				final boolean asks = (ask != null) ? ask.booleanValue() : EncounterRoster.asksFirst(_pveAskPercent, Rnd.get(100));
				style = asks ? _pveAskStyle : _pveAmbushStyle;
				if (EncounterRoster.noveltyRolled(_noveltyPercent, Rnd.get(10000)))
				{
					final EncounterRoster.Novelty novelty = EncounterRoster.novelty(3 + partySize, rnd);
					roster = novelty.roster;
					style = noveltyStyle(style, novelty.theme);
				}
				else
				{
					roster = EncounterRoster.pveParty(partySize, rnd);
				}
				break;
			}
			default:
			{
				break;
			}
		}
		if (roster != null)
		{
			roster = EncounterRoster.capped(roster, _maxActors);
		}
		final int size = (roster != null) ? roster.size() : EncounterPlanner.groupSize(kind, partySize, _horsemenMinSize, _maxActors);
		final boolean strong = (kind == Kind.PKER) || (kind == Kind.HORSEMEN); // the two with the 50% scroll
		final boolean red = strong; // red name (and the karma item drop): the Horsemen and the Pker only
		final boolean cp = strong || (kind == Kind.PK_PARTY); // CP potions
		final int reward = _adena[index];
		final Group group = _encounters.begin(size, style, (victim, lastActor) ->
		{
			if ((reward > 0) && victim.isOnline() && !victim.isDead())
			{
				victim.addAdena(ItemProcessType.REWARD, reward, lastActor, true); // one flat payout when the whole group is down
				_log.info("Phantom Encounters: " + kind + " reward " + reward + " adena to " + victim.getName() + ".");
			}
		});
		final double baseAngle = Rnd.nextDouble() * Math.PI * 2; // the group arrives from one general direction
		final String name = ((kind == Kind.PKER) && !_pkerName.isEmpty()) ? _pkerName : null;
		int spawned = 0;
		Player first = null;
		for (int i = 0; i < size; i++)
		{
			final Location where = pickSpawn(player, baseAngle);
			if (where == null)
			{
				continue;
			}
			final int level = EncounterPlanner.levelFor(player.getLevel(), _levelMin[index], _levelMax[index], Rnd.get(1000));
			final int enchant = EncounterPlanner.enchantIn(_enchantMin[index], _enchantMax[index], Rnd.get(1000));
			final Pick pick = (roster == null) ? null : roster.get(i);
			final PartyRole role = (pick == null) ? ROLES[Rnd.get(ROLES.length)] : PartyRole.valueOf(pick.role);
			final Player actor = _encounters.spawn(player, group, where, level, role, enchant, name, (pick == null) ? 0 : pick.classId, strong ? _redEscapePercent : _otherEscapePercent, !strong, cp);
			if (actor == null)
			{
				continue;
			}
			if (actor.isInsideZone(ZoneId.PEACE))
			{
				_encounters.discard(actor); // landed in a safe zone: not part of the encounter
				continue;
			}
			if ((_redKarma > 0) && red)
			{
				actor.setKarma(_redKarma); // red name: the player sees a killer coming
				actor.setPkKills(Math.max(actor.getPkKills(), PvpConfig.KARMA_PK_LIMIT)); // enough kills that the stock karma drop applies when it dies
			}
			spawned++;
			if (first == null)
			{
				first = actor;
			}
		}
		if (spawned == 0)
		{
			return false;
		}
		if (spawned < size)
		{
			group.shrinkTo(spawned); // some did not fit: the group is as big as what actually spawned
		}
		_log.info("Phantom Encounters: " + kind + " encounter for " + player.getName() + " (lvl " + player.getLevel() + ", party of " + partySize + "): " + spawned + " phantom(s), first " + first.getName() + " lvl " + first.getLevel() + ((roster == null) ? "" : (", roster " + roster)) + ".");
		return true;
	}

	/** A walkable point 650-900 units from the player, near {@code baseAngle}, reachable on foot, or {@code null}. */
	private static Location pickSpawn(Player player, double baseAngle)
	{
		for (int attempt = 0; attempt < 8; attempt++)
		{
			final double angle = baseAngle + ((Rnd.nextDouble() - 0.5) * 1.0);
			final int distance = Rnd.get(650, 901);
			final int x = player.getX() + (int) (Math.cos(angle) * distance);
			final int y = player.getY() + (int) (Math.sin(angle) * distance);
			final int z = GeoEngine.getInstance().getHeight(x, y, player.getZ());
			if (Math.abs(z - player.getZ()) > 250)
			{
				continue; // a cliff or another level: it could not walk up
			}
			if (GeoEngine.getInstance().canMoveToTarget(x, y, z, player.getX(), player.getY(), player.getZ(), player.getInstanceId()))
			{
				return new Location(x, y, z);
			}
		}
		return null;
	}
}
