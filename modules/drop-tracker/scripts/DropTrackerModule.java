/*
 * Copyright (c) 2013 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package modules.droptracker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureDeath;
import org.l2jmobius.gameserver.model.events.holders.actor.npc.attackable.OnAttackableKill;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogout;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.modules.GameModule;
import org.l2jmobius.gameserver.modules.ModuleContext;

/**
 * The Drop Tracker module, a benchmarking tool for farming runs. {@code .drops start} snapshots the inventory of every
 * member of the player's party (the bots included), the player's XP and SP, and starts counting kills per monster type.
 * {@code .drops stop} snapshots again and reports the per-hour rates. Gains are the change in each item summed across
 * the whole party, so it does not matter which member picked something up, and items handed between members cancel out.
 * <p>
 * The party is followed during the run: a member who joins later is counted from the moment they join, and a member who
 * leaves the party, logs out or despawns is counted up to the last snapshot taken while they were still there (see
 * {@link DropLedger}). A run ends when its owner logs out. With the switch off, or the directory removed, the server is
 * stock.
 */
public class DropTrackerModule implements GameModule
{
	private static final int ADENA = 57;
	private static final int ANCIENT_ADENA = 5575;
	private static final int BLUE_SEAL_STONE = 6360;
	private static final int GREEN_SEAL_STONE = 6361;
	private static final int RED_SEAL_STONE = 6362;
	private static final int BLUE_VALUE = 3;
	private static final int GREEN_VALUE = 5;
	private static final int RED_VALUE = 10;

	/** How often the party is re-read during a run, in milliseconds. Bounds how much of a leaver's last moments can be missed. */
	private static final long REFRESH_MS = 3000;

	private static final Map<Integer, Session> SESSIONS = new ConcurrentHashMap<>();

	private Logger _log;
	private int _aaLow;
	private int _aaMid;
	private int _aaHigh;
	private int _topLines;
	private boolean _writeCsv;
	private boolean _writeCsvOnLogout;
	private String _csvFile;

	@Override
	public void onEnable(ModuleContext context)
	{
		if (!context.config().getBoolean("Enabled", false))
		{
			return; // Switch off: register nothing, behave as stock.
		}

		_log = context.logging();
		_aaLow = context.config().getInt("AncientAdenaPriceLow", 12);
		_aaMid = context.config().getInt("AncientAdenaPriceMid", 15);
		_aaHigh = context.config().getInt("AncientAdenaPriceHigh", 18);
		_topLines = Math.max(1, context.config().getInt("TopItemLines", 12));
		_writeCsv = context.config().getBoolean("WriteCsv", true);
		_writeCsvOnLogout = context.config().getBoolean("WriteCsvOnLogout", false);
		_csvFile = context.config().getString("CsvFile", "log/drop-tracker.csv");

		context.events().<OnAttackableKill> onMonsters(EventType.ON_ATTACKABLE_KILL, this::onKill);
		context.events().<OnCreatureDeath> onPlayers(EventType.ON_CREATURE_DEATH, this::onDeath);
		// Global scope: Player.deleteMe only announces a logout when a global or the player's own listener exists.
		context.events().<OnPlayerLogout> onGlobal(EventType.ON_PLAYER_LOGOUT, this::onLogout);
		context.handlers().registerVoicedCommand(new DropsCommand());
		ThreadPool.scheduleAtFixedRate(this::refreshAll, REFRESH_MS, REFRESH_MS);
		_log.info("Drop Tracker module enabled, registered voiced command .drops");
	}

	// ------------------------------------------------------------------ events

	private void onKill(OnAttackableKill event)
	{
		if (SESSIONS.isEmpty())
		{
			return;
		}
		final Player attacker = event.getAttacker();
		final Attackable target = event.getTarget();
		if ((attacker == null) || (target == null))
		{
			return;
		}
		// Every run that tracks the killer counts the kill: two party members can each run their own.
		for (Session session : SESSIONS.values())
		{
			if (session.tracks(attacker))
			{
				session.addKill(target.getTemplate().getId(), target.getName(), target.getLevel());
			}
		}
	}

	private void onDeath(OnCreatureDeath event)
	{
		if (SESSIONS.isEmpty())
		{
			return;
		}
		final Creature target = event.getTarget();
		if ((target == null) || !target.isPlayer())
		{
			return;
		}
		final Player player = target.asPlayer();
		for (Session session : SESSIONS.values())
		{
			if (session.tracks(player))
			{
				session.deaths.incrementAndGet();
			}
		}
	}

