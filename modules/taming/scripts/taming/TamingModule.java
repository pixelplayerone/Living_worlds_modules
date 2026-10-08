/*
 * TamingModule.java
 *
 * Entry point for the Beast Collar taming module.
 *
 * Load order matters and is the reason this class is written the way it is:
 *
 *   1. Read the config, so every later decision has its numbers.
 *   2. Bind the two stock private maps. If that fails the module refuses to
 *      enable rather than appearing to work while quietly doing nothing.
 *   3. Rebuild every collar saved in the database. This has to happen before a
 *      single player connects, because stock login code restores a saved pet by
 *      looking up the template and profile that the pet was saved with, and
 *      those only exist once the module has put them back.
 *   4. Register the handlers. The capture effect and the collar item handler are
 *      registered here rather than in a master handler file, which is what lets
 *      this module exist without a single core edit.
 */
package taming;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.effects.AbstractEffect;
import org.l2jmobius.gameserver.model.events.EventType;
import org.l2jmobius.gameserver.model.skill.EffectScope;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.modules.GameModule;
import org.l2jmobius.gameserver.modules.ModuleConfig;
import org.l2jmobius.gameserver.modules.ModuleContext;

public class TamingModule implements GameModule
{
	private static final Logger LOGGER_LOG = Logger.getLogger(TamingModule.class.getName());

