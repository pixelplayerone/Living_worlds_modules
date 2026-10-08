package modules.arenadueling;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.managers.PhantomManager.PartyRole;
import org.l2jmobius.gameserver.managers.ZoneManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.player.DuelResult;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.zone.ZoneId;
import org.l2jmobius.gameserver.model.zone.ZoneType;
import org.l2jmobius.gameserver.modules.GameModule;
import org.l2jmobius.gameserver.modules.ModuleConfig;
import org.l2jmobius.gameserver.modules.ModuleContext;
import org.l2jmobius.gameserver.modules.ModuleDuels;

/**
 * The Arena Dueling module. Phantoms hang out at the town PvP arenas and spar with each other. Walk into one and a
 * phantom of your own class comes over and challenges you, or call a duel yourself by class, for an adena stake if you
 * like. The platform supplies the duelists and the duel system (see {@link ModuleDuels}); this module is the whole
 * feature: which arenas, who stands there, when they challenge, and what a stake pays.
 */
public class ArenaDuelingModule implements GameModule
{
	private static final long START_DELAY_MS = 45_000;
	private static final long TICK_MS = 5_000;
	private static final int ADENA_ID = 57;

	private static final PartyRole[] ROLES =
	{
		PartyRole.WARRIOR,
		PartyRole.WARRIOR,
		PartyRole.TANK,
		PartyRole.ARCHER,
		PartyRole.DAGGER,
		PartyRole.NUKER,
		PartyRole.NUKER,
		PartyRole.MONK
	};

	/** One configured arena and the duelists standing in it. */
	private static final class Arena
	{
		final String name;
		final ZoneType zone;
		final List<Player> regulars = new ArrayList<>();
		final Map<Integer, Long> challengeAt = new HashMap<>(); // per player in the arena: when a regular may challenge them
		long emptySince;
		long nextSparAt;

		Arena(String name, ZoneType zone)
		{
			this.name = name;
			this.zone = zone;
		}
	}

	/** An adena stake a player has put up for their next arena duel. */
	private static final class Stake
	{
		final int amount;
		final long expiresAt;
		boolean locked; // taken from the player's adena, waiting on the duel
		boolean started; // the player has been seen in the duel
		long lockedAt;

		Stake(int amount, long expiresAt)
		{
			this.amount = amount;
			this.expiresAt = expiresAt;
		}
	}

	private static final long UNSTARTED_REFUND_MS = 90_000L; // a locked stake whose duel has not started by then goes back

	private final Map<Integer, Stake> _stakes = new ConcurrentHashMap<>();
	private final Map<Integer, long[]> _won = new ConcurrentHashMap<>(); // per player: {window start, adena won in it}
	private final Object _stakeLock = new Object(); // every read-modify-write of a stake, an escrow or a win total
	private final Object _arenaLock = new Object(); // the arenas' lists: the tick and .duel both change them
	private final Map<Integer, Long> _callReadyAt = new ConcurrentHashMap<>();
	private final Map<String, Integer> _classNames = new HashMap<>();
	private final List<Arena> _arenas = new ArrayList<>();

	private String _pendingZoneNames = "";
	private Logger _log;
	private ModuleDuels _duels;
	private int _minPlayerLevel;
	private int _regularsPerArena;
	private int _maxDuelists;
	private int _levelSpread;
	private int _enchantMin;
	private int _enchantMax;
	private long _graceMs;
	private boolean _spar;
	private long _sparMinMs;
	private long _sparMaxMs;
	private int _challengePercent;
	private long _challengeDelayMs;
	private long _challengeCooldownMs;
	private boolean _callEnabled;
	private long _callCooldownMs;
	private boolean _wagers;
	private int[] _stakeChoices;
	private long _stakeExpireMs;
	private int _feePercent;
	private long _maxWinPerHour;