	/** The owner logged out: the run ends here. The party members are frozen at their last snapshot. */
	private void onLogout(OnPlayerLogout event)
	{
		final Player player = event.getPlayer();
		if (player == null)
		{
			return;
		}
		final Session session = SESSIONS.remove(player.getObjectId());
		if (session == null)
		{
			return;
		}
		finishWithoutOwner(session, "logged out");
	}

	/** Keeps every run's membership current, so leavers are frozen while their inventory is still readable. */
	private void refreshAll()
	{
		for (Session session : SESSIONS.values())
		{
			try
			{
				session.refresh();
			}
			catch (Exception e)
			{
				_log.warning("Drop Tracker: refresh failed for " + session.ownerName + ": " + e.getMessage());
			}
		}
	}

	/** Ends a run whose owner is already gone: nothing is re-read, the report goes to the server log only. */
	private void finishWithoutOwner(Session session, String reason)
	{
		session.ledger.closeAll();
		final Result result = build(session);
		_log.info("Drop Tracker: the run of " + session.ownerName + " ended because they " + reason + ". Report:\n" + String.join("\n", result.logLines) + "\nItems received: " + String.join(", ", result.itemLines) + "\nItems used: " + String.join(", ", result.usedLines));
		if (_writeCsv && _writeCsvOnLogout)
		{
			writeCsv(session, result);
		}
	}

	// ----------------------------------------------------------------- session

	private static class KillStat
	{
		String name;
		int level;
		int count;
	}

	private static class Session
	{
		final Player owner;
		final int ownerId;
		final String ownerName;
		final long startMs = System.currentTimeMillis();
		final long startExp;
		final long startSp;
		final int startLevel;
		final DropLedger ledger = new DropLedger();
		final Map<Integer, KillStat> kills = new ConcurrentHashMap<>();
		final AtomicInteger deaths = new AtomicInteger();
		volatile long endExp;
		volatile long endSp;
		volatile int endLevel;

		Session(Player owner)
		{
			this.owner = owner;
			ownerId = owner.getObjectId();
			ownerName = owner.getName();
			startExp = owner.getExp();
			startSp = owner.getSp();
			startLevel = owner.getLevel();
			endExp = startExp;
			endSp = startSp;
			endLevel = startLevel;
			refresh();
		}

		/** Re-reads who is in the owner's party right now and brings the ledger up to date. */
		void refresh()
		{
			final Map<Integer, Map<Integer, Long>> live = new HashMap<>();
			if (isLive(owner))
			{
				live.put(ownerId, snapshot(owner));
				final Party party = owner.getParty();
				if (party != null)
				{
					for (Player member : party.getMembers())
					{
						if ((member != null) && (member != owner) && isLive(member))
						{
							live.put(member.getObjectId(), snapshot(member));
						}
					}
				}
				endExp = owner.getExp();
				endSp = owner.getSp();
				endLevel = owner.getLevel();
			}
			ledger.update(live);
		}

		/** @return {@code true} if this run counts what the given player does right now */
		boolean tracks(Player player)
		{
			if (ledger.isActive(player.getObjectId()))
			{
				return true;
			}
			// A member who joined a moment ago is not in the ledger until the next refresh: check the party directly.
			if (isLive(owner) && (owner.getParty() != null) && (player.getParty() == owner.getParty()))
			{
				refresh();
				return ledger.isActive(player.getObjectId());
			}
			return false;
		}

		void addKill(int npcId, String name, int level)
		{
			final KillStat stat = kills.computeIfAbsent(npcId, k ->
			{
				final KillStat s = new KillStat();
				s.name = name;
				s.level = level;
				return s;
			});
			synchronized (stat)
			{
				stat.count++;
			}
		}

		int totalKills()
		{
			int total = 0;
			for (KillStat stat : kills.values())
			{
				synchronized (stat)
				{
					total += stat.count;
				}
			}
			return total;
		}
	}

	/** In the world right now: online, and the very object the world holds (a relogged character is a new object). */
	private static boolean isLive(Player player)
	{
		return (player != null) && player.isOnline() && (World.getInstance().getPlayer(player.getObjectId()) == player);
	}

