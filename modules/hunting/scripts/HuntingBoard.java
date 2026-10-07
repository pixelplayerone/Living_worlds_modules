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
package modules.hunting;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IParseBoardHandler;
import org.l2jmobius.gameserver.managers.RaidBossSpawnManager;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.events.holders.actor.npc.attackable.OnAttackableKill;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.model.variables.PlayerVariables;
import org.l2jmobius.gameserver.network.serverpackets.RadarControl;
import org.l2jmobius.gameserver.network.serverpackets.ShowMiniMap;

/** The Community Board side of the hunting tab: parses {@code _bbs_hunting} commands, pays out, and counts kills. */
final class HuntingBoard implements IParseBoardHandler
{
	static final String VAR_GROUNDS = "HuntingGrounds";
	static final String VAR_RAIDS = "HuntingRaids";
	private static final String NAVIGATION_PATH = "data/html/CommunityBoard/Custom/navigation.html";
	/** Save to the database every this many counted kills; accept, finish and claim always save at once. */
	private static final int SAVE_EVERY = 10;

	/**
	 * Raid boss templates that nothing in this server ever spawns, so no player could fight them: Ice Fairy Sirra and the
	 * Captain of the Ice Queen's Royal Guard, the six Anakazel levels, Carnamakos, Master Anays, and the Souls of Fire
	 * and Water (Nastron, Ashutar), which only have quest kill listeners. Bosses an event, quest or script does spawn
	 * (Sailren, Lilith, Anakim, Daimon, Icicle Emperor Bumbalump, Eilhalder von Hellmann) stay on the list.
	 */
	private static final java.util.Set<Integer> NEVER_SPAWNED = new java.util.HashSet<>(java.util.Arrays.asList(29056, 29060, 25273, 25333, 25334, 25335, 25336, 25337, 25338, 25517, 25306, 25316));

	private final HuntingRules _rules;
	private final GroundIndex _grounds = GroundIndex.fromRows(GroundData.ROWS);
	private volatile HuntingPages _pages;

	HuntingBoard(HuntingRules rules)
	{
		_rules = rules;
	}

	@Override
	public String[] getCommandList()
	{
		return new String[]
		{
			HuntingPages.CMD
		};
	}

	/** Builds the raid list on first use, once every spawn is loaded. */
	private synchronized HuntingPages pages()
	{
		if (_pages == null)
		{
			final List<HuntingPages.RaidInfo> raids = new ArrayList<>();
			// Every raid boss a player can actually fight, including the ones an event, quest or script spawns.
			for (NpcTemplate t : NpcData.getInstance().getTemplates(template -> "RaidBoss".equals(template.getType()) && !NEVER_SPAWNED.contains(template.getId())))
			{
				raids.add(new HuntingPages.RaidInfo(t.getId(), t.getName(), t.getLevel()));
			}
			_pages = new HuntingPages(_rules, _grounds, raids);
			IParseBoardHandler.LOG.info("HuntingBoard: " + _grounds.all().size() + " hunting grounds, " + raids.size() + " bounties listed.");
		}
		return _pages;
	}

	private static HuntingState load(Player player)
	{
		final PlayerVariables v = player.getVariables();
		return HuntingState.parse(v.getString(VAR_GROUNDS, ""), v.getString(VAR_RAIDS, ""));
	}

	private static void save(Player player, HuntingState s, boolean now)
	{
		final PlayerVariables v = player.getVariables();
		v.set(VAR_GROUNDS, s.groundsText());
		v.set(VAR_RAIDS, s.raidsText());
		if (now)
		{
			v.storeMe();
		}
	}

	@Override
	public boolean onCommand(String command, Player player)
	{
		// One player's progress is read, changed and saved under one lock, so a kill cannot save an older copy over a claim.
		synchronized (PlayerLocks.of(player.getObjectId()))
		{
			return command(command, player);
		}
	}

