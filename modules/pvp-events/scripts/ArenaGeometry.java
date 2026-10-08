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
package modules.pvpevents;

import java.util.List;

/** Where the two sides stand in an arena, worked out from sample points inside it. */
final class ArenaGeometry
{
	private ArenaGeometry()
	{
	}

	/**
	 * @param points sample points {x, y}
	 * @return the indexes of the two points furthest apart, or {@code null} if there are fewer than two
	 */
	static int[] farthestPair(List<int[]> points)
	{
		if ((points == null) || (points.size() < 2))
		{
			return null;
		}
		int bestA = 0;
		int bestB = 1;
		double best = -1;
		for (int a = 0; a < points.size(); a++)
		{
			for (int b = a + 1; b < points.size(); b++)
			{
				final double d = distance(points.get(a), points.get(b));
				if (d > best)
				{
					best = d;
					bestA = a;
					bestB = b;
				}
			}
		}
		return new int[]
		{
			bestA,
			bestB
		};
	}

	static double distance(int[] a, int[] b)
	{
		return Math.hypot(a[0] - b[0], a[1] - b[1]);
	}

	/**
	 * Spreads {@code count} fighters on a ring around an anchor, the first one on the anchor itself when alone.
	 * @return {x, y} for fighter {@code index}
	 */
	static int[] ring(int anchorX, int anchorY, int count, int index)
	{
		if (count <= 1)
		{
			return new int[]
			{
				anchorX,
				anchorY
			};
		}
		final double radius = Math.min(220, 55 + (count * 14));
		final double angle = (2 * Math.PI * index) / count;
		return new int[]
		{
			(int) Math.round(anchorX + (Math.cos(angle) * radius)),
			(int) Math.round(anchorY + (Math.sin(angle) * radius))
		};
	}

	/**
	 * Picks {@code count} points that are as far from each other as the samples allow (farthest-point sampling), so a
	 * free-for-all starts spread out.
	 * @param points sample points {x, y, z}
	 * @return the indexes picked; fewer than {@code count} only when there are fewer points
	 */
	static List<Integer> spread(List<int[]> points, int count)
	{
		final List<Integer> picked = new java.util.ArrayList<>();
		if ((points == null) || points.isEmpty() || (count < 1))
		{
			return picked;
		}
		final double[] nearest = new double[points.size()];
		java.util.Arrays.fill(nearest, Double.MAX_VALUE);
		int next = 0;
		while ((picked.size() < count) && (picked.size() < points.size()))
		{
			picked.add(next);
			double best = -1;
			int bestIndex = -1;
			for (int i = 0; i < points.size(); i++)
			{
				nearest[i] = Math.min(nearest[i], distance(points.get(i), points.get(next)));
				if ((nearest[i] > best) && !picked.contains(i))
				{
					best = nearest[i];
					bestIndex = i;
				}
			}
			if (bestIndex < 0)
			{
				break;
			}
			next = bestIndex;
		}
		return picked;
	}

	/** @return the {@code count} points closest to {@code (cx, cy)}, nearest first */
	static List<int[]> nearestTo(List<int[]> points, int cx, int cy, int count)
	{
		final List<int[]> sorted = new java.util.ArrayList<>(points);
		final int[] centre = new int[]
		{
			cx,
			cy
		};
		java.util.Collections.sort(sorted, new java.util.Comparator<int[]>()
		{
			@Override
			public int compare(int[] a, int[] b)
			{
				return Double.compare(distance(a, centre), distance(b, centre));
			}
		});
		return new java.util.ArrayList<>(sorted.subList(0, Math.max(0, Math.min(count, sorted.size()))));
	}
}
