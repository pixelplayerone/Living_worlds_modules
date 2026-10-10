package modules.phantommanagement;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.data.xml.FakePlayerData;
import org.l2jmobius.gameserver.modules.ModuleConfig;
import org.l2jmobius.gameserver.managers.PhantomBuddyManager;
import org.l2jmobius.gameserver.managers.PhantomBuffs;
import org.l2jmobius.gameserver.managers.PhantomManager;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.managers.PhantomManager.PartyRole;
import org.l2jmobius.gameserver.managers.PhantomManager.Recruit;
import org.l2jmobius.gameserver.managers.PhantomPartyManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.model.actor.instance.Cubic;
import org.l2jmobius.gameserver.model.item.enums.BodyPart;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.item.type.WeaponType;
import org.l2jmobius.gameserver.model.groups.Party;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.data.xml.SkillTreeData;
import org.l2jmobius.gameserver.model.effects.EffectType;
import org.l2jmobius.gameserver.model.skill.AbnormalType;
import org.l2jmobius.gameserver.model.skill.BuffInfo;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.skill.targets.TargetType;
import org.l2jmobius.gameserver.model.skill.holders.SkillLearn;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * Clean, compact in-game Phantom Management window. Every button calls the existing public phantom-manager API by routing
 * the same deterministic party/whisper commands already supported by the server.
 */
final class PhantomPanel
{
	private static final int NEARBY_RANGE = 1500;
	private static final int MAX_HTML = 8100;
	private static final int GRID_W = 288; // Four 72px cells give the 64px native button 8px breathing room per side.
	private static final int CELL_W = 72;
	private static final int TAB_W = 72;
	private static final int TAB_H = 21;
	private static final int SUBTAB_CELL_W = 144; // 2 centered sub-tabs = 288 total.
	private static final int SUBTAB_W = 112;
	// Phantoms-only content width: leaves room for the native NPC HTML scrollbar without changing button width.
	private static final int PHANTOM_GRID_W = 264;
	private static final int PHANTOM_CELL_W = 64;
	private static final int PHANTOM_SUBTAB_CELL_W = 132;
	private static final int BTN_W = 64;
	private static final int BTN_H = 17;
	private static final int RECHARGE_SKILL_ID = 1013;
	private static final long FORCE_MUSIC_FIRST_DELAY_MS = 1800;
	private static final long FORCE_MUSIC_STEP_MS = 3000;
	private static final int MUSIC_POOL_SLOTS = 12;
	private static final long HEAL_FULL_STEP_MS = 1800;
	private static final long HEAL_FULL_MAX_MS = 60000;
	private static final int[] SINGER_SONGS =
	{
		269, 264, 304, 267, 268, 305, 270, 265, 306, 308, 266, 349, 363, 364
	};
	private static final int[] DANCER_DANCES =
	{
		275, 271, 274, 310, 276, 273, 272, 277, 311, 307, 309
	};
	private static final int[] DANCER_DANCES_MAGE =
	{
		273, 276, 275, 271, 274, 310, 272, 277, 311, 307, 309
	};

	// Interlude second classes: exact class ids used by the core. These are exposed in Phantoms > Recruit and Phantoms > Friends.
	private static final int[][] INTERLUDE_SECOND_CLASSES =
	{
		{ 2, 3, 5, 6, 8, 9, 12, 13, 14, 16, 17 }, // Human
		{ 20, 21, 23, 24, 27, 28, 30 }, // Elf
		{ 33, 34, 36, 37, 40, 41, 43 }, // Dark Elf
		{ 46, 48, 51, 52 }, // Orc
		{ 55, 57 } // Dwarf
	};
	private static final String[] INTERLUDE_RACE_NAMES = { "Human", "Elf", "Dark Elf", "Orc", "Dwarf" };
	private static final String TAB_ON = "sek.cbui94";
	private static final String TAB_OFF = "sek.cbui92";
	private static final String BTN_ON = TAB_ON;
	private static final String BTN_OFF = "L2UI.DefaultButton";

	private final Map<Integer, String> _stance = new ConcurrentHashMap<>();
	private final Map<Integer, Boolean> _camp = new ConcurrentHashMap<>();
	private final Map<Integer, Integer> _pullSize = new ConcurrentHashMap<>();
	private final Map<Integer, String> _puller = new ConcurrentHashMap<>();
	/** Last raid order sent from the panel (engage / tank / all / holdfire / dpsstop / stopfight); absent = none. */
	private final Map<Integer, String> _raidOrder = new ConcurrentHashMap<>();
	private final Map<Integer, String> _page = new ConcurrentHashMap<>();
	/** Generation of the current page; invalidates older periodic refresh tasks when the page changes. */
	private final Map<Integer, Long> _pageGeneration = new ConcurrentHashMap<>();
	/** Auto refresh stops when the player has not clicked anything in the panel for this long. */
	private static final long AUTO_REFRESH_IDLE_MS = 20000;
	private final Map<Integer, Long> _lastInteraction = new ConcurrentHashMap<>();
	private final Map<Integer, int[]> _interactionState = new ConcurrentHashMap<>(); // {targetObjId, x, y}
	private final Map<Integer, String> _phantomsView = new ConcurrentHashMap<>();
	private final Map<Integer, String> _selectedMember = new ConcurrentHashMap<>();
	private final Map<Integer, Integer> _craftSex = new ConcurrentHashMap<>();
	private final Map<Integer, Boolean> _standLocked = new ConcurrentHashMap<>();
	private final Map<Integer, ScheduledFuture<?>> _partyAutoRefresh = new ConcurrentHashMap<>();
	private final Map<Integer, ScheduledFuture<?>> _targetsAutoRefresh = new ConcurrentHashMap<>();
	private final Map<Integer, ScheduledFuture<?>> _detailAutoRefresh = new ConcurrentHashMap<>();
	private final Map<Integer, ScheduledFuture<?>> _phantomsAutoRefresh = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _healFullUntil = new ConcurrentHashMap<>();
	private final Map<Integer, Player> _healFullHealer = new ConcurrentHashMap<>();
	private final Map<Integer, Player> _healFullTarget = new ConcurrentHashMap<>();
	private final Map<Integer, Map<String, Integer>> _buffPlanOwners = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _manualBuffing = new ConcurrentHashMap<>();

	// --- Heal Full / Stand All loop handles (one loop per player, always cancellable) ---
	private final Map<Integer, Long> _healFullGeneration = new ConcurrentHashMap<>();
	private final Map<Integer, ScheduledFuture<?>> _healFullTask = new ConcurrentHashMap<>();
	private final AtomicLong _healFullSequence = new AtomicLong();
	private final Map<Integer, ScheduledFuture<?>> _standTask = new ConcurrentHashMap<>();
	/** Phantoms whose core "no sit" flag was raised by Stand All for this owner, so it can be lowered again. */
	private final Map<Integer, Set<Integer>> _standPhantoms = new ConcurrentHashMap<>();

	// --- Friend creation safety ---
	private final Map<Integer, PendingCraft> _pendingCraft = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _lastCraft = new ConcurrentHashMap<>();
	private final Map<Integer, AtomicInteger> _craftInFlight = new ConcurrentHashMap<>();

	/** Every per-player map above, so one sweep can forget players who are gone. */
	private final List<Map<Integer, ?>> _perPlayerMaps = new ArrayList<>();

	private static final long SWEEP_INTERVAL_MS = 60000;
	private static final long CRAFT_CONFIRM_MS = 20000;
	private static final int MAX_FRIEND_NAME = 16;

	// Tunables (module.ini). Defaults are used when the config cannot be read.
	private volatile int _maxFriendsPerPlayer = 10;
	private volatile long _craftCooldownMs = 10000;
	private volatile boolean _confirmCraft = true;
	private volatile boolean _autoRefresh = true;
	private volatile long _autoRefreshIdleMs = 0L;
	private volatile List<String> _extraForbiddenNames = new ArrayList<>();

	private static final class PendingCraft
	{
		private final int classId;
		private final String name;
		private final long expires;

		private PendingCraft(int classId, String name, long expires)
		{
			this.classId = classId;
			this.name = name;
			this.expires = expires;
		}
	}

	PhantomPanel()
	{
		_perPlayerMaps.add(_stance);
		_perPlayerMaps.add(_camp);
		_perPlayerMaps.add(_pullSize);
		_perPlayerMaps.add(_puller);
		_perPlayerMaps.add(_raidOrder);
		_perPlayerMaps.add(_page);
		_perPlayerMaps.add(_pageGeneration);
		_perPlayerMaps.add(_lastInteraction);
		_perPlayerMaps.add(_interactionState);
		_perPlayerMaps.add(_phantomsView);
		_perPlayerMaps.add(_selectedMember);
		_perPlayerMaps.add(_craftSex);
		_perPlayerMaps.add(_standLocked);
		_perPlayerMaps.add(_partyAutoRefresh);
		_perPlayerMaps.add(_targetsAutoRefresh);
		_perPlayerMaps.add(_detailAutoRefresh);
		_perPlayerMaps.add(_phantomsAutoRefresh);
		_perPlayerMaps.add(_healFullUntil);
		_perPlayerMaps.add(_healFullHealer);
		_perPlayerMaps.add(_healFullTarget);
		_perPlayerMaps.add(_buffPlanOwners);
		_perPlayerMaps.add(_manualBuffing);
		_perPlayerMaps.add(_healFullGeneration);
		_perPlayerMaps.add(_healFullTask);
		_perPlayerMaps.add(_standTask);
		_perPlayerMaps.add(_standPhantoms);
		_perPlayerMaps.add(_pendingCraft);
		_perPlayerMaps.add(_lastCraft);
		_perPlayerMaps.add(_craftInFlight);
		ThreadPool.schedule(this::sweepTick, SWEEP_INTERVAL_MS);
	}

	/** Reads the tunables from the module config (config/module.ini). */
	void configure(ModuleConfig config)
	{
		_maxFriendsPerPlayer = Math.max(0, config.getInt("MaxFriendsPerPlayer", _maxFriendsPerPlayer));
		_craftCooldownMs = Math.max(0, config.getInt("FriendCreateCooldownSeconds", (int) (_craftCooldownMs / 1000))) * 1000L;
		_confirmCraft = config.getBoolean("ConfirmFriendCreation", true);
		_autoRefresh = config.getBoolean("AutoRefresh", true);
		final int idleSeconds = config.getInt("AutoRefreshSeconds", 0);
		_autoRefreshIdleMs = (idleSeconds <= 0) ? 0L : Math.max(5, Math.min(600, idleSeconds)) * 1000L;
		final List<String> extra = new ArrayList<>();
		for (String part : config.getString("ExtraForbiddenNames", "").split(","))
		{
			final String clean = part.trim().toLowerCase(Locale.ROOT);
			if (!clean.isEmpty())
			{
				extra.add(clean);
			}
		}
		_extraForbiddenNames = extra;
		System.out.println("[Phantom Management] Friends: max " + ((_maxFriendsPerPlayer == 0) ? "unlimited" : String.valueOf(_maxFriendsPerPlayer)) + " per player, cooldown " + (_craftCooldownMs / 1000) + "s, confirm " + _confirmCraft + ", " + extra.size() + " extra forbidden name fragments.");
	}

	/** Forgets everything kept for one player. Cancels any scheduled task stored in the maps. */
	private void purgePlayer(int objectId)
	{
		for (Map<Integer, ?> map : _perPlayerMaps)
		{
			final Object removed = map.remove(objectId);
			if (removed instanceof ScheduledFuture)
			{
				((ScheduledFuture<?>) removed).cancel(false);
			}
		}
	}

	/** Periodic cleanup: drops the data of players who logged out (there is no logout hook to rely on). */
	private void sweepTick()
	{
		try
		{
			final Set<Integer> ids = new HashSet<>();
			for (Map<Integer, ?> map : _perPlayerMaps)
			{
				ids.addAll(map.keySet());
			}
			for (Integer id : ids)
			{
				final Player player = World.getInstance().getPlayer(id.intValue());
				if ((player == null) || !player.isOnline())
				{
					purgePlayer(id.intValue());
				}
			}
		}
		catch (RuntimeException e)
		{
			System.err.println("[Phantom Management] Cleanup sweep failed: " + e);
		}
		finally
		{
			ThreadPool.schedule(this::sweepTick, SWEEP_INTERVAL_MS);
		}
	}

	private static final Field CORE_MEMBERS_FIELD;
	private static final Field CORE_HEAL_NOW_FIELD;
	private static final Field CORE_RECHARGE_TARGET_FIELD;
	private static final Field CORE_ASSIST_FIELD;
	private static final Field CORE_FOLLOWING_FIELD;
	private static final Field CORE_HOLDING_FIELD;
	private static final Field CORE_NO_SIT_UNTIL_FIELD;
	private static final Field CORE_CAMPS_FIELD;
	private static final Field CORE_CAMP_PULLING_FIELD;
	static
	{
		Field members = null;
		Field healNow = null;
		Field rechargeTarget = null;
		Field assist = null;
		Field following = null;
		Field holding = null;
		Field noSitUntil = null;
		Field camps = null;
		Field campPulling = null;
		try
		{
			members = PhantomPartyManager.class.getDeclaredField("_members");
			members.setAccessible(true);
			final Class<?> memberClass = Class.forName("org.l2jmobius.gameserver.managers.PhantomPartyManager$Member");
			healNow = memberClass.getDeclaredField("healNow");
			healNow.setAccessible(true);
			rechargeTarget = memberClass.getDeclaredField("rechargeTarget");
			rechargeTarget.setAccessible(true);
			assist = memberClass.getDeclaredField("assist");
			assist.setAccessible(true);
			following = memberClass.getDeclaredField("following");
			following.setAccessible(true);
			holding = memberClass.getDeclaredField("holding");
			holding.setAccessible(true);
			noSitUntil = memberClass.getDeclaredField("noSitUntil");
			noSitUntil.setAccessible(true);
			camps = PhantomPartyManager.class.getDeclaredField("_camps");
			camps.setAccessible(true);
			final Class<?> campClass = Class.forName("org.l2jmobius.gameserver.managers.PhantomPartyManager$Camp");
			campPulling = campClass.getDeclaredField("pulling");
			campPulling.setAccessible(true);
		}
		catch (ReflectiveOperationException e)
		{
			System.err.println("[Phantom Management] Core support reflection unavailable: " + e.getMessage());
		}
		CORE_MEMBERS_FIELD = members;
		CORE_HEAL_NOW_FIELD = healNow;
		CORE_RECHARGE_TARGET_FIELD = rechargeTarget;
		CORE_ASSIST_FIELD = assist;
		CORE_FOLLOWING_FIELD = following;
		CORE_HOLDING_FIELD = holding;
		CORE_NO_SIT_UNTIL_FIELD = noSitUntil;
		CORE_CAMPS_FIELD = camps;
		CORE_CAMP_PULLING_FIELD = campPulling;
	}

	private static boolean coreIsPulling(Player owner)
	{
		if ((owner == null) || (CORE_CAMPS_FIELD == null) || (CORE_CAMP_PULLING_FIELD == null))
		{
			return false;
		}
		try
		{
			final Object campsObject = CORE_CAMPS_FIELD.get(PhantomPartyManager.getInstance());
			if (!(campsObject instanceof Map))
			{
				return false;
			}
			final Map<?, ?> camps = (Map<?, ?>) campsObject;
			final Object camp = camps.get(owner.getObjectId());
			return (camp != null) && (CORE_CAMP_PULLING_FIELD.get(camp) != null);
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			return false;
		}
	}

	private static boolean isPartyPhantom(Player p)
	{
		return PhantomPartyManager.getInstance().isRecruit(p) || PhantomBuddyManager.getInstance().isBuddy(p);
	}

	/**
	 * True if {@code owner} is allowed to command {@code phantom}. The owner always comes from the core itself
	 * ({@code getRecruitOwner} for recruits and phantom friends, {@code getBuddyOwner} for personal buddies), the same
	 * owner the core's own party-chat commands check. A phantom the core does not attribute to anybody is nobody's.
	 * Every panel action goes through this check.
	 */
	private boolean controls(Player owner, Player phantom)
	{
		if ((owner == null) || (phantom == null) || (owner == phantom))
		{
			return false;
		}
		final Party party = owner.getParty();
		if ((party == null) || (phantom.getParty() != party))
		{
			return false;
		}
		Player phantomOwner = PhantomPartyManager.getInstance().getRecruitOwner(phantom);
		if (phantomOwner == null)
		{
			phantomOwner = PhantomBuddyManager.getInstance().getBuddyOwner(phantom);
		}
		return (phantomOwner != null) && (phantomOwner.getObjectId() == owner.getObjectId());
	}

	/** A phantom party member that the given player is allowed to command. */
	private boolean isOwnedPhantom(Player owner, Player member)
	{
		return (member != null) && (member != owner) && isPartyPhantom(member) && controls(owner, member);
	}

	private boolean hasPhantoms(Player owner)
	{
		final Party party = owner.getParty();
		if (party == null)
		{
			return false;
		}
		for (Player member : party.getMembers())
		{
			if (isOwnedPhantom(owner, member))
			{
				return true;
			}
		}
		return false;
	}

	private Player findPartyPhantom(Player owner, String name)
	{
		final Party party = owner.getParty();
		if ((party == null) || (name == null))
		{
			return null;
		}
		for (Player member : party.getMembers())
		{
			if ((member != owner) && member.getName().equalsIgnoreCase(name) && isOwnedPhantom(owner, member))
			{
				return member;
			}
		}
		return null;
	}

	private static List<Player> onlineFriends(Player owner)
	{
		final List<Player> result = new ArrayList<>();
		for (Integer id : owner.getFriendList())
		{
			final Player friend = World.getInstance().getPlayer(id.intValue());
			if ((friend != null) && PhantomManager.getInstance().isRegular(friend))
			{
				result.add(friend);
			}
		}
		return result;
	}