	@Override
	public void onEnable(ModuleContext context)
	{
		_log = context.logging();
		final ModuleConfig config = context.config();
		if (!config.getBoolean("Enabled", false))
		{
			return;
		}
		_duels = context.duels();
		_minPlayerLevel = Math.max(1, config.getInt("MinPlayerLevel", 10));
		_regularsPerArena = Math.max(0, config.getInt("RegularsPerArena", 3));
		_maxDuelists = Math.max(1, config.getInt("MaxDuelists", 12));
		_levelSpread = Math.max(0, config.getInt("LevelSpread", 2));
		_enchantMin = Math.max(0, config.getInt("EnchantMin", 0));
		_enchantMax = Math.max(_enchantMin, config.getInt("EnchantMax", 3));
		_graceMs = Math.max(5, config.getInt("LeaveGraceSeconds", 60)) * 1000L;
		_spar = config.getBoolean("Spar", true);
		_sparMinMs = Math.max(10, config.getInt("SparMinSeconds", 45)) * 1000L;
		_sparMaxMs = Math.max(_sparMinMs, config.getInt("SparMaxSeconds", 90) * 1000L);
		_challengePercent = Math.max(0, Math.min(100, config.getInt("ClassChallengePercent", 70)));
		_challengeDelayMs = Math.max(3, config.getInt("ChallengeDelaySeconds", 15)) * 1000L;
		_challengeCooldownMs = Math.max(1, config.getInt("ChallengeCooldownMinutes", 10)) * 60_000L;
		_callEnabled = config.getBoolean("CallDuels", true);
		_callCooldownMs = Math.max(0, config.getInt("CallCooldownSeconds", 30)) * 1000L;
		_wagers = config.getBoolean("Wagers", true);
		_stakeChoices = parseList(config.getString("Stakes", "10000,50000,100000,500000,1000000"));
		_stakeExpireMs = Math.max(30, config.getInt("StakeExpireSeconds", 180)) * 1000L;
		_feePercent = Math.max(0, Math.min(50, config.getInt("HouseFeePercent", 10)));
		_maxWinPerHour = Math.max(0, config.getInt("MaxWinPerHour", 2_000_000));

		for (PlayerClass playerClass : PlayerClass.values())
		{
			_classNames.put(ArenaRules.key(playerClass.name()), playerClass.getId());
		}
		_classNames.put("pk", 90);
		_classNames.put("gladiator", 2);
		_duels.addListener(this::onDuelEnd);
		context.handlers().registerVoicedCommand(new IVoicedCommandHandler()
		{
			@Override
			public boolean onCommand(String command, Player player, String params)
			{
				final String args = (params == null) ? "" : params.trim();
				if (command.equals("duel"))
				{
					duelCommand(player, args);
				}
				else
				{
					stakeCommand(player, args);
				}
				return true;
			}

			@Override
			public String[] getCommandList()
			{
				return new String[]
				{
					"duel",
					"stake"
				};
			}
		});
		_pendingZoneNames = config.getString("Arenas", "gludin_pvp,dion_monster_pvp,giran_pvp_battle");
		ThreadPool.scheduleAtFixedRate(this::tick, START_DELAY_MS, TICK_MS);
		_log.info("Arena Dueling module enabled.");
	}

	private static int[] parseList(String text)
	{
		final String[] parts = text.split(",");
		final int[] out = new int[parts.length];
		int n = 0;
		for (String part : parts)
		{
			final long value = ArenaRules.parseAmount(part);
			if ((value > 0) && (value <= Integer.MAX_VALUE))
			{
				out[n++] = (int) value;
			}
		}
		return java.util.Arrays.copyOf(out, n);
	}

	/** Finds the configured zones once the world is up, and opens them to duels. */
	private void resolveArenas()
	{
		if (_pendingZoneNames == null)
		{
			return;
		}
		for (String raw : _pendingZoneNames.split(","))
		{
			final String name = raw.trim();
			if (name.isEmpty())
			{
				continue;
			}
			final ZoneType zone = ZoneManager.getInstance().getZoneByName(name);
			if (zone == null)
			{
				_log.warning("Arena Dueling: no zone named '" + name + "' (see data/zones/pvp.xml); skipped.");
				continue;
			}
			_duels.openArena(zone);
			_arenas.add(new Arena(name, zone));
		}
		_pendingZoneNames = null;
		_log.info("Arena Dueling: " + _arenas.size() + " arena(s) open for duels.");
	}

