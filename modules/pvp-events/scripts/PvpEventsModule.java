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
package modules.pvpevents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IParseBoardHandler;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.managers.PhantomManager.PartyRole;
import org.l2jmobius.gameserver.managers.ZoneManager;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureDeath;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogout;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.zone.ZoneType;
import org.l2jmobius.gameserver.modules.GameModule;
import org.l2jmobius.gameserver.modules.ModuleConfig;
import org.l2jmobius.gameserver.modules.ModuleContext;
import org.l2jmobius.gameserver.modules.ModuleTeams;
import org.l2jmobius.gameserver.taskmanagers.DecayTaskManager;

/**
 * PvP Events: start a team event with bots from the Community Board. You and bots against bots, or bots against bots
 * to watch. A scenario names the two sides by role or class; the module drops the sides at opposite ends of an arena,
 * counts kills and damage, brings the dead back after a few seconds, ends on a kill limit or the clock, shows a results
 * table, and puts you back where you were standing. The teams and the bots' fighting come from the platform
 * ({@code context.teams()}).
 */
public class PvpEventsModule implements GameModule, IParseBoardHandler
{
	private static final Logger LOG = Logger.getLogger(PvpEventsModule.class.getName());
	/** Player variable: where the player stood, and their HP, MP and CP, when the event took them; cleared when it gives them back. */
	private static final String AWAY = "PvpEventAway";
	private static final String NAVIGATION_PATH = "data/html/CommunityBoard/Custom/navigation.html";
	private static final String[] DEFAULT_SCENARIOS =
	{
		"FFA Deathmatch | you, 29xany | kills=15, minutes=8",
		"King of the Hill | you, 29xany | respawn=off, minutes=8",
		"Team Deathmatch | you, 4xany vs 5xany | respawn=off",
		"Korean Deathmatch | you, 4xany vs 5xany | respawn=off, queue=both, minutes=10",
		"1v1 Duel | you vs 1xany | respawn=off",
		"9v9 Team Deathmatch | you, 8xany vs 9xany | respawn=off, minutes=8"
	};
	/** The third-profession classes 'any' picks from, grouped by what they do; buffers are left out (they do no damage). */
	private static final List<PlayerClass> ANY_FIGHTERS = new ArrayList<>();
	private static final List<PlayerClass> ANY_HEALERS = new ArrayList<>();
	static
	{
		for (PlayerClass pc : PlayerClass.values())
		{
			if (pc.level() != 3)
			{
				continue;
			}
			final PartyRole role = PhantomManager.roleForClass(pc);
			if (role == PartyRole.HEALER)
			{
				ANY_HEALERS.add(pc);
			}
			else if ((role != PartyRole.BUFFER) || (pc == PlayerClass.DOMINATOR)) // the Overlord line attacks; the other orc buffers do not
			{
				ANY_FIGHTERS.add(pc);
			}
		}
	}

	private enum Phase
	{
		SETUP,
		PREP,
		FIGHT,
		DONE
	}

	/** One fighter in the running event. */
	private static final class Member
	{
		Player player;
		final boolean blue;
		final boolean bot;
		final Location spawn;
		final PvpScenario.Slot slot;
		long reviveAt;
		/** the class this bot was made as, so a replacement keeps it */
		int classId;

		int side()
		{
			return blue ? 0 : 1;
		}

		Member(Player player, boolean blue, boolean bot, Location spawn, PvpScenario.Slot slot)
		{
			this.player = player;
			this.blue = blue;
			this.bot = bot;
			this.spawn = spawn;
			this.slot = slot;
		}
	}

	/** The event that is running. */
	private static final class Event
	{
		final Player starter;
		final PvpScenario scenario;
		final Map<Integer, Member> members = new ConcurrentSkipListMap<>();
		final PvpScore score;
		volatile Phase phase = Phase.SETUP;
		Location origin;
		int originHeading;
		double hp;
		double mp;
		double cp;
		Location blueAnchor;
		Location redAnchor;
		long goAt;
		long fightStartedAt;
		long endsAt;
		int killLimit;
		boolean respawn;
		boolean ending;
		int outsideTicks;
		int level;
		final boolean ffa;
		/** healer-class fighters on each side so far, to keep to the ratio */
		final int[] healers = new int[2];
		/** classes already used on each side, so 'any' spreads over many */
		final List<java.util.Set<PlayerClass>> used = new ArrayList<>();
		Location centre;
		final List<Location> ffaPool = new ArrayList<>();
		/** Per side (0 blue, 1 red): who is in play and who waits their turn, in order, when a side fights one at a time. */
		final boolean[] queued = new boolean[2];
		final List<List<Member>> waiting = new ArrayList<>();
		final Member[] active = new Member[2];
		final long[] releaseAt = new long[2];

		Event(Player starter, PvpScenario scenario)
		{
			this.starter = starter;
			this.scenario = scenario;
			this.ffa = scenario.ffa;
			this.score = new PvpScore(scenario.ffa);
			waiting.add(new ArrayList<>());
			waiting.add(new ArrayList<>());
			used.add(new java.util.HashSet<>());
			used.add(new java.util.HashSet<>());
		}
	}

	private ModuleContext _context;
	private ModuleTeams _teams;
	private final List<PvpScenario> _scenarios = new ArrayList<>();
	private final List<String> _broken = new ArrayList<>();
	private String[] _arenaNames;
	private ZoneType _arena;
	private final List<ZoneType> _arenaZones = new ArrayList<>();
	private int _maxPerSide;
	private int _maxFfa;
	private int _defaultKills;
	private int _defaultMinutes;
	private int _prepSeconds;
	private int _respawnSeconds;
	private int _queueSeconds;
	private boolean _defaultRespawn;
	private int _minLevel;
	private int _levelSpread;
	private int _enchantMin;
	private int _enchantMax;
	private long _winAdena;
	private long _killAdena;
	private boolean _showResults;
	private volatile Event _event;
	private volatile PvpPages.Result _last;

