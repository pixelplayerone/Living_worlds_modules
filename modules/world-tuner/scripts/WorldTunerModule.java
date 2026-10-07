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
package modules.worldtuner;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.xml.SpawnData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.Location;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.events.holders.OnServerStart;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.modules.GameModule;
import org.l2jmobius.gameserver.modules.ModuleContext;

/**
 * World Tuner: scales monster respawn times and how many monsters each spawn point holds, for the whole world or only
 * for a level range, some regions, or single monsters. Every setting is a line in config/module.ini.
 * <p>
 * Modules start before the spawn lists load, so the module waits for the server's "started" event, which fires once
 * every spawn list is loaded and spawned, and then makes a single pass over the spawn table. Raid bosses, instance
 * monsters, spawns that never respawn, day/night spawns and spawns made by quests or scripts are never touched.
 */
public class WorldTunerModule implements GameModule
{
	private final Random _random = new Random();
	private final AtomicBoolean _ran = new AtomicBoolean();

	@Override
	public void onEnable(ModuleContext context)
	{
		if (!context.config().getBoolean("Enabled", false))
		{
			return; // Switch off: register nothing, behave as stock.
		}

		final double respawn = context.config().getDouble("RespawnSpeedPercent", 50);
		final double count = context.config().getDouble("SpawnCountMultiplier", 1.0);
		final int floor = context.config().getInt("MinRespawnSeconds", 5);
		final int spread = Math.max(0, context.config().getInt("ExtraSpawnSpread", 150));
		final WorldTuner tuner = new WorldTuner(respawn, count, context.config().getInt("MinLevel", 1), context.config().getInt("MaxLevel", 85), context.config().getString("Regions", ""), context.config().getString("ExcludeNpcIds", ""), context.config().getString("Overrides", ""), context.config().getInt("MaxExtraPerSpawn", 4));
		if (tuner.isInert())
		{
			context.logging().info("World Tuner is enabled but respawn speed is 0%, the count multiplier is 1.0 and there are no overrides, so nothing changes.");
			return;
		}

		context.logging().info("World Tuner enabled: respawns " + respawn + "% faster (delay x" + String.format("%.3f", WorldTuner.multiplierOf(respawn)) + "), spawn count x" + count + ", levels " + context.config().getInt("MinLevel", 1) + "-" + context.config().getInt("MaxLevel", 85) + ". It will tune the spawns when the server finishes starting.");
		context.events().<OnServerStart> onGlobal(EventType.ON_SERVER_START, event -> runOnce("server start", tuner, floor, spread, context.logging()));
		// Backup: if the start notification never arrives, run the same pass once the spawn table has filled. Only one of the two ever runs.
		scheduleBackup(1, tuner, floor, spread, context.logging());
	}

	private void scheduleBackup(int attempt, WorldTuner tuner, int floor, int spread, Logger log)
	{
		ThreadPool.schedule(() ->
		{
			if (_ran.get())
			{
				return;
			}
			if (!SpawnTable.getInstance().getSpawnTable().isEmpty())
			{
				runOnce("backup timer", tuner, floor, spread, log);
			}
			else if (attempt < 10)
			{
				scheduleBackup(attempt + 1, tuner, floor, spread, log);
			}
			else
			{
				log.warning("World Tuner never saw the spawn table fill up, so it changed nothing.");
			}
		}, 120000);
	}

	private void runOnce(String trigger, WorldTuner tuner, int floor, int spread, Logger log)
	{
		if (_ran.compareAndSet(false, true))
		{
			log.info("World Tuner is tuning the spawns now (triggered by the " + trigger + ").");
			tuneAll(tuner, floor, spread, log);
		}
	}