	@Override
	public void onEnable(ModuleContext context)
	{
		final ModuleConfig config = context.config();
		final Logger log = context.logging();

		if (!config.getBoolean("Enabled", true))
		{
			log.info("disabled in module.ini");
			return;
		}

		final int collarItemId = config.getInt("CollarItemId", 9302);
		// The module's own skill ids. These work when cast by id, and the collar UI
		// calls the same code directly, but the client cannot draw them: a skill the
		// client has no data for gets no icon and no name, so it never appears in the
		// skill window and cannot be put on the shortcut bar.
		//
		// The trigger ids below are what the player actually presses. They are stock
		// skill ids the client already knows, which is the only way anything becomes
		// pressable. Set a trigger to 0 in module.ini to fall back to the module's own
		// id and give up the button.
		final int tamingSkillId = config.getInt("TamingSkillId", 9300);
		final int tamingSkillLevel = config.getInt("TamingSkillLevel", 1);
		final int recallSkillId = config.getInt("RecallSkillId", 9303);
		final int recallSkillLevel = config.getInt("RecallSkillLevel", 1);
		final int tamingTriggerId = config.getInt("TamingTriggerSkillId", 4549);
		final int recallTriggerId = config.getInt("RecallTriggerSkillId", 7000);
		final boolean grantTamingSkill = config.getBoolean("GrantTamingSkill", false);
		final boolean grantRecallSkill = config.getBoolean("GrantRecallSkill", true);
		final boolean grantCatalogue = config.getBoolean("GrantCatalogue", true);
		final int catalogueItemId = config.getInt("CatalogueItemId", 9303);
		final int low = config.getInt("SyntheticNpcIdLow", 700000);
		final int high = config.getInt("SyntheticNpcIdHigh", 799999);
		final int petCorpseTime = config.getInt("PetCorpseTime", 1200);
		// Optional module-wide hands for every tame. 0 keeps the wild creature's
		// own hands, so this is safe to leave off. A weapon installed on a collar
		// is handled separately and only shows on creatures that carried one.
		final int petRightHandItem = config.getInt("PetRightHandItem", 0);
		final int petLeftHandItem = config.getInt("PetLeftHandItem", 0);
		final int petWeaponEnchant = config.getInt("PetWeaponEnchant", 0);
		final boolean handsDebug = config.getBoolean("HandsDebug", false);
		final boolean showWildWeapon = config.getBoolean("ShowWildWeapon", true);

		TamingManager.setCollarItemId(collarItemId);
		TamingData.getInstance().configure(config, log);
		// A reagent price is held in this config and in the multisell list, because
		// Adena has to be an ingredient of a multisell row. Drift between the two is
		// reported here rather than left to be found by a player paying the wrong
		// amount. Only the window is affected; the store's own buttons still work.
		TamingData.getInstance().verifyMultisellPrices(log);
		TameForge.setCorpseTime(petCorpseTime);
		TameForge.setHandItems(petRightHandItem, petLeftHandItem, petWeaponEnchant);
		TameForge.setHandsDebug(handsDebug);
		TameForge.setShowWildWeapon(showWildWeapon);

		// Experience split. Stock code can only move the owner's share to the tame,
		// so 100/100 needs the owner's side at 100% and the tame's side granted
		// separately at 100%. Both are module-side; no core file is involved.
		final int ownerExpPercent = config.getInt("OwnerExpPercent", 100);
		final int tameExpPercent = config.getInt("TameExpPercent", 100);
		TameExperienceShare.configure(ownerExpPercent, tameExpPercent);
		TameExperienceShare.setDebug(config.getBoolean("ExperienceShareDebug", false));

		try
		{
			TameForge.init(low, high);
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			// Without the two private maps the module cannot give a collar its own
			// profile. Say so plainly and stay off, rather than half working.
			log.log(Level.SEVERE, "cannot start: this build does not expose the stock NPC and pet data tables the module needs, so it will not enable", e);
			return;
		}

// Reagents are sold by TameReagentShop, reached from the catalogue item or with
// .tamestore. The page's OPEN SHOP button opens multisell ReagentMultisellId, which
// is a real shop window; a native buy list is not used because it carries only an
// item id and a price - no display id - so the client would draw a blank row for a
// module item and the player could buy something they cannot see.

		// Drop tames whose collar is gone before hydrating, so the restore does not
		// report a collar back that does not exist.
		sweepOrphanedCollars(config, collarItemId, log);

		// Every saved collar has to be back in place before anyone logs in.
		TameForge.hydrateAll();

		context.handlers().registerItem(new DynamicPetSummon());
		// Keyed by class simple name: data/items/9300-9399.xml puts
		// "TameStoreCatalogue" on item 9303, and that string is the lookup key
		// ItemHandler.getHandler uses. See the header of TameStoreCatalogue.
		context.handlers().registerItem(new TameStoreCatalogue());
		context.handlers().registerEffect(TameMonster.class);
		context.handlers().registerEffect(ResummonPet.class);
		context.handlers().registerBypass(new TameCollarView());
		context.handlers().registerBypass(new TameCollarSummon());
		context.handlers().registerBypass(new TameReagentShop());
		context.handlers().registerVoicedCommand(new TameProfileCommand());
		context.handlers().registerAdminCommand(new TameDiagCommand());
		// The aura bench, reachable as .tameaura. It stays a voiced command so the dot form
		// works, but registration is not what authorises it: the bench checks isGM() at
		// every entry point (see TameAuraLab.isAllowed). Voiced registration alone was why
		// any player could open it, cast a rebuilt copy of any skill at any target and
		// skip the MP cost, cooldown, range and level requirements along the way.
		//
		// //tameaura still exists as the ADMIN handler TameDiagCommand, and voiced and
		// admin names live in separate registries, so both can be present at once.
		// The bypass half is a second handler because IBypassHandler and
		// IVoicedCommandHandler share one getCommandList() signature - see TameAuraLab.
		context.handlers().registerVoicedCommand(new TameAuraLab());
		context.handlers().registerBypass(new TameAuraLab.Bypass());

		// Auto-cast, which is the tame's casting surface. The player's shortcut bar
		// was tried at length and removed: a button is drawn from client data and cast
		// by the player, so the id has to be one the client already knows, which forces
		// a real playable skill and a grant the player can then use themselves. Nothing
		// about that is worth keeping now that the tame casts on its own.
		TameVisual.setEnabled(config.getBoolean("TameVisual", true));
		{
			final java.util.Map<String, String> effects = new java.util.HashMap<>();
			for (String affinity : new String[] { "FIRE", "EARTH", "WATER", "WIND", "HOLY", "DARK" })
			{
				effects.put(affinity, readEffect(config, "AffinityEffect" + affinity, "AffinityEffect" + java.lang.Character.toUpperCase(affinity.charAt(0)) + affinity.substring(1).toLowerCase(java.util.Locale.ROOT), "AffinityEffect" + affinity.toLowerCase(java.util.Locale.ROOT)));
			}
			// First and second awakening are configured apart. A single setting can only
			// pick one overlay for every awakened beast; these are two different stages and
			// the live client chose a different look for each.
			TameVisual.configure(effects, readEffect(config, "AwakenedEffect", "awakenedEffect", "awakenedeffect"),
					readEffect(config, "AwakenedEffectSecond", "awakenedEffectSecond", "awakenedeffectsecond", "AwakenedEffect2"));
		// Configured apart from the affinities because they key on rarity, not on
		// affinity. Both default to none, so nothing is layered until a name is chosen.
TameVisual.configureRarity(readEffect(config, "RarityEffectRare", "rarityEffectRare", "rarityeffectrare"),
				readEffect(config, "RarityEffectLegendary", "rarityEffectLegendary", "rarityeffectlegendary"));
			// Follow re-anchors the ground-anchored effects onto a moving beast. Off by
			// default: it costs two packets per viewer per tick, and an aura that already
			// travels does not need it.
			TameVisual.configureFollow(config.getString("AffinityFollow", ""), config.getInt("AffinityFollowMs", 150));
			// Self-heal, not immunity. See TameVisual.guardTick for why no bit can be made
			// unremovable and what this does instead.
			TameVisual.configurePermanent(config.getBoolean("AffinityPermanent", true), config.getInt("AffinityPermanentMs", 1000));
		}
		// The second route, for affinities the bitmask cannot carry. The enum is a fixed list
		// of client effects and does not promise any of them renders - INVINCIBILITY was
		// configured for holy on the strength of Set Hero using it and drew nothing at all -
		// so where a bitmask effect fails, a beast casts the skill that actually looks right
		// instead. Built visual-only: real id, zero hit time, zero cost, no effects.
		{
			final java.util.Map<String, String> skillAuras = new java.util.HashMap<>();
for (String affinity : new String[] { "FIRE", "EARTH", "WATER", "WIND", "HOLY", "DARK" })
				{
					skillAuras.put(affinity, readSkillId(config, affinity));
				}
			TameSkillAura.configure(skillAuras, config.getInt("AffinitySkillMs", 4000), config.getBoolean("AffinitySilentMagic", true));
		}
		if (config.getBoolean("AutoCast", true))
		{
TameAutoCast.setHealThreshold(config.getDouble("AutoCastHealPercent", 70.0) / 100.0);
			TameAutoCast.setSkillCooldown(config.getInt("AutoCastSkillCooldownMs", 5000));
			TameAutoCast.setMeleeFirst(config.getBoolean("AutoCastMeleeFirst", true));
			TameAutoCast.start(config.getInt("AutoCastIntervalMs", 1000), config.getInt("AutoCastMinGapMs", 2000));
		}

		// Second awakening: the affinity on-hit proc.
		TameAwakenedProc.configure(config.getString("AwakenedProc", "true"), config.getString("AwakenedProcLevel", "60"), config.getString("AwakenedProcChance", "0.25"));

		// The thirteen-race matchup circle. Not gated on awakening: a race is a property of the
		// creature, not a reward, so every collar tame carries it from the first summon.
		TameRaceAffinity.configure(config.getString("RaceAdvantage", "true"), config.getString("RaceAdvantagePct", "12"), config.getString("RaceDisadvantagePct", "8"));

		// Balance: the ceiling on compounding growth. Read here rather than defaulted at the
		// point of use so that a typo is reported once at boot with the rest of the config
		// instead of silently leaving every beast on the built-in default. The stat table is
		// rebuilt from stored base values on every summon, so this reaches existing beasts
		// with no database migration - they simply pick it up the next time they are called
		// out.
		PetProfileGenerator.setGrowthCeiling(config.getDouble("PetGrowthCeiling", 3.0));
		
		// The absolute stat ceilings, applied last, after growth and conversion. These bound
		// the number that actually reaches the client rather than the multiple that produced
		// it, because the growth ceiling alone only helps when the base value is ordinary.
		// HP, PAtk, MAtk, PDef and MDef are read from the tame's own generated level table and
		// so are capped while that table is built. Each 0 turns off that one cap.
		PetProfileGenerator.setHpCap(config.getInt("PetHpCap", 30000));
		PetProfileGenerator.setPAtkCap(config.getInt("PetPAtkCap", 22000));
		PetProfileGenerator.setMAtkCap(config.getInt("PetMAtkCap", 22000));
		PetProfileGenerator.setPDefCap(config.getInt("PetPDefCap", 3000));
		PetProfileGenerator.setMDefCap(config.getInt("PetMDefCap", 3000));
		
		// Crit rate, both attack speeds and movement speed are not in the level table at all -
		// they are inherited from the wild creature's own NPC template when the tame is forged
		// onto it, and none of this module's growth ever touches them. DEX is capped here too
		// because accuracy and evasion have no template key of their own; both are computed
		// from level and DEX by the core's own formula functions, so bounding DEX is the only
		// way to bound them.
		TameForge.setCritRateCap(config.getInt("PetCritRateCap", 450));
		TameForge.setMoveSpeedCap(config.getInt("PetMoveSpeedCap", 200));
		TameForge.setPAtkSpeedCap(config.getInt("PetPAtkSpeedCap", 950));
		TameForge.setCastSpeedCap(config.getInt("PetCastSpeedCap", 310));
		TameForge.setAccuracyCap(config.getInt("PetAccuracyCap", 180));
		TameForge.setEvasionCap(config.getInt("PetEvasionCap", 130));
		
		// Whether a tame's signature technique may be a real player damage skill. The pool is
		// the same 95 the SKILL VISUALS page lists, already verified condition-free and
		// weapon-independent, so no whitelist has to be maintained here.
		TameSkillPolicy.setPlayerDamageSkills(config.getBoolean("PlayerDamageSkills", true));
		TameSkillPolicy.setSignatureReach(config.getInt("SignatureMinRange", 900), config.getInt("SignatureReachBand", 12));

		// The skills were parsed from XML during the data phase, which runs before
		// modules enable. EffectHandler resolves an effect by name at parse time,
		// so 9300 and 9303 came out of that phase with no effect object at all and
		// the boot log warned about them. Rebuilding the effect now that the
		// handler is registered puts the module's logic back into the cached Skill
		// instances the server actually casts. This is a no-op if the effect is
		// already present, which is what makes a module reload harmless.
		installModuleEffect(tamingSkillId, tamingSkillLevel, "TameMonster");
		installModuleEffect(recallSkillId, recallSkillLevel, "ResummonPet");

		// Only the recall trigger is borrowed. Capture deliberately has no trigger
		// skill any more: the flute and bugle reagents carry the capture skill on the
		// item itself, which is both simpler and a better fit - the player casts the
		// reagent, not a permanent skill. The "Quest - Unsealed Altar" id that used to
		// back this is gone, along with the grant that handed it out.
		//
		// A trigger still works if it is attached, so this stays configurable for
		// anyone who wants a belt-and-braces cast button as well.
		if (tamingTriggerId > 0)
		{
			installModuleEffect(tamingTriggerId, 1, "TameMonster");
		}
		if (recallTriggerId > 0)
		{
			installModuleEffect(recallTriggerId, 1, "ResummonPet");
		}

		// Recall is a granted skill again, cast from the skill window. It spent a
		// revision as an item - a Recall Crystal carrying the skill - and that is
		// gone: a consumable that has to be replaced after every recall is a worse
		// answer than a skill the player already has, and the item bought nothing
		// the skill did not already give for free.
		//
		// The grant cannot be a shortcut bar button. The client draws a bar button
		// from its own data, so a module id has no icon there and no packet can
		// change that. The skill window is a different surface: the server sends the
		// id in SkillList and the client lists it, so the skill is present and
		// castable from there even though it cannot be pinned to a key.
		//
		// Recall and capture are now granted by SEPARATE switches, which they were
		// not before. One flag used to cover both, and because capture is meant to
		// come from the reagents, turning that single flag on to get recall back also
		// put Beast Binding in the player's skill list - a second way to do a thing
		// the flute already does, and another entry the client has no artwork for.
		final int recallGrantId = (recallTriggerId > 0) ? recallTriggerId : recallSkillId;
		final int captureGrantId = (tamingTriggerId > 0) ? tamingTriggerId : tamingSkillId;
		if (grantRecallSkill || grantTamingSkill)
		{
			context.events().onPlayers(EventType.ON_PLAYER_LOGIN, (org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogin event) -> {
				if (grantTamingSkill)
				{
					grantOne(event.getPlayer(), captureGrantId, 1);
				}
				if (grantRecallSkill)
				{
					grantOne(event.getPlayer(), recallGrantId, 1);
				}
			});
			context.events().onPlayers(EventType.ON_PLAYER_CREATE, (org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerCreate event) -> {
				if (grantTamingSkill)
				{
					grantOne(event.getPlayer(), captureGrantId, 1);
				}
				if (grantRecallSkill)
				{
					grantOne(event.getPlayer(), recallGrantId, 1);
				}
			});
		}
		if (!grantRecallSkill && !grantTamingSkill)
		{
			// Neither granted: take the granted-skill entries back off this character,
			// or a player who tested with them keeps a skill that no longer has any
			// code behind it.
			context.events().onPlayers(EventType.ON_PLAYER_LOGIN, (org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogin event) -> {
				ungrantOne(event.getPlayer(), recallGrantId);
				ungrantOne(event.getPlayer(), captureGrantId);
			});
		}

		// The store catalogue: an item the player double-clicks to open the reagent
		// store. It is granted rather than bought, because a shop you need an item
		// to buy before you can buy anything is a chicken and egg problem. Gating
		// this on nothing else - it is independent of both skill grants.
		if (grantCatalogue)
		{
			context.events().onPlayers(EventType.ON_PLAYER_LOGIN, (org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogin event) -> {
				grantCatalogue(event.getPlayer(), catalogueItemId);
			});
			context.events().onPlayers(EventType.ON_PLAYER_CREATE, (org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerCreate event) -> {
				grantCatalogue(event.getPlayer(), catalogueItemId);
			});
		}

		// Wounds and the worn-collar restore are reconciled on every login, and this
		// must not be gated on either grant switch. It used to sit inside the
		// GrantTamingSkill block, so with that flag off - which is how it shipped -
		// nothing revalidated at all on login and a beast left wounded by a crash
		// stayed wound_flags set forever.
		context.events().onPlayers(EventType.ON_PLAYER_LOGIN, (org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogin event) -> revalidateOnLogin(event.getPlayer()));

		// Clear up after the shortcut bar that this module used to ship. Anyone who ran
		// an earlier build still has the four borrowed stock skills in their own skill
		// list, and with the module's effect gone they are just four unexplained
		// entries in the client's skill window. This runs for every character and is
		// gated on grantTamingSkill only in the sense that it does not depend on it.
		//
		// Both trigger ids are 0 now, so both borrowed stock skills are revoked along
		// with the module's own blank ids. Recall borrowed 7000, whose client entry
		// is "NPC Default" with no icon - the black square under USE.
		//
		// With both triggers at 0 neither borrowed skill is protected, which is the
		// point: 4549 and 7000 are both on the list and characters who were granted
		// either are cleaned up on their next login without needing a one-off purge.
		// A zero in this set is harmless, no legacy id is 0.
		//
		// The keep set still earns its place: repointing a trigger at any other stock
		// skill makes that id protected from the sweep, which is what stops the cleanup
		// removing the very button it was just configured to grant.
		final java.util.Set<Integer> keep = new java.util.HashSet<>();
		keep.add(tamingTriggerId);
		keep.add(recallTriggerId);
		// The ids this module grants on purpose have to be protected too, and each
		// one only while its own switch is on. 9300 and 9303 are on the legacy list -
		// they used to be granted only for the reagent items to fire, and granting
		// one put a blank entry in the skill window that this sweep was written to
		// clean up. Recall is a granted skill again, so the sweep and the grant now
		// want the same id for opposite reasons on the same login: the grant is
		// registered above and runs first, so without this the character would be
		// handed Beast Recall and have it taken away again a moment later, every
		// single login, with no symptom other than a skill that never works.
		//
		// Protection is per-switch rather than "if either is on", so that turning
		// GrantTamingSkill off really does let the sweep take Beast Binding back off
		// a character who tested with it - which is the point of leaving that switch
		// off, since capture is meant to come from the reagents.
		if (grantRecallSkill)
		{
			keep.add(recallSkillId);
		}
		if (grantTamingSkill)
		{
			keep.add(tamingSkillId);
		}
		context.events().onPlayers(EventType.ON_PLAYER_LOGIN, (org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogin event) -> {
			final int revoked = TameLegacyShortcutGrant.revoke(event.getPlayer(), keep);
			if (revoked > 0)
			{
				LOGGER_LOG.info("removed " + revoked + " legacy shortcut-bar skill grant(s) from " + event.getPlayer().getName());
			}
		});

		// Puts a tame that was out when the client dropped back out again.
		//
		// Stock code restores a saved pet by looking its profile up from the collar's
		// ITEM id, and PetDataTable.getPetDataByItemId answers with the first profile
		// carrying that id. Every collar is the same item, so as soon as a player owns
		// two beasts the stock restore hands back whichever one happens to come first
		// in the map, not the one that was out. The individual still cannot be told
		// apart from the item, only from this module's own row.
		//
		// Doing it here also settles who wins: Player.onActionRequest performs the
		// stock restore, but only when the player has no summon, and it runs after
		// login. Summoning here means the beast is already out and the stock path
		// skips itself entirely.
		context.events().onPlayers(EventType.ON_PLAYER_LOGIN, (org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogin event) -> restoreTameOnLogin(event.getPlayer()));

		// The auto-cast mute is session state, so a relog starts from the config default
		// rather than inheriting the last session's choice. Doing it on login rather than
		// logout also means a mute cannot outlive the object id it was filed under.
		context.events().onPlayers(EventType.ON_PLAYER_LOGIN, (org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogin event) -> TameAutoCast.forget(event.getPlayer().getObjectId()));

		// The tame gear vault is session state too. Loading it on login puts the
		// held items in the world before any of the collar pages or commands can
		// ask for them, and dropping it on logout keeps a stale Player reference
		// from being carried into the next session. The items themselves are
		// already on disk: every move through transferItem persists immediately.
		context.events().onPlayers(EventType.ON_PLAYER_LOGIN, (org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogin event) -> TameGearVault.of(event.getPlayer()));
		context.events().onPlayers(EventType.ON_PLAYER_LOGOUT, (org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerLogout event) -> TameGearVault.forget(event.getPlayer()));

		// A dead tamed beast is a wounded profile: the record gets the wound flag
		// and the owner is told how long the corpse will stay under the module's
		// game rules. The corpse timer itself comes from the forged template.
		//
		// Registering against the NPC listener container (not the global one) is
		// deliberate: a pet is an Npc, and Creature.getListeners() resolves a
		// death through the creature's own class container. Global listeners are
		// never consulted for a creature's death in this build.
		context.events().onNpcs(EventType.ON_CREATURE_DEATH, (org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureDeath event) -> onTamedPetDeath(event));
		// The stock split only ever moves the owner's experience to the tame, so it
		// cannot give both sides the whole kill. The owner's share is set to 100 on
		// every forged template and the tame's own share is granted here from the
		// same kill. ON_CREATURE_KILLED carries the killer, which is what is needed
		// to find the owner and their collar.
		// This one has to be registered against the MONSTER container, not the NPC one.
		// ListenersContainer.hasListener resolves a dying creature by its class and
		// returns on the first match: a monster is checked against Monsters() before
		// Npcs() is ever considered. Everything that dies in this game is a Monster, so
		// a listener parked in Npcs() is never consulted and this handler would silently
		// never run. ON_CREATURE_DEATH below is the opposite case - it targets our own
		// Pet, which is an Npc and not a Monster, so onNpcs() is correct there.
		context.events().onMonsters(EventType.ON_CREATURE_KILLED, (org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureKilled event) -> grantTameShare(event));

		// Second awakening's on-hit proc, applied on spawn and re-checked on every
		// experience gain.
		//
		// Both are needed and neither is sufficient alone. ON_PLAYER_SUMMON_SPAWN
		// catches relog and first summon; ON_PLAYABLE_EXP_CHANGED catches a beast
		// that crosses the gate while already out. The experience hook is the
		// general answer to "is there an event when a pet levels" - there is no
		// level-up event for a Playable in this build, but experience change fires
		// for one and is dispatched before the level is recalculated, so it is
		// used purely as a signal to re-check rather than as a level to read.
		context.events().onPlayers(EventType.ON_PLAYER_SUMMON_SPAWN, (org.l2jmobius.gameserver.model.events.holders.actor.player.OnPlayerSummonSpawn event) -> onSummonSpawn(event.getSummon()));
		context.events().onNpcs(EventType.ON_PLAYABLE_EXP_CHANGED, (org.l2jmobius.gameserver.model.events.holders.actor.playable.OnPlayableExpChanged event) -> applyAwakenedProcOnExpChange(event));

		log.info("enabled: collar " + collarItemId + ", capture skill " + tamingSkillId + " pressed as stock id " + (tamingTriggerId > 0 ? tamingTriggerId : "none") + ", recall skill " + recallSkillId + " pressed as stock id " + (recallTriggerId > 0 ? recallTriggerId : "none") + ", private npc id band " + low + "-" + high + ", experience split " + TameExperienceShare.describe() + ". Use .tameprofile in game, or an admin .tamediag to check the module is healthy.");
	}

