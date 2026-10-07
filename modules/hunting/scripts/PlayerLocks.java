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

/**
 * One lock per player (shared between a few players, which only costs a short wait), so a kill and a claim for the same
 * player never read, change and save his progress at the same time. A kill that saved an older copy over a claim is
 * what let a contract be claimed twice. Pure, so it can be unit tested on its own.
 */
final class PlayerLocks
{
	private static final Object[] LOCKS = new Object[64];

	static
	{
		for (int i = 0; i < LOCKS.length; i++)
		{
			LOCKS[i] = new Object();
		}
	}

	private PlayerLocks()
	{
	}

	/** @return the lock to hold while reading, changing and saving this player's contracts */
	static Object of(int objectId)
	{
		return LOCKS[objectId & (LOCKS.length - 1)];
	}
}
