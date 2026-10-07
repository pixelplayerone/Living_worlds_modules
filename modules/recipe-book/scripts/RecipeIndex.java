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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The recipe book's dataset: pure Java, no server classes, so it is unit-tested on its own.
 * <p>
 * One index links four things that live in different data files: a recipe (what it makes and from what), the
 * recipe <b>scroll</b> (where you obtain the recipe), each ingredient (where it drops, which recipes use it) and
 * the quests that reward scrolls. Only recipes with a real source - a monster drop/spoil or a quest reward - are
 * kept, so every entry in the book is something a player can actually obtain.
 */
public final class RecipeIndex
{
	/** Product categories shown on the front page. */
	public enum Category
	{
		WEAPON("Weapons"),
		ARMOR("Armor"),
		JEWELRY("Jewelry"),
		OTHER("Other");

		private final String _label;

		Category(String label)
		{
			_label = label;
		}

		public String label()
		{
			return _label;
		}
	}

	/** Item grades, ordered. Index 0 is no-grade. */
	public static final String[] GRADES =
	{
		"No Grade",
		"D",
		"C",
		"B",
		"A",
		"S"
	};

	public static final class Ingredient
	{
		private final int _itemId;
		private final long _count;

		public Ingredient(int itemId, long count)
		{
			_itemId = itemId;
			_count = count;
		}

		public int itemId()
		{
			return _itemId;
		}

		public long count()
		{
			return _count;
		}
	}

	/** A monster that drops (or spoils) an item. {@code chance} is a base percentage, before server rates. */
	public static final class DropSource
	{
		private final int _npcId;
		private final String _npcName;
		private final int _level;
		private final double _chance;
		private final long _min;
		private final long _max;
		private final boolean _spoil;
		private final boolean _raid;

		public DropSource(int npcId, String npcName, int level, double chance, long min, long max, boolean spoil, boolean raid)
		{
			_npcId = npcId;
			_npcName = npcName;
			_level = level;
			_chance = chance;
			_min = min;
			_max = max;
			_spoil = spoil;
			_raid = raid;
		}

		public int npcId()
		{
			return _npcId;
		}

		public String npcName()
		{
			return _npcName;
		}

		public int level()
		{
			return _level;
		}

		public double chance()
		{
			return _chance;
		}

		public long min()
		{
			return _min;
		}

		public long max()
		{
			return _max;
		}

		public boolean spoil()
		{
			return _spoil;
		}

		public boolean raid()
		{
			return _raid;
		}
	}

	public static final class QuestSource
	{
		private final int _questId;
		private final String _name;
		private final int _startNpcId;
		private final String _startNpcName;

		public QuestSource(int questId, String name)
		{
			this(questId, name, 0, "");
		}

		public QuestSource(int questId, String name, int startNpcId, String startNpcName)
		{
			_questId = questId;
			_name = name;
			_startNpcId = startNpcId;
			_startNpcName = startNpcName;
		}

		/** Id of the NPC that hands the quest out, or 0 when unknown. */
		public int startNpcId()
		{
			return _startNpcId;
		}

		public String startNpcName()
		{
			return _startNpcName;
		}

		public int questId()
		{
			return _questId;
		}

		public String name()
		{
			return _name;
		}

		@Override
		public boolean equals(Object other)
		{
			return (other instanceof QuestSource) && (((QuestSource) other)._questId == _questId);
		}

		@Override
		public int hashCode()
		{
			return _questId;
		}
	}

	/** One craftable recipe. {@code scrollId} is the recipe item the player holds; it is also the recipe's key here. */
	public static final class Recipe
	{
		public final int scrollId;
		public final String name;
		public final int productId;
		public final String productName;
		public final int productCount;
		public final int craftLevel;
		public final boolean dwarven;
		public final int successRate;
		public final int mpCost;
		public final Category category;
		public final int grade;
		public final List<Ingredient> ingredients;

		public Recipe(int scrollId, String name, int productId, String productName, int productCount, int craftLevel, boolean dwarven, int successRate, int mpCost, Category category, int grade, List<Ingredient> ingredients)
		{
			this.scrollId = scrollId;
			this.name = name;
			this.productId = productId;
			this.productName = productName;
			this.productCount = productCount;
			this.craftLevel = craftLevel;
			this.dwarven = dwarven;
			this.successRate = successRate;
			this.mpCost = mpCost;
			this.category = category;
			this.grade = Math.max(0, Math.min(GRADES.length - 1, grade));
			this.ingredients = List.copyOf(ingredients);
		}
	}

	/** Collects raw rows, then {@link #build()} applies the source filter and the cross-links. */
	public static final class Builder
	{
		private final List<Recipe> _all = new ArrayList<>();
		private final Map<Integer, List<DropSource>> _drops = new HashMap<>();
		private final Map<Integer, List<QuestSource>> _quests = new HashMap<>();
		private final Map<Integer, String> _names = new HashMap<>();

		public Builder recipe(Recipe recipe)
		{
			_all.add(recipe);
			return this;
		}

		public Builder name(int itemId, String name)
		{
			_names.put(itemId, name);
			return this;
		}

		public Builder drop(int itemId, DropSource source)
		{
			_drops.computeIfAbsent(itemId, k -> new ArrayList<>()).add(source);
			return this;
		}

		public Builder quest(int itemId, QuestSource source)
		{
			final List<QuestSource> list = _quests.computeIfAbsent(itemId, k -> new ArrayList<>());
			if (!list.contains(source))
			{
				list.add(source);
			}
			return this;
		}

		public RecipeIndex build()
		{
			return new RecipeIndex(this);
		}
	}

