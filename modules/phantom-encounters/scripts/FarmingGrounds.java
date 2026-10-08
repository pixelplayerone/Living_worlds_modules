package modules.phantomencounters;

/** Where the server's named hunting grounds are. Pure: a point is in a ground if it falls in any ground circle. */
final class FarmingGrounds
{
	private FarmingGrounds()
	{
	}

	static boolean contains(int x, int y)
	{
		return contains(FarmingGroundData.CIRCLES, x, y);
	}

	/** @param circles x, y, radius, x, y, radius ... */
	static boolean contains(int[] circles, int x, int y)
	{
		for (int i = 0; (i + 2) < circles.length; i += 3)
		{
			final long dx = x - circles[i];
			final long dy = y - circles[i + 1];
			final long r = circles[i + 2];
			if (((dx * dx) + (dy * dy)) <= (r * r))
			{
				return true;
			}
		}
		return false;
	}
}