	/** @return the player's items by id, or {@code null} if they cannot be read (never an empty map for a gone player) */
	private static Map<Integer, Long> snapshot(Player player)
	{
		if (!isLive(player))
		{
			return null;
		}
		final Map<Integer, Long> map = new HashMap<>();
		try
		{
			for (Item item : player.getInventory().getItems())
			{
				map.merge(item.getTemplate().getId(), (long) item.getCount(), Long::sum);
			}
		}
		catch (Exception e)
		{
			return null;
		}
		return map;
	}

	/**
	 * @return the run this player owns, or {@code null}. A run left over from an earlier login (its owner object is not
	 *         this player) is discarded here, as a safety net in case the logout was missed.
	 */
	private Session currentRun(Player player)
	{
		final Session session = SESSIONS.get(player.getObjectId());
		if ((session != null) && (session.owner != player))
		{
			SESSIONS.remove(player.getObjectId(), session);
			finishWithoutOwner(session, "logged out earlier");
			player.sendMessage("Drop Tracker: your previous run ended when you logged out.");
			return null;
		}
		return session;
	}

	// ----------------------------------------------------------------- command

	private class DropsCommand implements IVoicedCommandHandler
	{
		private final String[] COMMANDS =
		{
			"drops"
		};

		@Override
		public boolean onCommand(String command, Player player, String params)
		{
			if (player == null)
			{
				return false;
			}
			final String arg = (params == null) ? "" : params.trim().toLowerCase(Locale.ROOT);
			final int id = player.getObjectId();
			switch (arg)
			{
				case "start":
				{
					if (currentRun(player) != null)
					{
						player.sendMessage("Drop Tracker: a run is already in progress. Use .drops status or .drops stop.");
						return true;
					}
					final Session session = new Session(player);
					SESSIONS.put(id, session);
					player.sendMessage("Drop Tracker: started. Tracking " + session.ledger.total() + " member(s); members who join later are added. Use .drops stop to finish.");
					_log.info("Drop Tracker: " + player.getName() + " started a run with " + session.ledger.total() + " tracked member(s).");
					return true;
				}
				case "status":
				{
					final Session session = currentRun(player);
					if (session == null)
					{
						player.sendMessage("Drop Tracker: no run in progress. Use .drops start.");
						return true;
					}
					session.refresh();
					report(player, session, false);
					return true;
				}
				case "stop":
				{
					final Session session = currentRun(player);
					if (session == null)
					{
						player.sendMessage("Drop Tracker: no run in progress. Use .drops start.");
						return true;
					}
					SESSIONS.remove(id, session);
					session.refresh();
					report(player, session, true);
					return true;
				}
				default:
				{
					player.sendMessage("Drop Tracker: .drops start | .drops status | .drops stop");
					return true;
				}
			}
		}

		@Override
		public String[] getCommandList()
		{
			return COMMANDS;
		}
	}

	// ------------------------------------------------------------------ report

	private static String n(long value)
	{
		return String.format(Locale.US, "%,d", value);
	}

	private static String signed(long value)
	{
		return (value < 0) ? ("-" + n(-value)) : ("+" + n(value));
	}

	private static String perHour(double value, double hours)
	{
		return n(Math.round(value / hours));
	}

	private static String duration(long ms)
	{
		final long s = ms / 1000;
		return String.format(Locale.US, "%dm %02ds", s / 60, s % 60);
	}

	private static String itemName(int itemId)
	{
		final ItemTemplate template = ItemData.getInstance().getTemplate(itemId);
		return (template == null) ? ("item " + itemId) : template.getName();
	}

	/** Everything a finished or running report says, built once so the in-game, log and CSV outputs agree. */
	private static class Result
	{
		/** What the player sees in chat: short. */
		final List<String> lines = new ArrayList<>();
		/** Everything, for the server log. */
		final List<String> logLines = new ArrayList<>();
		final List<String> itemLines = new ArrayList<>();
		final List<String> usedLines = new ArrayList<>();
		long elapsed;

		void both(String line)
		{
			lines.add(line);
			logLines.add(line);
		}

		void logOnly(String line)
		{
			logLines.add(line);
		}
		int kills;
		long exp;
		long sp;
		long adena;
		long blue;
		long green;
		long red;
		long aaItems;
		double vendor;
		int deaths;
	}