	@Override
	public void onEnable(ModuleContext context)
	{
		final ModuleConfig config = context.config();
		if (!config.getBoolean("Enabled", false))
		{
			return; // Switch off: register nothing, behave as stock.
		}
		_context = context;
		_teams = context.teams();
		_arenaNames = config.getString("Arenas", "colosseum_battle3,colosseum_battle1,colosseum_battle2").split(",");
		_maxPerSide = Math.max(2, Math.min(15, config.getInt("MaxPerSide", 10)));
		_maxFfa = Math.max(2, Math.min(40, config.getInt("MaxFfa", 30)));
		_defaultKills = Math.max(1, config.getInt("DefaultKills", 15));
		_defaultMinutes = Math.max(1, Math.min(60, config.getInt("DefaultMinutes", 5)));
		_prepSeconds = Math.max(0, Math.min(60, config.getInt("PrepSeconds", 15)));
		_respawnSeconds = Math.max(1, Math.min(60, config.getInt("RespawnSeconds", 3)));
		_queueSeconds = Math.max(0, Math.min(60, config.getInt("QueueSeconds", 5)));
		_defaultRespawn = config.getBoolean("Respawn", true);
		_minLevel = Math.max(1, config.getInt("MinPlayerLevel", 20));
		_levelSpread = Math.max(0, Math.min(10, config.getInt("LevelSpread", 0)));
		_enchantMin = Math.max(0, config.getInt("EnchantMin", 0));
		_enchantMax = Math.max(_enchantMin, config.getInt("EnchantMax", 3));
		_winAdena = Math.max(0, config.getLong("WinAdena", 0));
		_killAdena = Math.max(0, config.getLong("KillAdena", 0));
		_showResults = config.getBoolean("ShowResults", true);
		for (int i = 1; i <= 12; i++)
		{
			final String def = (i <= DEFAULT_SCENARIOS.length) ? DEFAULT_SCENARIOS[i - 1] : "";
			final String line = config.getString("Scenario" + i, def).trim();
			if (line.isEmpty())
			{
				continue;
			}
			final PvpScenario s = PvpScenario.parse(line, _maxPerSide, _maxFfa);
			if (s.error != null)
			{
				_broken.add("Scenario" + i + " " + s.error);
				LOG.warning("PvP Events: Scenario" + i + " " + s.error + ".");
				continue;
			}
			final String bad = unknownToken(s);
			if (bad != null)
			{
				_broken.add("Scenario" + i + ": no role or class '" + bad + "'");
				LOG.warning("PvP Events: Scenario" + i + ": no role or class '" + bad + "'.");
				continue;
			}
			_scenarios.add(s);
		}

		context.events().<OnCreatureDeath> onGlobal(EventType.ON_CREATURE_DEATH, this::onDeath);
		context.events().<OnPlayerLogout> onGlobal(EventType.ON_PLAYER_LOGOUT, event ->
		{
			final Event ev = _event;
			if ((ev != null) && (event.getPlayer() == ev.starter))
			{
				abandon(ev);
			}
		});
		context.events().<OnPlayerLogin> onGlobal(EventType.ON_PLAYER_LOGIN, event ->
		{
			final Player p = event.getPlayer();
			if ((p == null) || !p.getVariables().getString(AWAY, "").contains(","))
			{
				return;
			}
			// They logged out or the server went down mid-event: put them back where they were. A short delay lets the world load.
			ThreadPool.schedule(() ->
			{
				final Event running = _event;
				if (p.isOnline() && ((running == null) || (running.starter != p)))
				{
					resolveArena();
					if (inArena(p))
					{
						putBack(p, true);
					}
					else
					{
						p.getVariables().remove(AWAY); // they were moved on already
					}
				}
			}, 3000);
		});
		context.damage().addListener(this::onDamage);
		context.damage().addHealListener(this::onHeal);
		context.handlers().registerBoard(this);
		context.handlers().registerBoardTab(config.getString("TabLabel", "PvP Events"), PvpPages.CMD);
		context.handlers().registerVoicedCommand(new IVoicedCommandHandler()
		{
			@Override
			public boolean onCommand(String command, Player player, String params)
			{
				final String p = (params == null) ? "" : params.trim();
				return PvpEventsModule.this.onCommand(p.isEmpty() ? PvpPages.CMD : (PvpPages.CMD + " " + p), player);
			}

			@Override
			public String[] getCommandList()
			{
				return new String[]
				{
					"pvpevent"
				};
			}
		});
		ThreadPool.scheduleAtFixedRate(this::tick, 1000, 1000);
		context.logging().info("PvP Events enabled (tab and .pvpevent): " + _scenarios.size() + " scenario(s).");
	}

	@Override
	public String[] getCommandList()
	{
		return new String[]
		{
			PvpPages.CMD
		};
	}

	/** @return the first token that is neither a role nor a class, or {@code null} */
	private static String unknownToken(PvpScenario s)
	{
		for (List<PvpScenario.Slot> side : java.util.Arrays.asList(s.blue, s.red))
		{
			for (PvpScenario.Slot slot : side)
			{
				if (!slot.isRole() && (classByName(slot.token) == null))
				{
					return slot.token;
				}
			}
		}
		return null;
	}

	private static PlayerClass classByName(String token)
	{
		final String want = PvpScenario.squash(token);
		for (PlayerClass pc : PlayerClass.values())
		{
			if (PvpScenario.squash(pc.name()).equals(want))
			{
				return pc;
			}
		}
		return null;
	}