	// ---------------------------------------------------------------- the arenas

	private void tick()
	{
		try
		{
			if (!_duels.available())
			{
				return;
			}
			final long now = System.currentTimeMillis();
			synchronized (_arenaLock)
			{
				resolveArenas();
				for (Arena arena : _arenas)
				{
					try
					{
						tickArena(arena, now);
					}
					catch (Exception e)
					{
						_log.warning("Arena Dueling: arena " + arena.name + " tick failed: " + e);
					}
				}
			}
			sweepStakes(now);
		}
		catch (Exception e)
		{
			_log.warning("Arena Dueling tick failed: " + e);
		}
	}

	private List<Player> playersIn(Arena arena)
	{
		final List<Player> inside = new ArrayList<>();
		for (Player player : World.getInstance().getPlayers())
		{
			if (!_duels.isPhantom(player) && !player.isDead() && player.isOnline() && (player.getLevel() >= _minPlayerLevel) && arena.zone.isCharacterInZone(player))
			{
				inside.add(player);
			}
		}
		return inside;
	}

	private void tickArena(Arena arena, long now)
	{
		final List<Player> players = playersIn(arena);
		arena.regulars.removeIf(r -> !r.isOnline());
		if (players.isEmpty())
		{
			if (arena.regulars.isEmpty())
			{
				arena.emptySince = 0;
				arena.challengeAt.clear();
				return;
			}
			if (arena.emptySince == 0)
			{
				arena.emptySince = now;
			}
			if (ArenaRules.sendHome(arena.emptySince, now, _graceMs))
			{
				for (Player regular : arena.regulars)
				{
					_duels.discard(regular);
				}
				arena.regulars.clear();
				arena.emptySince = 0;
				arena.challengeAt.clear();
			}
			return;
		}
		arena.emptySince = 0;
		// A regular killed outside a duel goes; a fresh one takes its place.
		for (int i = arena.regulars.size() - 1; i >= 0; i--)
		{
			if (arena.regulars.get(i).isDead())
			{
				_duels.discard(arena.regulars.remove(i));
			}
		}
		final Player anchor = players.get(Rnd.get(players.size()));
		final int room = _maxDuelists - activeDuelists();
		if (ArenaRules.regularsToAdd(arena.regulars.size(), _regularsPerArena, room) > 0)
		{
			addRegular(arena, anchor, 0, null);
		}
		for (Player player : players)
		{
			considerChallenge(arena, player, now);
		}
		considerSpar(arena, now);
		arena.challengeAt.keySet().removeIf(id -> World.getInstance().getPlayer(id) == null);
	}

	private int activeDuelists()
	{
		int count = 0;
		for (Arena arena : _arenas)
		{
			count += arena.regulars.size();
		}
		return count;
	}

	/** Adds a duelist to the arena near {@code anchor}. {@code classId} 0 = any class. */
	private Player addRegular(Arena arena, Player anchor, int classId, String name)
	{
		final Location where = spotNear(arena, anchor);
		final int level = ArenaRules.regularLevel(anchor.getLevel(), _levelSpread, Rnd.get(2 * _levelSpread + 1));
		final PartyRole role = (classId > 0) ? PhantomManager.roleForClass(PlayerClass.getPlayerClass(classId)) : ROLES[Rnd.get(ROLES.length)];
		final Player duelist = _duels.spawn(where, level, role, Rnd.get(_enchantMin, _enchantMax), name, classId);
		if (duelist != null)
		{
			arena.regulars.add(duelist);
		}
		return duelist;
	}

	/** A spot in the arena a little way from the player, so a challenger has some walking to do but arrives in time. */
	private static Location spotNear(Arena arena, Player player)
	{
		Location best = null;
		for (int i = 0; i < 12; i++)
		{
			final Location point = arena.zone.getZone().getRandomPoint();
			final double distance = Math.hypot(point.getX() - player.getX(), point.getY() - player.getY());
			if ((distance >= 250) && (distance <= 700))
			{
				return point;
			}
			if (best == null)
			{
				best = point;
			}
		}
		return best;
	}