	private static List<Player> waitingPhantoms(Player owner)
	{
		final List<Player> result = new ArrayList<>();
		for (Player p : World.getInstance().getVisibleObjectsInRange(owner, Player.class, NEARBY_RANGE))
		{
			if ((p == owner) || p.isInParty() || p.isDead())
			{
				continue;
			}
			if (PhantomPartyManager.getInstance().isRecruit(p) || PhantomBuddyManager.getInstance().isBuddy(p))
			{
				result.add(p);
			}
		}
		return result;
	}

	void invite(Player owner, String name)
	{
		final Player target = (name == null) ? null : World.getInstance().getPlayer(name);
		if ((target == null) || (target == owner))
		{
			owner.sendMessage("[Phantom] No phantom named '" + name + "' is online.");
			return;
		}
		if (target.isInParty())
		{
			owner.sendMessage("[Phantom] " + target.getName() + " is already in a party.");
			return;
		}
		final boolean joined;
		if (PhantomPartyManager.getInstance().isRecruit(target))
		{
			joined = PhantomPartyManager.getInstance().onInvited(owner, target);
		}
		else if (PhantomBuddyManager.getInstance().isBuddy(target))
		{
			joined = PhantomBuddyManager.getInstance().onInvited(owner, target);
		}
		else if (PhantomManager.getInstance().isRegular(target) && owner.getFriendList().contains(target.getObjectId()))
		{
			joined = PhantomPartyManager.getInstance().onInvitedFriend(owner, target);
		}
		else
		{
			owner.sendMessage("[Phantom] " + target.getName() + " is not a phantom you can invite from here.");
			return;
		}
		owner.sendMessage(joined ? ("[Phantom] " + target.getName() + " joined your party.") : ("[Phantom] Could not party " + target.getName() + " (party full or phantom dead)."));
	}

	void drop(Player owner, String name)
	{
		final Player phantom = findPartyPhantom(owner, name);
		if (phantom == null)
		{
			owner.sendMessage("[Phantom] No phantom named '" + name + "' in your party.");
			return;
		}
		// A dropped phantom must not keep the Stand All "never sit" flag, nor stay recorded as ours.
		setCoreLong(coreMemberState(phantom), CORE_NO_SIT_UNTIL_FIELD, 0L);
		final Set<Integer> standing = _standPhantoms.get(owner.getObjectId());
		if (standing != null)
		{
			standing.remove(phantom.getObjectId());
		}
		route(owner, phantom, "dismiss");
		owner.sendMessage("[Phantom] " + phantom.getName() + " is leaving the party.");
		refreshLater(owner, "party", 2600);
	}

	void find(Player owner, String token, String level)
	{
		final Recruit recruit;
		if ("dd".equals(token))
		{
			recruit = PhantomManager.rollDpsRecruit(null);
		}
		else
		{
			final PartyRole role = PartyRole.fromToken(token);
			final Race race = (role == PartyRole.BOUNTY_HUNTER) ? Race.DWARF : null;
			recruit = (role == null) ? null : new Recruit(role, 0, race);
		}
		if (recruit == null)
		{
			owner.sendMessage("[Phantom] Unknown role '" + token + "'.");
			return;
		}
		PhantomPartyManager.getInstance().recruitFromShout(owner, java.util.Collections.singletonList(recruit), Math.max(0, Math.min(80, parseInt(level))));
		owner.sendMessage("[Phantom] Looking for a " + token + " - it will appear under Recruit in a few seconds.");
		_phantomsView.put(owner.getObjectId(), "recruit");
		pollRecruit(owner);
	}

	void findClass(Player owner, String classIdText, String level)
	{
		final int classId = parseInt(classIdText);
		final PlayerClass playerClass = isListedSecondClass(classId) ? PlayerClass.getPlayerClass(classId) : null;
		if ((playerClass == null) || !PhantomManager.isSelectableClass(playerClass))
		{
			owner.sendMessage("[Phantom] Invalid Interlude second class id.");
			return;
		}
		final PartyRole role = PhantomManager.roleForClass(playerClass);
		if (role == null)
		{
			owner.sendMessage("[Phantom] No Phantom role is defined for " + playerClass + ".");
			return;
		}
		final int requestedLevel = Math.max(0, Math.min(80, parseInt(level)));
		final int targetLevel = (requestedLevel > 0) ? requestedLevel : owner.getLevel();
		final PlayerClass resolvedClass = resolveClassForLevel(playerClass, targetLevel);
		// The selected second class is the branch/role the user asked for.
		// The actual class id is resolved from the requested level, but the UI
		// selection must remain the selected second-class name (e.g. Warsmith).
		final Recruit recruit = new Recruit(role, resolvedClass.getId(), playerClass.getRace());
		PhantomPartyManager.getInstance().recruitFromShout(owner, java.util.Collections.singletonList(recruit), requestedLevel);
		owner.sendMessage("[Phantom] Looking for " + playerClass.name().replace('_', ' ') + " (level " + targetLevel + ") - it will appear under Recruit in a few seconds.");
		_phantomsView.put(owner.getObjectId(), "recruit");
		pollRecruit(owner);
	}

