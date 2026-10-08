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
package modules.autopotions;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.handler.IItemHandler;
import org.l2jmobius.gameserver.handler.ItemHandler;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogout;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.skill.holders.SkillHolder;
import org.l2jmobius.gameserver.network.serverpackets.ExUseSharedGroupItem;
import org.l2jmobius.gameserver.modules.GameModule;
import org.l2jmobius.gameserver.modules.ModuleConfig;
import org.l2jmobius.gameserver.modules.ModuleContext;

/**
 * Auto Potions: one command, {@code .pots}, switches automatic potion drinking on and off. While it is on, CP, HP and
 * MP potions are drunk the moment a stat drops under its line, as fast as each potion's own reuse time allows (CP and
 * mana potions every half second). If the best potion is cooling down, the next one in the list is used. Each line can
 * be changed per player: {@code .pots hp 60}, {@code .pots cp off}.
 * <p>
 * The stock {@code .apon} / {@code .apoff} auto-potion (AutoPotions.ini) is a separate, once-a-second feature and is
 * left alone.
 */
public class AutoPotionsModule implements GameModule
{
	private static final String VAR = "PotionToggle";
	private static final String LINES = "PotionLines";

	private final Map<Integer, PotionLogic.Settings> _on = new ConcurrentHashMap<>();
	private final Map<Integer, Player> _players = new ConcurrentHashMap<>();
	private int[][] _ids;
	private int[][] _parallel;
	private int _defaultCp;
	private int _defaultHp;
	private int _defaultMp;
	private boolean _olympiad;
	private boolean _messages;
	private boolean _warn;
	private boolean _remember;

	@Override
	public void onEnable(ModuleContext context)
	{
		final ModuleConfig config = context.config();
		if (!config.getBoolean("Enabled", false))
		{
			return; // Switch off: register nothing, behave as stock.
		}
		_ids = new int[][]
		{
			PotionLogic.parseIds(config.getString("CpItemIds", "5592,5591")),
			PotionLogic.parseIds(config.getString("HpItemIds", "1061,1060")),
			PotionLogic.parseIds(config.getString("MpItemIds", "728"))
		};
		_parallel = new int[][]
		{
			PotionLogic.parseIds(config.getString("CpParallelIds", "")),
			PotionLogic.parseIds(config.getString("HpParallelIds", "1540,1539")),
			PotionLogic.parseIds(config.getString("MpParallelIds", ""))
		};
		_defaultCp = config.getInt("DefaultCpPercent", 90);
		_defaultHp = config.getInt("DefaultHpPercent", 70);
		_defaultMp = config.getInt("DefaultMpPercent", 70);
		_olympiad = config.getBoolean("AllowInOlympiad", false);
		_messages = config.getBoolean("Messages", false);
		_warn = config.getBoolean("OutOfPotionsWarning", true);
		_remember = config.getBoolean("RememberAcrossLogin", true);
		final long tick = Math.max(100, config.getInt("TickMillis", 250));

		context.handlers().registerVoicedCommand(new IVoicedCommandHandler()
		{
			@Override
			public boolean onCommand(String command, Player player, String params)
			{
				final PotionLogic.Settings s = _on.computeIfAbsent(player.getObjectId(), k -> new PotionLogic.Settings(_defaultCp, _defaultHp, _defaultMp));
				_players.put(player.getObjectId(), player);
				player.sendMessage(PotionLogic.apply(s, params));
				if (_remember)
				{
					player.getVariables().set(VAR, s.on ? "1" : "0");
					player.getVariables().set(LINES, PotionLogic.encode(s));
					player.getVariables().storeMe();
				}
				return true;
			}

			@Override
			public String[] getCommandList()
			{
				return new String[]
				{
					"pots"
				};
			}
		});
		context.events().<OnPlayerLogin> onGlobal(EventType.ON_PLAYER_LOGIN, event ->
		{
			final Player p = event.getPlayer();
			if (_remember && (p != null) && "1".equals(p.getVariables().getString(VAR, "0")))
			{
				final PotionLogic.Settings s = new PotionLogic.Settings(_defaultCp, _defaultHp, _defaultMp);
				s.on = true;
				PotionLogic.decode(s, p.getVariables().getString(LINES, null));
				_on.put(p.getObjectId(), s);
				_players.put(p.getObjectId(), p);
			}
		});
		context.events().<OnPlayerLogout> onGlobal(EventType.ON_PLAYER_LOGOUT, event ->
		{
			if (event.getPlayer() != null)
			{
				_on.remove(event.getPlayer().getObjectId());
				_players.remove(event.getPlayer().getObjectId());
			}
		});
		ThreadPool.scheduleAtFixedRate(this::tick, tick, tick);
		context.logging().info("Auto Potions enabled (.pots).");
	}