	private void considerChallenge(Arena arena, Player player, long now)
	{
		final Long due = arena.challengeAt.get(player.getObjectId());
		if (due == null)
		{
			arena.challengeAt.put(player.getObjectId(), now + _challengeDelayMs); // just arrived: give them a moment
			return;
		}
		if ((now < due) || player.isInDuel() || player.isProcessingRequest() || !player.canDuel())
		{
			return;
		}
		if (Rnd.get(100) >= _challengePercent)
		{
			arena.challengeAt.put(player.getObjectId(), now + 60_000L);
			return;
		}
		Player duelist = null;
		for (Player regular : arena.regulars)
		{
			if (_duels.isFree(regular) && (regular.getPlayerClass() == player.getPlayerClass()))
			{
				duelist = regular;
				break;
			}
		}
		if (duelist == null)
		{
			if ((_maxDuelists - activeDuelists()) <= 0)
			{
				freeSlot(arena);
			}
			duelist = addRegular(arena, player, player.getPlayerClass().getId(), null);
		}
		if ((duelist != null) && _duels.challenge(duelist, player))
		{
			arena.challengeAt.put(player.getObjectId(), now + _challengeCooldownMs);
			lockStake(player, duelist);
		}
		else
		{
			arena.challengeAt.put(player.getObjectId(), now + 30_000L);
		}
	}

	/** Makes room by sending the first idle regular home. */
	private void freeSlot(Arena arena)
	{
		for (Player regular : arena.regulars)
		{
			if (_duels.isFree(regular))
			{
				arena.regulars.remove(regular);
				_duels.discard(regular);
				return;
			}
		}
	}

	private void considerSpar(Arena arena, long now)
	{
		if (!_spar || (now < arena.nextSparAt))
		{
			return;
		}
		final List<Player> free = new ArrayList<>();
		for (Player regular : arena.regulars)
		{
			if (_duels.isFree(regular))
			{
				free.add(regular);
			}
		}
		final int[] pair = ArenaRules.sparPair(free.size(), Rnd.get(Math.max(1, free.size())), Rnd.get(Math.max(1, free.size() - 1)));
		if (pair == null)
		{
			arena.nextSparAt = now + 10_000L;
			return;
		}
		arena.nextSparAt = now + Rnd.get((int) _sparMinMs, (int) _sparMaxMs);
		_duels.challenge(free.get(pair[0]), free.get(pair[1]));
	}

	// ---------------------------------------------------------------- called duels and stakes

	private Arena arenaOf(Player player)
	{
		for (Arena arena : _arenas)
		{
			if (arena.zone.isCharacterInZone(player))
			{
				return arena;
			}
		}
		return null;
	}

	private void duelCommand(Player player, String args)
	{
		synchronized (_arenaLock)
		{
			callDuel(player, args);
		}
	}

	private void callDuel(Player player, String args)
	{
		final String[] part = args.isEmpty() ? new String[0] : args.split("\\s+");
		if ((part.length == 0) || part[0].equalsIgnoreCase("help"))
		{
			player.sendMessage("Arena duels: stand in a PvP arena, then .duel <class|any> [stake]. Example: .duel titan 50k");
			player.sendMessage("Stakes: " + stakeList() + (_wagers ? ". .stake <amount> sets one for the next arena duel, .stake off clears it." : " (wagers are off)."));
			return;
		}
		if (!_callEnabled || !_duels.available())
		{
			player.sendMessage("Called duels are not available.");
			return;
		}
		final Arena arena = arenaOf(player);
		if (arena == null)
		{
			player.sendMessage("You have to be inside one of the PvP arenas to call a duel.");
			return;
		}
		if ((player.getLevel() < _minPlayerLevel) || player.isDead() || player.isInDuel() || player.isProcessingRequest() || !player.canDuel())
		{
			player.sendMessage("You can't start a duel right now.");
			return;
		}
		final long now = System.currentTimeMillis();
		final long ready = _callReadyAt.getOrDefault(player.getObjectId(), 0L);
		if (now < ready)
		{
			player.sendMessage("Give it " + (((ready - now) / 1000) + 1) + " more seconds.");
			return;
		}
		int classId = 0;
		if (!part[0].equalsIgnoreCase("any"))
		{
			classId = ArenaRules.classIdFor(part[0], _classNames);
			if (classId == 0)
			{
				player.sendMessage("No class called '" + part[0] + "'. Use a class name like titan or phoenix knight (no spaces), or 'any'.");
				return;
			}
		}
		if (part.length > 1)
		{
			if (!setStake(player, part[1]))
			{
				return;
			}
		}
		if ((_maxDuelists - activeDuelists()) <= 0)
		{
			freeSlot(arena);
		}
		final Player duelist = addRegular(arena, player, classId, null);
		if (duelist == null)
		{
			player.sendMessage("Nobody is around to take that duel. Try again in a moment.");
			return;
		}
		_callReadyAt.put(player.getObjectId(), now + _callCooldownMs);
		if (_duels.challenge(duelist, player))
		{
			player.sendMessage(duelist.getName() + " (" + duelist.getPlayerClass().name().replace('_', ' ').toLowerCase() + ", level " + duelist.getLevel() + ") is coming to duel you.");
			lockStake(player, duelist);
		}
		else
		{
			player.sendMessage("They couldn't reach you. Make sure you're out of combat with HP and MP above half.");
		}
	}