	private boolean command(String command, Player player)
	{
		final HuntingPages pages = pages();
		final HuntingState state = load(player);
		final HuntingPages.Env env = () -> state;
		final String[] p = command.trim().split("\\s+");
		String html;
		try
		{
			final String sub = (p.length > 1) ? p[1].toLowerCase() : "home";
			switch (sub)
			{
				case "grounds":
				{
					html = pages.grounds(env);
					break;
				}
				case "gband":
				{
					html = pages.groundBand(band(p[2]), (p.length > 3) ? Integer.parseInt(p[3]) : 1, env);
					break;
				}
				case "claimg":
				{
					final GroundIndex.Ground g = pages.ground(p[2]);
					if ((g == null) || !state.claimGround(g.slug, pages.target()))
					{
						html = pages.notice("That contract is not finished yet.");
						break;
					}
					save(player, state, true);
					pay(player, _rules.groundReward(GroundIndex.gradeOf(g)));
					html = pages.groundBand(GroundIndex.gradeOf(g), 1, env);
					break;
				}
				case "claimgband":
				{
					final int band = band(p[2]);
					if (!state.claimGroundBand(band, pages.bandGroundSlugs(band)))
					{
						html = pages.notice("Claim every extermination contract in that range first, or the bonus is already paid.");
						break;
					}
					save(player, state, true);
					pay(player, pages.groundBandBonus(band));
					html = pages.groundBand(band, 1, env);
					break;
				}
				case "raids":
				{
					html = pages.raids(env);
					break;
				}
				case "rband":
				{
					html = pages.raidBand(band(p[2]), (p.length > 3) ? Integer.parseInt(p[3]) : 1, env);
					break;
				}
				case "claimr":
				{
					final int id = Integer.parseInt(p[2]);
					final HuntingPages.RaidInfo r = pages.raid(id);
					if ((r == null) || !state.claimRaid(id))
					{
						html = pages.notice("That contract is not ready to claim.");
						break;
					}
					save(player, state, true);
					pay(player, _rules.raidReward(r.level));
					html = pages.raidBand(HuntingRules.raidBandOf(r.level), 1, env);
					break;
				}
				case "claimband":
				{
					final int band = band(p[2]);
					if (!state.claimRaidBand(band, pages.bandBossIds(band)))
					{
						html = pages.notice("Claim every raid boss in that range first, or the bonus is already paid.");
						break;
					}
					save(player, state, true);
					pay(player, pages.bandReward(band));
					html = pages.raidBand(band, 1, env);
					break;
				}
				case "grand":
				{
					html = pages.grand(env);
					break;
				}
				case "claimgr":
				{
					final int id = Integer.parseInt(p[2]);
					final HuntingPages.GrandInfo g = HuntingPages.grandInfo(id);
					if ((g == null) || !state.claimRaid(id))
					{
						html = pages.notice("That grand boss is not ready to claim.");
						break;
					}
					save(player, state, true);
					pay(player, _rules.grandReward(g.level));
					html = pages.grand(env);
					break;
				}
				case "mine":
				{
					html = pages.mine(env);
					break;
				}
				case "clear":
				{
					player.getRadar().removeAllMarkers();
					player.sendMessage("Map marker cleared.");
					return true;
				}
				case "trace":
				{
					final GroundIndex.Ground g = pages.ground(p[2]);
					final HuntingPages.GrandInfo gb = p[2].startsWith("g") && !p[2].isEmpty() && Character.isDigit(p[2].charAt(p[2].length() - 1)) ? HuntingPages.grandInfo(Integer.parseInt(p[2].substring(1))) : null;
					if (gb != null)
					{
						mark(gb.x, gb.y, player);
					}
					else if (g != null)
					{
						mark(g.centerX(), g.centerY(), player);
					}
					else
					{
						trace(Integer.parseInt(p[2]), player);
					}
					return true;
				}
				default:
				{
					html = pages.home(env);
					break;
				}
			}
		}
		catch (RuntimeException e) // a hand-typed or stale bypass: show the front page, never an error
		{
			html = pages.home(env);
		}
		final String navigation = HtmCache.getInstance().getHtm(player, NAVIGATION_PATH);
		CommunityBoardHandler.separateAndSend(html.replace("%navigation%", (navigation != null) ? navigation : ""), player);
		return true;
	}

