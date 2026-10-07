package modules.questbook;

import java.util.Collections;
import java.util.List;

import modules.questbook.QuestIndex.Quest;

/** Standalone checks for the quest book: no server needed. Run: see TESTING.md. */
public final class QuestBookTest
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

	private static final QuestPages.Env ENV = new QuestPages.Env()
	{
		@Override
		public String itemName(int itemId)
		{
			return "Item<" + itemId + ">";
		}

		@Override
		public String npcName(int npcId)
		{
			return "Npc " + npcId;
		}

		@Override
		public int status(Quest quest)
		{
			return quest.id % 3;
		}

		@Override
		public int playerLevel()
		{
			return 45;
		}

		@Override
		public List<Integer> activeIds()
		{
			return Collections.singletonList(663);
		}
	};

	public static void main(String[] args)
	{
		final QuestIndex idx = QuestIndex.fromRows(QuestData.ROWS);
		final QuestPages pg = new QuestPages(idx);
		check("every generated row parsed", idx.size() == QuestData.ROWS.length);
		check("dataset is not tiny", idx.size() > 300);

		int bracketed = idx.byLevel(0, 0).size();
		for (int[] br : QuestPages.BRACKETS)
		{
			bracketed += idx.byLevel(br[0], br[1]).size();
		}
		check("brackets plus unknown cover every quest", bracketed == idx.size());

		final Quest q663 = idx.quest(663);
		check("Q663 present", q663 != null);
		check("Q663 level 50", q663 != null && q663.minLevel == 50);
		check("Q663 start npc 30846", q663 != null && q663.startNpcs.length > 0 && q663.startNpcs[0] == 30846);
		check("search by name", idx.search("seductive", 10).size() == 1);
		check("search by Q number", idx.search("Q663", 10).size() == 1 && idx.search("663", 10).get(0).id == 663);
		check("search blank is empty", idx.search("  ", 10).isEmpty());
		check("search limit honoured", idx.search("a", 3).size() == 3);

		final List<Quest> lvl = idx.byLevel(1, 85);
		boolean sorted = true;
		for (int i = 1; i < lvl.size(); i++)
		{
			sorted &= lvl.get(i - 1).minLevel <= lvl.get(i).minLevel;
		}
		check("lists sorted by level", sorted);

		// Every page the board can produce stays under the client's 16,383 character limit and carries no raw markup from data.
		int max = 0;
		boolean small = true;
		boolean traces = true;
		for (Quest q : idx.byLevel(0, 85))
		{
			final String v = pg.view(q, ENV);
			max = Math.max(max, v.length());
			small &= v.length() < 16000;
			for (int n : q.startNpcs)
			{
				traces &= v.contains("trace " + n) || q.startNpcs.length > 3;
			}
		}
		check("every quest page under 16k (max " + max + ")", small);
		check("start npcs are trace links", traces);

		int maxList = 0;
		boolean listsSmall = true;
		for (int[] br : QuestPages.BRACKETS)
		{
			final int pages = (idx.byLevel(br[0], br[1]).size() + QuestPages.PAGE_SIZE - 1) / QuestPages.PAGE_SIZE;
			for (int p = 1; p <= Math.max(1, pages); p++)
			{
				final String h = pg.list(br[0], br[1], p, ENV);
				maxList = Math.max(maxList, h.length());
				listsSmall &= h.length() < 16000;
			}
		}
		check("every list page under 16k (max " + maxList + ")", listsSmall);
		check("home under 16k", pg.home(ENV).length() < 16000);
		check("out-of-range page is clamped", pg.list(20, 29, 999, ENV).contains("Page "));
		check("home offers level, active and clear buttons", pg.home(ENV).contains("list 39 45 1") && pg.home(ENV).contains("active") && pg.home(ENV).contains("clear"));
		check("active page lists the active quest", pg.active(ENV).contains("view 663"));
		check("search escapes markup", !pg.search("<b>x</b>", ENV).contains("<b>x</b>"));
		check("unknown quest notice is safe", pg.notice("a <b> b").contains("&lt;b&gt;"));
		check("navigation placeholder present", pg.home(ENV).contains("%navigation%"));
		check("view shows level and Done/In progress labels", pg.view(idx.quest(1), ENV).contains("Level 2+") && pg.view(idx.quest(1), ENV).contains("In progress"));

		System.out.println(passed + " passed, " + failed + " failed");
		if (failed > 0)
		{
			System.exit(1);
		}
	}
}