	private Result build(Session session)
	{
		final Result r = new Result();
		r.elapsed = Math.max(1000, System.currentTimeMillis() - session.startMs);
		final double hours = r.elapsed / 3600000.0;
		r.kills = session.totalKills();
		final int kills = r.kills;

		// Net change of every item id, summed over every tracked member and every stretch they were tracked.
		final Map<Integer, Long> delta = session.ledger.delta();

		r.adena = delta.getOrDefault(ADENA, 0L);
		r.blue = delta.getOrDefault(BLUE_SEAL_STONE, 0L);
		r.green = delta.getOrDefault(GREEN_SEAL_STONE, 0L);
		r.red = delta.getOrDefault(RED_SEAL_STONE, 0L);
		r.aaItems = delta.getOrDefault(ANCIENT_ADENA, 0L);
		final long aaTotal = (r.blue * BLUE_VALUE) + (r.green * GREEN_VALUE) + (r.red * RED_VALUE) + r.aaItems;

		// Vendor value of everything gained apart from adena, seal stones and Ancient Adena.
		double vendor = 0;
		int unpriced = 0;
		final List<Map.Entry<Integer, Long>> gained = new ArrayList<>();
		final List<Map.Entry<Integer, Long>> used = new ArrayList<>();
		for (Map.Entry<Integer, Long> e : delta.entrySet())
		{
			final int itemId = e.getKey();
			if (e.getValue() == 0)
			{
				continue;
			}
			if ((itemId == ADENA) || (itemId == ANCIENT_ADENA) || (itemId == BLUE_SEAL_STONE) || (itemId == GREEN_SEAL_STONE) || (itemId == RED_SEAL_STONE))
			{
				continue;
			}
			final ItemTemplate template = ItemData.getInstance().getTemplate(itemId);
			final double value = (template == null) ? 0 : (template.getReferencePrice() / 2.0);
			if (e.getValue() > 0)
			{
				gained.add(e);
				vendor += value * e.getValue();
				if (value <= 0)
				{
					unpriced++;
				}
			}
			else
			{
				used.add(e);
			}
		}
		gained.sort((a, b) -> Double.compare(value(b), value(a)));
		r.vendor = vendor;

		r.exp = session.endExp - session.startExp;
		r.sp = session.endSp - session.startSp;
		r.deaths = session.deaths.get();
		final int departed = session.ledger.departed();

		r.both("=== Drop Tracker report ===");
		r.both("Time: " + duration(r.elapsed) + " | Level " + session.startLevel + " -> " + session.endLevel + " | Members tracked: " + session.ledger.total() + ((departed > 0) ? (" (" + departed + " left, counted until they left)") : "") + ((r.deaths > 0) ? (" | Deaths: " + r.deaths + " (XP loss included)") : ""));
		r.both("Kills: " + n(kills) + " (" + perHour(kills, hours) + "/hr" + ((kills > 0) ? String.format(Locale.US, ", %.1fs per kill", (r.elapsed / 1000.0) / kills) : "") + ")");
		final List<KillStat> stats = new ArrayList<>(session.kills.values());
		stats.sort((a, b) -> Integer.compare(b.count, a.count));
		for (KillStat stat : stats)
		{
			r.logOnly("  " + stat.name + " (L" + stat.level + "): " + n(stat.count) + " (" + perHour(stat.count, hours) + "/hr)"); // per-monster breakdown: log only
		}
		r.both("XP: " + signed(r.exp) + " (" + perHour(r.exp, hours) + "/hr" + ((kills > 0) ? (", " + n(r.exp / kills) + " per kill") : "") + ") | SP: " + signed(r.sp) + " (" + perHour(r.sp, hours) + "/hr)");
		r.both("Adena: " + signed(r.adena) + " (" + perHour(r.adena, hours) + "/hr" + ((kills > 0) ? (", " + n(r.adena / kills) + " per kill") : "") + ")");
		r.both("Ancient Adena: " + n(aaTotal) + " (" + perHour(aaTotal, hours) + "/hr)");
		r.logOnly("Seal stones: blue " + n(r.blue) + ", green " + n(r.green) + ", red " + n(r.red) + (r.aaItems != 0 ? (", Ancient Adena items " + n(r.aaItems)) : "") + " = " + n(aaTotal) + " Ancient Adena");
		if (kills > 0)
		{
			r.logOnly("Per kill: blue " + String.format(Locale.US, "%.1f", (double) r.blue / kills) + ", green " + String.format(Locale.US, "%.1f", (double) r.green / kills) + ", red " + String.format(Locale.US, "%.1f", (double) r.red / kills));
		}
		r.both("Vendor value of other items: " + n(Math.round(vendor)) + " (" + perHour(vendor, hours) + "/hr)" + ((unpriced > 0) ? (" | " + unpriced + " gained item type(s) have no price and count as 0") : ""));
		final double base = r.adena + vendor;
		r.both("Total per hour (adena + vendor items + Ancient Adena at " + _aaLow + "/" + _aaMid + "/" + _aaHigh + "): "
			+ perHour(base + (aaTotal * (double) _aaLow), hours) + " / " + perHour(base + (aaTotal * (double) _aaMid), hours) + " / " + perHour(base + (aaTotal * (double) _aaHigh), hours));

		// Items received: the seal stones first (they carry no vendor price), then everything else by value.
		for (int sealId : new int[]
		{
			BLUE_SEAL_STONE,
			GREEN_SEAL_STONE,
			RED_SEAL_STONE
		})
		{
			final long count = delta.getOrDefault(sealId, 0L);
			if (count > 0)
			{
				r.itemLines.add(itemName(sealId) + " x" + n(count));
			}
		}
		for (Map.Entry<Integer, Long> e : gained)
		{
			r.itemLines.add(itemName(e.getKey()) + " x" + n(e.getValue()));
		}
		for (Map.Entry<Integer, Long> e : used)
		{
			r.usedLines.add(itemName(e.getKey()) + " x" + n(-e.getValue()));
		}
		return r;
	}