	/**
	 * Stops the auto-cast scheduler.
	 *
	 * <p>Without this a module reload would leave the previous thread running its
	 * 1Hz tick against live tames, and a second one would start alongside it, so a
	 * reload would quietly double every tame's cast rate. The thread is a daemon as
	 * well, so it is never the reason the server refuses to exit.
	 */
	@Override
	public void onDisable(ModuleContext context)
	{
		TameAutoCast.stop();
		// An audition is a diagnostic, so it does not survive a reload. Left in place
		// it would silently keep layering a candidate effect onto every beast after
		// the admin who set it has forgotten about it.
		TameVisual.clearPreview();
		// Same reasoning as the audition: a scheduled cast is not state that should
		// outlive the module that configured it. Left running it would keep firing
		// against live tames after a reload, on top of whatever the new configuration
		// starts, so every beast would draw the effect twice at two rates.
		TameSkillAura.clear();
	}

	/**
	 * Removes tamed-pet rows whose collar item no longer exists.
	 *
	 * <p>Nothing in this module sees a collar being destroyed or its owner deleting
	 * their character, so those rows are never cleaned at the time they go stale and
	 * every boot restores them. See
	 * {@link TameProfileRepository#findOrphanedCollars(int)} for how they are
	 * identified and why the check is deliberately strict.
	 *
	 * <p>Default is {@code report}, which finds them and logs them and deletes
	 * nothing. That is the right default for a routine that removes saved player
	 * data: the first run should be something you read, not something you trust.
	 * Set it to {@code true} to delete, or {@code false} to do nothing at all.
	 *
	 * <p>One thing this cannot recover is the id band. TameForge seeds the next
	 * synthetic npc id from MAX(synthetic_npc_id) over the surviving rows, so
	 * removing an orphan stops the count from growing but does not give the slot
	 * back. Reusing a released id would change how an existing beast is addressed,
	 * so the band is treated as spent for good.
	 */
	private static void sweepOrphanedCollars(ModuleConfig config, int collarItemId, Logger log)
	{
		final String mode = config.getString("OrphanCollarSweep", "report").trim().toLowerCase(java.util.Locale.ROOT);
		if ("false".equals(mode) || "off".equals(mode) || "none".equals(mode))
		{
			return;
		}
		final List<TameProfileRepository.Orphan> orphans = TameProfileRepository.findOrphanedCollars(collarItemId);
		if (orphans.isEmpty())
		{
			return;
		}
		if (!("true".equals(mode) || "delete".equals(mode)))
		{
			// Report mode, and anything unrecognised. An unknown value reports rather
			// than deletes: a typo in the config should never be the thing that
			// destroys saved pets.
			log.warning("taming: found " + orphans.size() + " tame(s) whose collar no longer exists. Nothing was deleted. Set OrphanCollarSweep = true in config/module.ini to remove them.");
			for (final TameProfileRepository.Orphan orphan : orphans)
			{
				log.warning("taming:   " + orphan);
			}
			return;
		}
		int removed = 0;
		for (final TameProfileRepository.Orphan orphan : orphans)
		{
			// Logged before the delete, so if a delete turns out to be wrong the row
			// that caused it is already named in the log.
			log.info("taming: removing orphaned tame " + orphan);
			if (TameProfileRepository.purgeOrphan(orphan))
			{
				removed++;
			}
			else
			{
				log.warning("taming: could not remove orphaned tame " + orphan + ", its row is still there");
			}
		}
		log.info("taming: removed " + removed + " of " + orphans.size() + " orphaned tame(s). Bestiary records were kept, they are per owner and not per collar.");
	}