	// ---------------------------------------------------------------------
	// Commands and pages
	// ---------------------------------------------------------------------

	@Override
	public boolean onCommand(String command, Player player)
	{
		final String[] p = command.trim().split("\\s+");
		final String sub = (p.length > 1) ? p[1].toLowerCase() : "home";
		String html;
		try
		{
			switch (sub)
			{
				case "start":
				{
					final String problem = start(player, Integer.parseInt(p[2]));
					html = (problem != null) ? PvpPages.notice(problem) : home();
					break;
				}
				case "stop":
				{
					final Event ev = _event;
					if ((ev != null) && ((ev.starter == player) || player.isGM()))
					{
						finish(ev, null, "stopped");
					}
					html = home();
					break;
				}
				case "last":
				{
					html = (_last != null) ? PvpPages.result(_last) : PvpPages.notice("No event has finished yet.");
					break;
				}
				default:
				{
					html = home();
					break;
				}
			}
		}
		catch (RuntimeException e) // a hand-typed or stale bypass: show the front page, never an error
		{
			html = home();
		}
		send(player, html);
		return true;
	}

	private static void send(Player player, String html)
	{
		final String navigation = HtmCache.getInstance().getHtm(player, NAVIGATION_PATH);
		CommunityBoardHandler.separateAndSend(html.replace("%navigation%", (navigation != null) ? navigation : ""), player);
	}

	private String home()
	{
		resolveArena();
		PvpPages.Live live = null;
		final Event ev = _event;
		if (ev != null)
		{
			live = new PvpPages.Live();
			live.scenario = ev.scenario.name;
			live.preparing = ev.phase != Phase.FIGHT;
			final long now = System.currentTimeMillis();
			live.secondsLeft = (int) Math.max(0, ((ev.phase == Phase.FIGHT) ? (ev.endsAt - now) : (ev.goAt - now)) / 1000);
			live.blueKills = ev.score.kills(true);
			live.redKills = ev.score.kills(false);
			live.killLimit = ev.respawn ? ev.killLimit : 0;
			live.ffa = ev.ffa;
			final PvpScore.Entry top = ev.ffa ? ev.score.top() : null;
			if (top != null)
			{
				live.topName = top.name;
				live.topKills = top.kills;
			}
		}
		return PvpPages.home(_scenarios, _broken, (_arena == null) ? null : _arena.getName(), live, _last != null, _teams.available());
	}

	private void resolveArena()
	{
		if (_arena != null)
		{
			return;
		}
		for (String raw : _arenaNames)
		{
			final ZoneType zone = ZoneManager.getInstance().getZoneByName(raw.trim());
			if (zone != null)
			{
				_arenaZones.add(zone);
				if (_arena == null)
				{
					_arena = zone;
				}
			}
		}
	}

	/** @return {@code true} if the player stands inside any of the listed arenas, by map position only (the floor height differs between them) */
	private boolean inArena(Player p)
	{
		for (ZoneType zone : _arenaZones)
		{
			if (zone.isInsideZone(p.getX(), p.getY()))
			{
				return true;
			}
		}
		return false;
	}

	// ---------------------------------------------------------------------
	// Starting
	// ---------------------------------------------------------------------

	/** @return why the event cannot start, or {@code null} if it is starting */
	private synchronized String start(Player player, int index)
	{
		if (_event != null)
		{
			return "An event is already running.";
		}
		if ((index < 0) || (index >= _scenarios.size()))
		{
			return "No such scenario.";
		}
		if (!_teams.available())
		{
			return "Bots are off. Turn on FakePlayers and PhantomPvpEnabled in config/Custom/FakePlayers.ini.";
		}
		resolveArena();
		if (_arena == null)
		{
			return "No arena zone from the Arenas setting exists on this server.";
		}
		if (player.isDead() || player.isInDuel() || player.isInOlympiadMode() || player.isOnEvent() || player.isInStoreMode() || player.isMounted() || player.isFlying() || (player.getParty() != null))
		{
			return "Not now. Be alive, out of a party, a duel, a store and an event, and on foot.";
		}
		if (player.getLevel() < _minLevel)
		{
			return "You need to be level " + _minLevel + ".";
		}
		if ((player.getPvpFlag() != 0) || (player.getKarma() > 0) || player.isInCombat())
		{
			return "Not now. You are flagged, a PK or in combat; the arena is no way out of that.";
		}
		final Event ev = new Event(player, _scenarios.get(index));
		_event = ev;
		ThreadPool.execute(() -> setup(ev));
		return null;
	}

