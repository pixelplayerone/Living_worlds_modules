package modules.autopotions;

/** Pure checks for the potion toggle's rules. */
public class PotionLogicTest
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

	public static void main(String[] args)
	{
		final PotionLogic.Settings s = new PotionLogic.Settings(90, 70, 70);
		check("starts off", !s.on);
		check("toggle on", PotionLogic.apply(s, "").startsWith("Potions are ON") && s.on);
		check("toggle off", PotionLogic.apply(s, null).startsWith("Potions are OFF") && !s.on);
		check("on and off words", PotionLogic.apply(s, "on").contains("ON") && s.on && PotionLogic.apply(s, " OFF ").contains("OFF") && !s.on);
		check("status", PotionLogic.apply(s, "status").contains("CP below 90%") && PotionLogic.apply(s, "status").contains("HP below 70%"));
		check("set a line", PotionLogic.apply(s, "hp 60").contains("HP below 60%") && (s.percent[PotionLogic.HP] == 60));
		check("set with a percent sign", PotionLogic.apply(s, "mp 45%").contains("MP below 45%") && (s.percent[PotionLogic.MP] == 45));
		check("turn a line off", PotionLogic.apply(s, "cp off").contains("CP off") && (s.percent[PotionLogic.CP] == 0));
		check("bad percent is refused and changes nothing", PotionLogic.apply(s, "hp 150").startsWith("Use a percent") && (s.percent[PotionLogic.HP] == 60));
		check("junk percent is refused", PotionLogic.apply(s, "hp lots").startsWith("Use a percent") && (s.percent[PotionLogic.HP] == 60));
		check("unknown command shows help", PotionLogic.apply(s, "banana").startsWith("Potions: .pots"));
		check("starting lines are clamped", new PotionLogic.Settings(500, -3, 70).percent[PotionLogic.CP] == 100 && new PotionLogic.Settings(500, -3, 70).percent[PotionLogic.HP] == 0);

		check("needs when under", PotionLogic.needs(50, 70) && PotionLogic.needs(69, 70));
		check("does not need at or over", !PotionLogic.needs(70, 70) && !PotionLogic.needs(100, 70));
		check("a line at zero never fires", !PotionLogic.needs(0, 0) && !PotionLogic.needs(10, 0));

		final int[] ids = PotionLogic.parseIds("1540, 1539 ,x,1061,,0,-4,1060");
		check("parse ids", (ids.length == 4) && (ids[0] == 1540) && (ids[1] == 1539) && (ids[2] == 1061) && (ids[3] == 1060));
		check("parse nothing", PotionLogic.parseIds(null).length == 0 && PotionLogic.parseIds("").length == 0);

		// Choosing a potion: best first, skipping ones cooling down, and telling "none" from "all busy".
		final int[] list = { 1540, 1539, 1061 };
		check("takes the best available", PotionLogic.choose(list, id -> 5, id -> 0) == 1540);
		check("skips one on cooldown", PotionLogic.choose(list, id -> 5, id -> (id == 1540) ? 8000 : 0) == 1539);
		check("skips one the player does not have", PotionLogic.choose(list, id -> (id == 1540) ? 0 : 5, id -> 0) == 1539);
		check("all on cooldown waits", PotionLogic.choose(list, id -> 5, id -> 3000) == PotionLogic.ALL_ON_COOLDOWN);
		check("none owned", PotionLogic.choose(list, id -> 0, id -> 0) == PotionLogic.NONE_OWNED);
		check("an owned one on cooldown is not 'none owned'", PotionLogic.choose(list, id -> (id == 1061) ? 2 : 0, id -> 500) == PotionLogic.ALL_ON_COOLDOWN);
		check("empty list", PotionLogic.choose(new int[0], id -> 5, id -> 0) == PotionLogic.NONE_OWNED);

		final int[] par = {1540, 1539};
		check("ready: both off reuse", java.util.Arrays.equals(PotionLogic.ready(par, id -> 5, id -> 0), par));
		check("ready: only the one off reuse", java.util.Arrays.equals(PotionLogic.ready(par, id -> 5, id -> (id == 1540) ? 300 : 0), new int[] {1539}));
		check("ready: skips one not carried", java.util.Arrays.equals(PotionLogic.ready(par, id -> (id == 1539) ? 5 : 0, id -> 0), new int[] {1539}));
		check("ready: none when all cooling", PotionLogic.ready(par, id -> 5, id -> 400).length == 0);
		check("owns", PotionLogic.owns(par, id -> (id == 1539) ? 1 : 0) && !PotionLogic.owns(par, id -> 0) && !PotionLogic.owns(new int[0], id -> 9));

		// Saving and restoring the lines (custom values used to reset to the defaults after a relog).
		final PotionLogic.Settings c = new PotionLogic.Settings(90, 70, 70);
		PotionLogic.apply(c, "hp 55"); PotionLogic.apply(c, "cp off"); PotionLogic.apply(c, "mp 33");
		check("encode", PotionLogic.encode(c).equals("0,55,33"));
		final PotionLogic.Settings d = new PotionLogic.Settings(90, 70, 70);
		PotionLogic.decode(d, PotionLogic.encode(c));
		check("decode restores custom lines and off", (d.percent[PotionLogic.CP] == 0) && (d.percent[PotionLogic.HP] == 55) && (d.percent[PotionLogic.MP] == 33));
		final PotionLogic.Settings e = new PotionLogic.Settings(90, 70, 70);
		PotionLogic.decode(e, "banana"); PotionLogic.decode(e, "1,2"); PotionLogic.decode(e, "1,2,300"); PotionLogic.decode(e, null); PotionLogic.decode(e, "a,b,c");
		check("bad saved text changes nothing", (e.percent[PotionLogic.CP] == 90) && (e.percent[PotionLogic.HP] == 70) && (e.percent[PotionLogic.MP] == 70));

		// The wait before a potion can be drunk is the longest of its own reuse, its shared group and its skill; -1 (no stamp) is no wait.
		check("no stamps means ready", PotionLogic.reuseLeft(-1, -1, -1) == 0);
		check("item reuse blocks", PotionLogic.reuseLeft(8000, -1, -1) == 8000);
		check("shared group blocks a fresh potion", PotionLogic.reuseLeft(-1, 450, 0) == 450);
		check("longest wait wins", PotionLogic.reuseLeft(100, 9000, 300) == 9000);

		System.out.println(passed + " passed, " + failed + " failed");
		if (failed > 0)
		{
			System.exit(1);
		}
	}
}