	/**
	 * Reads an abnormal-visual-effect setting, accepting the spellings a person
	 * editing module.ini is likely to write.
	 *
	 * <p>The affinities are upper case here because they double as map keys in
	 * {@code TameVisual}, while the file was originally written with the tidier
	 * {@code AffinityEffectFire}. The lookup is case sensitive and there is no way to
	 * enumerate a config's keys, so the candidates are spelled out rather than
	 * generated - an earlier attempt built them by lower-casing the tail, which
	 * produced {@code Affinityeffectfire} and matched nothing either. Getting this
	 * wrong is silent: the key resolves to the empty default, every affinity becomes
	 * "no aura configured", and the beasts lose their auras with nothing in the log
	 * beyond six lines saying the setting was not found.
	 *
	 * <p>The exact spelling wins if present; the rest are tried in order.
	 */
	private static String readEffect(ModuleConfig config, String... keys)
	{
		for (String key : keys)
		{
			final String value = config.getString(key, null);
			if (value != null)
			{
				return value;
			}
		}
		return "";
	}

	/**
	 * Reads one affinity's skill-aura id, spelled the same way {@link #readEffect} does.
	 *
	 * <p>Reusing the same alias scheme rather than inventing a second one, for the same
	 * reason that scheme exists: a key that does not resolve is silent, and six affinities
	 * silently losing their auras is the failure mode worth designing against.
	 *
	 * @param affinity the affinity in upper case, e.g. {@code HOLY}
	 */
	private static String readSkillId(ModuleConfig config, String affinity)
	{
		// The camel-case spelling is tried first on purpose, and the all-caps one is not.
		// ModuleConfig logs a line for every alias that misses, so putting the form the
		// file does not use at the head of the list means a correct configuration still
		// prints "AffinitySkillHOLY not found" twice at every boot and teaches the reader
		// to ignore the log - which is exactly how the real failure gets missed.
		final String camel = java.lang.Character.toUpperCase(affinity.charAt(0)) + affinity.substring(1).toLowerCase(java.util.Locale.ROOT);
		return readEffect(config, "AffinitySkill" + camel, "AffinitySkill" + affinity,
				"AffinitySkill" + affinity.toLowerCase(java.util.Locale.ROOT), "AffinitySkillVisual" + camel);
	}