	private void setup(Event ev)
	{
		try
		{
			final Player player = ev.starter;
			final List<int[]> sample = new ArrayList<>();
			final int count = ev.ffa ? 200 : 40;
			for (int i = 0; i < count; i++)
			{
				final Location point = _arena.getZone().getRandomPoint();
				sample.add(new int[]
				{
					point.getX(),
					point.getY(),
					point.getZ()
				});
			}
			final int[] pair = ArenaGeometry.farthestPair(sample);
			if (pair == null)
			{
				fail(ev, "The arena has no room to fight in.");
				return;
			}
			// Pull both ends a third of the way to the middle, so a ring of fighters stays well inside the walls.
			long cx = 0;
			long cy = 0;
			long cz = 0;
			for (int[] s : sample)
			{
				cx += s[0];
				cy += s[1];
				cz += s[2];
			}
			cx /= sample.size();
			cy /= sample.size();
			cz /= sample.size();
			final int[] a = towards(sample.get(pair[0]), cx, cy, cz);
			final int[] b = towards(sample.get(pair[1]), cx, cy, cz);
			ev.blueAnchor = new Location(a[0], a[1], a[2]);
			ev.redAnchor = new Location(b[0], b[1], b[2]);
			ev.centre = new Location((int) cx, (int) cy, (int) cz);
			ev.origin = new Location(player.getX(), player.getY(), player.getZ());
			ev.originHeading = player.getHeading();
			ev.hp = player.getCurrentHp();
			ev.mp = player.getCurrentMp();
			ev.cp = player.getCurrentCp();
			// Written down first, so a logout, crash or restart in the arena still sends them back.
			player.getVariables().set(AWAY, ev.origin.getX() + "," + ev.origin.getY() + "," + ev.origin.getZ() + "," + ev.originHeading + "," + (long) ev.hp + "," + (long) ev.mp + "," + (long) ev.cp);
			player.getVariables().storeMe();
			ev.respawn = (ev.scenario.respawn == -1) ? _defaultRespawn : (ev.scenario.respawn == 1);
			ev.killLimit = (ev.scenario.kills > 0) ? ev.scenario.kills : _defaultKills;

			final int blueSize = ev.scenario.sizeOf(true);
			final int redSize = ev.scenario.sizeOf(false);
			final List<Location> ffaSpots = new ArrayList<>();
			if (ev.ffa)
			{
				// Keep the fighters in the middle of the arena: only the points nearest its centre, so nobody starts on the edge.
				final List<int[]> core = ArenaGeometry.nearestTo(sample, (int) cx, (int) cy, Math.max(blueSize * 3, sample.size() / 2));
				ev.ffaPool.addAll(toLocations(core));
				final List<Integer> picked = ArenaGeometry.spread(core, blueSize);
				java.util.Collections.shuffle(picked);
				for (int i : picked)
				{
					ffaSpots.add(new Location(core.get(i)[0], core.get(i)[1], core.get(i)[2]));
				}
				if (ffaSpots.size() < blueSize)
				{
					fail(ev, "The arena has no room for " + blueSize + " fighters.");
					return;
				}
			}
			int blueIndex = 0;
			int redIndex = 0;
			int ffaIndex = 0;
			// The player takes the first place on his side; spectators wait at the middle of the arena.
			if (ev.scenario.you != 0)
			{
				final boolean blue = ev.scenario.you == 1;
				final Location spot = ev.ffa ? ffaSpots.get(ffaIndex++) : spotOnRing(blue ? a : b, blue ? blueSize : redSize, 0);
				ev.members.put(player.getObjectId(), new Member(player, blue, false, spot, null));
				if (PhantomManager.roleForClass(player.getPlayerClass()) == PartyRole.HEALER)
				{
					ev.healers[blue ? 0 : 1]++; // you count toward your side's healers
				}
				ev.used.get(blue ? 0 : 1).add(player.getPlayerClass());
				ev.score.add(player.getObjectId(), player.getName(), blue, false, roleLabel(player));
				if (blue)
				{
					blueIndex = 1;
				}
				else
				{
					redIndex = 1;
				}
			}
			final int level = player.getLevel();
			ev.level = level;
			int made = 0;
			int wanted = 0;
			for (int side = 0; side < (ev.ffa ? 1 : 2); side++)
			{
				final boolean blue = side == 0;
				final int[] anchor = blue ? a : b;
				final int size = blue ? blueSize : redSize;
				for (PvpScenario.Slot slot : blue ? ev.scenario.blue : ev.scenario.red)
				{
					for (int n = 0; n < slot.count; n++)
					{
						wanted++;
						final Location spot;
						if (ev.ffa)
						{
							spot = ffaSpots.get(ffaIndex++);
						}
						else
						{
							spot = spotOnRing(anchor, size, blue ? blueIndex++ : redIndex++);
						}
						final Player bot = spawnBot(ev, slot, blue, spot, level, 0);
						if (bot != null)
						{
							made++;
							final Member botMember = new Member(bot, blue, true, spot, slot);
							botMember.classId = bot.getPlayerClass().getId();
							ev.members.put(bot.getObjectId(), botMember);
							ev.score.add(bot.getObjectId(), bot.getName(), blue, true, roleLabel(bot));
						}
					}
				}
			}
			final StringBuilder roster = new StringBuilder();
			for (Member m : ev.members.values())
			{
				roster.append(m.blue ? (ev.ffa ? "" : "B:") : "R:").append(m.player.getPlayerClass().name()).append(' ');
			}
			LOG.info("PvP Events: " + ev.scenario.name + " made " + made + " of " + wanted + " bots: " + roster.toString().trim());
			if ((wanted > 0) && (made < ((wanted + 1) / 2)))
			{
				fail(ev, "The bots could not be made (" + made + " of " + wanted + ").");
				return;
			}
			if (_event != ev)
			{
				for (Member m : ev.members.values()) // stopped or abandoned while the bots were being made
				{
					if (m.bot)
					{
						_teams.discard(m.player);
					}
				}
				return;
			}
			if (ev.scenario.you != 0)
			{
				player.teleToLocation(ev.members.get(player.getObjectId()).spawn);
				if (ev.ffa)
				{
					_teams.joinSolo(player);
				}
				else
				{
					_teams.join(player, ev.scenario.you == 1); // the team circle shows from the start
				}
				_teams.lock(player, true); // held in place until the timer ends, like the bots
				_teams.buffLikeFighter(player); // buffs stripped, then the same kit the bots arrive with
				ThreadPool.schedule(() ->
				{
					if ((_event == ev) && (ev.phase == Phase.PREP) && player.isOnline())
					{
						_teams.buffLikeFighter(player); // again once the teleport has landed, in case it wiped them
						LOG.info("PvP Events: " + player.getName() + " has " + player.getBuffCount() + " buffs for " + ev.scenario.name + ".");
					}
				}, 3000);
				player.sendMessage("PvP event: " + ev.scenario.name + ". Starts in " + _prepSeconds + " seconds. You have the same buffs as the bots.");
			}
			else
			{
				player.teleToLocation(ev.centre);
				player.sendMessage("PvP event: " + ev.scenario.name + ". You are watching.");
			}
			if (!ev.ffa)
			{
				setupQueues(ev);
				formParties(ev);
			}
			ev.goAt = System.currentTimeMillis() + (_prepSeconds * 1000L);
			ev.phase = Phase.PREP;
		}
		catch (RuntimeException e)
		{
			LOG.warning("PvP Events: setup failed: " + e);
			fail(ev, "The event could not be set up.");
		}
	}

