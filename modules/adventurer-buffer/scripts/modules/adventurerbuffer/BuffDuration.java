// SPDX-License-Identifier: GPL-3.0-or-later
package modules.adventurerbuffer;

final class BuffDuration
{
    static int parse(String raw)
    {
        if ((raw==null) || !raw.trim().matches("[0-9]+")) {
            throw new IllegalArgumentException("BuffDurationSeconds must be an integer from 1 through 86400.");
        }
        final int seconds;
        try { seconds=Integer.parseInt(raw.trim()); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("BuffDurationSeconds must be an integer from 1 through 86400.",error); }
        if ((seconds<1) || (seconds>86400)) {
            throw new IllegalArgumentException("BuffDurationSeconds must be from 1 through 86400; zero/permanent durations are not supported.");
        }
        return seconds;
    }
}