	/**
	 * Re-attaches one module effect to the already-parsed skill. The skill is a
	 * cached singleton, so this works without touching the data files or SkillData.
	 *
	 * When the skill was parsed, createEffect() returned null because the handler
	 * was not on the list yet, and the parser still put that null into the effect
	 * list. Stock applyEffectScope() silently skips null entries, so the skill
	 * casts but does nothing. Once the handler is registered the effect is rebuilt
	 * from just its name, which is all this module's skills pass in the XML.
	 *
	 * <p>Also how the tame bar buttons work: they attach to stock skills that have no
	 * &lt;effects&gt; element at all, so getEffects() hands back null rather than an
	 * empty list (it is a straight Map.get). A null list here is normal, not a
	 * failure - addEffect() allocates one, which is what later makes hasEffects()
	 * true and gets applyEffectScope() to run the effect at all.
	 */
	static void installModuleEffect(int skillId, int skillLevel, String effectName)
	{
		final Skill skill = SkillData.getInstance().getSkill(skillId, skillLevel);
		if (skill == null)
		{
			LOGGER_LOG.warning("skill " + skillId + " level " + skillLevel + " did not load");
			return;
		}
		final List<AbstractEffect> existing = skill.getEffects(EffectScope.GENERAL);
		if (existing != null)
		{
			for (AbstractEffect effect : existing)
			{
				if ((effect != null) && effect.getClass().getSimpleName().equals(effectName))
				{
					// Already installed (module reload).
					return;
				}
			}
			// Drop the dead null left behind by the failed parse so the list is honest.
			existing.removeIf(effectEntry -> effectEntry == null);
		}
		final StatSet params = new StatSet();
		params.set("name", effectName);
		final AbstractEffect effect = AbstractEffect.createEffect(null, null, params, new StatSet());
		if (effect == null)
		{
			LOGGER_LOG.warning("effect handler " + effectName + " has not been registered yet");
			return;
		}
		skill.addEffect(EffectScope.GENERAL, effect);
		LOGGER_LOG.info("re-attached " + effectName + " to skill " + skillId + " level " + skillLevel);
	}