	/** For scenarios that fight one at a time: the first of a queued side is in play (you, on your own side), the rest wait. */
	private void setupQueues(Event ev)
	{
		if (!ev.scenario.queue || ev.respawn)
		{
			return;
		}
		if (ev.scenario.queueBoth)
		{
			ev.queued[0] = true;
			ev.queued[1] = true;
		}
		else
		{
			ev.queued[(ev.scenario.you == 1) ? 1 : 0] = true; // the side facing the player waits its turn
		}
		for (int side = 0; side < 2; side++)
		{
			if (!ev.queued[side])
			{
				continue;
			}
			final List<Member> order = new ArrayList<>();
			for (Member m : ev.members.values())
			{
				if (m.side() == side)
				{
					if (m.bot)
					{
						order.add(m);
					}
					else
					{
						order.add(0, m); // you go first
					}
				}
			}
			if (!order.isEmpty())
			{
				ev.active[side] = order.get(0);
				ev.waiting.get(side).addAll(order.subList(1, order.size()));
			}
		}
	}

	/** Each side becomes one party for the event: you lead yours, a bot leads the other. */
	private void formParties(Event ev)
	{
		for (int side = 0; side < 2; side++)
		{
			final List<Player> party = new ArrayList<>();
			for (Member m : ev.members.values())
			{
				if (m.side() == side)
				{
					if (m.bot)
					{
						party.add(m.player);
					}
					else
					{
						party.add(0, m.player);
					}
				}
			}
			_teams.formParty(party);
		}
	}

	private static List<Location> toLocations(List<int[]> points)
	{
		final List<Location> out = new ArrayList<>();
		for (int[] p : points)
		{
			out.add(new Location(p[0], p[1], p[2]));
		}
		return out;
	}

	private static int[] towards(int[] point, long cx, long cy, long cz)
	{
		return new int[]
		{
			(int) (point[0] + ((cx - point[0]) / 3)),
			(int) (point[1] + ((cy - point[1]) / 3)),
			(int) (point[2] + ((cz - point[2]) / 3))
		};
	}

	/** A place on the ring round an anchor, or the anchor itself if that place is outside the arena. */
	private Location spotOnRing(int[] anchor, int size, int index)
	{
		final int[] at = ArenaGeometry.ring(anchor[0], anchor[1], size, index);
		if (_arena.isInsideZone(at[0], at[1]))
		{
			return new Location(at[0], at[1], anchor[2]);
		}
		return new Location(anchor[0], anchor[1], anchor[2]);
	}

	/** The most healer-class fighters a side of this size may have: one per four, two at the most. */
	static int healerCap(int sideSize)
	{
		return Math.min(2, sideSize / 4);
	}

	/**
	 * Picks the class for an 'any' slot: a random third-profession class, favouring ones this side has not got yet, and a
	 * healer only while the side is under its healer cap (never in a free-for-all).
	 */
	private PlayerClass chooseAny(Event ev, int side)
	{
		final int size = ev.scenario.sizeOf(side == 0);
		final boolean healerOk = !ev.ffa && (ev.healers[side] < healerCap(size)) && (Rnd.get(100) < 40);
		final List<PlayerClass> pool = new ArrayList<>(healerOk ? ANY_HEALERS : ANY_FIGHTERS);
		final List<PlayerClass> fresh = new ArrayList<>();
		for (PlayerClass pc : pool)
		{
			if (!ev.used.get(side).contains(pc))
			{
				fresh.add(pc);
			}
		}
		final List<PlayerClass> from = fresh.isEmpty() ? pool : fresh;
		PlayerClass pick = from.get(Rnd.get(from.size()));
		for (int tries = 0; (tries < 3) && isLessCommon(pick) && Rnd.nextBoolean(); tries++) // half as likely to stay
		{
			pick = from.get(Rnd.get(from.size()));
		}
		return pick;
	}

	/** Sword Muses, Spectral Dancers and the dwarf classes are not great fighters; they are in the mix but a bit rarer. */
	private static boolean isLessCommon(PlayerClass pc)
	{
		final PartyRole role = PhantomManager.roleForClass(pc);
		return (role == PartyRole.SINGER) || (role == PartyRole.DANCER) || (pc.getRace() == org.l2jmobius.gameserver.model.actor.enums.creature.Race.DWARF);
	}