	/** Pays adena, or nothing when the rewards are switched off ({@code AdenaRewards = False}). */
	private static void pay(Player player, int adena)
	{
		if (adena > 0)
		{
			player.addAdena(ItemProcessType.REWARD, adena, null, true);
		}
	}

	private static int band(String s)
	{
		final int b = Integer.parseInt(s);
		if ((b < 0) || (b >= HuntingRules.RAID_BANDS))
		{
			throw new IllegalArgumentException("band");
		}
		return b;
	}

	/** Counts a kill in every hunting ground it happened in, or marks a raid boss slain. Nothing needs to be accepted. */
	void onKill(OnAttackableKill event)
	{
		final Player player = event.getAttacker();
		final Attackable target = event.getTarget();
		if ((player == null) || (target == null) || target.isRaidMinion() || (target.getInstanceId() != 0))
		{
			return;
		}
		synchronized (PlayerLocks.of(player.getObjectId()))
		{
			count(player, target);
		}
	}

	private void count(Player player, Attackable target)
	{
		final HuntingPages pages = pages();
		final PlayerVariables v = player.getVariables();
		final HuntingState s = HuntingState.parse(v.getString(VAR_GROUNDS, ""), v.getString(VAR_RAIDS, ""));
		if (target.isRaid())
		{
			final int grand = HuntingPages.grandId(target.getId());
			if (grand != 0)
			{
				if (s.raidKilled(grand))
				{
					save(player, s, true);
					player.sendMessage("Grand boss slain: " + target.getName() + ". Claim your reward in the Hunting tab.");
				}
				return;
			}
			if (pages.raid(target.getId()) != null)
			{
				if (s.raidKilled(target.getId()))
				{
					save(player, s, true);
					player.sendMessage("Bounty complete: " + target.getName() + " is slain. Claim your reward in the Hunting tab.");
				}
			}
			return;
		}
		if (!target.isMonster())
		{
			return;
		}
		final ToIntFunction<String> goal = pages.target();
		boolean changed = false;
		boolean finished = false;
		for (GroundIndex.Ground g : _grounds.at(target.getX(), target.getY()))
		{
			if (s.wantsKillIn(g.slug, goal))
			{
				changed = true;
				if (s.addKill(g.slug, goal))
				{
					finished = true;
					player.sendMessage("Extermination complete: " + g.name + ". Claim your reward in the Hunting tab.");
				}
			}
		}
		if (changed)
		{
			save(player, s, finished || ((s.groundProgressTotal() % SAVE_EVERY) == 0));
		}
	}

	private static void trace(int bossId, Player player)
	{
		final Spawn spawn = RaidBossSpawnManager.getInstance().getSpawns().get(bossId);
		if (spawn == null)
		{
			player.sendMessage("That boss is not placed in the world. An event, quest or script brings it out.");
			return;
		}
		mark(spawn.getX(), spawn.getY(), spawn.getZ(), player);
	}

	/** Marks a ground by its map position; the height is taken from the player, which is close enough for the marker. */
	private static void mark(int x, int y, Player player)
	{
		mark(x, y, player.getZ(), player);
	}

	private static void mark(int x, int y, int z, Player player)
	{
		player.getRadar().removeAllMarkers(); // one marker at a time: a new trace replaces the old one
		player.sendPacket(new ShowMiniMap(-1));
		ThreadPool.schedule(() ->
		{
			player.getRadar().addMarker(x, y, z);
			player.sendPacket(new RadarControl(0, 2, x, y, z));
		}, 500);
	}
}
