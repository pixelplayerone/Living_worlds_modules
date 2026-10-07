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

import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.modules.GameModule;
import org.l2jmobius.gameserver.modules.ModuleContext;

/**
 * Recipe Book: a Community Board tab that lists every craftable recipe a player can actually obtain (monster drop or
 * quest reward), shows what each needs against what the player holds, where every ingredient comes from, and keeps a
 * personal goal list with a combined shopping list. Browse-only: it changes nothing in the world.
 */
public class RecipeBookModule implements GameModule
{
	@Override
	public void onEnable(ModuleContext context)
	{
		if (!context.config().getBoolean("Enabled", false))
		{
			return; // Switch off: register nothing, behave as stock.
		}

		final RecipeBoard board = new RecipeBoard();
		context.handlers().registerBoard(board);
		context.handlers().registerBoardTab(context.config().getString("TabLabel", "Recipe Book"), RecipePages.CMD);

		context.handlers().registerVoicedCommand(new IVoicedCommandHandler()
		{
			@Override
			public boolean onCommand(String command, Player player, String params)
			{
				if (command.equalsIgnoreCase("clearmark"))
				{
					player.getRadar().removeAllMarkers();
					player.sendMessage("Map marker cleared.");
					return true;
				}
				return board.onCommand(RecipePages.CMD, player);
			}

			@Override
			public String[] getCommandList()
			{
				return new String[]
				{
					"recipebook",
					"clearmark"
				};
			}
		});
		context.logging().info("Recipe Book enabled (tab and .recipebook). The index is built on first use.");
	}
}