	/** True only for the 31 Interlude second classes the Phantoms tab actually offers. */
	private static boolean isListedSecondClass(int classId)
	{
		for (int[] race : INTERLUDE_SECOND_CLASSES)
		{
			for (int id : race)
			{
				if (id == classId)
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Same letters-and-digits rule the game uses for character names, at most 16 characters. */
	private static boolean isValidFriendName(String name)
	{
		if ((name == null) || name.isEmpty() || (name.length() > MAX_FRIEND_NAME))
		{
			return false;
		}
		for (int i = 0; i < name.length(); i++)
		{
			final char c = name.charAt(i);
			if (!(((c >= 'a') && (c <= 'z')) || ((c >= 'A') && (c <= 'Z')) || ((c >= '0') && (c <= '9'))))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Same checks the character-creation window applies (CharacterCreate): the name may not contain any entry of the
	 * server's ForbiddenNames (Player.ini) and may not be a reserved fake-player name. ExtraForbiddenNames in
	 * module.ini is added on top.
	 */
	private boolean isForbiddenName(String name)
	{
		final String lower = name.toLowerCase(Locale.ROOT);
		for (String fragment : _extraForbiddenNames)
		{
			if (lower.contains(fragment))
			{
				return true;
			}
		}
		if (PlayerConfig.FORBIDDEN_NAMES != null)
		{
			for (String fragment : PlayerConfig.FORBIDDEN_NAMES)
			{
				final String clean = (fragment == null) ? "" : fragment.trim().toLowerCase(Locale.ROOT);
				if (!clean.isEmpty() && lower.contains(clean))
				{
					return true;
				}
			}
		}
		return FakePlayerData.getInstance().getProperName(name) != null;
	}

	void craft(Player owner, String classSpec, String name)
	{
		final int ownerId = owner.getObjectId();
		final long now = System.currentTimeMillis();

		// Only the classes listed in the tab are accepted; a hand-made button cannot ask for anything else.
		final int classId = (classSpec == null) ? -1 : parseInt(classSpec);
		final PlayerClass selected = isListedSecondClass(classId) ? PlayerClass.getPlayerClass(classId) : null;
		if ((selected == null) || !PhantomManager.isSelectableClass(selected))
		{
			owner.sendMessage("[Phantom] Pick a class from the list.");
			return;
		}

		PendingCraft pending = _pendingCraft.get(ownerId);
		if ((pending != null) && (now > pending.expires))
		{
			_pendingCraft.remove(ownerId);
			pending = null;
		}

		// An empty name box on the same class confirms the name that is waiting for confirmation.
		final String typed = (name == null) ? "" : name.trim();
		final String friendName;
		if (typed.isEmpty())
		{
			if ((pending == null) || (pending.classId != classId))
			{
				owner.sendMessage("[Phantom] Type the friend's name first.");
				return;
			}
			friendName = pending.name;
		}
		else
		{
			friendName = typed;
		}

		if (!isValidFriendName(friendName))
		{
			owner.sendMessage("[Phantom] Invalid name. Use only letters and numbers (max " + MAX_FRIEND_NAME + ").");
			_pendingCraft.remove(ownerId);
			return;
		}
		if (isForbiddenName(friendName))
		{
			owner.sendMessage("[Phantom] That name is not allowed.");
			_pendingCraft.remove(ownerId);
			return;
		}

		// Permanent characters: hard limit per player, counting those still being created. A Friend removed from the
		// friend list keeps its saved character, so it still counts here: deleting and re-creating cannot pile up rows.
		final int max = _maxFriendsPerPlayer;
		final AtomicInteger inFlight = _craftInFlight.computeIfAbsent(ownerId, k -> new AtomicInteger());
		final int removedButSaved = countRemovedCrafts(owner);
		final int owned = owner.getFriendList().size() + removedButSaved + inFlight.get();
		if ((max > 0) && (owned >= max))
		{
			owner.sendMessage("[Phantom] Friend limit reached (" + owned + "/" + max + ")." + ((removedButSaved > 0) ? (" " + removedButSaved + " of them were removed from your friend list but are still saved on the server; add them back as friends instead of creating new ones.") : " Remove a friend before creating another."));
			_pendingCraft.remove(ownerId);
			return;
		}
		final long wait = _craftCooldownMs - (now - _lastCraft.getOrDefault(ownerId, 0L));
		if (wait > 0)
		{
			owner.sendMessage("[Phantom] Please wait " + ((wait + 999) / 1000) + "s before creating another friend.");
			return;
		}

		// Second step: the first click only asks, the same class again creates and saves the character.
		if (_confirmCraft && ((pending == null) || (pending.classId != classId) || !pending.name.equalsIgnoreCase(friendName)))
		{
			_pendingCraft.put(ownerId, new PendingCraft(classId, friendName, now + CRAFT_CONFIRM_MS));
			owner.sendMessage("[Phantom] Create permanent friend " + friendName + " (" + displayClassName(selected) + ", level " + owner.getLevel() + ")" + ((max > 0) ? (" - slot " + (owned + 1) + "/" + max) : "") + "? It is saved on the server. Click the same class again (leave Name empty) within " + (CRAFT_CONFIRM_MS / 1000) + "s to confirm.");
			return;
		}
		_pendingCraft.remove(ownerId);
		_lastCraft.put(ownerId, now);

		final int sex = _craftSex.getOrDefault(ownerId, -1);
		final String resolvedClassSpec = resolveClassSpecForLevel(Integer.toString(classId), owner.getLevel());
		inFlight.incrementAndGet();
		ThreadPool.execute(() ->
		{
			try
			{
				owner.sendMessage("[Phantom] " + PhantomManager.getInstance().craftFriend(owner, friendName, resolvedClassSpec, 0, sex, -1, -1, -1));
				rememberCraft(owner, friendName);
			}
			finally
			{
				inFlight.decrementAndGet();
			}
		});
		refreshLater(owner, "phantoms", 1500);
		refreshLater(owner, "phantoms", 3500);
	}

	/** Player variable holding the object ids of every Friend this player created here, comma separated. */
	private static final String CRAFTED_VAR = "PhantomMgmtCrafted";

	/** Records a Friend that was just created, so it keeps counting against the limit after a friend-delete. */
	private static void rememberCraft(Player owner, String friendName)
	{
		final int id = CharInfoTable.getInstance().getIdByName(friendName);
		if (id <= 0)
		{
			return; // creation failed (cap reached, name taken), nothing was saved
		}
		synchronized (owner)
		{
			final Set<Integer> ids = craftedIds(owner);
			if (ids.add(id))
			{
				final StringBuilder sb = new StringBuilder();
				for (int craftedId : ids)
				{
					if (sb.length() > 0)
					{
						sb.append(',');
					}
					sb.append(craftedId);
				}
				owner.getVariables().set(CRAFTED_VAR, sb.toString());
				owner.getVariables().storeMe();
			}
		}
	}

	/** Friends this player created that are no longer on the friend list but whose character still exists. */
	private static int countRemovedCrafts(Player owner)
	{
		int count = 0;
		for (int id : craftedIds(owner))
		{
			if (!owner.getFriendList().contains(id) && (CharInfoTable.getInstance().getNameById(id) != null))
			{
				count++;
			}
		}
		return count;
	}

	private static Set<Integer> craftedIds(Player owner)
	{
		final Set<Integer> ids = new java.util.LinkedHashSet<>();
		for (String part : owner.getVariables().getString(CRAFTED_VAR, "").split(","))
		{
			final int id = parseInt(part.trim());
			if (id > 0)
			{
				ids.add(id);
			}
		}
		return ids;
	}

	void setCraftSex(Player owner, String value)
	{
		final int sex;
		switch (value)
		{
			case "m": sex = 0; break;
			case "f": sex = 1; break;
			default: sex = -1; break;
		}
		_craftSex.put(owner.getObjectId(), sex);
	}

	void setPullSize(Player owner, String value)
	{
		final int id = owner.getObjectId();
		final int size = Math.max(1, Math.min(3, parseInt(value)));
		Player puller = null;
		final String pullerName = _puller.get(id);
		if (pullerName != null)
		{
			puller = findPartyPhantom(owner, pullerName);
		}
		if ((puller == null) || !PhantomPartyManager.getInstance().isRecruit(puller))
		{
			puller = pickPuller(owner);
		}
		if (puller == null)
		{
			owner.sendMessage("[Phantom] Nobody can pull: you need a recruited tank or damage dealer in the party.");
			return;
		}
		_pullSize.put(id, size);
		route(owner, puller, "pull " + size);
		_puller.put(id, puller.getName());
		_camp.put(id, true);
		owner.sendMessage("[Phantom] Camp on. " + puller.getName() + " will fetch " + size + " mob" + ((size > 1) ? "s" : "") + " at a time and bring them here.");
	}

	private Player pickPuller(Player owner)
	{
		final Party party = owner.getParty();
		if (party == null)
		{
			return null;
		}
		Player fallback = null;
		for (Player member : party.getMembers())
		{
			if (!isOwnedPhantom(owner, member) || !PhantomPartyManager.getInstance().isRecruit(member))
			{
				continue;
			}
			final PartyRole role = PhantomManager.roleForClass(member.getPlayerClass());
			if (role == PartyRole.TANK)
			{
				return member;
			}
			if ((fallback == null) && !role.isSupport())
			{
				fallback = member;
			}
		}
		return fallback;
	}

	void pull(Player owner, String name)
	{
		final Player phantom = findPartyPhantom(owner, name);
		if ((phantom == null) || !PhantomPartyManager.getInstance().isRecruit(phantom))
		{
			owner.sendMessage("[Phantom] Pick a recruited phantom to pull.");
			return;
		}
		final PartyRole role = PhantomManager.roleForClass(phantom.getPlayerClass());
		if (role.isSupport())
		{
			owner.sendMessage("[Phantom] " + phantom.getName() + " is support and cannot be the puller. Choose a tank or damage dealer.");
			return;
		}
		final int id = owner.getObjectId();
		final int size = Math.max(1, _pullSize.getOrDefault(id, 1));
		_pullSize.put(id, size);
		route(owner, phantom, "pull " + size);
		_puller.put(id, phantom.getName());
		_camp.put(id, true);
		owner.sendMessage("[Phantom] " + phantom.getName() + " is the puller (" + size + " at a time). Camp planted where you stand.");
	}

	void group(Player owner, String key)
	{
		final String text = groupText(key);
		if (text == null)
		{
			return;
		}
		if ("setcamp".equals(key))
		{
			if (!hasPhantoms(owner))
			{
				owner.sendMessage("[Phantom] You have no phantoms in your party.");
				return;
			}
			final int id = owner.getObjectId();
			_camp.put(id, false);
			_pullSize.put(id, 0);
			_puller.remove(id);
			PhantomPartyManager.getInstance().handlePartyChat(owner, "break camp");
			ThreadPool.schedule(() ->
			{
				if (!owner.isOnline())
				{
					return;
				}
				PhantomPartyManager.getInstance().handlePartyChat(owner, "camp here");
				_camp.put(id, true);
				owner.sendMessage("[Phantom] Camp reset and planted where you stand.");
			}, 200L);
			return;
		}
		if (!hasPhantoms(owner))
		{
			owner.sendMessage("[Phantom] You have no phantoms in your party.");
			return;
		}
		if ("dpsstop".equals(key))
		{
			// Tank Holds is a controlled raid pause: every tank keeps fighting, exactly one healer keeps
			// supporting the tank, and everybody else (DPS, buffers and extra healers) is stopped. Do not
			// use a party-wide stop because that would also stop the tanks and the selected healer.
			boolean healerKept = false;
			int stopped = 0;
			int tanksKept = 0;
			for (Player member : owner.getParty().getMembers())
			{
				if (!isOwnedPhantom(owner, member))
				{
					continue;
				}
				final PartyRole role = PhantomManager.roleForClass(member.getPlayerClass());
				if (role == PartyRole.TANK)
				{
					tanksKept++;
					continue;
				}
				if ((role == PartyRole.HEALER) && !healerKept && !member.isDead())
				{
					healerKept = true;
					continue;
				}
				route(owner, member, "stop");
				stopped++;
			}
			_raidOrder.put(owner.getObjectId(), "dpsstop");
			owner.sendMessage("[Phantom] Tank Holds: " + tanksKept + " tank" + ((tanksKept == 1) ? "" : "s") + " and " + (healerKept ? "1 healer" : "no healer") + " remain; " + stopped + " other phantom" + ((stopped == 1) ? " is" : "s are") + " stopped.");
			return;
		}
		if ("cubicstatus".equals(key))
		{
			for (Player member : owner.getParty().getMembers())
			{
				if (isOwnedPhantom(owner, member) && isCubicKnight(member.getPlayerClass()))
				{
					route(owner, member, "cubics?");
					owner.sendMessage("[Phantom] " + member.getName() + ": cubics?.");
				}
			}
			return;
		}
		if ("weaponstatus".equals(key))
		{
			for (Player member : owner.getParty().getMembers())
			{
				if (isOwnedPhantom(owner, member) && isWeaponSwitcher(member.getPlayerClass()))
				{
					route(owner, member, "weapons?");
					owner.sendMessage("[Phantom] " + member.getName() + ": weapons?.");
				}
			}
			return;
		}
		if ("status".equals(key))
		{
			partyStatus(owner);
			return;
		}
		if ("healfull".equals(key))
		{
			final WorldObject targetObject = owner.getTarget();
			final Player target = (targetObject instanceof Player) ? (Player) targetObject : null;
			if ((target == null) || !target.isInPartyWith(owner))
			{
				owner.sendMessage("[Phantom] Target a party member first.");
				return;
			}
			Player healer = null;
			for (Player member : owner.getParty().getMembers())
			{
				if (isOwnedPhantom(owner, member) && isHealResClass(member.getPlayerClass()) && !member.isDead() && member.isOnline())
				{
					healer = member;
					break;
				}
			}
			if (healer == null)
			{
				owner.sendMessage("[Phantom] No eligible healer is in your party.");
				return;
			}
			startHealFull(owner, healer, target);
			return;
		}
		if ("res".equals(key))
		{
			// Resurrection is a support action, not a generic party-chat order. Send it directly to each
			// eligible healer/buffer so the Core's per-member res loop is armed deterministically.
			boolean armed = false;
			for (Player member : owner.getParty().getMembers())
			{
				if (!isOwnedPhantom(owner, member) || !isHealResClass(member.getPlayerClass()))
				{
					continue;
				}
				route(owner, member, "res");
				armed = true;
			}
			owner.sendMessage(armed ? "[Phantom] Resurrection order sent to eligible healers/buffers." : "[Phantom] No eligible healer/buffer is in your party.");
		}
		else if ("follow".equals(key))
		{
			// The core's normal "follow" command means follow + assist. For this UI button, Follow is a pure
			// movement mode: stay with the leader but do not proactively attack the leader's target. We keep the
			// core movement/hunting machinery and only override the three Member state flags through module-only
			// reflection, so no core source change is required. Holding=true is intentional: the core's holding
			// branch still calls driveFollow when the member falls behind, while suppressing assist/free hunting.
			PhantomPartyManager.getInstance().handlePartyChat(owner, "follow");
			for (Player member : owner.getParty().getMembers())
			{
				if (!isOwnedPhantom(owner, member))
				{
					continue;
				}
				final Object state = coreMemberState(member);
				setCoreBoolean(state, CORE_ASSIST_FIELD, false);
				setCoreBoolean(state, CORE_FOLLOWING_FIELD, true);
				setCoreBoolean(state, CORE_HOLDING_FIELD, true);
			}
		}
		else if ("standall".equals(key))
		{
			setStandAll(owner, true);
		}
		else if ("upall".equals(key))
		{
			// "Up" is the compact combat button: stop active MP recharge orders and keep every phantom standing.
			silentRecharge(owner, null, true);
			setStandAll(owner, true);
		}
		else if ("sitall".equals(key))
		{
			setStandAll(owner, false);
		}
		else if ("heal".equals(key))
		{
			silentHeal(owner, null);
		}
		else if ("recharge".equals(key))
		{
			silentRecharge(owner, null, false);
		}
		else if ("stoprecharge".equals(key))
		{
			silentRecharge(owner, null, true);
		}
		else if ("buffme".equals(key) || "buffall".equals(key))
		{
			startManualBuffPlan(owner, "buffall".equals(key));
		}
		else if (!"follow".equals(key))
		{
			PhantomPartyManager.getInstance().handlePartyChat(owner, text);
		}
		if (!"heal".equals(key) && !"recharge".equals(key) && !"stoprecharge".equals(key))
		{
			owner.sendMessage("[Phantom] " + describe(key));
		}
		final int id = owner.getObjectId();
		switch (key)
		{
			case "hold": case "assist":
				_stance.put(id, key); _camp.put(id, false); if ("hold".equals(key)) _raidOrder.remove(id); break;
			case "follow":
				_stance.put(id, "follow"); _camp.put(id, false); break;
			case "free":
				_stance.put(id, "farm"); _camp.put(id, false); break;
			case "camp": _camp.put(id, true); break;
			case "nocamp": _camp.put(id, false); _pullSize.put(id, 0); _puller.remove(id); break;
			case "nopull": _pullSize.put(id, 0); _puller.remove(id); break;
			case "holdfire": _raidOrder.put(id, "holdfire"); break;
			case "all": _raidOrder.put(id, "all"); break;
			case "tank": _raidOrder.put(id, "tank"); break;
			case "engage": _raidOrder.put(id, "engage"); break;
			case "stopfight": _raidOrder.remove(id); break;
			case "dpsstop": _raidOrder.put(id, "dpsstop"); break;
			default: break;
		}
	}

	/** Single-writer Core-aware buff planner used by the manual Buff Me/Buff All buttons. */
	private void startManualBuffPlan(Player owner, boolean all)
	{
		if (_manualBuffing.putIfAbsent(owner.getObjectId(), System.currentTimeMillis()) != null)
		{
			return;
		}
		startSafeBuffPlan(owner, all, true);
		ThreadPool.schedule(() -> {
			_manualBuffing.remove(owner.getObjectId());
			_buffPlanOwners.remove(owner.getObjectId());
		}, 30000L);
	}

	private void startSafeBuffPlan(Player owner, boolean all, boolean forceExisting)
	{
		final Party party = owner.getParty();
		if (party == null)
		{
			return;
		}
		final int ownerId = owner.getObjectId();
		final Map<String, Integer> owners = _buffPlanOwners.computeIfAbsent(ownerId, k -> new ConcurrentHashMap<>());
		final List<Player> buffers = new ArrayList<>();
		final List<Player> targets = new ArrayList<>();
		for (Player member : party.getMembers())
		{
			if (!member.isOnline() || member.isDead())
			{
				continue;
			}
			if (member == owner)
			{
				targets.add(member);
				continue;
			}
			if (!isOwnedPhantom(owner, member) || (PhantomManager.roleForClass(member.getPlayerClass()) == PartyRole.TANK))
			{
				continue;
			}
			if (all)
			{
				targets.add(member);
			}
			final PartyRole role = PhantomManager.roleForClass(member.getPlayerClass());
			if (role.isSupport() && (role != PartyRole.SINGER) && (role != PartyRole.DANCER))
			{
				buffers.add(member);
			}
		}
	
		final List<SafeBuffOrder> orders = new ArrayList<>();
		for (Player target : targets)
		{
			final boolean casterTarget = target.isMageClass();
			final PhantomBuffs.Tier tier = (target == owner) ? PhantomBuffs.Tier.LEADER : PhantomBuffs.Tier.MEMBER;
			final Map<String, SafeBuffCandidate> bestBySlot = new HashMap<>();
			// Let the updated Core decide the complete curated buff set. We iterate the actual learned skills instead of
			// copying PhantomBuffs' private AUTO_* id lists into the module, so new class/3rd-profession buffs in the Core
			// become available here automatically without inventing ids or stale lists.
			for (Player buffer : buffers)
			{
				final Collection<Skill> knownSkills = buffer.getAllSkills();
				for (Skill skill : knownSkills)
				{
					if ((skill == null) || !PhantomBuffs.wanted(skill.getId(), casterTarget, tier) || PhantomBuffs.tankAvoids(skill.getId()))
					{
						continue;
					}
					final String slot = buffSlotKey(skill);
					if (slot == null)
					{
						continue;
					}
					final String ownerKey = target.getObjectId() + ":" + slot;
					final Integer lockedOwner = owners.get(ownerKey);
					if (lockedOwner != null)
					{
						boolean lockedCasterAlive = false;
						for (Player possibleOwner : buffers)
						{
							if ((possibleOwner.getObjectId() == lockedOwner.intValue()) && possibleOwner.isOnline() && !possibleOwner.isDead())
							{
								lockedCasterAlive = true;
								break;
							}
						}
						if (!lockedCasterAlive)
						{
							owners.remove(ownerKey, lockedOwner);
						}
						else if (lockedOwner.intValue() != buffer.getObjectId())
						{
							continue;
						}
					}
					final SafeBuffCandidate candidate = new SafeBuffCandidate(buffer, target, skill, ownerKey);
					final SafeBuffCandidate current = bestBySlot.get(slot);
					if ((current == null) || isStrongerBuffCandidate(candidate, current))
					{
						bestBySlot.put(slot, candidate);
					}
				}
			}

			for (SafeBuffCandidate candidate : bestBySlot.values())
			{
				final BuffInfo active = getActiveBuff(candidate.target, candidate.skill);
				// Never downgrade an already active stronger effect. For equal strength, manual Buff Me/Buff All
				// deliberately refreshes; the automatic mode only refreshes when the Core says the effect is needed.
				if ((active != null) && (active.getSkill().getAbnormalLevel() > candidate.skill.getAbnormalLevel()))
				{
					continue;
				}
				if (!forceExisting && !PhantomBuffs.needsBuff(candidate.target, candidate.skill, 3))
				{
					continue;
				}
				owners.put(candidate.ownerKey, candidate.caster.getObjectId());
				orders.add(new SafeBuffOrder(candidate.caster, candidate.target, candidate.skill, candidate.ownerKey, forceExisting));
			}
		}

		// Music is handled through the existing delayed rotation, but participates in the same single-writer map.
		forceBuffMeMusic(owner, forceExisting);
		for (int i = 0; i < orders.size(); i++)
		{
			final SafeBuffOrder order = orders.get(i);
			final long delay = 700L + (i * 900L);
			ThreadPool.schedule(() -> executeSafeBuff(owner, order), delay);
		}
	}

	/** Returns the currently active effect occupying the exact abnormal slot, if any. */
	private static BuffInfo getActiveBuff(Player target, Skill skill)
	{
		if ((target == null) || (skill == null))
		{
			return null;
		}
		final AbnormalType type = skill.getAbnormalType();
		if ((type == null) || type.isNone())
		{
			return target.getEffectList().getBuffInfoBySkillId(skill.getId());
		}
		return target.getEffectList().getBuffInfoByAbnormalType(type);
	}

	/**
	 * Compares two candidates for the same abnormal slot. The Core's abnormal level is the primary strength
	 * indicator; skill level is only a deterministic tie-breaker. We never invent a semantic ranking between
	 * different equal-level effects sharing a slot.
	 */
	private static boolean isStrongerBuffCandidate(SafeBuffCandidate candidate, SafeBuffCandidate current)
	{
		final int candidateAbnormal = candidate.skill.getAbnormalLevel();
		final int currentAbnormal = current.skill.getAbnormalLevel();
		if (candidateAbnormal != currentAbnormal)
		{
			return candidateAbnormal > currentAbnormal;
		}
		if (candidate.skill.getLevel() != current.skill.getLevel())
		{
			return candidate.skill.getLevel() > current.skill.getLevel();
		}
		return candidate.skill.getId() < current.skill.getId();
	}

	private static final class SafeBuffCandidate
	{
		private final Player caster;
		private final Player target;
		private final Skill skill;
		private final String ownerKey;

		private SafeBuffCandidate(Player caster, Player target, Skill skill, String ownerKey)
		{
			this.caster = caster;
			this.target = target;
			this.skill = skill;
			this.ownerKey = ownerKey;
		}
	}


	private static String buffSlotKey(Skill skill)
	{
		if (skill == null)
		{
			return null;
		}
		final org.l2jmobius.gameserver.model.skill.AbnormalType type = skill.getAbnormalType();
		if ((type == null) || type.isNone())
		{
			return "ID:" + skill.getId();
		}
		return type.name();
	}

	private void executeSafeBuff(Player owner, SafeBuffOrder order)
	{
		final int ownerId = owner.getObjectId();
		if (!_manualBuffing.containsKey(ownerId) || !owner.isOnline() || !order.caster.isOnline() || !order.target.isOnline())
		{
			return;
		}
		if ((owner.getParty() == null) || (order.caster.getParty() != owner.getParty()) || (order.target.getParty() != owner.getParty()) || order.caster.isDead() || order.target.isDead())
		{
			return;
		}
		final Integer slotOwner = _buffPlanOwners.getOrDefault(owner.getObjectId(), new ConcurrentHashMap<>()).get(order.ownerKey);
		if ((slotOwner == null) || (slotOwner.intValue() != order.caster.getObjectId()))
		{
			return;
		}
		final Skill skill = order.caster.getKnownSkill(order.skill.getId());
		if ((skill == null) || order.caster.isSkillDisabled(skill) || (order.caster.getCurrentMp() < skill.getMpConsume()) || !PhantomBuffs.canAffordReagent(order.caster, skill) || (!order.forceExisting && !PhantomBuffs.needsBuff(order.target, skill, 3)))
		{
			return;
		}
		if (!skill.checkCondition(order.caster, order.target, false))
		{
			return;
		}
		if (!PhantomBuffs.reserveBuff(order.target.getObjectId(), skill, order.caster.getObjectId(), PhantomBuffs.buffHoldMillis(skill)))
		{
			return;
		}
		final WorldObject previousTarget = order.caster.getTarget();
		try
		{
			order.caster.setTarget(order.target);
			order.caster.doCast(skill);
		}
		finally
		{
			order.caster.setTarget(previousTarget);
		}
	}

	private static final class SafeBuffOrder
	{
		private final Player caster;
		private final Player target;
		private final Skill skill;
		private final String ownerKey;
		private final boolean forceExisting;

		private SafeBuffOrder(Player caster, Player target, Skill skill, String ownerKey, boolean forceExisting)
		{
			this.caster = caster;
			this.target = target;
			this.skill = skill;
			this.ownerKey = ownerKey;
			this.forceExisting = forceExisting;
		}
	}

	private void partyStatus(Player owner)
	{
		final Party party = owner.getParty();
		if (party == null)
		{
			owner.sendMessage("[Phantom] You are not in a party.");
			return;
		}
		owner.sendMessage("[Phantom] Party status:");
		for (Player member : party.getMembers())
		{
			if (!isOwnedPhantom(owner, member))
			{
				continue;
			}
			final PartyRole role = PhantomManager.roleForClass(member.getPlayerClass());
			final String roleName = (role == null) ? "UNKNOWN" : role.name().replace('_', ' ');
			final StringBuilder line = new StringBuilder("[Phantom] ").append(member.getName()).append(" (").append(roleName).append("): ")
				.append(member.getCurrentHpPercent()).append("% HP / ").append(member.getCurrentMpPercent()).append("% MP");
			if (member.isDead())
			{
				line.append(" [DEAD]");
			}
			final Object state = coreMemberState(member);
			final Object rechargeTarget = getCoreObject(state, CORE_RECHARGE_TARGET_FIELD);
			if (rechargeTarget instanceof Player)
			{
				line.append(" [Recharge ").append(((Player) rechargeTarget).getName()).append(']');
			}
			if (isCubicKnight(member.getPlayerClass()))
			{
				line.append(" [Cubics:").append(cubicSummary(member)).append(']');
			}
			if (isWeaponSwitcher(member.getPlayerClass()))
			{
				line.append(" [Weapon:").append((member.getActiveWeaponItem() == null) ? "none" : member.getActiveWeaponItem().getItemType()).append(']');
			}
			owner.sendMessage(line.toString());
		}
	}

	private static String cubicSummary(Player phantom)
	{
		if (phantom.getPlayerClass().equalsOrChildOf(PlayerClass.TEMPLE_KNIGHT))
		{
			return cubicMark(phantom, Cubic.LIFE_CUBIC, "Life") + cubicMark(phantom, Cubic.STORM_CUBIC, "Storm") + cubicMark(phantom, Cubic.ATTRACT_CUBIC, "Attract.");
		}
		return cubicMark(phantom, Cubic.VAMPIRIC_CUBIC, "Vampiric") + cubicMark(phantom, Cubic.POLTERGEIST_CUBIC, "Phantom") + cubicMark(phantom, Cubic.VIPER_CUBIC, "Viper");
	}

	private static String cubicMark(Player phantom, int cubicId, String label)
	{
		return " " + label + "=" + ((phantom.getCubicById(cubicId) != null) ? "ON" : "OFF");
	}

	private static Object getCoreObject(Object state, Field field)
	{
		if ((state == null) || (field == null))
		{
			return null;
		}
		try
		{
			return field.get(state);
		}
		catch (IllegalAccessException e)
		{
			return null;
		}
	}

	private static Object coreMemberState(Player phantom)
	{
		if ((CORE_MEMBERS_FIELD == null) || (phantom == null))
		{
			return null;
		}
		try
		{
			@SuppressWarnings("unchecked")
			final Map<Integer, Object> members = (Map<Integer, Object>) CORE_MEMBERS_FIELD.get(PhantomPartyManager.getInstance());
			return members.get(phantom.getObjectId());
		}
		catch (IllegalAccessException e)
		{
			return null;
		}
	}

	private static boolean setCoreBoolean(Object state, Field field, boolean value)
	{
		if ((state == null) || (field == null))
		{
			return false;
		}
		try
		{
			field.setBoolean(state, value);
			return true;
		}
		catch (IllegalAccessException e)
		{
			return false;
		}
	}

	private static boolean setCoreLong(Object state, Field field, long value)
	{
		if ((state == null) || (field == null))
		{
			return false;
		}
		try
		{
			field.setLong(state, value);
			return true;
		}
		catch (IllegalAccessException e)
		{
			return false;
		}
	}

	private static boolean setCoreObject(Object state, Field field, Object value)
	{
		if ((state == null) || (field == null))
		{
			return false;
		}
		try
		{
			field.set(state, value);
			return true;
		}
		catch (IllegalAccessException e)
		{
			return false;
		}
	}

	private void silentHeal(Player owner, Player onlyPhantom)
	{
		final Party party = owner.getParty();
		if (party == null)
		{
			return;
		}

		final Player target;
		if (onlyPhantom != null)
		{
			target = onlyPhantom;
		}
		else
		{
			final WorldObject targetObject = owner.getTarget();
			target = (targetObject instanceof Player) ? (Player) targetObject : null;
		}
		if ((target == null) || !target.isInPartyWith(owner) || target.isDead() || !target.isOnline())
		{
			owner.sendMessage("[Phantom] Target a party member first.");
			return;
		}

		Player healer = null;
		for (Player member : party.getMembers())
		{
			if (!isOwnedPhantom(owner, member) || !isHealResClass(member.getPlayerClass()) || member.isDead() || !member.isOnline())
			{
				continue;
			}
			if ((onlyPhantom == null) || (member == onlyPhantom))
			{
				healer = member;
				break;
			}
		}
		if (healer == null)
		{
			owner.sendMessage("[Phantom] No eligible healer is in your party.");
			return;
		}

		final Skill healSkill = directHealSkill(healer);
		if (healSkill == null)
		{
			owner.sendMessage("[Phantom] No direct heal skill is available.");
			return;
		}
		if (!skillCanBeCast(healer, target, healSkill))
		{
			owner.sendMessage("[Phantom] " + healer.getName() + " cannot cast Heal right now.");
			return;
		}

		final WorldObject previousTarget = healer.getTarget();
		try
		{
			healer.setTarget(target);
			healer.doCast(healSkill);
		}
		finally
		{
			healer.setTarget(previousTarget);
		}
	}

	/** Keep a selected healer casting direct heals on the player the owner targeted until that target is full. */
	private void startHealFull(Player owner, Player healer, Player target)
	{
		final int ownerId = owner.getObjectId();
		// One loop per player: a new click replaces the previous loop instead of stacking another one.
		stopHealFull(ownerId);
		final long generation = _healFullSequence.incrementAndGet();
		_healFullGeneration.put(ownerId, generation);
		_healFullUntil.put(ownerId, System.currentTimeMillis() + HEAL_FULL_MAX_MS);
		_healFullHealer.put(ownerId, healer);
		_healFullTarget.put(ownerId, target);
		healFullTick(owner, generation);
	}

	private void stopHealFull(int ownerId)
	{
		_healFullGeneration.remove(ownerId);
		_healFullUntil.remove(ownerId);
		_healFullHealer.remove(ownerId);
		_healFullTarget.remove(ownerId);
		final ScheduledFuture<?> task = _healFullTask.remove(ownerId);
		if (task != null)
		{
			task.cancel(false);
		}
	}

	private void healFullTick(Player owner, long generation)
	{
		final int ownerId = owner.getObjectId();
		final Long current = _healFullGeneration.get(ownerId);
		if ((current == null) || (current.longValue() != generation))
		{
			return; // replaced by a newer Heal Full or already stopped
		}
		final Long until = _healFullUntil.get(ownerId);
		final Player healer = _healFullHealer.get(ownerId);
		final Player healTarget = _healFullTarget.get(ownerId);
		if ((until == null) || (healer == null) || (healTarget == null))
		{
			stopHealFull(ownerId);
			return;
		}
		if (!owner.isOnline() || !healer.isOnline() || healer.isDead() || !healTarget.isOnline() || healTarget.isDead())
		{
			stopHealFull(ownerId);
			return;
		}
		// Stop when the party is gone, the healer is no longer one of our phantoms, or the target left the party.
		if ((owner.getParty() == null) || !isOwnedPhantom(owner, healer) || !healTarget.isInPartyWith(owner))
		{
			stopHealFull(ownerId);
			owner.sendMessage("[Phantom] Heal Full stopped (healer or target left the party).");
			return;
		}
		if (healTarget.getCurrentHpPercent() >= 100)
		{
			stopHealFull(ownerId);
			owner.sendMessage("[Phantom] Heal Full completed on " + healTarget.getName() + ".");
			return;
		}
		if (System.currentTimeMillis() >= until)
		{
			stopHealFull(ownerId);
			owner.sendMessage("[Phantom] Heal Full stopped (60s safety limit).");
			return;
		}
		final Skill healSkill = directHealSkill(healer);
		if (healSkill != null && skillCanBeCast(healer, healTarget, healSkill))
		{
			final WorldObject previousTarget = healer.getTarget();
			try
			{
				healer.setTarget(healTarget);
				healer.doCast(healSkill);
			}
			finally
			{
				healer.setTarget(previousTarget);
			}
		}
		// Keep the handle so the loop can be cancelled (new Heal Full, logout sweep). Only store it if this loop is
		// still the current one, otherwise a loop replaced during this tick would leave a stray task behind.
		final ScheduledFuture<?> next = ThreadPool.schedule(() -> healFullTick(owner, generation), HEAL_FULL_STEP_MS);
		final Long stillCurrent = _healFullGeneration.get(ownerId);
		if ((stillCurrent != null) && (stillCurrent.longValue() == generation))
		{
			_healFullTask.put(ownerId, next);
		}
		else
		{
			next.cancel(false);
		}
	}

	private static Skill directHealSkill(Player healer)
	{
		Skill best = null;
		for (SkillLearn learn : SkillTreeData.getInstance().getCompleteClassSkillTree(healer.getPlayerClass()).values())
		{
			if (learn == null)
			{
				continue;
			}
			final Skill skill = SkillData.getInstance().getSkill(learn.getSkillId(), learn.getSkillLevel());
			if ((skill == null) || (skill.getTargetType() != TargetType.ONE) || skill.isDamage() || (skill.getName() == null) || !skill.getName().toLowerCase().contains("heal"))
			{
				continue;
			}
			final Skill known = healer.getKnownSkill(skill.getId());
			if ((known != null) && ((best == null) || (known.getLevel() > best.getLevel())))
			{
				best = known;
			}
		}
		return best;
	}

	private void silentRecharge(Player owner, Player onlyPhantom, boolean stop)
	{
		final Party party = owner.getParty();
		if (party == null)
		{
			return;
		}

		if (stop)
		{
			for (Player member : party.getMembers())
			{
				if (isOwnedPhantom(owner, member) && (member.getKnownSkill(RECHARGE_SKILL_ID) != null))
				{
					route(owner, member, "stop recharge");
				}
			}
			return;
		}

		final WorldObject targetObject = owner.getTarget();
		final Player target = (targetObject instanceof Player) ? (Player) targetObject : null;
		if ((target == null) || !target.isInPartyWith(owner) || target.isDead() || !target.isOnline())
		{
			owner.sendMessage("[Phantom] Target a party member first.");
			return;
		}

		if (onlyPhantom != null)
		{
			if (!onlyPhantom.isDead() && onlyPhantom.isOnline() && (onlyPhantom.getKnownSkill(RECHARGE_SKILL_ID) != null))
			{
				route(owner, onlyPhantom, "recharge " + target.getName());
			}
			else
			{
				owner.sendMessage("[Phantom] " + onlyPhantom.getName() + " cannot Recharge right now.");
			}
			return;
		}

		for (Player member : party.getMembers())
		{
			if (isOwnedPhantom(owner, member) && !member.isDead() && member.isOnline() && (member.getKnownSkill(RECHARGE_SKILL_ID) != null))
			{
				route(owner, member, "recharge " + target.getName());
				return;
			}
		}

		owner.sendMessage("[Phantom] No eligible Elder is in your party.");
	}

	/** Keeps the whole party standing until the player explicitly allows sitting again.
	 * This is deliberately a module-side lock: the stock phantom MP-rest logic will sit a low-MP member,
	 * so we immediately stand them again while the lock is active.
	 */
	private void setStandAll(Player owner, boolean stand)
	{
		final int ownerId = owner.getObjectId();
		if (!stand)
		{
			// Sit All: end the lock (and its loop) first, then let the phantoms sit.
			releaseStand(ownerId);
			final Party party = owner.getParty();
			if (party != null)
			{
				for (Player member : party.getMembers())
				{
					if (isOwnedPhantom(owner, member) && !member.isDead())
					{
						setCoreLong(coreMemberState(member), CORE_NO_SIT_UNTIL_FIELD, 0L);
						if (member.isCastingNow())
						{
							member.abortCast();
						}
						member.getAI().setIntention(org.l2jmobius.gameserver.ai.Intention.IDLE);
						member.sitDown(false);
					}
				}
			}
			return;
		}

		_standLocked.put(ownerId, Boolean.TRUE);
		keepPartyStanding(owner);
		startStandLoop(owner);
	}

	/** Starts the 0.5 s keep-standing loop unless this player already has one running. */
	private synchronized void startStandLoop(Player owner)
	{
		if (!_standTask.containsKey(owner.getObjectId()))
		{
			scheduleStandLoop(owner);
		}
	}

	/** Ends Stand All for a player: no more loop, and the phantoms we kept up are allowed to rest again. */
	private synchronized void releaseStand(int ownerId)
	{
		_standLocked.remove(ownerId);
		final ScheduledFuture<?> task = _standTask.remove(ownerId);
		if (task != null)
		{
			task.cancel(false);
		}
		final Set<Integer> phantomIds = _standPhantoms.remove(ownerId);
		if (phantomIds != null)
		{
			for (Integer phantomId : phantomIds)
			{
				final Player phantom = World.getInstance().getPlayer(phantomId.intValue());
				if (phantom != null)
				{
					setCoreLong(coreMemberState(phantom), CORE_NO_SIT_UNTIL_FIELD, 0L);
				}
			}
		}
	}

	private void scheduleStandLoop(Player owner)
	{
		final int ownerId = owner.getObjectId();
		_standTask.put(ownerId, ThreadPool.schedule(() ->
		{
			// The loop only lives while the player is online, still locked and has phantoms of their own in the party.
			if (!_standLocked.containsKey(ownerId) || !owner.isOnline() || !hasPhantoms(owner))
			{
				releaseStand(ownerId);
				return;
			}
			keepPartyStanding(owner);
			scheduleStandLoop(owner);
		}, 500));
	}

	private void keepPartyStanding(Player owner)
	{
		final Party party = owner.getParty();
		if (party == null)
		{
			return;
		}
		final Set<Integer> phantomIds = _standPhantoms.computeIfAbsent(owner.getObjectId(), k -> ConcurrentHashMap.newKeySet());
		for (Player member : party.getMembers())
		{
			if (isOwnedPhantom(owner, member) && !member.isDead())
			{
				phantomIds.add(member.getObjectId());
				setCoreLong(coreMemberState(member), CORE_NO_SIT_UNTIL_FIELD, Long.MAX_VALUE);
				if (member.isSitting())
				{
					member.standUp();
				}
			}
		}
	}

	/**
	 * Buff Me must refresh songs/dances even when the singer/dancer still has its own music effects active.
	 * The core's normal rotation deliberately checks the caster's effect list, so a second Buff Me after the
	 * leader removed the buffs would otherwise skip music. Named music requests use the core's pendingSong path,
	 * which intentionally bypasses that caster-effect gate. Here we replay exactly the same priority/cap model as
	 * the core's automatic music rotation, one request at a time so the previous cast cannot be overwritten.
	 */
	private void forceBuffMeMusic(Player owner, boolean forceExisting)
	{
		final Party party = owner.getParty();
		if (party == null)
		{
			return;
		}

		final List<Player> musicMembers = new ArrayList<>();
		for (Player member : party.getMembers())
		{
			if (member == owner)
			{
				continue;
			}
			// Only our own phantoms: a human player (or somebody else's phantom) of a singer/dancer class must never be driven.
			if (!isOwnedPhantom(owner, member))
			{
				continue;
			}
			final PartyRole role = PhantomManager.roleForClass(member.getPlayerClass());
			if ((role != PartyRole.TANK) && ((role == PartyRole.SINGER) || (role == PartyRole.DANCER)))
			{
				musicMembers.add(member);
			}
		}
		if (musicMembers.isEmpty())
		{
			return;
		}

		final int cap = Math.max(1, MUSIC_POOL_SLOTS / musicMembers.size());
		final boolean mageHeavy = countPartyCasters(owner, party) >= 2;
		final List<MusicOrder> orders = new ArrayList<>();
		for (Player phantom : musicMembers)
		{
			final PartyRole role = PhantomManager.roleForClass(phantom.getPlayerClass());
			final int[] priority = (role == PartyRole.DANCER) ? (mageHeavy ? DANCER_DANCES_MAGE : DANCER_DANCES) : SINGER_SONGS;
			int added = 0;
			for (int skillId : priority)
			{
				final Skill skill = phantom.getKnownSkill(skillId);
				if (skill == null)
				{
					continue;
				}
				orders.add(new MusicOrder(phantom, skill));
				if (++added >= cap)
				{
					break;
				}
			}
		}

		for (int i = 0; i < orders.size(); i++)
		{
			final MusicOrder order = orders.get(i);
			final long delay = FORCE_MUSIC_FIRST_DELAY_MS + (i * FORCE_MUSIC_STEP_MS);
			ThreadPool.schedule(() ->
			{
				if (!owner.isOnline() || !order.phantom.isOnline() || (owner.getParty() != order.phantom.getParty()) || order.phantom.isCastingNow())
				{
					return;
				}
				if (order.skill.getId() == 0 || order.phantom.getKnownSkill(order.skill.getId()) == null)
				{
					return;
				}
				if (order.phantom.isSkillDisabled(order.skill) || (order.phantom.getCurrentMp() < order.skill.getMpConsume()) || !PhantomBuffs.canAffordReagent(order.phantom, order.skill))
				{
					return;
				}
				// Songs and dances are party-wide. If the leader already has this exact effect (or an equal/stronger
				// effect occupies the same abnormal slot), do not make another singer/dancer cast it.
				if (!skillCanBeCast(order.phantom, owner, order.skill) || (!forceExisting && !PhantomBuffs.needsBuff(owner, order.skill, 0)))
				{
					return;
				}
				final String slot = buffSlotKey(order.skill);
				if (slot != null)
				{
					final Map<String, Integer> owners = _buffPlanOwners.computeIfAbsent(owner.getObjectId(), k -> new ConcurrentHashMap<>());
					final String ownerKey = owner.getObjectId() + ":" + slot;
					final Integer existingOwner = owners.putIfAbsent(ownerKey, order.phantom.getObjectId());
					if ((existingOwner != null) && (existingOwner.intValue() != order.phantom.getObjectId()))
					{
						return;
					}
				}
				if (!PhantomBuffs.reserveBuff(owner.getObjectId(), order.skill, order.phantom.getObjectId(), PhantomBuffs.buffHoldMillis(order.skill)))
				{
					return;
				}
				// Cast directly instead of routing a named whisper through PhantomPartyManager.
				// The whisper path sends a chat reply such as "singing song of wind" for every skill.
				// Buff Me music is an internal refresh, so it should apply silently.
				final WorldObject previousTarget = order.phantom.getTarget();
				try
				{
					order.phantom.setTarget(order.phantom);
					order.phantom.doCast(order.skill);
				}
				finally
				{
					order.phantom.setTarget(previousTarget);
				}
			}, delay);
		}
	}

	private static boolean skillCanBeCast(Player caster, Player target, Skill skill)
	{
		return (caster != null) && (target != null) && (skill != null) && !caster.isSkillDisabled(skill) && (caster.getCurrentMp() >= skill.getMpConsume()) && PhantomBuffs.canAffordReagent(caster, skill) && skill.checkCondition(caster, target, false);
	}

	private int countPartyCasters(Player owner, Party party)
	{
		int count = owner.isMageClass() ? 1 : 0;
		for (Player member : party.getMembers())
		{
			if (!isOwnedPhantom(owner, member) || !PhantomPartyManager.getInstance().isRecruit(member))
			{
				continue;
			}
			if (PhantomManager.roleForClass(member.getPlayerClass()) == PartyRole.NUKER)
			{
				count++;
			}
		}
		return count;
	}

	private static final class MusicOrder
	{
		private final Player phantom;
		private final Skill skill;

		private MusicOrder(Player phantom, Skill skill)
		{
			this.phantom = phantom;
			this.skill = skill;
		}
	}

	void member(Player owner, String key, String name)
	{
		final Player phantom = findPartyPhantom(owner, name);
		final String text = memberText(key);
		if ((phantom == null) || (text == null))
		{
			owner.sendMessage("[Phantom] No phantom named '" + name + "' in your party.");
			return;
		}
		if ("heal".equals(key))
		{
			if (isHealResClass(phantom.getPlayerClass()))
			{
				final WorldObject targetObject = owner.getTarget();
				final Player target = (targetObject instanceof Player) ? (Player) targetObject : null;
				if ((target == null) || !target.isInPartyWith(owner))
				{
					owner.sendMessage("[Phantom] Target a party member first.");
					return;
				}
				startHealFull(owner, phantom, target);
			}
		}
		else if ("recharge".equals(key))
		{
			if (isRechargeClass(phantom.getPlayerClass()))
			{
				silentRecharge(owner, phantom, false);
			}
		}
		else if ("stoprecharge".equals(key))
		{
			if (isRechargeClass(phantom.getPlayerClass()))
			{
				silentRecharge(owner, phantom, true);
			}
		}
		else if ("res".equals(key))
		{
			if (isHealResClass(phantom.getPlayerClass()))
			{
				route(owner, phantom, text);
			}
		}
		else
		{
			route(owner, phantom, text);
		}
		if (!"heal".equals(key) && !"recharge".equals(key) && !"stoprecharge".equals(key))
		{
			owner.sendMessage("[Phantom] " + phantom.getName() + ": " + text + ".");
		}
	}

	void openMember(Player owner, String name)
	{
		final Player phantom = findPartyPhantom(owner, name);
		if (phantom == null)
		{
			owner.sendMessage("[Phantom] No phantom named '" + name + "' in your party.");
			return;
		}
		final int objectId = owner.getObjectId();
		_selectedMember.put(objectId, phantom.getName());
		_page.put(objectId, "member");
		_pageGeneration.put(objectId, _pageGeneration.getOrDefault(objectId, 0L) + 1L);
	}

	void buff(Player owner, String name, String buff)
	{
		final Player phantom = findPartyPhantom(owner, name);
		if (phantom == null)
		{
			owner.sendMessage("[Phantom] No phantom named '" + name + "' in your party.");
			return;
		}
		final int skillId = parseInt(buff);
		if (skillId <= 0)
		{
			memberTextCommand(owner, name, buff);
			return;
		}

		final Skill skill = phantom.getKnownSkill(skillId);
		if (skill == null)
		{
			return;
		}
		if (phantom.isSkillDisabled(skill))
		{
			return;
		}
		if ((phantom.getCurrentMp() < skill.getMpConsume()) || !PhantomBuffs.canAffordReagent(phantom, skill))
		{
			owner.sendMessage("[Phantom] " + phantom.getName() + ": " + skill.getName() + " cannot be cast: insufficient MP or reagent.");
			return;
		}
		if (!skill.checkCondition(phantom, owner, false))
		{
			owner.sendMessage("[Phantom] " + phantom.getName() + ": " + skill.getName() + " cannot be cast now (conditions not met).");
			return;
		}
		final WorldObject previousTarget = phantom.getTarget();
		try
		{
			phantom.setTarget(owner);
			phantom.doCast(skill);
			owner.sendMessage("[Phantom] " + phantom.getName() + ": " + skill.getName() + ".");
		}
		finally
		{
			phantom.setTarget(previousTarget);
		}
	}

	void music(Player owner, String name, int skillId)
	{
		final Player phantom = findPartyPhantom(owner, name);
		if (phantom == null)
		{
			owner.sendMessage("[Phantom] No phantom named '" + name + "' in your party.");
			return;
		}
		final Skill skill = phantom.getKnownSkill(skillId);
		if (skill == null)
		{
			return;
		}
		if (phantom.isSkillDisabled(skill))
		{
			return;
		}
		if ((phantom.getCurrentMp() < skill.getMpConsume()) || !PhantomBuffs.canAffordReagent(phantom, skill))
		{
			owner.sendMessage("[Phantom] " + phantom.getName() + ": " + skill.getName() + " cannot be cast: insufficient MP or reagent.");
			return;
		}
		if (!skill.checkCondition(phantom, owner, false))
		{
			owner.sendMessage("[Phantom] " + phantom.getName() + ": " + skill.getName() + " cannot be cast now (conditions not met).");
			return;
		}
		final WorldObject previousTarget = phantom.getTarget();
		try
		{
			phantom.setTarget(owner);
			phantom.doCast(skill);
			owner.sendMessage("[Phantom] " + phantom.getName() + ": " + skill.getName() + ".");
		}
		finally
		{
			phantom.setTarget(previousTarget);
		}
	}

	void cubic(Player owner, String name, String action)
	{
		if (action.startsWith("weapon:"))
		{
			weapon(owner, name, action.substring(7));
			return;
		}
		final Player phantom = findPartyPhantom(owner, name);
		if ((phantom == null) || !PhantomPartyManager.getInstance().isRecruit(phantom))
		{
			owner.sendMessage("[Phantom] Cubics are only available for recruited members.");
			return;
		}
		if ("status".equalsIgnoreCase(action))
		{
			route(owner, phantom, "cubics?");
			owner.sendMessage("[Phantom] " + phantom.getName() + ": cubics?");
			return;
		}
		final String text = action.startsWith("drop:") ? "drop " + action.substring(5) + " cubic" : action + " cubic";
		route(owner, phantom, text);
		owner.sendMessage("[Phantom] " + phantom.getName() + ": " + text + ".");
	}

	private void weapon(Player owner, String name, String action)
	{
		final Player phantom = findPartyPhantom(owner, name);
		if ((phantom == null) || !PhantomPartyManager.getInstance().isRecruit(phantom) || !isWeaponSwitcher(phantom.getPlayerClass()))
		{
			owner.sendMessage("[Phantom] Weapon switching is only available for Gladiator, Warlord, Destroyer and Dwarf classes.");
			return;
		}
		final String text;
		switch (action.toLowerCase())
		{
			case "spare":
				text = isPolearmSpare(phantom.getPlayerClass()) ? "switch to polearm" : "use your blunt";
				break;
			case "back":
				text = "switch back";
				break;
			case "status":
				text = "weapons?";
				break;
			default:
				return;
		}

		if ("status".equalsIgnoreCase(action))
		{
			route(owner, phantom, text);
			owner.sendMessage("[Phantom] " + phantom.getName() + ": " + text + ".");
			return;
		}

		final WeaponType wanted = "back".equalsIgnoreCase(action) ? mainWeaponType(phantom.getPlayerClass()) : (isPolearmSpare(phantom.getPlayerClass()) ? WeaponType.POLE : WeaponType.BLUNT);
		final Item target = findWeapon(phantom, wanted, "back".equalsIgnoreCase(action));
		if (target == null)
		{
			// Keep the Core command as the fallback if the module cannot resolve the carried weapon.
			route(owner, phantom, text);
		}
		else
		{
			equipWeaponFast(phantom, target);
		}
		owner.sendMessage("[Phantom] " + phantom.getName() + ": " + text + ".");
	}

	private static Item findWeapon(Player phantom, WeaponType wanted, boolean main)
	{
		Item fallback = null;
		for (Item item : phantom.getInventory().getItems())
		{
			if ((item == null) || !item.isWeapon() || item.getTemplate().isMagicWeapon() || (item.getItemType() == WeaponType.FISHINGROD))
			{
				continue;
			}
			final WeaponType type = (WeaponType) item.getItemType();
			if (main && (wanted == WeaponType.SWORD) && (type == WeaponType.BLUNT))
			{
				// Destroyer/Titan can roll either a two-handed sword or a two-handed blunt as main.
				fallback = item;
				continue;
			}
			if (type == wanted)
			{
				if (!item.isEquipped())
				{
					return item;
				}
				return null;
			}
		}
		return main ? fallback : null;
	}

	private static WeaponType mainWeaponType(PlayerClass playerClass)
	{
		if ((playerClass != null) && playerClass.equalsOrChildOf(PlayerClass.GLADIATOR))
		{
			return WeaponType.DUAL;
		}
		if ((playerClass != null) && playerClass.equalsOrChildOf(PlayerClass.WARLORD))
		{
			return WeaponType.POLE;
		}
		if ((playerClass != null) && playerClass.equalsOrChildOf(PlayerClass.DESTROYER))
		{
			return WeaponType.SWORD;
		}
		return WeaponType.BLUNT;
	}

	private static void equipWeaponFast(Player phantom, Item weapon)
	{
		equipWeaponFast(phantom, weapon, 0);
	}

	private static void equipWeaponFast(Player phantom, Item weapon, int tries)
	{
		if (phantom.isDead() || (weapon.getOwnerId() != phantom.getObjectId()) || weapon.isEquipped() || phantom.isMounted() || phantom.isDisarmed())
		{
			return;
		}
		if (phantom.isCastingNow() || phantom.isCastingSimultaneouslyNow())
		{
			if (tries < 20)
			{
				ThreadPool.schedule(() -> equipWeaponFast(phantom, weapon, tries + 1), 100);
			}
			return;
		}
		// Keep the fast module-only switch: do not wait for attackEndTime and do not abort the current attack.
		phantom.useEquippableItem(weapon, false);
		if (weapon.isEquipped())
		{
			if (weapon.getTemplate().getBodyPart() == BodyPart.R_HAND)
			{
				for (Item item : phantom.getInventory().getItems())
				{
					if (!item.isEquipped() && item.isArmor() && (item.getTemplate().getBodyPart() == BodyPart.L_HAND))
					{
						phantom.useEquippableItem(item, false);
						break;
					}
				}
			}
			return;
		}
		if (tries < 20)
		{
			ThreadPool.schedule(() -> equipWeaponFast(phantom, weapon, tries + 1), 100);
		}
	}

	void teleport(Player owner, String destination)
	{
		final String text = teleportText(destination);
		if (text == null)
		{
			return;
		}
		if (!hasPhantoms(owner))
		{
			owner.sendMessage("[Phantom] You have no phantoms in your party.");
			return;
		}
		PhantomPartyManager.getInstance().handlePartyChat(owner, "go to " + text);
		owner.sendMessage("[Phantom] Travelling to " + text + ".");
	}

	void setView(Player owner, String view)
	{
		_phantomsView.put(owner.getObjectId(), view);
	}

	private void memberTextCommand(Player owner, String name, String command)
	{
		final Player phantom = findPartyPhantom(owner, name);
		if (phantom == null)
		{
			owner.sendMessage("[Phantom] No phantom named '" + name + "' in your party.");
			return;
		}
		route(owner, phantom, command);
		owner.sendMessage("[Phantom] " + phantom.getName() + ": " + command + ".");
	}

	private static void route(Player owner, Player phantom, String text)
	{
		if (PhantomPartyManager.getInstance().isRecruit(phantom))
		{
			PhantomPartyManager.getInstance().handleWhisper(owner, phantom, text);
		}
		else if (PhantomBuddyManager.getInstance().isBuddy(phantom))
		{
			PhantomBuddyManager.getInstance().handleWhisper(owner, phantom, text);
		}
	}

	private boolean isPhantomPanelHtmlActive(Player owner)
	{
		final int objectId = owner.getObjectId();
		// Any other NpcHtmlMessage (NPC dialog, //admin, ...) clears and rebuilds the player's NPC_HTML action cache.
		// So if the panel's own Home tab action is no longer valid, another window replaced the panel: stop refreshing.
		if (owner.validateHtmlAction("ph_home") < 0)
		{
			return false;
		}
		// The client never tells the server that a window was closed, so use heuristics: stop refreshing when the
		// player has gone idle, changed target or walked away from where the panel was last used.
		if (_autoRefreshIdleMs <= 0)
		{
			return true; // AutoRefreshSeconds = 0: always refresh while no other window replaced the panel.
		}
		if ((System.currentTimeMillis() - _lastInteraction.getOrDefault(objectId, 0L)) > _autoRefreshIdleMs)
		{
			return false;
		}
		final int[] state = _interactionState.get(objectId);
		if (state != null)
		{
			final int targetId = (owner.getTarget() != null) ? owner.getTarget().getObjectId() : 0;
			if ((targetId != state[0]) || (Math.abs(owner.getX() - state[1]) > 30) || (Math.abs(owner.getY() - state[2]) > 30))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * After a Recruit request, checks every 250 ms and repaints the Phantoms page the moment a new phantom shows up in the
	 * waiting list (instead of waiting for fixed 1s/2.5s/5s refreshes). Gives up after ~15 s.
	 */
	private void pollRecruit(Player owner)
	{
		final Set<String> known = new HashSet<>();
		for (Player phantom : waitingPhantoms(owner))
		{
			known.add(phantom.getName());
		}
		pollRecruitStep(owner, known, 0);
	}

	private void pollRecruitStep(Player owner, Set<String> known, int step)
	{
		ThreadPool.schedule(() ->
		{
			final int objectId = owner.getObjectId();
			if (!owner.isOnline() || !"phantoms".equals(_page.get(objectId)) || !isPhantomPanelHtmlActive(owner))
			{
				return;
			}
			boolean appeared = false;
			for (Player phantom : waitingPhantoms(owner))
			{
				if (!known.contains(phantom.getName()))
				{
					appeared = true;
					break;
				}
			}
			if (appeared)
			{
				render(owner, "phantoms");
			}
			else if (step < 60)
			{
				pollRecruitStep(owner, known, step + 1);
			}
		}, 250);
	}

	private void refreshLater(Player owner, String page, long delayMs)
	{
		final int objectId = owner.getObjectId();
		final long generation = _pageGeneration.getOrDefault(objectId, 0L);
		ThreadPool.schedule(() ->
		{
			if (owner.isOnline() && (generation == _pageGeneration.getOrDefault(objectId, 0L)) && page.equals(_page.get(objectId)) && isPhantomPanelHtmlActive(owner))
			{
				render(owner, page);
			}
		}, delayMs);
	}

	private static void cancelRefresh(Map<Integer, ScheduledFuture<?>> tasks, int objectId)
	{
		final ScheduledFuture<?> task = tasks.remove(objectId);
		if (task != null)
		{
			task.cancel(false);
		}
	}

	private void ensurePhantomsAutoRefresh(Player owner)
	{
		ensureAutoRefresh(owner, "phantoms", _phantomsAutoRefresh, 3000);
	}

	private void ensurePartyAutoRefresh(Player owner)
	{
		ensureAutoRefresh(owner, "party", _partyAutoRefresh, 3000);
	}

	private void ensureTargetsAutoRefresh(Player owner)
	{
		ensureAutoRefresh(owner, "targets", _targetsAutoRefresh, 3000);
	}

	private void ensureDetailAutoRefresh(Player owner)
	{
		ensureAutoRefresh(owner, "cubics", _detailAutoRefresh, 2000);
	}

	private void ensureAutoRefresh(Player owner, String page, Map<Integer, ScheduledFuture<?>> tasks, long delayMs)
	{
		final int objectId = owner.getObjectId();
		final long generation = _pageGeneration.getOrDefault(objectId, 0L);
		if (!_autoRefresh || tasks.containsKey(objectId))
		{
			return;
		}
		final ScheduledFuture<?> task = ThreadPool.schedule(() ->
		{
			tasks.remove(objectId);
			if (owner.isOnline() && (generation == _pageGeneration.getOrDefault(objectId, 0L)) && page.equals(_page.get(objectId)) && isPhantomPanelHtmlActive(owner))
			{
				render(owner, page);
			}
		}, delayMs);
		tasks.put(objectId, task);
	}

	private static int parseInt(String value)
	{
		try
		{
			return Integer.parseInt(value.trim());
		}
		catch (Exception e)
		{
			return 0;
		}
	}

	private static String groupText(String key)
	{
		switch (key)
		{
			case "assist": return "assist";
			case "free": return "attack freely";
			case "follow": return "follow";
			case "hold": return "stay";
			case "camp": return "camp here";
			case "setcamp": return "set camp";
			case "nocamp": return "break camp";
			case "nopull": return "stop pulling";
			case "tank": return "tank attack";
			case "all": return "all attack";
			case "holdfire": return "hold fire";
			case "engage": return "engage";
			case "stopfight": return "stop";
			case "dpsstop": return "stop";
			case "rebuff": return "buff all";
			case "buffall": return "buff all";
			case "buffme": return "buff me songs dance";
			case "standall": return "stand";
			case "upall": return "stand";
			case "sitall": return "sit";
			case "songs": return "songs";
			case "heal": return "heal me";
			case "res": return "res";
			case "recharge": return "recharge";
			case "stoprecharge": return "stop recharge";
			case "spoilassist": return "spoil on assist";
			case "loot": return "party return loot";
			case "brb": return "brb";
			case "disbandall": return "bye";
			case "cubicstatus": return "cubics?";
			case "weaponstatus": return "weapons?";
			case "status": return "status";
			case "healfull": return "heal full";
			default: return null;
		}
	}

	private static String describe(String key)
	{
		switch (key)
		{
			case "assist": return "Assist: phantoms fight the mob you target.";
			case "free": return "Farm: phantoms hunt on their own around you.";
			case "follow": return "Follow: phantoms stay with you and do not attack on their own.";
			case "hold": return "Hold: phantoms stop following and stay put.";
			case "camp": return "Camp on: the party holds this spot and kills what is brought here.";
			case "nocamp": return "Camp off: the party is free again.";
			case "nopull": return "No pull: the puller stops fetching mobs.";
			case "tank": return "Tank Attack: the tank engages the raid first.";
			case "all": return "Raid: everyone attacks now.";
			case "holdfire": return "Raid: wait for the tank to engage.";
			case "engage": return "Engage: attack your target (raid boss: tank pulls first, then everyone joins).";
			case "stopfight": return "Stop: the party stops fighting and drops raid orders, but keeps following you.";
			case "dpsstop": return "Tank Holds: all tanks keep fighting and one healer keeps the tank alive; DPS, buffers and extra healers stop.";
			case "rebuff": return "Buffers rebuff the whole party.";
			case "buffall": return "Everyone gets the party buffs; singers sing and dancers dance.";
			case "buffme": return "Buffers refresh your full kit; singers sing and dancers dance.";
			case "standall": return "All phantoms stand up and stay standing until you press Sit All.";
			case "upall": return "Stop active MP recharge orders and keep all phantoms standing.";
			case "sitall": return "All phantoms are allowed to sit and recover MP again.";
			case "songs": return "Singers sing and dancers dance.";
			case "heal": return "Healers cast a heal on you.";
			case "res": return "Healers/buffers attempt Resurrection on fallen members.";
			case "recharge": return "Elders recharge party MP.";
			case "stoprecharge": return "Stop ongoing Recharge orders.";
			case "spoilassist": return "Spoiler: spoil your assist targets.";
			case "loot": return "Hand collected loot back to you.";
			case "brb": return "BRB: phantoms wait for you.";
			case "disbandall": return "Every phantom says goodbye and leaves.";
			case "status": return "Party status: HP/MP, Recharge, Cubics and current weapon.";
			case "healfull": return "Heal Full: the selected healer keeps healing your target until full.";
			default: return key;
		}
	}

	private static String memberText(String key)
	{
		switch (key)
		{
			case "assist": return "assist";
			case "free": return "attack freely";
			case "stay": return "stay";
			case "follow": return "follow";
			case "buff": return "buff all";
			case "buffme": return "buff me";
			case "heal": return "heal me";
			case "res": return "res";
			case "recharge": return "recharge";
			case "stoprecharge": return "stop recharge";
			case "spoilassist": return "spoil on assist";
			case "songs": return "songs";
			case "dance": return "dance";
			case "hold": return "hold";
			default: return null;
		}
	}

	private static String btn(String label, String action, boolean active)
	{
		return "<button value=\"" + label + "\" action=\"bypass -h " + action + "\" width=" + BTN_W + " height=" + BTN_H + " back=\"" + BTN_ON + "\" fore=\"" + (active ? BTN_ON : BTN_OFF) + "\">";
	}

	private static String unavailableBtn(String label, String action)
	{
		return "<button value=\"" + label + "\" action=\"bypass -h " + action + "\" width=" + BTN_W + " height=" + BTN_H + " back=\"L2UI.DefaultButton\" fore=\"888888\">";
	}

	private static String tabBtn(String label, String action, boolean active, int width)
	{
		return "<button value=\"" + label + "\" action=\"bypass -h " + action + "\" width=" + width + " height=" + TAB_H + " back=\"" + TAB_ON + "\" fore=\"" + (active ? TAB_ON : TAB_OFF) + "\">";
	}

	/** Compact selector button used by the long 31-class lists so the client can keep the full page scrollable. */
	private static String classBtn(String label, String action)
	{
		// Use the same styled buttons as the rest of the panel (Party Control, etc.).
		return btn(label, action, false);
	}

	private static String centeredBtnCell(String content, int width)
	{
		return "<td width=" + width + " align=center valign=middle>" + content + "</td>";
	}

	/** Adds a small vertical breathing space between logical sections. */
	private static String gap()
	{
		return "<br1><br1>";
	}

	/** A subtle full-width divider used between the Home page sections. */
	private static String sectionDivider()
	{
		return "<table width=" + GRID_W + " cellspacing=0 cellpadding=0><tr><td height=18></td></tr></table>"
				+ "<img src=\"L2UI.SquareGray\" width=" + GRID_W + " height=1>"
				+ "<table width=" + GRID_W + " cellspacing=0 cellpadding=0><tr><td height=18></td></tr></table>";
	}

	private static String homeGap()
	{
		return "<table width=" + GRID_W + " cellspacing=0 cellpadding=0><tr><td height=10></td></tr></table>";
	}

	/** Extra-tight spacer used only before Party Control so the complete Home page fits. */
	private static String homeTightGap()
	{
		return "<table width=" + GRID_W + " cellspacing=0 cellpadding=0><tr><td height=6></td></tr></table>";
	}

	private static String td(int width, String content)
	{
		return "<td width=" + width + " align=left valign=middle>" + content + "</td>";
	}

	private static String tr(String... cells)
	{
		final StringBuilder sb = new StringBuilder("<table width=" + (cells.length * CELL_W) + " cellspacing=0 cellpadding=0><tr>");
		for (String c : cells)
		{
			sb.append(c);
		}
		return sb.append("</tr></table>").toString();
	}

	private static String gridRow(String... contents)
	{
		final StringBuilder sb = new StringBuilder("<table width=" + GRID_W + " cellspacing=2 cellpadding=0><tr>");
		for (int i = 0; i < 4; i++)
		{
			sb.append(centeredBtnCell((i < contents.length) ? contents[i] : "", CELL_W));
		}
		return sb.append("</tr></table>").toString();
	}

	/** Same four-button layout as the normal grid, narrowed only for the Phantoms pages to clear the native scrollbar. */
	private static String phantomGridRow(String... contents)
	{
		final StringBuilder sb = new StringBuilder("<table width=" + PHANTOM_GRID_W + " cellspacing=0 cellpadding=0><tr>");
		for (int i = 0; i < 4; i++)
		{
			sb.append(centeredBtnCell((i < contents.length) ? contents[i] : "", PHANTOM_CELL_W));
		}
		return sb.append("</tr></table>").toString();
	}

	/** Same Phantoms grid, with three controls centered across the 264px Phantoms width. */
	private static String phantomCenteredGridRow(String... contents)
	{
		final int count = Math.min(3, contents.length);
		final int side = (PHANTOM_GRID_W - (count * PHANTOM_CELL_W)) / 2;
		final StringBuilder sb = new StringBuilder("<table width=" + PHANTOM_GRID_W + " cellspacing=0 cellpadding=0><tr>");
		sb.append("<td width=" + side + "></td>");
		for (int i = 0; i < count; i++)
		{
			sb.append(centeredBtnCell(contents[i], PHANTOM_CELL_W));
		}
		sb.append("<td width=" + side + "></td>");
		return sb.append("</tr></table>").toString();
	}

	/** Compact class grid so all 31 second classes fit under MAX_HTML. */
	private static String phantomClassGrid(int[] classIds, String actionPrefix, String variable)
	{
		final StringBuilder sb = new StringBuilder("<table width=" + PHANTOM_GRID_W + " cellspacing=3 cellpadding=0>");
		for (int i = 0; i < classIds.length; i += 4)
		{
			sb.append("<tr>");
			for (int j = 0; j < 4; j++)
			{
				final int idx = i + j;
				if (idx < classIds.length)
				{
					final PlayerClass pc = PlayerClass.getPlayerClass(classIds[idx]);
					sb.append("<td width=" + PHANTOM_CELL_W + " align=center>").append(classBtn(classButtonName(pc), actionPrefix + " " + pc.getId() + " " + variable)).append("</td>");
				}
				else
				{
					sb.append("<td width=" + PHANTOM_CELL_W + "></td>");
				}
			}
			sb.append("</tr>");
		}
		return sb.append("</table>").toString();
	}

	/** Centers a 2- or 3-button row within the same 288px four-column grid. */
	private static String centeredGridRow(String... contents)
	{
		final int count = Math.min(3, Math.max(1, contents.length));
		final int side = (count == 2) ? CELL_W : (CELL_W / 2);
		final StringBuilder sb = new StringBuilder("<table width=" + GRID_W + " cellspacing=2 cellpadding=0><tr>");
		sb.append("<td width=" + side + "></td>");
		for (int i = 0; i < count; i++)
		{
			sb.append(centeredBtnCell(contents[i], CELL_W));
		}
		sb.append("<td width=" + side + "></td>");
		return sb.append("</tr></table>").toString();
	}

	private static String head(String text)
	{
		return "<font color=\"LEVEL\">" + text + "</font>";
	}

	private static String classLabel(Player p)
	{
		final String[] words = p.getPlayerClass().name().toLowerCase().split("_");
		final StringBuilder sb = new StringBuilder();
		for (String word : words)
		{
			if (word.isEmpty()) continue;
			if (sb.length() > 0) sb.append(' ');
			sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return sb.toString();
	}

	/** Resolves the selected Interlude second class from the current class hierarchy. */
	private static String secondClassLabel(Player p)
	{
		PlayerClass playerClass = p.getPlayerClass();
		if (playerClass == null)
		{
			return "Unknown";
		}
		while ((playerClass.level() > 2) && (playerClass.getParent() != null))
		{
			playerClass = playerClass.getParent();
		}
		return className(playerClass);
	}

	private static String className(PlayerClass playerClass)
	{
		final String[] words = playerClass.name().toLowerCase().split("_");
		final StringBuilder sb = new StringBuilder();
		for (String word : words)
		{
			if (word.isEmpty()) continue;
			if (sb.length() > 0) sb.append(' ');
			sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return sb.toString();
	}

	private static String partySpacer(int height)
	{
		return "<table width=" + GRID_W + " cellspacing=0 cellpadding=0><tr><td height=" + height + "></td></tr></table>";
	}

	private static String pageOf(String page)
	{
		if (page.equals("party") || page.equals("member") || page.equals("buffs") || page.equals("music") || page.equals("cubics")) return "party";
		if (page.equals("targets") || page.equals("teleport")) return "targets";
		if (page.equals("phantoms") || page.equals("waiting")) return "phantoms";
		return "home";
	}

	/** Entry point for real player actions (.phantom, panel buttons). Resets the idle/position state of auto refresh. */
	void show(Player player, String page)
	{
		final int objectId = player.getObjectId();
		_lastInteraction.put(objectId, System.currentTimeMillis());
		_interactionState.put(objectId, new int[]
		{
			(player.getTarget() != null) ? player.getTarget().getObjectId() : 0,
			player.getX(),
			player.getY()
		});
		render(player, page);
	}

	/** Renders a page. Also used by auto refresh, which must NOT reset the interaction state. */
	private void render(Player player, String page)
	{
		final int objectId = player.getObjectId();
		// Cancel any refresh task belonging to the previous page instance before rendering the new page.
		cancelRefresh(_partyAutoRefresh, objectId);
		cancelRefresh(_targetsAutoRefresh, objectId);
		cancelRefresh(_detailAutoRefresh, objectId);
		cancelRefresh(_phantomsAutoRefresh, objectId);
		_page.put(objectId, page);
		_pageGeneration.put(objectId, _pageGeneration.getOrDefault(objectId, 0L) + 1L);
		final StringBuilder sb = new StringBuilder(5000);
		final String top = pageOf(page);
		sb.append("<html><body scroll=\"yes\"><center>");
		sb.append(head("Phantom-Management")).append("<br1>");
		sb.append("<table width=" + GRID_W + " cellspacing=0 cellpadding=0><tr>");
		sb.append(centeredBtnCell(tabBtn("Home", "ph_home", top.equals("home"), TAB_W), TAB_W));
		sb.append(centeredBtnCell(tabBtn("Party", "ph_party", top.equals("party"), TAB_W), TAB_W));
		sb.append(centeredBtnCell(tabBtn("Targets", "ph_targets", top.equals("targets"), TAB_W), TAB_W));
		sb.append(centeredBtnCell(tabBtn("Phantoms", "ph_phantoms", top.equals("phantoms"), TAB_W), TAB_W));
		sb.append("</tr></table>");
		sb.append("<img src=\"L2UI.SquareGray\" width=" + GRID_W + " height=1><br1>");

		switch (page)
		{
			case "party": partyPage(player, sb); break;
			case "member": memberPage(player, sb); break;
			case "buffs": buffPage(player, sb); break;
			case "music": musicPage(player, sb); break;
			case "cubics": cubicPage(player, sb); break;
			case "targets": targetsPage(player, sb); break;
			case "teleport": teleportPage(player, sb); break;
			case "phantoms": phantomsPage(player, sb); break;
			default: homePage(player, sb); break;
		}
		sb.append("</center></body></html>");
		String html = sb.toString();
		if (html.length() > MAX_HTML)
		{
			html = html.substring(0, MAX_HTML) + "</body></html>";
		}
		final NpcHtmlMessage message = new NpcHtmlMessage();
		message.setHtml(html);
		player.sendPacket(message);
	}

	private void homePage(Player p, StringBuilder sb)
	{
		final int id = p.getObjectId();
		final String stance = _stance.getOrDefault(id, "assist");
		final int pull = _pullSize.getOrDefault(id, 1);
		final String puller = _puller.get(id);

		sb.append(homeGap()).append(homeGap());
		sb.append(head("Party Stance:")).append("<br1>");
		sb.append(gridRow(btn("Hold", "ph_do hold home", stance.equals("hold")), btn("Assist", "ph_do assist home", stance.equals("assist")), btn("Farm", "ph_do free home", stance.equals("farm")), btn("Follow", "ph_do follow home", stance.equals("follow"))));
		sb.append(sectionDivider()).append(head("Camp:")).append("<br1>");
		sb.append(homeGap());
		final boolean stopPullOn = (pull == 0);
		sb.append(centeredGridRow(btn("Set Camp", "ph_do setcamp home", false), btn("End Camp", "ph_do nocamp home", false), btn("Stop Pull", "ph_do nopull home", stopPullOn)));
		sb.append(homeGap());
		sb.append(centeredGridRow(btn("Pull 1", "ph_size 1", pull == 1), btn("Pull 2", "ph_size 2", pull == 2), btn("Pull 3", "ph_size 3", pull == 3)));
		sb.append(homeGap()).append("Puller: ").append((puller != null && pull > 0) ? puller + " x" + pull : "none").append("<br1>");
		sb.append(homeTightGap());
		sb.append(sectionDivider()).append(head("Party Control:")).append("<br1>");
		sb.append(homeGap());
		sb.append(gridRow(btn("Buff Me", "ph_do buffme home", false), btn("Buff All", "ph_do buffall home", false), btn("Recharge", "ph_do recharge home", false), btn("Stop MP Up", "ph_do upall home", false)));
		sb.append(homeGap());
		sb.append(gridRow(btn("Heal", "ph_do heal home", false), btn("Heal Full", "ph_do healfull home", false), btn("Stand All", "ph_do standall home", false), btn("Sit All", "ph_do sitall home", false)));
		sb.append(homeGap());
		sb.append(gridRow(btn("Loot", "ph_do loot home", false), btn("Cubics?", "ph_do cubicstatus home", false), btn("Weapons?", "ph_do weaponstatus home", false), btn("Travel", "ph_teleport", false)));
		sb.append(homeGap());
		sb.append(centeredGridRow(btn("Disband", "ph_do disbandall home", false)));
	}

	private void partyPage(Player p, StringBuilder sb)
	{
		final Party party = p.getParty();
		final List<Player> others = new ArrayList<>();
		if (party != null)
		{
			for (Player member : party.getMembers())
			{
				if ((member != p) && (others.size() < 8))
				{
					others.add(member);
				}
			}
		}

		sb.append(head("Party members (" + others.size() + "/8):")).append(partySpacer(4));
		if (others.isEmpty())
		{
			sb.append("You are not in a party.");
		}
		else
		{
			for (int i = 0; i < others.size(); i++)
			{
				final Player member = others.get(i);
				final PartyRole role = PhantomManager.roleForClass(member.getPlayerClass());
				final String roleLabel = (role == null) ? "UNKNOWN" : role.name().replace('_', ' ');
				// Manage / Drop only for phantoms this player owns; other members are shown read-only.
				final boolean phantom = isOwnedPhantom(p, member);
				final String manage = phantom ? btn("Manage", "ph_more " + member.getName(), false) : "";
				final String drop = phantom ? btn("Drop", "ph_drop " + member.getName(), false) : "";

				// Exactly two content rows per member: name (role) + Manage, then level/HP + Drop.
				sb.append("<table width=").append(GRID_W).append(" cellspacing=0 cellpadding=0>");
				sb.append("<tr>");
				sb.append("<td width=216 height=16 align=left valign=middle>")
					.append(member.getName()).append(" (").append(roleLabel).append(")</td>");
				sb.append("<td width=72 height=16 align=right valign=middle>").append(manage).append("</td>");
				sb.append("</tr>");
				sb.append("<tr>");
				sb.append("<td width=216 height=16 align=left valign=middle>Lv. ")
					.append(member.getLevel()).append("  ").append(member.getCurrentHpPercent()).append("% HP</td>");
				sb.append("<td width=72 height=16 align=right valign=middle>").append(drop).append("</td>");
				sb.append("</tr>");
				sb.append("</table>");
				if (i < (others.size() - 1))
				{
					// Keep the divider independent from the member table; cellpadding provides a subtle, real
					// breathing space above and below the line without changing the two-row button layout.
					sb.append("<table width=").append(GRID_W).append(" cellspacing=0 cellpadding=2><tr><td>");
					sb.append("<img src=\"L2UI.SquareGray\" width=").append(GRID_W).append(" height=1>");
					sb.append("</td></tr></table>");
				}
			}
			sb.append(partySpacer(2));
		}
		ensurePartyAutoRefresh(p);
	}

	private void memberPage(Player p, StringBuilder sb)
	{
		final String name = _selectedMember.get(p.getObjectId());
		final Player phantom = (name == null) ? null : findPartyPhantom(p, name);
		if (phantom == null)
		{
			sb.append("No phantom selected.<br1>");
			sb.append(memberBack());
			return;
		}
		final PartyRole role = PhantomManager.roleForClass(phantom.getPlayerClass());
		sb.append(head(phantom.getName() + " - " + role.name() + " Lv " + phantom.getLevel())).append("<br1>");

		// Main controls: the same commands as Home, but scoped to this phantom. Pull selects this phantom as puller.
		sb.append(phantomGridRow(
			btn("Assist", "ph_mem assist " + phantom.getName(), false),
			btn("Free", "ph_mem free " + phantom.getName(), false),
			btn("Hold", "ph_mem stay " + phantom.getName(), false),
			btn("Follow", "ph_mem follow " + phantom.getName(), false)));
		if (!role.isSupport())
		{
			sb.append(phantomGridRow(btn("Pull", "ph_pull " + phantom.getName(), false), "", "", ""));
		}

		if (role.isSupport())
		{
			sb.append(gap()).append(head("Support:")).append("<br1>");
			final boolean healRes = isHealResClass(phantom.getPlayerClass());
			final boolean recharge = isRechargeClass(phantom.getPlayerClass());

			// Support buttons stay in the same 4-column grid used by the other Manage controls.
			if (healRes && recharge)
			{
				sb.append(phantomGridRow(
					btn("Buff All", "ph_mem buff " + phantom.getName(), false),
					btn("Buff Me", "ph_mem buffme " + phantom.getName(), false),
					btn("Heal Full", "ph_mem heal " + phantom.getName(), false),
					btn("Resurrect", "ph_mem res " + phantom.getName(), false)));
				sb.append(phantomGridRow(
					btn("Recharge", "ph_mem recharge " + phantom.getName(), false),
					btn("Stop MP Up", "ph_mem stoprecharge " + phantom.getName(), false),
					btn("Buffs", "ph_buffs " + phantom.getName(), false),
					""));
			}
			else if (healRes)
			{
				sb.append(phantomGridRow(
					btn("Buff All", "ph_mem buff " + phantom.getName(), false),
					btn("Buff Me", "ph_mem buffme " + phantom.getName(), false),
					btn("Heal Full", "ph_mem heal " + phantom.getName(), false),
					btn("Resurrect", "ph_mem res " + phantom.getName(), false)));
				sb.append(phantomGridRow(btn("Buffs", "ph_buffs " + phantom.getName(), false), "", "", ""));
			}
			else if (recharge)
			{
				sb.append(phantomGridRow(
					btn("Buff All", "ph_mem buff " + phantom.getName(), false),
					btn("Buff Me", "ph_mem buffme " + phantom.getName(), false),
					btn("Recharge", "ph_mem recharge " + phantom.getName(), false),
					btn("Stop MP Up", "ph_mem stoprecharge " + phantom.getName(), false)));
				sb.append(phantomGridRow(btn("Buffs", "ph_buffs " + phantom.getName(), false), "", "", ""));
			}
			else
			{
				sb.append(phantomGridRow(
					btn("Buff All", "ph_mem buff " + phantom.getName(), false),
					btn("Buff Me", "ph_mem buffme " + phantom.getName(), false),
					btn("Buffs", "ph_buffs " + phantom.getName(), false),
					""));
			}
		}

		if (isCubicKnight(phantom.getPlayerClass()))
		{
			sb.append(gap()).append(head("Cubics:")).append("<br1>");
			sb.append(phantomGridRow(btn("Cubics", "ph_cubics " + phantom.getName(), false), "", "", ""));
		}

		if (isWeaponSwitcher(phantom.getPlayerClass()))
		{
			sb.append(gap()).append(head("Weapons:")).append("<br1>");
			sb.append(phantomGridRow(btn("Weapons", "ph_cubics " + phantom.getName(), false), "", "", ""));
		}

		if (role == PartyRole.SINGER)
		{
			sb.append(gap()).append(head("Singer:")).append("<br1>");
			sb.append(phantomGridRow(
				btn("Buff All", "ph_mem songs " + phantom.getName(), false),
				btn("Songs", "ph_musicpage " + phantom.getName(), false),
				"", ""));
		}
		else if (role == PartyRole.DANCER)
		{
			sb.append(gap()).append(head("Dancer:")).append("<br1>");
			sb.append(phantomGridRow(
				btn("Buff All", "ph_mem dance " + phantom.getName(), false),
				btn("Dances", "ph_musicpage " + phantom.getName(), false),
				"", ""));
		}

		sb.append(memberBack());
	}

	private static String memberBack()
	{
		return "<img src=\"L2UI.SquareGray\" width=" + PHANTOM_GRID_W + " height=1>"
				+ "<table width=" + PHANTOM_GRID_W + " cellspacing=0 cellpadding=0><tr><td height=8></td></tr></table>"
				+ phantomCenteredGridRow(btn("Back", "ph_party", false));
	}

	private static boolean isCubicKnight(PlayerClass playerClass)
	{
		return (playerClass != null) && (playerClass.equalsOrChildOf(PlayerClass.TEMPLE_KNIGHT) || playerClass.equalsOrChildOf(PlayerClass.SHILLIEN_KNIGHT));
	}

	private static boolean isWeaponSwitcher(PlayerClass playerClass)
	{
		return (playerClass != null) && (playerClass.equalsOrChildOf(PlayerClass.GLADIATOR) || playerClass.equalsOrChildOf(PlayerClass.WARLORD) || playerClass.equalsOrChildOf(PlayerClass.DESTROYER) || playerClass.equalsOrChildOf(PlayerClass.BOUNTY_HUNTER) || playerClass.equalsOrChildOf(PlayerClass.WARSMITH));
	}

	private static boolean isPolearmSpare(PlayerClass playerClass)
	{
		return (playerClass != null) && (playerClass.equalsOrChildOf(PlayerClass.DESTROYER) || playerClass.equalsOrChildOf(PlayerClass.BOUNTY_HUNTER) || playerClass.equalsOrChildOf(PlayerClass.WARSMITH));
	}

	private static String weaponSpareLabel(PlayerClass playerClass)
	{
		return isPolearmSpare(playerClass) ? "Polearm" : "Blunt";
	}

	private static boolean isHealResClass(PlayerClass playerClass)
	{
		return (playerClass != null) && (playerClass.equalsOrChildOf(PlayerClass.BISHOP) || playerClass.equalsOrChildOf(PlayerClass.PROPHET) || playerClass.equalsOrChildOf(PlayerClass.ELDER) || playerClass.equalsOrChildOf(PlayerClass.SHILLIEN_ELDER));
	}

	private static boolean isRechargeClass(PlayerClass playerClass)
	{
		return (playerClass != null) && (playerClass.equalsOrChildOf(PlayerClass.ELDER) || playerClass.equalsOrChildOf(PlayerClass.SHILLIEN_ELDER));
	}

	private static String buffClass(PlayerClass playerClass)
	{
		if (playerClass == null) return "";
		if (playerClass.equalsOrChildOf(PlayerClass.BISHOP)) return "BISHOP";
		if (playerClass.equalsOrChildOf(PlayerClass.PROPHET)) return "PROPHET";
		if (playerClass.equalsOrChildOf(PlayerClass.ELDER)) return "ELDER";
		if (playerClass.equalsOrChildOf(PlayerClass.SHILLIEN_ELDER)) return "SHILLIEN_ELDER";
		if (playerClass.equalsOrChildOf(PlayerClass.OVERLORD)) return "OVERLORD";
		if (playerClass.equalsOrChildOf(PlayerClass.WARCRYER)) return "WARCRYER";
		return playerClass.name();
	}

	private void buffPage(Player p, StringBuilder sb)
	{
		final String name = _selectedMember.get(p.getObjectId());
		final Player phantom = (name == null) ? null : findPartyPhantom(p, name);
		sb.append(head("Buffs: " + ((name == null) ? "" : name))).append("<br1>");
		if (phantom == null)
		{
			sb.append("No phantom selected.");
			return;
		}

		final String[][] preferred;
		switch (buffClass(phantom.getPlayerClass()))
		{
			case "BISHOP": preferred = new String[][]
			{
				{ "Kiss of Eva", "Kiss of Eva" }, { "Might", "Might" }, { "Focus", "Focus" }, { "Holy Weap", "Holy Weapon" },
				{ "Mental Shield", "Mental Shield" }, { "Shield", "Shield" }, { "Concentration", "Concentration" }, { "Resist Fire", "Resist Fire" },
				{ "Wind Walk", "Wind Walk" }, { "Acumen", "Acumen" }, { "Bersk. Spirit", "Berserker Spirit" }, { "Regeneration", "Regeneration" },
				{ "Celestial Sh", "Celestial Shield" }, { "Body Avatar", "Body of Avatar" }, { "Invocation", "Invocation" }, { "Prayer", "Prayer" },
				{ "Divine Protec", "Divine Protection" }, { "Salvation", "Salvation" }
			}; break;
			case "PROPHET": preferred = new String[][]
			{
				{ "Holy Weap", "Holy Weapon" }, { "Wind Walk", "Wind Walk" }, { "Might", "Might" }, { "Resist Fire", "Resist Fire" },
				{ "Shield", "Shield" }, { "Acumen", "Acumen" }, { "Bersk. Spirit", "Berserker Spirit" }, { "Focus", "Focus" },
				{ "Haste", "Haste" }, { "Kiss of Eva", "Kiss of Eva" }, { "Magic Barrier", "Magic Barrier" }, { "Bless Shield", "Bless Shield" },
				{ "Death Whisp", "Death Whisper" }, { "Guidance", "Guidance" }, { "Invigor", "Invigor" }, { "Mental Shield", "Mental Shield" },
				{ "Regeneration", "Regeneration" }, { "Resist Aqua", "Resist Aqua" }, { "Resist Wind", "Resist Wind" }, { "Concentration", "Concentration" },
				{ "Resist Poison", "Resist Poison" }, { "Bless Soul", "Blessed Soul" }, { "Bless Body", "Blessed Body" }, { "Great Might", "Greater Might" },
				{ "Great Shield", "Greater Shield" }, { "Holy Resist", "Holy Resistance" }, { "Unholy Resist", "Unholy Resistance" }, { "Element Protec", "Elemental Protection" },
				{ "Prop. Fire", "Prophecy of Fire" }, { "Mystic Immun", "Mystic Immunity" }
			}; break;
			case "ELDER": preferred = new String[][]
			{
				{ "Holy Weap", "Holy Weapon" }, { "Wind Walk", "Wind Walk" }, { "Might", "Might" }, { "Resist Poison", "Resist Poison" },
				{ "Shield", "Shield" }, { "Agility", "Agility" }, { "Decre. Weight", "Decrease Weight" }, { "Kiss of Eva", "Kiss of Eva" },
				{ "Mental Shield", "Mental Shield" }, { "Regeneration", "Regeneration" }, { "Concentration", "Concentration" }, { "Bless Shield", "Bless Shield" },
				{ "Wild Magic", "Wild Magic" }, { "Adv. Block", "Advanced Block" }, { "Invocation", "Invocation" }, { "Resist Shock", "Resist Shock" },
				{ "Clarity", "Clarity" }, { "Unholy Resist", "Unholy Resistance" }, { "Arcane Protec", "Arcane Protection" }, { "Divine Protec", "Divine Protection" },
				{ "Prop. Water", "Prophecy of Water" }
			}; break;
			case "SHILLIEN_ELDER": preferred = new String[][]
			{
				{ "Wind Walk", "Wind Walk" }, { "Might", "Might" }, { "Resist Wind", "Resist Wind" }, { "Shield", "Shield" },
				{ "Empower", "Empower" }, { "Focus", "Focus" }, { "Kiss of Eva", "Kiss of Eva" }, { "Death Whisp", "Death Whisper" },
				{ "Guidance", "Guidance" }, { "Mental Shield", "Mental Shield" }, { "Concentration", "Concentration" }, { "Wild Magic", "Wild Magic" },
				{ "Invocation", "Invocation" }, { "Vampiric Rage", "Vampiric Rage" }, { "Holy Resist", "Holy Resistance" }, { "Arcane Protec", "Arcane Protection" },
				{ "Prop. Wind", "Prophecy of Wind" }
			}; break;
			case "OVERLORD": preferred = new String[][]
			{
				{ "Ch. Fire", "Chant of Fire" }, { "Ch. Battle", "Chant of Battle" }, { "Ch. Shield", "Chant of Shielding" }, { "Flame Ch.", "Flame Chant" },
				{ "Ch. Life", "Chant of Life" }, { "Soul Shield", "Soul Shield" }, { "Pa' Gift", "Pa'agrian Gift" }, { "Bless Pa'", "Blessings of Pa'agrio" },
				{ "Rage Pa'", "The Rage of Pa'agrio" }, { "Glory Pa'", "The Glory of Pa'agrio" }, { "Tact Pa'", "The Tact of Pa'agrio" },
				{ "Vision Pa'", "The Vision of Pa'agrio" }, { "Wisdom Pa'", "The Wisdom of Pa'agrio" },
				{ "Protec Pa'", "Under the Protection of Pa'agrio" }, { "Pa' Haste", "Pa'agrian Haste" },
				{ "Eye Pa'", "The Eye of Pa'agrio" }, { "Soul Pa'", "The Soul of Pa'agrio" }, { "Pa' Emblem", "Pa'agrio's Emblem" },
				{ "Victories Pa'", "Victories of Pa'agrio" }, { "Flames Invin", "Flames of Invincibility" }
			}; break;
			case "WARCRYER": preferred = new String[][]
			{
				{ "Pa' Gift", "Pa'agrian Gift" }, { "Bless. Pa", "Blessings of Pa'agrio" }, { "Soul Shield", "Soul Shield" }, { "Ch. Shield", "Chant of Shielding" },
				{ "Ch. Battle", "Chant of Battle" }, { "Ch. Fire", "Chant of Fire" }, { "Ch. Evasion", "Chant of Evasion" }, { "Ch. Fury", "Chant of Fury" },
				{ "Ch. Rage", "Chant of Rage" }, { "Flame Ch.", "Flame Chant" }, { "Ch. Eagle", "Chant of Eagle" }, { "Ch. Predator", "Chant of Predator" },
				{ "Ch. Life", "Chant of Life" }, { "Ch. Revenge", "Chant of Revenge" }, { "Ch. Vampire", "Chant of Vampire" }, { "Earth Ch.", "Earth Chant" },
				{ "War Ch.", "War Chant" }, { "Ch. Spirit", "Chant of Spirit" }, { "Ch. Victory", "Chant of Victory" }, { "Magnus' Ch.", "Magnus' Chant" }
			}; break;
			default: preferred = new String[0][0]; break;
		}

		final Map<Integer, Skill> treeSkills = new HashMap<>();
		for (SkillLearn learn : SkillTreeData.getInstance().getCompleteClassSkillTree(phantom.getPlayerClass()).values())
		{
			if ((learn == null) || (learn.getGetLevel() > 80))
			{
				continue;
			}
			final Skill previous = treeSkills.get(learn.getSkillId());
			if ((previous == null) || (learn.getSkillLevel() > previous.getLevel()))
			{
				final Skill skill = SkillData.getInstance().getSkill(learn.getSkillId(), learn.getSkillLevel());
				if (isBuffSkill(skill))
				{
					treeSkills.put(learn.getSkillId(), skill);
				}
			}
		}

		final Map<String, Integer> idByName = new HashMap<>();
		for (Skill skill : treeSkills.values())
		{
			idByName.put(normalizeBuffName(skill.getName()), skill.getId());
		}

		final List<SkillEntry> entries = new ArrayList<>();
		final Map<Integer, Boolean> added = new HashMap<>();
		for (String[] spec : preferred)
		{
			final Integer id = idByName.get(normalizeBuffName(spec[1]));
			if ((id != null) && !added.containsKey(id))
			{
				entries.add(new SkillEntry(spec[0], id));
				added.put(id, true);
			}
		}

		final List<Skill> extras = new ArrayList<>();
		for (Skill skill : treeSkills.values())
		{
			if (!added.containsKey(skill.getId()))
			{
				extras.add(skill);
			}
		}
		extras.sort(Comparator.comparing(Skill::getName, String.CASE_INSENSITIVE_ORDER));
		for (Skill skill : extras)
		{
			entries.add(new SkillEntry(shortBuffLabel(skill.getName()), skill.getId()));
		}

		for (int i = 0; i < entries.size(); i += 4)
		{
			final String[] cells = new String[] { "", "", "", "" };
			for (int j = 0; j < 4 && (i + j) < entries.size(); j++)
			{
				final SkillEntry entry = entries.get(i + j);
				final Skill known = phantom.getKnownSkill(entry.id);
				cells[j] = (known == null) ? unavailableBtn(entry.label, "ph_buff " + name + " " + entry.id) : btn(entry.label, "ph_buff " + name + " " + entry.id, false);
			}
			sb.append(gridRow(cells));
		}
		sb.append(gridRow(btn("Back", "ph_more " + name, false)));
	}

	private static boolean isBuffSkill(Skill skill)
	{
		return (skill != null) && !skill.isPassive() && !skill.isToggle() && skill.isActive() && !skill.isDebuff() && !skill.isDance() && skill.isContinuous() && (skill.getEffectPoint() >= 0) && !skill.hasEffectType(EffectType.HEAL, EffectType.CPHEAL, EffectType.MANAHEAL_PERCENT);
	}

	private static String shortBuffLabel(String name)
	{
		if (name == null)
		{
			return "Buff";
		}
		return name.replace("Resistance", "Resist.").replace("Greater", "Great");
	}

	private static final class SkillEntry
	{
		private final String label;
		private final int id;

		private SkillEntry(String label, int id)
		{
			this.label = label;
			this.id = id;
		}
	}

	private static String normalizeBuffName(String name)
	{
		return (name == null) ? "" : name.toLowerCase().replace('’', '\'').replaceAll("[^a-z0-9']", "").replace("ospaagrio", "ofpaagrio");
	}

	private void musicPage(Player p, StringBuilder sb)
	{
		final String name = _selectedMember.get(p.getObjectId());
		final Player phantom = (name == null) ? null : findPartyPhantom(p, name);
		if (phantom == null)
		{
			sb.append(head("Music: " + ((name == null) ? "" : name))).append("<br1>");
			sb.append("No phantom selected.");
			return;
		}

		final PartyRole role = PhantomManager.roleForClass(phantom.getPlayerClass());
		final boolean singer = role == PartyRole.SINGER;
		sb.append(head((singer ? "Songs: " : "Dances: ") + phantom.getName())).append("<br1>");

		final int[] ids = singer ? new int[]
		{
			364, 264, 306, 269, 270, 265, 363, 349, 308, 305, 304, 267, 266, 268
		} : new int[]
		{
			307, 273, 309, 274, 275, 276, 277, 311, 366, 272, 310, 271, 365
		};
		final String[] labels = singer ? new String[]
		{
			"Champion", "Earth", "Flame Guard", "Hunter", "Invocation", "Life", "Meditation", "Renewal", "Storm Guard", "Vengeance", "Vitality", "Warding", "Water", "Wind"
		} : new String[]
		{
			"Aqua Guard", "Concentration", "Earth Guard", "Fire", "Fury", "Inspiration", "Light", "Protection", "Shadows", "Mystic", "Vampire", "Warrior", "Siren's Dance"
		};

		for (int i = 0; i < ids.length; i += 4)
		{
			final String[] cells = new String[] { "", "", "", "" };
			for (int j = 0; j < 4 && (i + j) < ids.length; j++)
			{
				final int index = i + j;
				final int skillId = ids[index];
				cells[j] = (phantom.getKnownSkill(skillId) == null) ? unavailableBtn(labels[index], "ph_music " + name + " " + skillId) : btn(labels[index], "ph_music " + name + " " + skillId, false);
			}
			sb.append(gridRow(cells));
		}
		sb.append(gridRow(btn("Back", "ph_more " + name, false)));
	}


	private void cubicPage(Player p, StringBuilder sb)
	{
		final String name = _selectedMember.get(p.getObjectId());
		final Player phantom = (name == null) ? null : findPartyPhantom(p, name);
		if ((phantom != null) && isWeaponSwitcher(phantom.getPlayerClass()))
		{
			weaponPage(phantom, name, sb);
			ensureDetailAutoRefresh(p);
			return;
		}
		sb.append(head("Cubics: " + ((name == null) ? "" : name))).append("<br1>");
		if ((phantom == null) || (phantom.getPlayerClass() == null)) return;

		// Show only the three cubics belonging to this knight class, in one aligned row.
		if (phantom.getPlayerClass().equalsOrChildOf(PlayerClass.TEMPLE_KNIGHT))
		{
			sb.append(cubicGridRow(
				cubicStateButton(phantom, 67, Cubic.LIFE_CUBIC, "Life", "life"),
				cubicStateButton(phantom, 10, Cubic.STORM_CUBIC, "Storm", "storm"),
				cubicStateButton(phantom, 449, Cubic.ATTRACT_CUBIC, "Attract.", "attract")));
		}
		else if (phantom.getPlayerClass().equalsOrChildOf(PlayerClass.SHILLIEN_KNIGHT))
		{
			sb.append(cubicGridRow(
				cubicStateButton(phantom, 22, Cubic.VAMPIRIC_CUBIC, "Vampiric", "vampiric"),
				cubicStateButton(phantom, 33, Cubic.POLTERGEIST_CUBIC, "Phantom", "phantom"),
				cubicStateButton(phantom, 278, Cubic.VIPER_CUBIC, "Viper", "viper")));
		}
		sb.append(gridRow(btn("Cubics?", "ph_cubic " + phantom.getName() + " status", false), btn("Back", "ph_more " + name, false)));
		ensureDetailAutoRefresh(p);
	}

	private void weaponPage(Player phantom, String name, StringBuilder sb)
	{
		sb.append(head("Weapons: " + ((name == null) ? "" : name))).append("<br1>");
		sb.append(gridRow(
			btn(weaponSpareLabel(phantom.getPlayerClass()), "ph_cubic " + name + " weapon:spare", false),
			btn("Switch Back", "ph_cubic " + name + " weapon:back", false),
			btn("Weapons?", "ph_cubic " + name + " weapon:status", false),
			""));
		sb.append(gridRow(btn("Back", "ph_more " + name, false)));
	}

	private static String cubicGridRow(String... contents)
	{
		// Use the same four-column grid as the rest of Manage so every cubic button has identical
		// width and horizontal spacing as the other controls.
		return gridRow(contents);
	}

	private static String cubicStateButton(Player phantom, int skillId, int cubicId, String label, String command)
	{
		final boolean up = phantom.getCubicById(cubicId) != null;
		final String stateLabel = label + (up ? " On" : " Off");
		final String action = "ph_cubic " + phantom.getName() + " " + (up ? "drop:" : "") + command;
		return "<button value=\"" + stateLabel + "\" action=\"bypass -h " + action + "\" width=" + BTN_W + " height=" + BTN_H + " back=\"" + BTN_ON + "\" fore=\"" + (up ? BTN_ON : BTN_OFF) + "\">";
	}

	private void targetsPage(Player p, StringBuilder sb)
	{
		final int id = p.getObjectId();
		final WorldObject target = p.getTarget();
		if (target instanceof Creature)
		{
			final Creature creature = (Creature) target;
			sb.append(head("Your target:")).append(" ").append(creature.getName()).append(" Lv ").append(creature.getLevel()).append(" (HP ").append(creature.getCurrentHpPercent()).append("%)<br1>");
		}
		else
		{
			sb.append(head("Your target:")).append(" none<br1>");
		}
		final String order = _raidOrder.getOrDefault(id, "");
		sb.append(head("Raid Gate:")).append("<br1>");
		sb.append(centeredGridRow(btn("Tank Attack", "ph_do tank targets", "tank".equals(order)), btn("All Attack", "ph_do all targets", "all".equals(order))));
		sb.append(centeredGridRow(btn("Tank Holds", "ph_do dpsstop targets", "dpsstop".equals(order)), btn("Stop Fight", "ph_do stopfight targets", "stopfight".equals(order))));

		sb.append(head("Raid Orders:")).append("<br1>");
		sb.append(gridRow(btn("Heal", "ph_do heal targets", false), btn("Buff All", "ph_do buffall targets", false), btn("Resurrect", "ph_do res targets", false), btn("Recharge", "ph_do recharge targets", false)));
		sb.append(gridRow(btn("Stop MP", "ph_do upall targets", false), btn("Stand All", "ph_do standall targets", false), btn("Sit All", "ph_do sitall targets", false), btn("Loot", "ph_do loot targets", false)));
		ensureTargetsAutoRefresh(p);
	}

	private void teleportPage(Player p, StringBuilder sb)
	{
		sb.append(head("Party Teleport:")).append("<br1>");
		final String[][] dests =
		{
			{"TI", "ti"}, {"Elven", "elf"}, {"Dark Elf", "darkelf"}, {"Orc", "orc"},
			{"Dwarf", "dwarf"}, {"Gludin", "gludin"}, {"Gludio", "gludio"}, {"Dion", "dion"},
			{"Giran", "giran"}, {"Oren", "oren"}, {"Aden", "aden"}, {"Heine", "heine"},
			{"Hunter", "hunter"}, {"Rune", "rune"}, {"Goddard", "goddard"}
		};
		for (int i = 0; i < dests.length; i += 4)
		{
			sb.append(gridRow(tpButton(dests, i), tpButton(dests, i + 1), tpButton(dests, i + 2), tpButton(dests, i + 3)));
		}
		sb.append(gridRow(btn("Back", "ph_home", false)));
	}

	private static String tpButton(String[][] dests, int index)
	{
		if ((index < 0) || (index >= dests.length)) return "";
		return btn(dests[index][0], "ph_tp " + dests[index][1], false);
	}

	private static String teleportText(String token)
	{
		switch (token)
		{
			case "ti": return "Talking Island Village";
			case "elf": return "Elven Village";
			case "darkelf": return "Dark Elven Village";
			case "orc": return "Orc Village";
			case "dwarf": return "Dwarven Village";
			case "gludin": return "Gludin Village";
			case "gludio": return "Gludio";
			case "dion": return "Dion";
			case "giran": return "Giran";
			case "oren": return "Oren";
			case "aden": return "Aden";
			case "heine": return "Heine";
			case "hunter": return "Hunter's Village";
			case "rune": return "Rune";
			case "goddard": return "Goddard";
			default: return null;
		}
	}

	private void phantomsPage(Player p, StringBuilder sb)
	{
		final String view = _phantomsView.getOrDefault(p.getObjectId(), "recruit");
		final boolean friendsView = view.startsWith("friends");
		sb.append("<table width=" + PHANTOM_GRID_W + " cellspacing=0 cellpadding=0><tr>");
		sb.append("<td width=" + PHANTOM_SUBTAB_CELL_W + " align=center>" + tabBtn("Recruit", "ph_view recruit", !friendsView, SUBTAB_W) + "</td>");
		sb.append("<td width=" + PHANTOM_SUBTAB_CELL_W + " align=center>" + tabBtn("Friends", "ph_view friends", friendsView, SUBTAB_W) + "</td>");
		sb.append("</tr></table>").append(gap());

		if (!friendsView)
		{
			// Recruit is split into two small pages so the Interlude client never has to parse a huge HTML list.
			int recruitPage = 0;
			if (view.length() > "recruit".length())
			{
				try
				{
					recruitPage = Math.max(0, Integer.parseInt(view.substring("recruit".length())));
				}
				catch (NumberFormatException e)
				{
					recruitPage = 0;
				}
			}
			recruitPage = Math.min(recruitPage, 1);

			// Keep waiting recruits at the top so they are visible immediately after a refresh.
			waitingSection(p, sb, "recruit");
			sb.append(gap()).append(head("Recruit " + (recruitPage + 1) + "/2")).append("<br1>Level (empty = yours): <edit var=\"l\" width=40 height=15><br1>");
			final int firstRace = (recruitPage == 0) ? 0 : 2;
			final int lastRace = (recruitPage == 0) ? 2 : INTERLUDE_SECOND_CLASSES.length;
			for (int r = firstRace; r < lastRace; r++)
			{
				sb.append(classRaceHeader(INTERLUDE_RACE_NAMES[r]));
				sb.append(phantomClassGrid(INTERLUDE_SECOND_CLASSES[r], "ph_findclass", "$l"));
			}
			sb.append(gap()).append(phantomCenteredGridRow(
					btn("<", "ph_view recruit0", recruitPage == 0),
					btn(">", "ph_view recruit1", recruitPage == 1)));
			return;
		}

		final List<Player> friends = onlineFriends(p);
		int friendPage = 0;
		if (view.startsWith("friends") && !view.startsWith("friendsc") && (view.length() > "friends".length()))
		{
			try
			{
				friendPage = Math.max(0, Integer.parseInt(view.substring("friends".length())));
			}
			catch (NumberFormatException e)
			{
				friendPage = 0;
			}
		}
		final int friendPageSize = 5;
		final int friendPages = Math.max(1, (friends.size() + friendPageSize - 1) / friendPageSize);
		friendPage = Math.min(friendPage, friendPages - 1);
		sb.append(gap()).append(head("Friends online (" + friends.size() + "):")).append("<br1>");
		final int friendStart = friendPage * friendPageSize;
		final int friendEnd = Math.min(friendStart + friendPageSize, friends.size());
		for (int i = friendStart; i < friendEnd; i++)
		{
			final Player friend = friends.get(i);
			sb.append("<table width=" + PHANTOM_GRID_W + " cellspacing=0 cellpadding=0><tr>");
			sb.append(td(198, friend.getName() + " " + secondClassLabel(friend) + " Lv." + friend.getLevel()));
			sb.append(centeredBtnCell(friend.isInParty() ? btn("In party", "ph_view friends" + friendPage, true) : btn("Invite", "ph_invite " + friend.getName(), false), PHANTOM_CELL_W));
			sb.append("</tr></table>");
		}
		if (friends.isEmpty()) sb.append("No friends online yet.<br1>");
		if (friendPages > 1)
		{
			sb.append(gap()).append(phantomCenteredGridRow(btn("<", "ph_view friends" + (friendPage - 1), friendPage == 0), btn(">", "ph_view friends" + (friendPage + 1), friendPage >= friendPages - 1)));
		}
		final int sex = _craftSex.getOrDefault(p.getObjectId(), -1);
		sb.append(gap()).append(head("New friend (your level):")).append("<br1>");
		sb.append(phantomCenteredGridRow(btn("Male", "ph_sex m", sex == 0), btn("Female", "ph_sex f", sex == 1), btn("Random", "ph_sex r", sex == -1)));
		sb.append("Name: <edit var=\"fname\" width=110 height=15><br1>");
		int friendsClassPage = 0;
		if (view.startsWith("friendsc") && (view.length() > "friendsc".length()))
		{
			try
			{
				friendsClassPage = Math.max(0, Integer.parseInt(view.substring("friendsc".length())));
			}
			catch (NumberFormatException e)
			{
				friendsClassPage = 0;
			}
		}
		friendsClassPage = Math.min(friendsClassPage, 1);
		final int firstRace = (friendsClassPage == 0) ? 0 : 2;
		final int lastRace = (friendsClassPage == 0) ? 2 : INTERLUDE_SECOND_CLASSES.length;
		sb.append(head("Classes " + (friendsClassPage + 1) + "/2")).append("<br1>");
		for (int r = firstRace; r < lastRace; r++)
		{
			sb.append(classRaceHeader(INTERLUDE_RACE_NAMES[r]));
			sb.append(phantomClassGrid(INTERLUDE_SECOND_CLASSES[r], "ph_craft", "$fname"));
		}
		sb.append(gap()).append(phantomCenteredGridRow(
				btn("<", "ph_view friendsc0", friendsClassPage == 0),
				btn(">", "ph_view friendsc1", friendsClassPage == 1)));
	}

	/**
	 * Resolves a selected Interlude second-class branch to the class the character actually has at the requested level.
	 * 1-19 = base class, 20-39 = first occupation, 40-75 = selected second class, 76-80 = its third class.
	 */
	private static PlayerClass resolveClassForLevel(PlayerClass selectedSecondClass, int level)
	{
		if (selectedSecondClass == null)
		{
			return null;
		}
		final int targetLevel = Math.max(1, Math.min(80, level));
		final int targetDepth = (targetLevel >= 76) ? 3 : ((targetLevel >= 40) ? 2 : ((targetLevel >= 20) ? 1 : 0));
		PlayerClass current = selectedSecondClass;
		while ((current != null) && (current.level() > targetDepth))
		{
			current = current.getParent();
		}
		if (targetDepth <= 2)
		{
			return current;
		}
		for (PlayerClass next : selectedSecondClass.getNextClasses())
		{
			if (next.level() == 3)
			{
				return next;
			}
		}
		// Every Interlude second class in this selector has one third-class child.
		return selectedSecondClass;
	}

	/** Numeric class specs are the only form used by the 31-class Friends selector. */
	private static String resolveClassSpecForLevel(String classSpec, int level)
	{
		if ((classSpec == null) || classSpec.trim().isEmpty())
		{
			return classSpec;
		}
		try
		{
			final int selectedId = Integer.parseInt(classSpec.trim());
			final PlayerClass selected = PlayerClass.getPlayerClass(selectedId);
			if ((selected == null) || !PhantomManager.isSelectableClass(selected))
			{
				return classSpec;
			}
			return Integer.toString(resolveClassForLevel(selected, level).getId());
		}
		catch (NumberFormatException e)
		{
			return classSpec;
		}
	}

	private static String displayClassName(PlayerClass playerClass)
	{
		return playerClass.name().replace('_', ' ');
	}

	private static String classButtonName(PlayerClass playerClass)
	{
		switch (playerClass.getId())
		{
			case 2: return "Gladiator";
			case 3: return "Warlord";
			case 5: return "Paladin";
			case 6: return "D. Avenger";
			case 8: return "T. Hunter";
			case 9: return "Hawkeye";
			case 12: return "Sorcerer";
			case 13: return "Necro";
			case 14: return "Warlock";
			case 16: return "Bishop";
			case 17: return "Prophet";
			case 20: return "T. Knight";
			case 21: return "Sw. Singer";
			case 23: return "P. Walker";
			case 24: return "S. Ranger";
			case 27: return "Sp. Singer";
			case 28: return "E. Summoner";
			case 30: return "E. Elder";
			case 33: return "S. Knight";
			case 34: return "Bl. Dancer";
			case 36: return "A. Walker";
			case 37: return "P. Ranger";
			case 40: return "Sp. Howler";
			case 41: return "P. Summoner";
			case 43: return "S. Elder";
			case 46: return "Destroyer";
			case 48: return "Tyrant";
			case 51: return "Overlord";
			case 52: return "Warcryer";
			case 55: return "B. Hunter";
			case 57: return "Warsmith";
			default: return displayClassName(playerClass);
		}
	}

	private static String classRaceHeader(String race)
	{
		return "<br1><font color=\"LEVEL\"><b>" + race + "</b></font>";
	}

	private void waitingSection(Player p, StringBuilder sb, String refreshView)
	{
		final List<Player> waiting = waitingPhantoms(p);
		final int maxWaiting = "recruit".equals(refreshView) ? 8 : waiting.size();
		final int shown = Math.min(waiting.size(), maxWaiting);
		sb.append(gap()).append(head("Waiting for your invite (" + shown + "):")).append("<br1>");
		for (int i = 0; i < shown; i++)
		{
			final Player phantom = waiting.get(i);
			sb.append("<table width=" + PHANTOM_GRID_W + " cellspacing=0 cellpadding=0><tr>");
			sb.append(td(198, phantom.getName() + " " + secondClassLabel(phantom) + " Lv." + phantom.getLevel()));
			sb.append(centeredBtnCell(btn("Invite", "ph_invite " + phantom.getName(), false), PHANTOM_CELL_W));
			sb.append("</tr></table>");
		}
		if (waiting.isEmpty()) sb.append("Nobody yet. Use Recruit, then wait a few seconds.<br1>");
	}

	private void helpPage(StringBuilder sb)
	{
		sb.append(head("Party chat / whisper:")).append("<br1>");
		sb.append("assist, attack freely, follow, hold, gather<br1>");
		sb.append("camp here, NAME pull 3, stop pulling, break camp<br1>");
		sb.append("buff me, buff all, heal me, res, recharge, songs, dance<br1>");
		sb.append("tank attack, all attack, hold fire (raids)<br1>");
		sb.append("go to PLACE, status, bye<br1>");
		sb.append("party return loot (Bounty Hunter)<br1>");
		sb.append("Specific buffs, songs/dances and cubics are available from Party > More.<br1>");
		sb.append(gridRow(btn("Back", "ph_home", false)));
	}
}