	/** @param forcedClassId a class to make it as (a replacement keeps its class), or 0 to choose from the slot */
	private Player spawnBot(Event ev, PvpScenario.Slot slot, boolean blue, Location spot, int playerLevel, int forcedClassId)
	{
		final int side = blue ? 0 : 1;
		PartyRole role;
		int classId = forcedClassId;
		if (forcedClassId > 0)
		{
			role = PhantomManager.roleForClass(PlayerClass.getPlayerClass(forcedClassId));
		}
		else if (slot.isRole())
		{
			if (slot.token.equals("any"))
			{
				final PlayerClass pick = chooseAny(ev, side);
				classId = pick.getId();
				role = PhantomManager.roleForClass(pick);
			}
			else if (slot.token.equals("bounty"))
			{
				role = PartyRole.BOUNTY_HUNTER;
			}
			else
			{
				role = PartyRole.valueOf(slot.token.toUpperCase());
			}
		}
		else
		{
			final PlayerClass pc = classByName(slot.token);
			if (pc == null)
			{
				return null;
			}
			classId = pc.getId();
			role = PhantomManager.roleForClass(pc);
		}
		if (classId == PlayerClass.DOMINATOR.getId())
		{
			role = PartyRole.NUKER; // geared and driven as a caster that attacks, not as a support
		}
		if (ev.ffa && (role == PartyRole.HEALER)) // a free-for-all has nobody to heal
		{
			final PlayerClass pick = chooseAny(ev, side);
			classId = pick.getId();
			role = PhantomManager.roleForClass(pick);
		}
		if (role == PartyRole.HEALER)
		{
			ev.healers[side]++;
		}
		if (classId > 0)
		{
			ev.used.get(side).add(PlayerClass.getPlayerClass(classId));
		}
		final int level = Math.max(1, playerLevel + ((_levelSpread == 0) ? 0 : (Rnd.get((_levelSpread * 2) + 1) - _levelSpread)));
		final int enchant = (_enchantMax > _enchantMin) ? (_enchantMin + Rnd.get((_enchantMax - _enchantMin) + 1)) : _enchantMin;
		final Player bot;
		if (ev.ffa)
		{
			bot = _teams.spawnSolo(spot, ev.centre, level, role, enchant, null, classId);
		}
		else
		{
			bot = _teams.spawn(blue, spot, null, level, role, enchant, null, classId);
		}
		return bot;
	}

