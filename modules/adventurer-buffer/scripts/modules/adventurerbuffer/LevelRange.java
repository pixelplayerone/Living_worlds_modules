// SPDX-License-Identifier: GPL-3.0-or-later
package modules.adventurerbuffer;

/** Strict configuration; malformed settings must never silently widen eligibility. */
public final class LevelRange
{
    private final int min;
    private final int max;
    public LevelRange(int min, int max) { this.min=min; this.max=max; }
    public int min() { return min; }
    public int max() { return max; }
    @Override public boolean equals(Object other)
    {
        if (this==other) { return true; }
        if (!(other instanceof LevelRange)) { return false; }
        LevelRange range=(LevelRange)other;
        return (min==range.min) && (max==range.max);
    }
    @Override public int hashCode() { return 31*min+max; }
    @Override public String toString() { return "LevelRange[min="+min+", max="+max+"]"; }

    public static LevelRange parse(String minimum, String maximum, int serverMax)
    {
        try
        {
            int min = Integer.parseInt(minimum.trim());
            int max = Integer.parseInt(maximum.trim());
            if ((min < 1) || (max < min) || (max > serverMax))
            {
                throw new IllegalArgumentException();
            }
            return new LevelRange(min, max);
        }
        catch (RuntimeException error)
        {
            throw new IllegalArgumentException("Adventurer Buffer: require integer settings 1 <= MinLevel <= MaxLevel <= " + serverMax + "; received MinLevel=" + minimum + ", MaxLevel=" + maximum, error);
        }
    }

    public boolean includes(int level) { return (level >= min) && (level <= max); }
}