	private String stakeList()
	{
		final StringBuilder out = new StringBuilder();
		for (int stake : _stakeChoices)
		{
			out.append(out.length() == 0 ? "" : ", ").append(stake);
		}
		return out.length() == 0 ? "none" : out.toString();
	}

	private void stakeCommand(Player player, String args)
	{
		if (!_wagers)
		{
			player.sendMessage("Wagers are off.");
			return;
		}
		synchronized (_stakeLock)
		{
			final Stake stake = _stakes.get(player.getObjectId());
			if (args.isEmpty())
			{
				if ((stake != null) && stake.locked)
				{
					player.sendMessage("Your " + stake.amount + " adena is locked in your arena duel.");
				}
				else if ((stake != null) && ArenaRules.stakeLive(stake.expiresAt, System.currentTimeMillis()))
				{
					player.sendMessage("Your next arena duel is for " + stake.amount + " adena (" + ((stake.expiresAt - System.currentTimeMillis()) / 1000) + "s left). .stake off clears it.");
				}
				else
				{
					player.sendMessage("No stake set. Allowed: " + stakeList() + ". Use .stake <amount>.");
				}
				return;
			}
			if ((stake != null) && stake.locked)
			{
				player.sendMessage("Your " + stake.amount + " adena is locked in your arena duel until it is over.");
				return;
			}
			if (args.equalsIgnoreCase("off") || args.equals("0"))
			{
				_stakes.remove(player.getObjectId());
				player.sendMessage("Stake cleared.");
				return;
			}
			setStake(player, args);
		}
	}

	private boolean setStake(Player player, String text)
	{
		if (!_wagers)
		{
			player.sendMessage("Wagers are off; this duel is for honor only.");
			return true;
		}
		synchronized (_stakeLock)
		{
			final Stake current = _stakes.get(player.getObjectId());
			if ((current != null) && current.locked)
			{
				player.sendMessage("Your " + current.amount + " adena is locked in your arena duel until it is over.");
				return false;
			}
			final int stake = ArenaRules.allowedStake(ArenaRules.parseAmount(text), _stakeChoices);
			if (stake == 0)
			{
				player.sendMessage("Pick one of the allowed stakes: " + stakeList() + ".");
				return false;
			}
			if (player.getAdena() < stake)
			{
				player.sendMessage("You don't have " + stake + " adena.");
				return false;
			}
			_stakes.put(player.getObjectId(), new Stake(stake, System.currentTimeMillis() + _stakeExpireMs));
			player.sendMessage("Staked " + stake + " adena on your next arena duel. It is taken when the duel is set up. Win: your stake back plus " + ArenaRules.winnings(stake, _feePercent, Integer.MAX_VALUE) + ". Lose or walk away: it is gone.");
			return true;
		}
	}