	private static String roleLabel(Player p)
	{
		final String name = p.getPlayerClass().name().replace('_', ' ').toLowerCase();
		return Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

	private void fail(Event ev, String message)
	{
		LOG.warning("PvP Events: " + message);
		ev.starter.sendMessage("PvP event: " + message);
		cleanup(ev, true);
	}

	// ---------------------------------------------------------------------
	// Running
	// ---------------------------------------------------------------------

	private void tick()
	{
		final Event ev = _event;
		if (ev == null)
		{
			return;
		}
		try
		{
			final long now = System.currentTimeMillis();
			if (ev.phase == Phase.PREP)
			{
				if (now >= ev.goAt)
				{
					go(ev, now);
				}
				return;
			}
			if (ev.phase != Phase.FIGHT)
			{
				return;
			}
			if (!ev.starter.isOnline())
			{
				abandon(ev);
				return;
			}
			if (!ev.ending && !ev.starter.isDead())
			{
				ev.outsideTicks = inArena(ev.starter) ? 0 : (ev.outsideTicks + 1);
				if (ev.outsideTicks >= 6)
				{
					finish(ev, null, "you left the arena");
					return;
				}
			}
			replaceGoneBots(ev);
			for (int side = 0; side < 2; side++)
			{
				final Member in = ev.active[side];
				if ((in != null) && (in.player.isDead() || !in.player.isOnline()) && !ev.waiting.get(side).isEmpty())
				{
					if (ev.releaseAt[side] == 0)
					{
						ev.releaseAt[side] = now + (_queueSeconds * 1000L);
					}
					else if (now >= ev.releaseAt[side])
					{
						ev.releaseAt[side] = 0;
						final Member next = ev.waiting.get(side).remove(0);
						ev.active[side] = next;
						release(ev, next);
						ev.starter.sendMessage("PvP event: the next " + (((ev.scenario.you != 0) && (next.side() == (ev.scenario.you - 1))) ? "of yours is" : "one is") + " coming.");
					}
				}
			}
			for (Member m : ev.members.values())
			{
				if (m.player.isDead())
				{
					keepBody(m.player);
				}
				if (m.player.isDead() && ev.respawn)
				{
					if (m.reviveAt == 0)
					{
						m.reviveAt = now + (_respawnSeconds * 1000L);
					}
					else if (now >= m.reviveAt)
					{
						m.reviveAt = 0;
						revive(ev, m);
					}
				}
			}
			if (ev.ffa)
			{
				if (ev.respawn)
				{
					final PvpScore.Entry top = ev.score.top();
					if ((top != null) && (top.kills >= ev.killLimit))
					{
						finish(ev, null, "first to " + ev.killLimit + " kills", top);
						return;
					}
				}
				else
				{
					final List<Member> alive = new ArrayList<>();
					for (Member m : ev.members.values())
					{
						if (m.player.isOnline() && !m.player.isDead())
						{
							alive.add(m);
						}
					}
					if (alive.size() <= 1)
					{
						finish(ev, null, "last one standing", alive.isEmpty() ? null : ev.score.get(alive.get(0).player.getObjectId()));
						return;
					}
				}
			}
			else if (ev.respawn)
			{
				if (ev.score.kills(true) >= ev.killLimit)
				{
					finish(ev, Boolean.TRUE, "first to " + ev.killLimit + " kills");
					return;
				}
				if (ev.score.kills(false) >= ev.killLimit)
				{
					finish(ev, Boolean.FALSE, "first to " + ev.killLimit + " kills");
					return;
				}
			}
			else
			{
				final boolean blueAlive = anyAlive(ev, true);
				final boolean redAlive = anyAlive(ev, false);
				if (!blueAlive || !redAlive)
				{
					finish(ev, blueAlive ? Boolean.TRUE : (redAlive ? Boolean.FALSE : null), "last team standing");
					return;
				}
			}
			if (now >= ev.endsAt)
			{
				if (ev.ffa)
				{
					finish(ev, null, "time is up", ev.score.top());
				}
				else
				{
					finish(ev, ev.score.leader(), "time is up");
				}
			}
		}
		catch (RuntimeException e)
		{
			LOG.warning("PvP Events: tick failed: " + e);
			abandon(ev);
		}
	}

	private void go(Event ev, long now)
	{
		if (ev.scenario.you != 0)
		{
			_teams.lock(ev.starter, false);
		}
		for (Member m : ev.members.values()) // buffs are on: everyone starts at full HP, MP and CP
		{
			_teams.fullHeal(m.player);
		}
		for (Member m : ev.members.values())
		{
			if (m.bot && !ev.waiting.get(m.side()).contains(m))
			{
				release(ev, m);
			}
		}
		ev.fightStartedAt = now;
		ev.endsAt = now + ((ev.scenario.minutes > 0 ? ev.scenario.minutes : _defaultMinutes) * 60000L);
		ev.phase = Phase.FIGHT;
		ev.starter.sendMessage("PvP event: FIGHT!");
	}

	/** Lets a held bot loose: it heads for the other side and fights whatever it meets. */
	private void release(Event ev, Member m)
	{
		_teams.setRally(m.player, ev.ffa ? ev.centre : (m.blue ? ev.redAnchor : ev.blueAnchor));
		_teams.hold(m.player, false);
	}

	private static boolean anyAlive(Event ev, boolean blue)
	{
		for (Member m : ev.members.values())
		{
			if ((m.blue == blue) && m.player.isOnline() && !m.player.isDead())
			{
				return true;
			}
		}
		return false;
	}

	/** A bot that was removed from the world (it should not happen) is replaced by a new one that keeps its score. */
	private void replaceGoneBots(Event ev)
	{
		List<Member> gone = null;
		for (Member m : ev.members.values())
		{
			if (m.bot && !m.player.isOnline())
			{
				if (gone == null)
				{
					gone = new ArrayList<>();
				}
				gone.add(m);
			}
		}
		if (gone == null)
		{
			return;
		}
		for (Member m : gone)
		{
			final int oldId = m.player.getObjectId();
			if (!ev.respawn)
			{
				continue; // out for good: the dead stay down, and so does one that vanished
			}
			final Player fresh = spawnBot(ev, m.slot, m.blue, m.spawn, ev.level, m.classId);
			if (fresh == null)
			{
				continue;
			}
			LOG.warning("PvP Events: bot " + m.player.getName() + " left the world; replaced by " + fresh.getName() + ".");
			ev.members.remove(oldId);
			m.player = fresh;
			m.reviveAt = 0;
			ev.members.put(fresh.getObjectId(), m);
			ev.score.replace(oldId, fresh.getObjectId(), fresh.getName());
			release(ev, m);
		}
	}

	/**
	 * A dead fighter must not decay (the server logs a dead player out after a while, and the decay is queued after the
	 * death event, so cancelling in the death handler is too early) and its servitor goes with it.
	 */
	private void keepBody(Player dead)
	{
		DecayTaskManager.getInstance().cancel(dead);
		final org.l2jmobius.gameserver.model.actor.Summon pet = dead.getSummon();
		if ((pet != null) && !pet.isDead())
		{
			pet.unSummon(dead);
			if (pet.isSpawned())
			{
				pet.deleteMe();
			}
		}
	}

	private void revive(Event ev, Member m)
	{
		final Location where = ev.ffa ? (ev.ffaPool.isEmpty() ? _arena.getZone().getRandomPoint() : ev.ffaPool.get(Rnd.get(ev.ffaPool.size()))) : m.spawn;
		if (m.bot)
		{
			_teams.revive(m.player, where);
			if (m.player.isDead())
			{
				LOG.warning("PvP Events: could not revive " + m.player.getName() + ".");
			}
			return;
		}
		m.player.doRevive();
		m.player.setCurrentHp(m.player.getMaxHp());
		m.player.setCurrentMp(m.player.getMaxMp());
		m.player.setCurrentCp(m.player.getMaxCp());
		m.player.teleToLocation(where);
		_teams.buffLikeFighter(m.player); // dying wipes the buffs; the bots get theirs back too
	}

	private void onDeath(OnCreatureDeath event)
	{
		final Event ev = _event;
		if ((ev == null) || (ev.phase != Phase.FIGHT) || !event.getTarget().isPlayer())
		{
			return;
		}
		final Player victim = event.getTarget().asPlayer();
		if (!ev.members.containsKey(victim.getObjectId()))
		{
			return;
		}
		keepBody(victim); // the decay is queued just after this event, so the tick cancels it again; the servitor goes now
		ThreadPool.schedule(() -> keepBody(victim), 500);
		final Creature killer = event.getAttacker();
		final Player credit = owner(killer);
		ev.score.kill((credit == null) ? 0 : credit.getObjectId(), victim.getObjectId());
	}

	private void onDamage(Creature attacker, Creature target, double damage, Skill skill, boolean damageOverTime)
	{
		final Event ev = _event;
		if ((ev == null) || (ev.phase != Phase.FIGHT))
		{
			return;
		}
		final Player dealer = owner(attacker);
		final Player taker = owner(target);
		if ((dealer != null) && (taker != null) && ev.score.has(dealer.getObjectId()) && ev.score.has(taker.getObjectId()))
		{
			ev.score.damage(dealer.getObjectId(), taker.getObjectId(), (long) damage);
		}
	}

	private void onHeal(Creature healer, Creature target, double amount, Skill skill)
	{
		final Event ev = _event;
		if ((ev == null) || (ev.phase != Phase.FIGHT))
		{
			return;
		}
		final Player from = owner(healer);
		final Player to = owner(target);
		if ((from != null) && (to != null) && ev.score.has(from.getObjectId()) && ev.score.has(to.getObjectId()))
		{
			ev.score.heal(from.getObjectId(), to.getObjectId(), (long) amount);
		}
	}

	private static Player owner(Creature c)
	{
		if (c == null)
		{
			return null;
		}
		if (c.isPlayer())
		{
			return c.asPlayer();
		}
		return c.isSummon() ? c.asSummon().getOwner() : null;
	}

	// ---------------------------------------------------------------------
	// Ending
	// ---------------------------------------------------------------------

	private void finish(Event ev, Boolean winnerBlue, String reason)
	{
		finish(ev, winnerBlue, reason, null);
	}

	/** @param ffaWinner the winner of a free-for-all, or {@code null} for none */
	private void finish(Event ev, Boolean winnerBlue, String reason, PvpScore.Entry ffaWinner)
	{
		synchronized (ev)
		{
			if (ev.ending)
			{
				return;
			}
			ev.ending = true;
		}
		final PvpPages.Result r = new PvpPages.Result();
		r.scenario = ev.scenario.name;
		r.winnerBlue = winnerBlue;
		r.reason = reason;
		r.seconds = (ev.fightStartedAt == 0) ? 0 : (int) ((System.currentTimeMillis() - ev.fightStartedAt) / 1000);
		r.score = ev.score;
		r.youIn = ev.scenario.you != 0;
		r.youBlue = ev.scenario.you == 1;
		r.ffa = ev.ffa;
		r.winnerName = (ffaWinner == null) ? null : ffaWinner.name;
		r.youWon = r.youIn && (ffaWinner != null) && (ffaWinner.id == ev.starter.getObjectId());
		if (r.youIn)
		{
			final PvpScore.Entry mine = ev.score.get(ev.starter.getObjectId());
			long reward = 0;
			if (mine != null)
			{
				reward += _killAdena * mine.kills;
			}
			if (r.youWon || ((winnerBlue != null) && !ev.ffa && (winnerBlue.booleanValue() == r.youBlue)))
			{
				reward += _winAdena;
			}
			if (reason.equals("stopped") || reason.equals("you left the arena"))
			{
				reward = 0;
			}
			r.reward = Math.min(reward, Integer.MAX_VALUE);
		}
		_last = r;
		cleanup(ev, true);
		if (r.reward > 0)
		{
			ev.starter.addAdena(ItemProcessType.REWARD, (int) r.reward, null, true);
		}
		if (_showResults && ev.starter.isOnline())
		{
			ThreadPool.schedule(() -> send(ev.starter, PvpPages.result(r)), 1500);
		}
	}

	/** Ends the event without results, for a player who is gone or an error. */
	private void abandon(Event ev)
	{
		cleanup(ev, ev.starter.isOnline());
	}

	/** Gives back a player the event still holds: to where they stood, with the HP, MP and CP they had. */
	private void putBack(Player p, boolean teleport)
	{
		final String[] f = p.getVariables().getString(AWAY, "").split(",");
		p.getVariables().remove(AWAY);
		p.getVariables().storeMe();
		if (f.length < 7)
		{
			return;
		}
		try
		{
			if (p.isDead())
			{
				p.doRevive();
			}
			p.setCurrentHp(Math.min(p.getMaxHp(), Math.max(1, Long.parseLong(f[4]))));
			p.setCurrentMp(Math.min(p.getMaxMp(), Long.parseLong(f[5])));
			p.setCurrentCp(Math.min(p.getMaxCp(), Long.parseLong(f[6])));
			if (teleport)
			{
				p.teleToLocation(new Location(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3])));
				p.sendMessage("PvP event: you were put back where you were when it took you.");
			}
		}
		catch (NumberFormatException e)
		{
			LOG.warning("PvP Events: bad saved position for " + p.getName() + ": " + e);
		}
	}

	private void cleanup(Event ev, boolean bringHome)
	{
		ev.phase = Phase.DONE;
		_teams.lock(ev.starter, false);
		for (Member m : new ArrayList<>(ev.members.values()))
		{
			try
			{
				_teams.disbandParty(m.player); // the event's parties end with it
			}
			catch (RuntimeException e)
			{
				LOG.warning("PvP Events: could not disband a party: " + e);
			}
		}
		for (Member m : new ArrayList<>(ev.members.values()))
		{
			if (m.bot)
			{
				_teams.discard(m.player);
			}
			else
			{
				_teams.leave(m.player);
			}
		}
		final Player p = ev.starter;
		_teams.leave(p);
		if (bringHome && p.isOnline())
		{
			// Only from inside the arena: someone who walked out (to town, to shop) is left where they are.
			if (inArena(p) && (ev.origin != null))
			{
				if (p.isDead())
				{
					p.doRevive();
				}
				// Back with the HP, MP and CP they came with, not healed.
				p.setCurrentHp(Math.min(p.getMaxHp(), Math.max(1, ev.hp)));
				p.setCurrentMp(Math.min(p.getMaxMp(), ev.mp));
				p.setCurrentCp(Math.min(p.getMaxCp(), ev.cp));
				p.teleToLocation(new Location(ev.origin.getX(), ev.origin.getY(), ev.origin.getZ(), ev.originHeading));
			}
			p.getVariables().remove(AWAY);
			p.getVariables().storeMe();
		}
		if (_event == ev)
		{
			_event = null;
		}
	}
}
