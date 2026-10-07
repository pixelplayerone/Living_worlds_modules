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
package modules.recipebook;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.cache.HtmCache;
import org.l2jmobius.gameserver.config.RatesConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.xml.RecipeData;
import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IParseBoardHandler;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.item.recipe.RecipeList;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.network.serverpackets.RadarControl;
import org.l2jmobius.gameserver.network.serverpackets.ShowMiniMap;

import modules.recipebook.RecipeIndex.Category;
import modules.recipebook.RecipeIndex.DropSource;
import modules.recipebook.RecipeIndex.Recipe;

/** The Community Board side of the recipe book: parses the {@code _bbs_recipebook} commands and sends the pages. */
final class RecipeBoard implements IParseBoardHandler
{
	private static final String NAVIGATION_PATH = "data/html/CommunityBoard/Custom/navigation.html";
	private static final String GOALS_VAR = "RecipeBookGoals";

	private volatile RecipeIndex _index;
	private volatile RecipePages _pages;

	@Override
	public String[] getCommandList()
	{
		return new String[]
		{
			RecipePages.CMD
		};
	}

	/** Builds the index on first use, once the datapack is certainly loaded. */
	private synchronized RecipePages pages()
	{
		if (_pages == null)
		{
			_index = RecipeIndexLoader.load();
			_pages = new RecipePages(_index);
			IParseBoardHandler.LOG.info("RecipeBook: " + _index.size() + " recipes with a drop or quest source; " + _index.excludedCount() + " left out (no way to obtain them).");
		}
		return _pages;
	}

	@Override
	public boolean onCommand(String command, Player player)
	{
		final RecipePages pages = pages();
		final Env env = new Env(player, _index);
		final String[] p = command.trim().split("\\s+");
		String html;
		try
		{
			final String sub = (p.length > 1) ? p[1].toLowerCase() : "home";
			switch (sub)
			{
				case "list":
				{
					html = pages.list(Category.valueOf(p[2]), Integer.parseInt(p[3]), (p.length > 4) ? Integer.parseInt(p[4]) : 1, env);
					break;
				}
				case "view":
				{
					final Recipe r = _index.recipe(Integer.parseInt(p[2]));
					html = (r != null) ? pages.view(r, env) : pages.notice("That recipe is not in the book.");
					break;
				}
				case "item":
				{
					final int id = Integer.parseInt(p[2]);
					html = _index.knowsItem(id) ? pages.item(id, (p.length > 3) ? Integer.parseInt(p[3]) : 1, (p.length > 4) ? Integer.parseInt(p[4]) : 1, env) : pages.notice("That item is not part of any recipe in the book.");
					break;
				}
				case "search":
				{
					html = pages.search(String.join(" ", java.util.Arrays.copyOfRange(p, 2, p.length)), env);
					break;
				}
				case "goals":
				{
					html = pages.goals(env);
					break;
				}
				case "goal":
				{
					html = goal(p, player, pages, env);
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

	private String goal(String[] p, Player player, RecipePages pages, Env env)
	{
		final List<Integer> goals = new ArrayList<>(env.goals());
		final String action = p[2].toLowerCase();
		if (action.equals("clear"))
		{
			goals.clear();
		}
		else
		{
			final int id = Integer.parseInt(p[3]);
			if (action.equals("add") && (_index.recipe(id) != null) && !goals.contains(id))
			{
				if (goals.size() >= RecipePages.MAX_GOALS)
				{
					return pages.notice("Goal list is full (" + RecipePages.MAX_GOALS + "). Remove one first.");
				}
				goals.add(id);
			}
			else if (action.equals("del"))
			{
				goals.remove(Integer.valueOf(id));
			}
		}
		final StringBuilder sb = new StringBuilder();
		for (int id : goals)
		{
			sb.append(sb.length() > 0 ? "," : "").append(id);
		}
		if (sb.length() == 0)
		{
			player.getVariables().remove(GOALS_VAR);
		}
		else
		{
			player.getVariables().set(GOALS_VAR, sb.toString());
		}
		player.getVariables().storeMe();
		return pages.goals(new Env(player, _index));
	}

	private static void trace(int npcId, Player player)
	{
		final Spawn spawn = SpawnTable.getInstance().getAnySpawn(npcId);
		if (spawn == null)
		{
			player.sendMessage("No spawn found for that one. It may be a boss, an instance monster, or an NPC that is not placed in the world.");
			return;
		}
		player.getRadar().removeAllMarkers(); // one marker at a time: tracing a new monster replaces the old one
		player.sendPacket(new ShowMiniMap(-1));
		ThreadPool.schedule(() ->
		{
			player.getRadar().addMarker(spawn.getX(), spawn.getY(), spawn.getZ());
			player.sendPacket(new RadarControl(0, 2, spawn.getX(), spawn.getY(), spawn.getZ()));
		}, 500);
	}

	/** Player-specific answers for the page builder. */
	private static final class Env implements RecipePages.Env
	{
		private final Player _player;
		private final RecipeIndex _index;

		Env(Player player, RecipeIndex index)
		{
			_player = player;
			_index = index;
		}

		@Override
		public long have(int itemId)
		{
			return (long) _player.getInventory().getInventoryItemCount(itemId, -1) + _player.getWarehouse().getInventoryItemCount(itemId, -1);
		}

		@Override
		public double chance(DropSource source, int itemId)
		{
			double rate;
			if (source.spoil())
			{
				rate = RatesConfig.RATE_SPOIL_DROP_CHANCE_MULTIPLIER;
			}
			else if (RatesConfig.RATE_DROP_CHANCE_BY_ID.get(itemId) != null)
			{
				rate = RatesConfig.RATE_DROP_CHANCE_BY_ID.get(itemId);
			}
			else if (source.raid())
			{
				rate = RatesConfig.RATE_RAID_DROP_CHANCE_MULTIPLIER;
			}
			else
			{
				rate = RatesConfig.RATE_DEATH_DROP_CHANCE_MULTIPLIER;
			}
			return Math.min(100, source.chance() * rate);
		}

		@Override
		public List<Integer> goals()
		{
			final List<Integer> out = new ArrayList<>();
			final String raw = _player.getVariables().getString(GOALS_VAR, "");
			for (String part : raw.split(","))
			{
				try
				{
					if (!part.isEmpty())
					{
						out.add(Integer.parseInt(part.trim()));
					}
				}
				catch (NumberFormatException e)
				{
					// skip a damaged entry
				}
			}
			return out;
		}

		@Override
		public boolean learned(Recipe recipe)
		{
			final RecipeList list = RecipeData.getInstance().getRecipeByItemId(recipe.scrollId);
			return (list != null) && _player.hasRecipeList(list.getId());
		}
	}
}
