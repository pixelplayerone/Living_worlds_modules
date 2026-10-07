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

import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.actor.npc.attackable.OnAttackableKill;
import org.l2jmobius.gameserver.modules.GameModule;
import org.l2jmobius.gameserver.modules.ModuleContext;

/**
 * Hunting: a Community Board tab with Extermination Contracts (kills are counted in each of the server's named
 * hunting grounds, once each) and Bounties (one per raid boss, once each, with a bonus for clearing a level range).
 * Everything is tracked automatically, with nothing to accept. Progress is saved in player variables, so it survives
 * restarts.
 */
public class HuntingModule implements GameModule
{
	@Override
	public void onEnable(ModuleContext context)
	{
		if (!context.config().getBoolean("Enabled", false))
		{
			return; // Switch off: register nothing, behave as stock.
		}

		final HuntingRules rules = new HuntingRules(context.config().getString("GroundKills", "100,250,350,500,600,750,1000"), context.config().getString("GroundRewards", "100000,250000,500000,1000000,2000000,3000000,5000000"), context.config().getString("GroundSectionRewards", "1000000,2500000,5000000,10000000,15000000,30000000,40000000"), context.config().getDouble("RaidAdenaFactor", 400.0), context.config().getDouble("RaidBandBonus", 0.5), context.config().getDouble("GrandAdenaFactor", 2000.0), context.config().getBoolean("AdenaRewards", true));
		final HuntingBoard board = new HuntingBoard(rules);
		context.handlers().registerBoard(board);
		context.handlers().registerBoardTab(context.config().getString("TabLabel", "Hunting"), HuntingPages.CMD);
		context.events().<OnAttackableKill> onGlobal(EventType.ON_ATTACKABLE_KILL, board::onKill);
		context.handlers().registerVoicedCommand(new IVoicedCommandHandler()
		{
			@Override
			public boolean onCommand(String command, Player player, String params)
			{
				return board.onCommand(HuntingPages.CMD, player);
			}

			@Override
			public String[] getCommandList()
			{
				return new String[]
				{
					"hunting"
				};
			}
		});
		context.logging().info("Hunting enabled (tab and .hunting)." + (rules.paying() ? "" : " Adena rewards are off."));
	}
}
