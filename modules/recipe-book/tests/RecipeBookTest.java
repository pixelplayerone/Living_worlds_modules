package modules.recipebook;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import modules.recipebook.RecipeIndex.Category;
import modules.recipebook.RecipeIndex.DropSource;
import modules.recipebook.RecipeIndex.Ingredient;
import modules.recipebook.RecipeIndex.QuestSource;
import modules.recipebook.RecipeIndex.Recipe;

/**
 * Standalone checks for the recipe book's index and pages. No server needed:
 * javac -d out scripts/RecipeIndex.java scripts/RecipePages.java tests/RecipeBookTest.java && java -cp out modules.recipebook.RecipeBookTest
 */
public class RecipeBookTest
{
	private static int passed;
	private static int failed;

	private static void check(String name, boolean ok)
	{
		if (ok)
		{
			passed++;
		}
		else
		{
			failed++;
			System.out.println("FAIL: " + name);
		}
	}

	private static Recipe recipe(int scroll, String product, Category cat, int grade, int success, Ingredient... parts)
	{
		return new Recipe(scroll, product, scroll + 5000, product, 1, 3, true, success, 30, cat, grade, List.of(parts));
	}

	private static DropSource drop(int npc, String name, int lvl, double chance, boolean spoil, boolean raid)
	{
		return new DropSource(npc, name, lvl, chance, 1, 1, spoil, raid);
	}

	static final class FakeEnv implements RecipePages.Env
	{
		final Map<Integer, Long> held = new java.util.HashMap<>();
		final List<Integer> goals = new ArrayList<>();
		final List<Integer> learned = new ArrayList<>();

		@Override
		public long have(int itemId)
		{
			return held.getOrDefault(itemId, 0L);
		}

		@Override
		public double chance(DropSource s, int itemId)
		{
			return Math.min(100, s.chance() * 2); // pretend the server runs x2
		}

		@Override
		public List<Integer> goals()
		{
			return goals;
		}

		@Override
		public boolean learned(Recipe r)
		{
			return learned.contains(r.scrollId);
		}
	}

	private static int count(String h, String t)
	{
		int n = 0;
		int i = 0;
		while ((i = h.indexOf(t, i)) >= 0)
		{
			n++;
			i += t.length();
		}
		return n;
	}

	private static boolean balanced(String h)
	{
		for (String t : new String[]
		{
			"table",
			"tr",
			"td",
			"a",
			"font"
		})
		{
			if ((count(h, "<" + t + " ") + count(h, "<" + t + ">")) != count(h, "</" + t + ">"))
			{
				return false;
			}
		}
		return true;
	}

