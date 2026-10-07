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

import java.util.ArrayList;
import java.util.List;

/** The named hunting grounds a player can be sent to. Pure data plus lookups. */
public final class GroundIndex
{
	/** One hunting ground: a name, the level range it is meant for, and the circles that make up its area. */
	public static final class Ground
	{
		public final String slug;
		public final String name;
		public final int minLevel;
		public final int maxLevel;
		private final int[] _circles; // x, y, radius, x, y, radius ...

		Ground(String slug, String name, int minLevel, int maxLevel, int[] circles)
		{
			this.slug = slug;
			this.name = name;
			this.minLevel = minLevel;
			this.maxLevel = maxLevel;
			_circles = circles;
		}

		public boolean contains(int x, int y)
		{
			for (int i = 0; i < _circles.length; i += 3)
			{
				final long dx = x - _circles[i];
				final long dy = y - _circles[i + 1];
				final long r = _circles[i + 2];
				if (((dx * dx) + (dy * dy)) <= (r * r))
				{
					return true;
				}
			}
			return false;
		}

		public int centerX()
		{
			return _circles[0];
		}

		public int centerY()
		{
			return _circles[1];
		}

		public double averageLevel()
		{
			return (minLevel + maxLevel) / 2.0;
		}
	}

	/** Grades by the ground's average level: 1-19, 20-39, 40-51, 52-60, 61-75, 76-79, 80+. */
	public static final int[] GRADE_MIN =
	{
		1,
		20,
		40,
		52,
		61,
		76,
		80
	};
	public static final int GRADES = GRADE_MIN.length;
	private static final String[] GRADE_LABELS =
	{
		"1-19",
		"20-39",
		"40-51",
		"52-60",
		"61-75",
		"76-79",
		"80+"
	};

	private final List<Ground> _grounds = new ArrayList<>();

	/** @return the grade (0-based) a ground belongs to, from its average level */
	public static int gradeOf(Ground g)
	{
		int grade = 0;
		for (int i = 0; i < GRADES; i++)
		{
			if (g.averageLevel() >= GRADE_MIN[i])
			{
				grade = i;
			}
		}
		return grade;
	}

	public static String gradeLabel(int grade)
	{
		return GRADE_LABELS[Math.max(0, Math.min(GRADES - 1, grade))];
	}

	public static GroundIndex fromRows(String[][] rows)
	{
		final GroundIndex idx = new GroundIndex();
		for (String[] row : rows)
		{
			final String[] n = row[4].split(",");
			final int[] c = new int[n.length];
			for (int i = 0; i < n.length; i++)
			{
				c[i] = Integer.parseInt(n[i].trim());
			}
			idx._grounds.add(new Ground(row[0], row[1], Integer.parseInt(row[2]), Integer.parseInt(row[3]), c));
		}
		return idx;
	}

	public List<Ground> all()
	{
		return _grounds;
	}

	public Ground bySlug(String slug)
	{
		for (Ground g : _grounds)
		{
			if (g.slug.equals(slug))
			{
				return g;
			}
		}
		return null;
	}

	/** @return the grounds of a grade, easiest first: by average level, then top of the range, then name */
	public List<Ground> inGrade(int grade)
	{
		final List<Ground> out = new ArrayList<>();
		for (Ground g : _grounds)
		{
			if (gradeOf(g) == grade)
			{
				out.add(g);
			}
		}
		out.sort((x, y) -> (x.averageLevel() != y.averageLevel()) ? Double.compare(x.averageLevel(), y.averageLevel()) : (x.maxLevel != y.maxLevel) ? Integer.compare(x.maxLevel, y.maxLevel) : x.name.compareTo(y.name));
		return out;
	}

	/** @return the grounds containing this point (areas can overlap) */
	public List<Ground> at(int x, int y)
	{
		final List<Ground> out = new ArrayList<>();
		for (Ground g : _grounds)
		{
			if (g.contains(x, y))
			{
				out.add(g);
			}
		}
		return out;
	}
}