	/**
	 * Gives the player the store catalogue if they do not already have one.
	 *
	 * <p>Checked by item id rather than granted blindly, because this runs on every
	 * login: an unconditional add would hand a second catalogue to a player who
	 * already had one on each login, quietly filling the bag. The item is not
	 * stackable, so they would each be a separate object.
	 *
	 * <p>A player who somehow destroyed theirs gets another one next login, which
	 * is the intended behaviour for a permission slip.
	 */
	private static void grantCatalogue(org.l2jmobius.gameserver.model.actor.Player player, int itemId)
	{
		if (player.getInventory().getItemByItemId(itemId) != null)
		{
			return;
		}
		if (player.getInventory().addItem(org.l2jmobius.gameserver.model.item.enums.ItemProcessType.REWARD, itemId, 1, player, null) == null)
		{
			// The bag was full, so the catalogue could not be given. The next login
			// tries again. Not worth interrupting the session over.
			LOGGER_LOG.warning("could not grant the store catalogue " + itemId + " to " + player.getName() + "; the inventory is full");
		}
	}

	/**
	 * Adds one granted skill if the character does not have it. The stock
	 * class tree is left alone, so a server that already hands these out some
	 * other way keeps working and nothing is granted twice.
	 */
	private static void grantOne(org.l2jmobius.gameserver.model.actor.Player player, int skillId, int skillLevel)
	{
		final Skill skill = org.l2jmobius.gameserver.data.xml.SkillData.getInstance().getSkill(skillId, skillLevel);
		if (skill != null)
		{
			// Replacing is harmless: a character who already learned it keeps the
			// same skill, and one who did not now gets it.
			player.addSkill(skill, true);
		}
	}

	/**
	 * Takes a granted skill back off a character.
	 *
	 * <p>Level 1 is the only level this module ever granted, so this matches the
	 * shape of the grant rather than removing whatever level the player happens to
	 * have. A player who levelled a real class skill to the same id keeps it.
	 */
	private static void ungrantOne(org.l2jmobius.gameserver.model.actor.Player player, int skillId)
	{
		final Skill skill = org.l2jmobius.gameserver.data.xml.SkillData.getInstance().getSkill(skillId, 1);
		if ((skill != null) && (player.getKnownSkill(skillId) != null))
		{
			player.removeSkill(skill, false, false);
		}
	}

	/**
	 * Everything a beast needs put on it the moment it appears in the world.
	 *
	 * <p>Two separate concerns share one entry point because both are answered by the same
	 * question - is this a collar tame, and if so what does its profile say - and splitting them
	 * would mean asking it twice on every summon for no gain.
	 */
	private static void onSummonSpawn(org.l2jmobius.gameserver.model.actor.Summon summon)
	{
		if (!isCollarTame(summon))
		{
			return;
		}
		final TameProfile profile = TameProfileRepository.loadByCollar(summon.getControlObjectId());
		if (profile == null)
		{
			return;
		}
		TameRaceAffinity.apply(summon, profile);
		applyAwakenedProc(summon, profile);
	}

	/**
	 * Puts second awakening's on-hit proc on a beast that has just spawned.
	 *
	 * <p>Guarded twice: the summon must be a pet bound to one of our forged
	 * templates, and the collar behind it must actually be at stage two. A stock
	 * pet that happens to spawn while this listener is registered must not
	 * acquire a trigger skill, so the template check is not optional.
	 */
	private static void applyAwakenedProc(org.l2jmobius.gameserver.model.actor.Summon summon)
	{
		if (!isCollarTame(summon))
		{
			return;
		}
		final TameProfile profile = TameProfileRepository.loadByCollar(summon.getControlObjectId());
		if (profile == null)
		{
			return;
		}
		applyAwakenedProc(summon, profile);
	}

	private static void applyAwakenedProc(org.l2jmobius.gameserver.model.actor.Summon summon, TameProfile profile)
	{
		TameAwakenedProc.apply(summon, profile, summon.getLevel());
	}

