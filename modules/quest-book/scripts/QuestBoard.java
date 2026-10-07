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
package modules.questbook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IParseBoardHandler;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.script.Quest;
import org.l2jmobius.gameserver.model.script.QuestState;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.network.serverpackets.RadarControl;
import org.l2jmobius.gameserver.network.serverpackets.ShowMiniMap;

/** The Community Board side of the quest book: parses {@code _bbs_questbook} commands and sends the pages. */
final class QuestBoard implements IParseBoardHandler
{
	private static final String NAVIGATION_PATH = "data/html/CommunityBoard/Custom/navigation.html";

	private volatile QuestIndex _index;
	private volatile QuestPages _pages;

	@Override
	public String[] getCommandList()
	{
		return new String[]
		{
			QuestPages.CMD
		};
	}

	/** Builds the index on first use. */
	private synchronized QuestPages pages()
	{
		if (_pages == null)
		{
			_index = QuestIndex.fromRows(QuestData.ROWS);
			_pages = new QuestPages(_index);
			IParseBoardHandler.LOG.info("QuestBook: " + _index.size() + " quests indexed.");
		}
		return _pages;
	}

	@Override
	public boolean onCommand(String command, Player player)
	{
		final QuestPages pages = pages();
		final Env env = new Env(player);
		final String[] p = command.trim().split("\\s+");
		String html;
		try
		{
			final String sub = (p.length > 1) ? p[1].toLowerCase() : "home";
			switch (sub)
			{
				case "list":
				{
					html = pages.list(Integer.parseInt(p[2]), Integer.parseInt(p[3]), (p.length > 4) ? Integer.parseInt(p[4]) : 1, env);
					break;
				}
				case "view":
				{
					final QuestIndex.Quest q = _index.quest(Integer.parseInt(p[2]));
					html = (q != null) ? pages.view(q, env) : pages.notice("That quest is not in the book.");
					break;
				}
				case "search":
				{
					html = pages.search(String.join(" ", Arrays.copyOfRange(p, 2, p.length)), env);
					break;
				}
				case "active":
				{
					html = pages.active(env);
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
					trace(Integer.parseInt(p[2]), player);
					return true; // the map and marker are the answer; the page stays as it is
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

	private static void trace(int npcId, Player player)
	{
		final Spawn spawn = SpawnTable.getInstance().getAnySpawn(npcId);
		if (spawn == null)
		{
			player.sendMessage("No spawn found for that one. It may be a boss, an instance monster, or an NPC that is not placed in the world.");
			return;
		}
		player.getRadar().removeAllMarkers(); // one marker at a time: a new trace replaces the old one
		player.sendPacket(new ShowMiniMap(-1));
		ThreadPool.schedule(() ->
		{
			player.getRadar().addMarker(spawn.getX(), spawn.getY(), spawn.getZ());
			player.sendPacket(new RadarControl(0, 2, spawn.getX(), spawn.getY(), spawn.getZ()));
		}, 500);
	}

	/** Player-specific answers for the page builder. */
	private static final class Env implements QuestPages.Env
	{
		private final Player _player;

		Env(Player player)
		{
			_player = player;
		}

		@Override
		public String itemName(int itemId)
		{
			final ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
			return (item != null) ? item.getName() : ("Item " + itemId);
		}

		@Override
		public String npcName(int npcId)
		{
			final NpcTemplate npc = NpcData.getInstance().getTemplate(npcId);
			return (npc != null) ? npc.getName() : ("NPC " + npcId);
		}

		@Override
		public int status(QuestIndex.Quest quest)
		{
			final QuestState state = _player.getQuestState(quest.script);
			if (state == null)
			{
				return QuestPages.STATUS_NONE;
			}
			if (state.isCompleted())
			{
				return QuestPages.STATUS_DONE;
			}
			return state.isStarted() ? QuestPages.STATUS_ACTIVE : QuestPages.STATUS_NONE;
		}

		@Override
		public int playerLevel()
		{
			return _player.getLevel();
		}

		@Override
		public List<Integer> activeIds()
		{
			final List<Integer> ids = new ArrayList<>();
			for (Quest quest : _player.getAllActiveQuests())
			{
				ids.add(quest.getId());
			}
			return ids;
		}
	}
}