	/** The phantom is on its way: take the player's stake and lock it in. */
	private void lockStake(Player player, Player duelist)
	{
		synchronized (_stakeLock)
		{
			final Stake stake = _stakes.get(player.getObjectId());
			if ((stake == null) || stake.locked || !ArenaRules.stakeLive(stake.expiresAt, System.currentTimeMillis()))
			{
				return;
			}
			if ((player.getAdena() < stake.amount) || !player.reduceAdena(ItemProcessType.FEE, stake.amount, duelist, true))
			{
				_stakes.remove(player.getObjectId());
				player.sendMessage("You no longer have " + stake.amount + " adena. This duel is for honor only.");
				return;
			}
			stake.locked = true;
			stake.lockedAt = System.currentTimeMillis();
			player.sendMessage(duelist.getName() + " is in for " + stake.amount + " adena. Your stake is locked until the duel is over; walking away forfeits it.");
		}
	}

	/** Gives back stakes whose duel never came about (declined challenge, phantom gone before the countdown). */
	private void sweepStakes(long now)
	{
		synchronized (_stakeLock)
		{
			for (Map.Entry<Integer, Stake> entry : _stakes.entrySet())
			{
				final Stake stake = entry.getValue();
				if (!stake.locked)
				{
					if (!ArenaRules.stakeLive(stake.expiresAt, now))
					{
						_stakes.remove(entry.getKey());
					}
					continue;
				}
				final Player player = World.getInstance().getPlayer(entry.getKey());
				if (player == null)
				{
					continue; // logged out in the duel: it counts as walking away
				}
				if (player.isInDuel())
				{
					stake.started = true;
				}
				else if (stake.started || ((now - stake.lockedAt) > UNSTARTED_REFUND_MS))
				{
					_stakes.remove(entry.getKey());
					player.addAdena(ItemProcessType.REFUND, stake.amount, null, true);
					player.sendMessage("No duel came of it. Your " + stake.amount + " adena is back.");
				}
			}
		}
	}

	// ---------------------------------------------------------------- results

	private void onDuelEnd(Player first, Player second, DuelResult result)
	{
		final Player player = !_duels.isPhantom(first) ? first : (!_duels.isPhantom(second) ? second : null);
		if (player == null)
		{
			return;
		}
		final Player other = (player == first) ? second : first;
		synchronized (_stakeLock)
		{
			final Stake stake = _stakes.get(player.getObjectId());
			if ((stake == null) || !stake.locked)
			{
				return;
			}
			_stakes.remove(player.getObjectId());
			final Boolean firstWon = ModuleDuels.firstWon(result);
			final Boolean playerWon = (firstWon == null) ? null : Boolean.valueOf((player == first) == firstWon.booleanValue());
			final boolean present = (other != null) && other.isOnline() && _duels.isDuelist(other);
			switch (ArenaRules.verdict(playerWon, result == DuelResult.CANCELED, present))
			{
				case WIN:
				{
					final long now = System.currentTimeMillis();
					final long[] won = _won.computeIfAbsent(player.getObjectId(), id -> new long[] { now, 0 });
					if ((now - won[0]) >= 3_600_000L)
					{
						won[0] = now;
						won[1] = 0;
					}
					final int prize = ArenaRules.winnings(stake.amount, _feePercent, ArenaRules.winRoom(_maxWinPerHour, won[1]));
					won[1] += prize;
					player.addAdena(ItemProcessType.REWARD, stake.amount + prize, other, true);
					player.sendMessage((prize > 0) ? ("You beat " + other.getName() + ": your " + stake.amount + " back and " + prize + " adena won.") : ("You beat " + other.getName() + ": your " + stake.amount + " back. You have reached the hourly win limit, so nothing more is paid."));
					break;
				}
				case LOSE:
				{
					player.sendMessage((playerWon == null) ? ("You left the duel. Your " + stake.amount + " adena is forfeit.") : (other.getName() + " takes your " + stake.amount + " adena."));
					break;
				}
				default:
				{
					player.addAdena(ItemProcessType.REFUND, stake.amount, null, true);
					player.sendMessage("No winner. Your " + stake.amount + " adena is back.");
					break;
				}
			}
		}
	}
}