	private void tick()
	{
		for (Map.Entry<Integer, PotionLogic.Settings> e : _on.entrySet())
		{
			final PotionLogic.Settings s = e.getValue();
			final Player player = _players.get(e.getKey());
			if (!s.on || (player == null) || !player.isOnline() || player.isAlikeDead() || !canUseItems(player) || (!_olympiad && player.isInOlympiadMode()))
			{
				continue;
			}
			for (int kind = 0; kind < 3; kind++)
			{
				final int current = (kind == PotionLogic.CP) ? player.getCurrentCpPercent() : (kind == PotionLogic.HP) ? player.getCurrentHpPercent() : player.getCurrentMpPercent();
				if (!PotionLogic.needs(current, s.percent[kind]))
				{
					s.warned[kind] = false;
					continue;
				}
				drink(player, s, kind);
			}
		}
	}

	private void drink(Player player, PotionLogic.Settings s, int kind)
	{
		final int[] ids = _ids[kind];
		final int[] parallel = _parallel[kind];
		if (parallel.length > 0)
		{
			// Every potion in the parallel list is drunk whenever it is off reuse, so a quick potion and a greater
			// potion both run on their own timers. Only if the player carries none of them does the fallback list apply.
			final int[] ready = PotionLogic.ready(parallel, id -> countOf(player, id), id -> reuseOf(player, id));
			if (ready.length > 0)
			{
				for (int id : ready)
				{
					use(player, id, kind);
				}
				return;
			}
			if (PotionLogic.owns(parallel, id -> countOf(player, id)))
			{
				return; // all cooling down: wait for the next tick
			}
		}
		final int pick = PotionLogic.choose(ids, id ->
		{
			final Item item = player.getInventory().getItemByItemId(id);
			return (item == null) ? 0 : item.getCount();
		}, id -> reuseOf(player, id));
		if (pick == PotionLogic.NONE_OWNED)
		{
			if (_warn && !s.warned[kind])
			{
				s.warned[kind] = true;
				player.sendMessage("Potions: out of " + PotionLogic.NAMES[kind] + " potions.");
			}
			return;
		}
		if (pick == PotionLogic.ALL_ON_COOLDOWN)
		{
			return; // try again next tick
		}
		use(player, pick, kind);
	}

	private static long countOf(Player player, int id)
	{
		final Item item = player.getInventory().getItemByItemId(id);
		return (item == null) ? 0 : item.getCount();
	}

	/** How long until this potion can be drunk: its own reuse, the shared group it belongs to, and its skill's reuse, whichever is longest. */
	private static long reuseOf(Player player, int id)
	{
		final Item item = player.getInventory().getItemByItemId(id);
		return (item == null) ? 0 : reuseLeft(player, item);
	}

	private static long reuseLeft(Player player, Item item)
	{
		long skillMs = 0;
		final SkillHolder[] skills = item.getTemplate().getSkills();
		if (skills != null)
		{
			for (SkillHolder holder : skills)
			{
				if ((holder.getSkill() != null) && (holder.getSkill().getReuseDelay() > 0))
				{
					skillMs = Math.max(skillMs, player.getSkillRemainingReuseTime(holder.getSkill().getReuseHashCode()));
				}
			}
		}
		if (item.getReuseDelay() <= 0)
		{
			return PotionLogic.reuseLeft(-1, -1, skillMs);
		}
		return PotionLogic.reuseLeft(player.getItemRemainingReuseTime(item.getObjectId()), player.getReuseDelayOnGroup(item.getSharedReuseGroup()), skillMs);
	}

	/** The same conditions a click on an item checks: not stunned, asleep, afraid, paralyzed, trading or in a private store. */
	private static boolean canUseItems(Player player)
	{
		return !player.isStunned() && !player.isSleeping() && !player.isAfraid() && !player.isParalyzed() && !player.isInStoreMode() && (player.getActiveTradeList() == null);
	}

	private void use(Player player, int itemId, int kind)
	{
		final Item potion = player.getInventory().getItemByItemId(itemId);
		if ((potion == null) || (potion.getEtcItem() == null))
		{
			return;
		}
		final IItemHandler handler = ItemHandler.getInstance().getHandler(potion.getEtcItem());
		if (handler == null)
		{
			return;
		}
		if (reuseLeft(player, potion) > 0)
		{
			return; // still cooling down: do not ask the handler, it would answer with a re-use line every tick
		}
		try
		{
			// A click starts the item's reuse timer and the shared group's after a successful use; do the same, or this
			// potion is never seen as cooling down and is drunk every tick.
			final int reuseDelay = potion.getReuseDelay();
			final int group = potion.getSharedReuseGroup();
			if (!handler.onItemUse(player, potion, false))
			{
				return;
			}
			if (reuseDelay > 0)
			{
				player.addTimeStampItem(potion, reuseDelay);
				if (group > 0)
				{
					player.sendPacket(new ExUseSharedGroupItem(itemId, group, reuseDelay, reuseDelay));
				}
			}
			if (_messages)
			{
				player.sendMessage("Potions: " + PotionLogic.NAMES[kind] + " potion used.");
			}
		}
		catch (Exception ex)
		{
			// a potion that cannot be used right now is not worth more than a skipped tick
		}
	}
}