	private final Map<Integer, Recipe> _recipes = new TreeMap<>();
	private final Map<Integer, List<DropSource>> _drops = new HashMap<>();
	private final Map<Integer, List<QuestSource>> _quests;
	private final Map<Integer, String> _names;
	private final Map<Integer, List<Recipe>> _usedIn = new HashMap<>();
	private final int _excluded;

	private RecipeIndex(Builder b)
	{
		_quests = b._quests;
		_names = b._names;
		int excluded = 0;
		for (Recipe recipe : b._all)
		{
			if (b._drops.containsKey(recipe.scrollId) || b._quests.containsKey(recipe.scrollId))
			{
				_recipes.put(recipe.scrollId, recipe);
			}
			else
			{
				excluded++; // no monster drops it and no quest rewards it: not obtainable, so not in the book
			}
		}
		_excluded = excluded;
		for (Map.Entry<Integer, List<DropSource>> e : b._drops.entrySet())
		{
			final List<DropSource> sorted = new ArrayList<>(e.getValue());
			sorted.sort(Comparator.comparingDouble(DropSource::chance).reversed().thenComparingInt(DropSource::level).thenComparingInt(DropSource::npcId));
			_drops.put(e.getKey(), Collections.unmodifiableList(sorted));
		}
		for (Recipe recipe : _recipes.values())
		{
			for (Ingredient ingredient : recipe.ingredients)
			{
				final List<Recipe> uses = _usedIn.computeIfAbsent(ingredient.itemId(), k -> new ArrayList<>());
				if (!uses.contains(recipe))
				{
					uses.add(recipe);
				}
			}
		}
		for (List<Recipe> uses : _usedIn.values())
		{
			uses.sort(Comparator.comparingInt((Recipe r) -> r.grade).thenComparing(r -> r.productName));
		}
	}

	/** @return the recipe whose scroll is {@code scrollId}, or {@code null} if it is not in the book */
	public Recipe recipe(int scrollId)
	{
		return _recipes.get(scrollId);
	}

	public int size()
	{
		return _recipes.size();
	}

	/** @return how many recipes were left out for having no drop or quest source */
	public int excludedCount()
	{
		return _excluded;
	}

	public String nameOf(int itemId)
	{
		final String name = _names.get(itemId);
		return (name != null) ? name : ("Item " + itemId);
	}

	/** @return the recipes of a category and grade, sorted by product name */
	public List<Recipe> list(Category category, int grade)
	{
		final List<Recipe> out = new ArrayList<>();
		for (Recipe r : _recipes.values())
		{
			if ((r.category == category) && (r.grade == grade))
			{
				out.add(r);
			}
		}
		out.sort(Comparator.comparing((Recipe r) -> r.productName).thenComparingInt(r -> r.scrollId));
		return out;
	}

	public int count(Category category, int grade)
	{
		int n = 0;
		for (Recipe r : _recipes.values())
		{
			if ((r.category == category) && (r.grade == grade))
			{
				n++;
			}
		}
		return n;
	}

	/** @return monsters that drop or spoil {@code itemId}, best chance first (never null) */
	public List<DropSource> dropsOf(int itemId)
	{
		final List<DropSource> list = _drops.get(itemId);
		return (list != null) ? list : List.of();
	}

	/** @return quests that reward {@code itemId} (never null) */
	public List<QuestSource> questsOf(int itemId)
	{
		final List<QuestSource> list = _quests.get(itemId);
		return (list != null) ? list : List.of();
	}

	/** @return the book's recipes that use {@code itemId} as an ingredient (never null) */
	public List<Recipe> usedIn(int itemId)
	{
		final List<Recipe> list = _usedIn.get(itemId);
		return (list != null) ? list : List.of();
	}

	/** @return {@code true} if this item shows up anywhere in the book as an ingredient, a product or a scroll */
	public boolean knowsItem(int itemId)
	{
		if (_usedIn.containsKey(itemId) || _recipes.containsKey(itemId))
		{
			return true;
		}
		for (Recipe r : _recipes.values())
		{
			if (r.productId == itemId)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Case-insensitive search over recipe name, product name, scroll name and ingredient names. Every word of the
	 * query must match somewhere.
	 * @param query the player's text
	 * @return matches sorted by grade then name (never null)
	 */
	public List<Recipe> search(String query)
	{
		final List<Recipe> out = new ArrayList<>();
		if (query == null)
		{
			return out;
		}
		final String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
		if ((words.length == 0) || words[0].isEmpty())
		{
			return out;
		}
		for (Recipe r : _recipes.values())
		{
			final StringBuilder hay = new StringBuilder();
			hay.append(r.name).append(' ').append(r.productName).append(' ').append(nameOf(r.scrollId));
			for (Ingredient ingredient : r.ingredients)
			{
				hay.append(' ').append(nameOf(ingredient.itemId()));
			}
			final String text = hay.toString().toLowerCase(Locale.ROOT);
			boolean all = true;
			for (String w : words)
			{
				if (!text.contains(w))
				{
					all = false;
					break;
				}
			}
			if (all)
			{
				out.add(r);
			}
		}
		out.sort(Comparator.comparingInt((Recipe r) -> r.grade).thenComparing(r -> r.productName));
		return out;
	}

	/**
	 * Adds up the ingredients of several goals.
	 * @param scrollIds the goal recipes; ids not in the book are skipped
	 * @return item id to total count needed, in a stable order
	 */
	public Map<Integer, Long> totals(List<Integer> scrollIds)
	{
		final Map<Integer, Long> totals = new TreeMap<>();
		for (int id : scrollIds)
		{
			final Recipe r = _recipes.get(id);
			if (r == null)
			{
				continue;
			}
			for (Ingredient ingredient : r.ingredients)
			{
				totals.merge(ingredient.itemId(), ingredient.count(), Long::sum);
			}
		}
		return totals;
	}
}