	private void report(Player to, Session session, boolean finished)
	{
		final Result r = build(session);
		if (!finished)
		{
			r.lines.set(0, "=== Drop Tracker status ===");
		}

		// In-game: the short summary and the top items received. Kills per monster, the seal stone split and the
		// items used up are tracked but only written to the server log.
		for (String line : r.lines)
		{
			to.sendMessage(line);
		}
		if (!r.itemLines.isEmpty())
		{
			to.sendMessage("Items received: " + String.join(", ", r.itemLines.subList(0, Math.min(_topLines, r.itemLines.size()))) + ((r.itemLines.size() > _topLines) ? (" ... +" + (r.itemLines.size() - _topLines) + " more in the server log") : ""));
		}

		if (finished)
		{
			session.ledger.closeAll();
			_log.info("Drop Tracker report for " + session.ownerName + ":\n" + String.join("\n", r.logLines) + "\nItems received: " + String.join(", ", r.itemLines) + "\nItems used: " + String.join(", ", r.usedLines));
			if (_writeCsv)
			{
				writeCsv(session, r);
			}
		}
	}

	private double value(Map.Entry<Integer, Long> e)
	{
		final ItemTemplate template = ItemData.getInstance().getTemplate(e.getKey());
		return (template == null) ? 0 : ((template.getReferencePrice() / 2.0) * e.getValue());
	}

	private void writeCsv(Session session, Result r)
	{
		try
		{
			final Path path = Path.of(_csvFile);
			if (path.getParent() != null)
			{
				Files.createDirectories(path.getParent());
			}
			final boolean fresh = !Files.exists(path);
			final double hours = r.elapsed / 3600000.0;
			final StringBuilder sb = new StringBuilder();
			if (fresh)
			{
				sb.append("time,player,start_level,end_level,seconds,kills,kills_per_hr,xp,xp_per_hr,sp,sp_per_hr,adena,adena_per_hr,blue_stones,green_stones,red_stones,ancient_adena_items,vendor_value,deaths\n");
			}
			sb.append(LocalDateTime.now()).append(',').append(session.ownerName).append(',').append(session.startLevel).append(',').append(session.endLevel).append(',')
				.append(r.elapsed / 1000).append(',').append(r.kills).append(',').append(Math.round(r.kills / hours)).append(',')
				.append(r.exp).append(',').append(Math.round(r.exp / hours)).append(',').append(r.sp).append(',').append(Math.round(r.sp / hours)).append(',')
				.append(r.adena).append(',').append(Math.round(r.adena / hours)).append(',').append(r.blue).append(',').append(r.green).append(',').append(r.red).append(',')
				.append(r.aaItems).append(',').append(Math.round(r.vendor)).append(',').append(r.deaths).append('\n');
			Files.writeString(path, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
		catch (IOException e)
		{
			_log.warning("Drop Tracker: could not write " + _csvFile + ": " + e.getMessage());
		}
	}
}