	/**
	 * Second awakening's proc, re-checked on every experience gain.
	 *
	 * <p>Registered against the NPC container on purpose. A Pet is a Summon is a
	 * Playable, but there is no playable container: {@code ListenersContainer}
	 * resolves a creature by class and returns at the first match, in the order
	 * isMonster, isNpc, isPlayer. A Pet answers true to isNpc and is never a
	 * monster, so Npcs() is where its events land - the same reasoning as the
	 * death listener above.
	 *
	 * <p>Fires on every exp gain rather than on level-up, because no such event
	 * exists for a Playable. Comparing levels here would also be wrong twice over:
	 * the event is dispatched <em>before</em> the level is recalculated, so
	 * {@code getLevel()} is still the old value. Passing the live level through and
	 * letting the proc decide keeps that ordering out of this method entirely.
	 *
	 * <p>Registering twice is harmless - trigger skills are keyed by skill id, so
	 * the second call replaces the first.
	 */
	private static void applyAwakenedProcOnExpChange(org.l2jmobius.gameserver.model.events.holders.actor.playable.OnPlayableExpChanged event)
	{
		try
		{
			final org.l2jmobius.gameserver.model.actor.Playable active = event.getActiveChar();
			if (!(active instanceof org.l2jmobius.gameserver.model.actor.Summon))
			{
				return;
			}
			final org.l2jmobius.gameserver.model.actor.Summon summon = (org.l2jmobius.gameserver.model.actor.Summon) active;
			if (!isCollarTame(summon))
			{
				return;
			}
			final TameProfile profile = TameProfileRepository.loadByCollar(summon.getControlObjectId());
			if (profile == null)
			{
				return;
			}
			// The core's ceiling for a pet is one server-wide constant, so a beast's
			// own cap is enforced here instead - while the level is still the old one
			// and the experience it will be judged on can still be pulled back.
			TameLevelCap.hold(summon, profile);
			TameAwakenedProc.apply(summon, profile, crossedLevel(summon));
		}
		catch (Exception e)
		{
			LOGGER_LOG.log(Level.WARNING, "could not re-check the second awakening proc on experience gain", e);
		}
	}

	/**
	 * The level a summon will hold once this experience gain is applied.
	 *
	 * <p>The experience event fires after the new total is written but before the
	 * core turns it into a level, so {@code getLevel()} is still the old value and
	 * is useless for the one question being asked here: has the beast just reached
	 * the level its second awakening is gated on. Reading it off the experience is
	 * what makes a proc land on the gain that actually crossed the gate rather than
	 * on whatever gain happens to come next - which, for a beast that then stops
	 * gaining experience, is to say never.
	 */
	private static int crossedLevel(org.l2jmobius.gameserver.model.actor.Summon summon)
	{
		final int required = TameAwakenedProc.getLevel();
		if ((required < 1) || (summon.getLevel() >= required))
		{
			return summon.getLevel();
		}
		final org.l2jmobius.gameserver.data.xml.ExperienceData experience = org.l2jmobius.gameserver.data.xml.ExperienceData.getInstance();
		if (experience == null)
		{
			return summon.getLevel();
		}
		// Experience for 'required' is the total a beast holds once it has arrived
		// there, and the total has already been written when this runs - so a beast
		// sitting at or past it has crossed on this very gain.
		final long arrivedAt = experience.getExpForLevel(required);
		return (summon.getStat().getExp() >= arrivedAt) ? required : summon.getLevel();
	}

	/**
	 * Whether this summon is one of ours: a pet bound to a collar, on a template
	 * inside this module's synthetic band. Stock pets must never pick up a trigger
	 * skill, so this is the guard that matters on every event that can fire for
	 * any creature in the world.
	 */
	private static boolean isCollarTame(org.l2jmobius.gameserver.model.actor.Summon summon)
	{
		if ((summon == null) || !summon.isPet())
		{
			return false;
		}
		final org.l2jmobius.gameserver.model.actor.templates.NpcTemplate template = summon.getTemplate();
		if ((template == null) || !TameForge.isForged(template.getId()))
		{
			return false;
		}
		return summon.getSummoner() instanceof org.l2jmobius.gameserver.model.actor.Player;
	}

	/**
	 * Runs for every creature death on the server, so it only acts when the dying
	 * creature is one of this module's pets: a Pet whose template is inside this
	 * module's synthetic band. The profile is flagged wounded and the owner is
	 * told how long the corpse will last. Decay itself is left to stock, which
	 * now reads the longer corpse time off the forged template.
	 */
	private static void onTamedPetDeath(org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureDeath event)
	{
		try
		{
			final org.l2jmobius.gameserver.model.actor.Creature target = event.getTarget();
			if ((target == null) || !(target instanceof org.l2jmobius.gameserver.model.actor.instance.Pet))
			{
				return;
			}
			final org.l2jmobius.gameserver.model.actor.instance.Pet pet = (org.l2jmobius.gameserver.model.actor.instance.Pet) target;
			final org.l2jmobius.gameserver.model.actor.templates.NpcTemplate template = pet.getTemplate();
			if ((template == null) || !TameForge.isForged(template.getId()))
			{
				return;
			}
			final org.l2jmobius.gameserver.model.actor.Player owner = pet.getOwner();
			if (owner == null)
			{
				return;
			}
			TameProfileRepository.markWounded(owner.getObjectId(), pet.getControlObjectId());
			owner.sendMessage("Your beast has fallen. Revive it within " + (TameForge.getCorpseTimeSeconds() / 60) + " minutes or it is lost.");
		}
		catch (Exception e)
		{
			LOGGER_LOG.log(Level.WARNING, "could not record a tamed pet death", e);
		}
	}

