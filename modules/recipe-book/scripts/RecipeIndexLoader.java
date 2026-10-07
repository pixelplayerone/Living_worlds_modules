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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.l2jmobius.gameserver.data.holders.RecipeHolder;
import org.l2jmobius.gameserver.data.holders.RecipeStatHolder;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.data.xml.RecipeData;
import org.l2jmobius.gameserver.model.actor.enums.npc.DropType;
import org.l2jmobius.gameserver.model.actor.holders.npc.DropGroupHolder;
import org.l2jmobius.gameserver.model.actor.holders.npc.DropHolder;
import org.l2jmobius.gameserver.model.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.model.item.Armor;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.item.Weapon;
import org.l2jmobius.gameserver.model.item.enums.BodyPart;
import org.l2jmobius.gameserver.model.item.recipe.RecipeList;

import modules.recipebook.RecipeIndex.Category;
import modules.recipebook.RecipeIndex.DropSource;
import modules.recipebook.RecipeIndex.Ingredient;
import modules.recipebook.RecipeIndex.QuestSource;
import modules.recipebook.RecipeIndex.Recipe;

/** Reads the server's recipe, item, npc and quest-reward data into a {@link RecipeIndex}. Run once, lazily. */
final class RecipeIndexLoader
{
	private RecipeIndexLoader()
	{
	}

	static RecipeIndex load()
	{
		final RecipeIndex.Builder builder = new RecipeIndex.Builder();
		final Set<Integer> wanted = new HashSet<>(); // item ids worth tracking drops for: scrolls and ingredients

		for (RecipeList list : RecipeData.getInstance().getAllRecipes())
		{
			final int scrollId = list.getRecipeId();
			final ItemTemplate product = ItemData.getInstance().getTemplate(list.getItemId());
			if (product == null)
			{
				continue;
			}
			final List<Ingredient> ingredients = new ArrayList<>();
			for (RecipeHolder part : list.getRecipes())
			{
				ingredients.add(new Ingredient(part.getItemId(), part.getQuantity()));
				wanted.add(part.getItemId());
				builder.name(part.getItemId(), nameOf(part.getItemId()));
			}
			int mp = 0;
			for (RecipeStatHolder stat : list.getStatUse())
			{
				if (stat.getType().name().equals("MP"))
				{
					mp += stat.getValue();
				}
			}
			wanted.add(scrollId);
			builder.name(scrollId, nameOf(scrollId));
			builder.name(product.getId(), product.getName());
			builder.recipe(new Recipe(scrollId, list.getRecipeName(), product.getId(), product.getName(), list.getCount(), list.getLevel(), list.isDwarvenRecipe(), list.getSuccessRate(), mp, categoryOf(product), gradeOf(product), ingredients));
		}

		for (NpcTemplate npc : NpcData.getInstance().getTemplates(t -> (t.getDropGroups() != null) || (t.getDropList() != null) || (t.getSpoilList() != null)))
		{
			final boolean raid = npc.getType().equals("RaidBoss") || npc.getType().equals("GrandBoss");
			if (npc.getDropGroups() != null)
			{
				for (DropGroupHolder group : npc.getDropGroups())
				{
					for (DropHolder drop : group.getDropList())
					{
						add(builder, wanted, npc, drop, (group.getChance() / 100.0) * drop.getChance(), raid);
					}
				}
			}
			if (npc.getDropList() != null)
			{
				for (DropHolder drop : npc.getDropList())
				{
					add(builder, wanted, npc, drop, drop.getChance(), raid);
				}
			}
			if (npc.getSpoilList() != null)
			{
				for (DropHolder drop : npc.getSpoilList())
				{
					add(builder, wanted, npc, drop, drop.getChance(), raid);
				}
			}
		}

		final String[][] names = QuestSources.NAMES;
		for (int[] row : QuestSources.REWARDS)
		{
			String name = "Quest " + row[1];
			for (String[] n : names)
			{
				if (Integer.parseInt(n[0]) == row[1])
				{
					name = n[1];
					break;
				}
			}
			int startNpc = 0;
			for (int[] st : QuestSources.STARTS)
			{
				if (st[0] == row[1])
				{
					startNpc = st[1];
					break;
				}
			}
			final NpcTemplate start = startNpc > 0 ? NpcData.getInstance().getTemplate(startNpc) : null;
			builder.quest(row[0], new QuestSource(row[1], name, start != null ? startNpc : 0, start != null ? start.getName() : ""));
		}
		return builder.build();
	}

	private static void add(RecipeIndex.Builder builder, Set<Integer> wanted, NpcTemplate npc, DropHolder drop, double chance, boolean raid)
	{
		if (!wanted.contains(drop.getItemId()))
		{
			return;
		}
		builder.drop(drop.getItemId(), new DropSource(npc.getId(), npc.getName(), npc.getLevel(), chance, drop.getMin(), drop.getMax(), drop.getDropType() == DropType.SPOIL, raid));
	}

	private static String nameOf(int itemId)
	{
		final ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
		return (item != null) ? item.getName() : ("Item " + itemId);
	}

	private static int gradeOf(ItemTemplate item)
	{
		// ItemGrade ordinal order is NONE, D, C, B, A, S - the same order the book uses.
		return item.getItemGrade().ordinal();
	}

	private static Category categoryOf(ItemTemplate item)
	{
		if (item instanceof Weapon)
		{
			return Category.WEAPON;
		}
		if (item instanceof Armor)
		{
			final BodyPart part = item.getBodyPart();
			if ((part == BodyPart.R_EAR) || (part == BodyPart.L_EAR) || (part == BodyPart.LR_EAR) || (part == BodyPart.NECK) || (part == BodyPart.R_FINGER) || (part == BodyPart.L_FINGER) || (part == BodyPart.LR_FINGER))
			{
				return Category.JEWELRY;
			}
			return Category.ARMOR;
		}
		return Category.OTHER;
	}
}