	public static void main(String[] args)
	{
		// ---- data: 6 recipes, only some obtainable ----
		final RecipeIndex.Builder b = new RecipeIndex.Builder();
		b.name(1, "Charcoal").name(2, "Animal Bone").name(3, "Steel & Iron").name(4, "Mithril Alloy");
		b.name(100, "Recipe: Short Sword (100%)").name(101, "Recipe: Broad Sword (60%)").name(102, "Recipe: Quest Ring (100%)").name(103, "Recipe: Unobtainable Helm (100%)").name(104, "Recipe: Spoil Bow (100%)").name(105, "Recipe: Big <Axe> \"X\"");
		b.recipe(recipe(100, "Short Sword", Category.WEAPON, 1, 100, new Ingredient(1, 10), new Ingredient(2, 5)));
		b.recipe(recipe(101, "Broad Sword", Category.WEAPON, 1, 60, new Ingredient(1, 20), new Ingredient(3, 2)));
		b.recipe(recipe(102, "Quest Ring", Category.JEWELRY, 2, 100, new Ingredient(4, 1)));
		b.recipe(recipe(103, "Unobtainable Helm", Category.ARMOR, 1, 100, new Ingredient(1, 1))); // 100%, no source
		b.recipe(recipe(104, "Spoil Bow", Category.WEAPON, 1, 100, new Ingredient(2, 7)));
		b.recipe(recipe(105, "Big <Axe> \"X\"", Category.WEAPON, 3, 60, new Ingredient(3, 1)));
		b.drop(100, drop(20001, "Gremlin", 5, 0.5, false, false));
		b.drop(100, drop(20002, "Goblin", 9, 2.5, false, false));
		b.drop(100, drop(20003, "Spoiled Rat", 3, 1.0, true, false));
		b.drop(101, drop(20004, "Big Boss", 40, 3.0, false, true));
		b.drop(104, drop(20005, "Spoiler Target", 12, 4.0, true, false));
		b.drop(105, drop(20006, "Axe Carrier", 30, 1.0, false, false));
		b.quest(102, new QuestSource(216, "Trial Of The Guildsman", 30103, "Valkon"));
		b.drop(1, drop(30001, "Charcoal Beast", 8, 10, false, false));
		final RecipeIndex idx = b.build();
		final RecipePages pg = new RecipePages(idx);
		final FakeEnv env = new FakeEnv();

		// ---- the source filter ----
		final String viewPage = pg.view(idx.recipe(102), env);
		check("quest line links to its start NPC", viewPage.contains("bypass " + RecipePages.CMD + " trace 30103") && viewPage.contains("Starts with Valkon") && viewPage.contains(">Quest: Trial Of The Guildsman (Q216)</a>"));
		check("5 obtainable recipes kept", idx.size() == 5);
		check("1 unobtainable excluded", idx.excludedCount() == 1);
		check("100% recipe with no source excluded", idx.recipe(103) == null);
		check("100% recipe WITH a drop kept", idx.recipe(100) != null);
		check("quest-only recipe kept", idx.recipe(102) != null);
		check("spoil-only recipe kept", idx.recipe(104) != null);
		check("excluded recipe is not searchable", idx.search("unobtainable").isEmpty());
		check("excluded recipe is not an ingredient user", idx.usedIn(1).stream().noneMatch(r -> r.scrollId == 103));

		// ---- browsing ----
		check("count weapons NG", idx.count(Category.WEAPON, 1) == 3);
		check("count armor NG is zero (only the excluded one)", idx.count(Category.ARMOR, 1) == 0);
		check("list sorted by product name", idx.list(Category.WEAPON, 1).get(0).productName.equals("Broad Sword"));
		check("list has 3 weapons", idx.list(Category.WEAPON, 1).size() == 3);

		// ---- sources ----
		check("drops sorted best chance first", idx.dropsOf(100).get(0).npcName().equals("Goblin"));
		check("quest source returned", idx.questsOf(102).get(0).name().equals("Trial Of The Guildsman"));
		check("no sources returns empty list", idx.dropsOf(999).isEmpty() && idx.questsOf(999).isEmpty());

		// ---- search ----
		check("search by product", idx.search("short sword").size() == 1);
		check("search is case-insensitive", idx.search("SHORT").size() == 1);
		check("search by ingredient name", idx.search("charcoal").size() == 2);
		check("every word must match", idx.search("charcoal bone").size() == 1);
		check("empty query finds nothing", idx.search("   ").isEmpty() && idx.search(null).isEmpty());
		check("search sorted by grade", idx.search("o").get(0).grade <= idx.search("o").get(idx.search("o").size() - 1).grade);

		// ---- uses / totals ----
		check("usedIn lists both recipes using charcoal", idx.usedIn(1).size() == 2);
		final Map<Integer, Long> totals = idx.totals(List.of(100, 101, 9999));
		check("totals add up across goals", totals.get(1) == 30L && totals.get(2) == 5L && totals.get(3) == 2L);
		check("unknown goal id ignored", !totals.containsKey(9999));
		check("knowsItem for ingredient, product, scroll", idx.knowsItem(1) && idx.knowsItem(100 + 5000) && idx.knowsItem(100));
		check("knowsItem false for stranger", !idx.knowsItem(777));

		// ---- pages ----
		final String home = pg.home(env);
		check("home has navigation marker once", count(home, "%navigation%") == 1);
		check("home links a populated cell", home.contains("list WEAPON 1 1"));
		check("home does not link an empty cell", !home.contains("list ARMOR 1 1"));
		check("home shows the recipe count", home.contains("5 recipes in the book"));
		check("home offers Clear map marker", home.contains("recipebook clear"));
		check("home balanced", balanced(home));

		final String list = pg.list(Category.WEAPON, 1, 1, env);
		check("list shows recipes and links", list.contains("view 100") && list.contains("Short Sword"));
		check("list says Drop for drop recipe", list.contains(">Drop<"));
		check("list page clamps high", pg.list(Category.WEAPON, 1, 99, env).contains("Short Sword"));
		check("list page clamps low", pg.list(Category.WEAPON, 1, -4, env).contains("Short Sword"));
		check("list balanced", balanced(list));
		check("quest recipe tagged Quest", pg.list(Category.JEWELRY, 2, 1, env).contains(">Quest<"));

		env.held.put(1, 4L);
		env.held.put(2, 5L);
		final String view = pg.view(idx.recipe(100), env);
		check("view lists ingredients with links", view.contains("item 1 1") && view.contains("Animal Bone"));
		check("short ingredient is red, enough is green", view.contains("color=\"FF6666\">4<") && view.contains("color=\"66FF66\">5<"));
		check("view shows the recipe's own drop sources", view.contains("Goblin") && view.contains("Gremlin"));
		check("view applies server rate to chance", view.contains("5%")); // 2.5 x2
		check("view offers Add to goals", view.contains("goal add 100") && !view.contains("goal del 100"));
		check("view balanced", balanced(view));
		env.goals.add(100);
		check("view offers Remove when pinned", pg.view(idx.recipe(100), env).contains("goal del 100"));
		check("view of quest recipe shows quest", pg.view(idx.recipe(102), env).contains("Trial Of The Guildsman"));
		check("learned flag shown", pg.view(idx.recipe(100), withLearned(env, 100)).contains("learned"));
		check("boss marked", pg.view(idx.recipe(101), env).contains("(boss)"));
		check("spoil labelled", pg.view(idx.recipe(104), env).contains(">Spoil<"));

		final String item = pg.item(1, 1, env);
		check("item page shows where it drops", item.contains("Charcoal Beast"));
		check("item page lists recipes that use it", item.contains("Used in 2 recipes") && item.contains("Short Sword"));
		check("item with no source says merchant/craft", pg.item(2, 1, env).contains("merchant"));
		check("scroll item teaches its recipe", pg.item(100, 1, env).contains("Teaches"));
		check("item page balanced", balanced(item));

		// ---- escaping ----
		final String esc = pg.view(idx.recipe(105), env);
		check("product name escaped", esc.contains("Big &lt;Axe&gt; &quot;X&quot;") && !esc.contains("<Axe>"));
		check("ampersand in ingredient escaped", pg.view(idx.recipe(101), env).contains("Steel &amp; Iron"));
		final String hostile = pg.search("<script>\"onload\"</script>", env);
		check("search query escaped", !hostile.contains("<script>") && hostile.contains("&lt;script&gt;"));
		check("search page balanced", balanced(hostile));
		check("search no match message", hostile.contains("Nothing in the book"));

		// ---- search limit ----
		final RecipeIndex.Builder many = new RecipeIndex.Builder();
		for (int i = 0; i < 40; i++)
		{
			many.name(5000 + i, "Recipe " + i).recipe(recipe(5000 + i, "Sword " + i, Category.WEAPON, 1, 100, new Ingredient(1, 1)));
			many.drop(5000 + i, drop(1, "Mob", 1, 1, false, false));
		}
		final RecipePages bigPg = new RecipePages(many.build());
		final String lim = bigPg.search("sword", env);
		check("search is capped", count(lim, "view 50") <= RecipePages.SEARCH_LIMIT && lim.contains("40 matches"));
		check("list pages for 40 recipes", bigPg.list(Category.WEAPON, 1, 4, env).contains("page") || bigPg.list(Category.WEAPON, 1, 4, env).contains("Page 4 / 4"));

		// ---- goals ----
		final FakeEnv g = new FakeEnv();
		check("empty goals message", pg.goals(g).contains("Nothing pinned yet"));
		g.goals.add(100);
		g.goals.add(101);
		g.goals.add(55555); // stale id from a removed recipe
		g.held.put(1, 30L); // charcoal fully covered
		g.held.put(2, 2L);
		final String goals = pg.goals(g);
		check("goals shows both pinned and ignores the stale one", goals.contains("Short Sword") && goals.contains("Broad Sword") && goals.contains("My Goals (2/"));
		check("shopping list totals charcoal need", goals.contains(">30<"));
		check("covered ingredient shows ok", goals.contains(">ok<"));
		check("missing shown", goals.contains("color=\"FF6666\">3<")); // bone 5 needed, 2 held
		check("scrolls to find listed when not held", goals.contains("Recipes to find") && goals.contains("Recipe: Short Sword"));
		g.learned.add(100);
		g.held.put(101, 1L);
		check("no recipes to find once learned or held", !pg.goals(g).contains("Recipes to find"));
		check("most missing first", goals.indexOf("Animal Bone") < goals.indexOf("Charcoal"));
		check("goals balanced", balanced(goals));
		check("notice page", pg.notice("Goal list is full").contains("Goal list is full") && balanced(pg.notice("x")));

		// ---- size ----
		final RecipeIndex.Builder huge = new RecipeIndex.Builder();
		huge.name(1, "Charcoal");
		huge.recipe(recipe(1, "Item", Category.WEAPON, 1, 100, new Ingredient(1, 1)));
		for (int i = 0; i < 300; i++)
		{
			huge.drop(1, drop(i, "A Monster With A Fairly Long Name " + i, 50, 1, false, false));
		}
		check("drop pages are bounded", new RecipePages(huge.build()).item(1, 1, env).length() < 16000);

		System.out.println(passed + " passed, " + failed + " failed");
		if (failed > 0)
		{
			System.exit(1);
		}
	}

	private static FakeEnv withLearned(FakeEnv base, int scroll)
	{
		final FakeEnv e = new FakeEnv();
		e.held.putAll(base.held);
		e.goals.addAll(base.goals);
		e.learned.add(scroll);
		return e;
	}
}