	/**
	 * Grants the tame its share of a kill, on top of what the owner keeps.
	 * <p>
	 * This is the second half of the share. The owner's side is handled by stock
	 * code using {@code get_exp_type}, which this module sets to the full
	 * percentage, so the owner is already whole by the time this runs and the tame
	 * is not yet paid. Only the tame that belongs to the killer and that comes
	 * from a forged collar is touched.
	 */
	private static void grantTameShare(org.l2jmobius.gameserver.model.events.holders.actor.creature.OnCreatureKilled event)
	{
		try
		{
			final double factor = TameExperienceShare.tameFactor();
			if (factor <= 0.0)
			{
				return;
			}
			// The kill is usually credited to whichever creature swung last, which for a
			// fighting tame is the tame itself, not the owner. Only a summon-linked
			// attacker counts, and the player only leads when they landed the blow.
			final org.l2jmobius.gameserver.model.actor.Creature attacker = event.getAttacker();
			final org.l2jmobius.gameserver.model.actor.Player owner;
			if (attacker instanceof org.l2jmobius.gameserver.model.actor.Player)
			{
				owner = (org.l2jmobius.gameserver.model.actor.Player) attacker;
			}
			else if ((attacker instanceof org.l2jmobius.gameserver.model.actor.instance.Pet) && (((org.l2jmobius.gameserver.model.actor.instance.Pet) attacker).getOwner() != null))
			{
				owner = ((org.l2jmobius.gameserver.model.actor.instance.Pet) attacker).getOwner();
			}
			else
			{
				return;
			}
			if (!owner.hasPet() || !owner.getSummon().isPet())
			{
				return;
			}
			// Match the conditions stock pet experience uses, so a kill is rewarded the
			// same way for both sides and a tame out of range earns nothing.
			if (owner.calculateDistance3D(owner.getSummon()) >= org.l2jmobius.gameserver.config.PlayerConfig.ALT_PARTY_RANGE)
			{
				return;
			}
			// The killer may have been the owner while the tame was elsewhere, or the
			// reverse. Either way only the tame standing with the owner earns.
			final org.l2jmobius.gameserver.model.actor.instance.Pet pet = owner.getSummon().asPet();
			if ((attacker instanceof org.l2jmobius.gameserver.model.actor.instance.Pet) && (attacker != pet))
			{
				return;
			}
			final org.l2jmobius.gameserver.model.actor.templates.NpcTemplate template = pet.getTemplate();
			if ((template == null) || !TameForge.isForged(template.getId()) || pet.isDead())
			{
				return;
			}
			final org.l2jmobius.gameserver.model.actor.Creature target = event.getTarget();
			if (target == null)
			{
				return;
			}
			if (!(target instanceof org.l2jmobius.gameserver.model.actor.Npc))
			{
				return;
			}
			final org.l2jmobius.gameserver.model.actor.templates.NpcTemplate killed = ((org.l2jmobius.gameserver.model.actor.Npc) target).getTemplate();
			if (killed == null)
			{
				return;
			}
			// Most creatures carry experience but no skill points at all, so both parts
			// have to be handed over independently instead of requiring both to exist.
			final double killedExp = killed.getExp();
			final double killedSp = killed.getSP();
			if ((killedExp <= 0.0) && (killedSp <= 0.0))
			{
				return;
			}
			// Base template values only. Applying the owner's premium, event and rate
			// multipliers a second time would inflate the tame well past the owner.
			pet.addExpAndSp(killedExp * factor, killedSp * factor);
			if (TameExperienceShare.debug())
			{
				LOGGER_LOG.info("tame share: " + pet.getName() + " gained " + (long) (killedExp * factor)
						+ " exp and " + (long) (killedSp * factor) + " sp from " + killed.getName()
						+ " (template base " + (long) killedExp + "/" + (long) killedSp + ")");
			}
		}
		catch (Exception e)
		{
			LOGGER_LOG.log(Level.WARNING, "could not grant the tame its share of a kill", e);
		}
	}

	/**
	 * Brings back the beast whose collar this player is wearing, so reconnecting
	 * does not swap one beast for another. Runs before the stock restore gets a
	 * chance, and does nothing at all when the player already has a summon or is
	 * not wearing a collar.
	 *
	 * <p>Only a worn collar counts. Restoring "whatever was out last" would put a
	 * beast back that its owner had deliberately taken off, which is the one
	 * outcome the worn state exists to prevent.
	 */
	private static void restoreTameOnLogin(org.l2jmobius.gameserver.model.actor.Player player)
	{
		try
		{
			// The stock core has a restore of its own, which runs later, in
			// Player.onActionRequest(). It looks the saved summon up by the collar's
			// ITEM id, so with every collar sharing one id it answers with whichever
			// profile carries that id first and can hand back a beast its owner never
			// called - the phantom the owner reported. A tamed beast is this module's
			// to restore, so its core entry is dropped here, before the stock restore
			// is ever consulted. Stock pets (wolves, hatchlings) keep their entry.
			clearCoreTameRestore(player);
			if (player.hasSummon())
			{
				return;
			}
			final org.l2jmobius.gameserver.model.item.instance.Item collar = DynamicPetSummon.findWornCollar(player);
			if (collar == null)
			{
				// Nothing worn, so nothing comes back on its own. This is what makes
				// taking a collar off mean it: a beast the owner put away must not
				// reappear at the next login just because it was out recently.
				return;
			}
			// onLogin: relogging must not be refused by the state guards meant for a
			// player pressing the summon button.
			DynamicPetSummon.summon(player, collar, true);
		}
		catch (Exception e)
		{
			LOGGER_LOG.log(Level.WARNING, "could not restore a tamed beast on login", e);
		}
	}

	/**
	 * Removes this player's entry from the core's saved-pet map when the saved
	 * summon is a tamed beast, so the stock restore in {@code Player.onActionRequest}
	 * cannot spawn a beast from the collar's shared item id.
	 *
	 * <p>The entry is only dropped when the item the core saved resolves to this
	 * module's collar. A saved ordinary pet is left untouched, so stock pets keep
	 * restoring exactly as before.
	 */
	private static void clearCoreTameRestore(org.l2jmobius.gameserver.model.actor.Player player)
	{
		try
		{
			final java.util.Map<Integer, Integer> pets = org.l2jmobius.gameserver.data.sql.CharSummonTable.getInstance().getPets();
			final Integer savedItemObjectId = pets.get(Integer.valueOf(player.getObjectId()));
			if (savedItemObjectId == null)
			{
				return;
			}
			final org.l2jmobius.gameserver.model.item.instance.Item saved = player.getInventory().getItemByObjectId(savedItemObjectId.intValue());
			if ((saved != null) && (saved.getId() == TamingManager.getCollarItemId()))
			{
				pets.remove(Integer.valueOf(player.getObjectId()));
			}
		}
		catch (Exception e)
		{
			LOGGER_LOG.log(Level.WARNING, "could not clear the stock pet restore for " + player.getName(), e);
		}
	}

	/**
	 * Stock pet code can restore a pet from the database before this module has
	 * a say in it, and a pet whose profile carries a wound flag is supposed to
	 * be out of action. If the login session came back with such a pet alive, it
	 * is dismissed back into its collar instead of being left in the world.
	 */
	private static void revalidateOnLogin(org.l2jmobius.gameserver.model.actor.Player player)
	{
		try
		{
			if ((player.getSummon() == null) || !player.getSummon().isPet())
			{
				return;
			}
			final TameProfile profile = TameProfileRepository.load(player.getObjectId(), player.getSummon().getControlObjectId());
			if ((profile != null) && (profile.getWoundFlags() != 0))
			{
				player.getSummon().unSummon(player);
				player.sendMessage("Your wounded beast was recalled into its collar. Heal it with .tameheal before calling it out again.");
			}
		}
		catch (Exception e)
		{
			LOGGER_LOG.log(Level.WARNING, "could not revalidate a wounded pet on login", e);
		}
	}
}