	/** One pass over every spawn point. Runs once, after the spawn lists are loaded and spawned. */
	private void tuneAll(WorldTuner tuner, int floorSeconds, int spread, Logger log)
	{
		int seen = 0;
		int changed = 0;
		int added = 0;
		int removed = 0;
		int failed = 0;

		// A snapshot, because adding extra spawn points changes the table while we walk it.
		final List<Spawn> snapshot = new ArrayList<>();
		for (Set<Spawn> set : SpawnTable.getInstance().getSpawnTable().values())
		{
			snapshot.addAll(set);
		}

		for (Spawn spawn : snapshot)
		{
			try
			{
				if (!eligible(spawn))
				{
					continue;
				}
				seen++;
				final String region = WorldTuner.regionOf(SpawnData.getInstance().getSpawnFile(spawn.getNpcSpawnTemplateId()));
				final WorldTuner.Plan plan = tuner.decide(spawn.getId(), spawn.getTemplate().getLevel(), region, _random);
				if (plan.isNoOp())
				{
					continue;
				}
				changed++;

				if (plan.remove)
				{
					spawn.stopRespawn();
					for (Npc npc : new ArrayList<>(spawn.getSpawnedNpcs()))
					{
						npc.deleteMe();
					}
					SpawnTable.getInstance().removeSpawn(spawn);
					removed++;
					continue;
				}

				spawn.setRespawnMinDelay(WorldTuner.scaleDelay(spawn.getRespawnMinDelay(), plan.respawnMultiplier, floorSeconds));
				spawn.setRespawnMaxDelay(WorldTuner.scaleDelay(spawn.getRespawnMaxDelay(), plan.respawnMultiplier, floorSeconds));
				for (int i = 0; i < plan.extraSpawns; i++)
				{
					try
					{
						addExtra(spawn, spread);
						added++;
					}
					catch (Exception e) // one failed extra costs that extra only, never the rest of the spawn point
					{
						if (++failed <= 3)
						{
							log.log(Level.WARNING, "World Tuner could not add an extra spawn for monster " + spawn.getId(), e);
						}
					}
				}
			}
			catch (Exception e)
			{
				if (++failed <= 3)
				{
					log.log(Level.WARNING, "World Tuner could not tune a spawn point for monster " + spawn.getId(), e);
				}
			}
		}
		log.info("World Tuner summary: " + seen + " stock spawn points eligible, " + changed + " tuned, " + added + " extra spawn points added, " + removed + " switched off, " + failed + " failed.");
	}

	/** A stock hunting-ground spawn that is alive right now and comes back after it dies. */
	private static boolean eligible(Spawn spawn)
	{
		return (spawn.getNpcSpawnTemplateId() > 0) // from the stock spawn lists, not a quest or script
			&& (spawn.getInstanceId() == 0)
			&& spawn.getTemplate().isType("Monster") // raid and grand bosses have their own types
			&& spawn.isRespawnEnabled()
			&& (spawn.getRespawnMinDelay() > 0)
			&& !spawn.getSpawnedNpcs().isEmpty(); // skips day/night spawns that are not out right now
	}

	/** Adds one more spawn point of the same kind, placed near the original so the extras form a pack. */
	private static void addExtra(Spawn spawn, int spread) throws Exception
	{
		final Spawn extra = new Spawn(spawn.getId());
		extra.setAmount(1);
		final Location base = (spawn.getSpawnLocation() != null) ? spawn.getSpawnLocation() : spawn; // the original point, not wherever the last spawn landed
		int x = base.getX();
		int y = base.getY();
		final int z = base.getZ();
		// Spread the extras around the original so they do not stand on one spot. The server does this itself only for a
		// spawn's first appearance, and each extra comes back at its own point after that.
		for (int attempt = 0; (attempt < 5) && (spread > 0); attempt++)
		{
			final int tx = base.getX() + Rnd.get(-spread, spread);
			final int ty = base.getY() + Rnd.get(-spread, spread);
			if (GeoEngine.getInstance().canMoveToTarget(base.getX(), base.getY(), z, tx, ty, z, 0))
			{
				x = tx;
				y = ty;
				break;
			}
		}
		extra.setXYZ(x, y, z);
		extra.setHeading(Rnd.get(65536));
		extra.setRespawnMinDelay(spawn.getRespawnMinDelay());
		extra.setRespawnMaxDelay(spawn.getRespawnMaxDelay());
		extra.setChaseRange(spawn.getChaseRange());
		extra.setLocationId(spawn.getLocationId());
		final String name = spawn.getName(); // null for the many stock spawns that have no name
		if ((name != null) && !name.isEmpty())
		{
			extra.setName(name); // the name also links it to the original's AI settings
		}
		if (spawn.getSpawnTerritory() != null)
		{
			extra.setSpawnTerritory(spawn.getSpawnTerritory());
		}
		extra.setSpawnTemplateId(spawn.getNpcSpawnTemplateId());
		SpawnTable.getInstance().addSpawn(extra);
		extra.init();
	}
}
