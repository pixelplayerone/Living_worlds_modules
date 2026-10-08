-- Beast Collar taming module, schema v1.
--
-- Safe to run on a fresh database or on one that already has these tables.
-- The module manager runs this file on EVERY boot, not once on install, so every
-- statement here has to be a no-op on the second run as well as the first. The
-- tables are CREATE TABLE IF NOT EXISTS, the column is ADD COLUMN IF NOT EXISTS,
-- and the two migrations are UPDATEs written to be idempotent. A bare ALTER here
-- takes the whole module down after one successful boot, because the install is
-- reported as failed and the module is then refused - see the 'worn' column below.
--
-- synthetic_npc_id is the one column that belongs to this module rather than to
-- the original design. It stores the private NPC id that the captured
-- creature's template is cloned under, so the same individual can be rebuilt
-- after a restart. See TameForge for how it is used.

CREATE TABLE IF NOT EXISTS tamed_pet (
    tame_uuid CHAR(36) NOT NULL,
    collar_object_id INT NOT NULL,
    owner_id INT NOT NULL,
    source_npc_id INT NOT NULL,
    source_level INT NOT NULL,
    source_type VARCHAR(32) NOT NULL DEFAULT 'Monster',
    race VARCHAR(32) NOT NULL DEFAULT 'NONE',
    family VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    role VARCHAR(32) NOT NULL DEFAULT 'FIGHTER',
    rarity VARCHAR(20) NOT NULL DEFAULT 'COMMON',
    potential TINYINT UNSIGNED NOT NULL DEFAULT 50,
    offense_potential TINYINT UNSIGNED NOT NULL DEFAULT 25,
    defense_potential TINYINT UNSIGNED NOT NULL DEFAULT 25,
    vitality_potential TINYINT UNSIGNED NOT NULL DEFAULT 25,
    skill_potential TINYINT UNSIGNED NOT NULL DEFAULT 25,
    growth_percent DECIMAL(5,2) NOT NULL DEFAULT 2.00,
    temperament VARCHAR(20) NOT NULL DEFAULT 'LOYAL',
    affinity VARCHAR(20) NOT NULL DEFAULT 'NEUTRAL',
    bond INT UNSIGNED NOT NULL DEFAULT 0,
    awakening_stage TINYINT UNSIGNED NOT NULL DEFAULT 0,
    awakening_path VARCHAR(24) NOT NULL DEFAULT '',
    current_level TINYINT UNSIGNED NOT NULL DEFAULT 1,
    max_pet_level TINYINT UNSIGNED NOT NULL DEFAULT 80,
    profile_seed BIGINT NOT NULL,
    profile_version SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    pet_name VARCHAR(16) NOT NULL,
    base_hp INT NOT NULL,
    base_mp INT NOT NULL,
    base_patk INT NOT NULL,
    base_pdef INT NOT NULL,
    base_matk INT NOT NULL,
    base_mdef INT NOT NULL,
    conversion_multiplier DECIMAL(5,4) NOT NULL DEFAULT 1.0000,
    wound_flags INT UNSIGNED NOT NULL DEFAULT 0,
    synthetic_npc_id INT NOT NULL DEFAULT 0,
    active TINYINT(1) NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (tame_uuid),
    UNIQUE KEY uq_tamed_pet_collar (collar_object_id),
    KEY idx_tamed_pet_owner (owner_id),
    KEY idx_tamed_pet_species (source_npc_id),
    KEY idx_tamed_pet_synthetic (synthetic_npc_id),
    CONSTRAINT chk_tamed_pet_potential CHECK (potential <= 100),
    CONSTRAINT chk_tamed_pet_growth CHECK (growth_percent >= 0.50 AND growth_percent <= 12.00)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tamed_pet_skill (
    tame_uuid CHAR(36) NOT NULL,
    skill_id INT NOT NULL,
    skill_level INT NOT NULL,
    slot_type VARCHAR(20) NOT NULL,
    inheritance_quality VARCHAR(20) NOT NULL DEFAULT 'STABLE',
    unlock_level TINYINT UNSIGNED NOT NULL DEFAULT 1,
    awakened TINYINT(1) NOT NULL DEFAULT 0,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tame_uuid, skill_id, slot_type),
    KEY idx_tamed_pet_skill_unlock (tame_uuid, unlock_level),
    CONSTRAINT fk_tamed_pet_skill_profile FOREIGN KEY (tame_uuid) REFERENCES tamed_pet(tame_uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tamed_pet_equipment (
    tame_uuid CHAR(36) NOT NULL,
    slot_type VARCHAR(20) NOT NULL,
item_id INT NOT NULL DEFAULT 0,
    enchant_level INT NOT NULL DEFAULT 0,
    custom_data VARCHAR(255) NOT NULL DEFAULT '',
    item_object_id INT NOT NULL DEFAULT 0,
    PRIMARY KEY (tame_uuid, slot_type),
    CONSTRAINT fk_tamed_pet_equipment_profile FOREIGN KEY (tame_uuid) REFERENCES tamed_pet(tame_uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tamed_pet_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tame_uuid CHAR(36) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    event_data VARCHAR(1000) NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tamed_pet_history_tame (tame_uuid),
    CONSTRAINT fk_tamed_pet_history_profile FOREIGN KEY (tame_uuid) REFERENCES tamed_pet(tame_uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tamed_pet_bestiary (
    owner_id INT NOT NULL,
    source_npc_id INT NOT NULL,
    discovered TINYINT(1) NOT NULL DEFAULT 1,
    individuals_seen INT UNSIGNED NOT NULL DEFAULT 1,
    best_potential TINYINT UNSIGNED NOT NULL DEFAULT 0,
    best_growth DECIMAL(5,2) NOT NULL DEFAULT 0.00,
    highest_awakening TINYINT UNSIGNED NOT NULL DEFAULT 0,
    rare_skill_count INT UNSIGNED NOT NULL DEFAULT 0,
    first_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_id, source_npc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Second awakening and the awakening technique family.
--
-- Rarity decides how many awakening techniques a beast is offered: one for a
-- common beast, two for RARE and above, three for a legendary beast with skill
-- potential 85 or more. The extras live in the AWAKENING_2 and AWAKENING_3 slots.
--
-- Both awakenings used to grant only the bare 'AWAKENING' row, and every code
-- path that asked "is this an awakening technique?" tested for that exact string.
-- Two consequences, and they are the same fault seen twice:
--
--   1. AWAKENING_2 and AWAKENING_3 were never granted, so a beast that had earned
--      a second or third technique showed it as permanently locked.
--   2. Because they were not recognised as awakening techniques, they were also
--      not treated as gated. Their 'enabled' flag was set to 1 on insert - the
--      insert treats anything that is not the exact string 'AWAKENING' as an
--      ordinary skill - so they came into the deck the moment the beast reached
--      the unlock level, with no awakening at all.
--
-- A rarity beast therefore appeared to grant a second awakening technique the
-- instant it hit level 30, which is what "the second awakening gives me both
-- skills" describes. The fix is to match the family rather than the name, in code
-- and here.
--
-- This repairs beasts captured before the fix. Existing rows are corrected in
-- place rather than requiring a re-capture:
--
--   - a beast that never awakened has its AWAKENING% rows darkened again, so a
--     technique granted for free stops being granted;
--   - a beast that has awakened has its family opened, so an earned technique
--     stops being locked.
--
-- AWAKENING2 is left out of both cases on purpose. It is the technique the SECOND
-- awakening opens, and 'AWAKENING%' matches it because the string starts with
-- AWAKENING, so it has to be excluded explicitly or this statement hands out a
-- second awakening's technique at the first one.
--
-- Idempotent: running it twice changes nothing the second time.
UPDATE tamed_pet_skill s
  INNER JOIN tamed_pet p ON p.tame_uuid = s.tame_uuid
  SET s.awakened = CASE WHEN p.awakening_stage >= 1 THEN 1 ELSE 0 END,
      s.enabled  = CASE WHEN p.awakening_stage >= 1 THEN 1 ELSE 0 END
  WHERE s.slot_type LIKE 'AWAKENING%'
    AND s.slot_type <> 'AWAKENING2';

-- Whether this is the collar its owner is wearing.
--
-- There is no worn state for a pet collar anywhere in the core. A stock
-- PET_COLLAR has no equipment slot, no appearance and no stat attached to wearing
-- it - a pet is either summoned or it is not, which is the whole of that model -
-- and no packet carries a worn flag, because the item is in the player's inventory
-- either way and the client has nothing to draw differently.
--
-- So this is the module's own state. It marks which collar a recall answers, and
-- decides whether login brings a beast back by itself. One collar per owner: the
-- collars are not tradable and a beast belongs to whoever holds it, so there is no
-- case where two players could claim the same one.
--
-- Defaults to 0, so an existing install behaves exactly as it did before this
-- column existed: recall falls back to the most recently used collar, and login
-- restores the same beast it always did. Nothing needs migrating.
--
-- IF NOT EXISTS is load-bearing, not decoration. Without it this statement raises
-- 'Duplicate column name worn' on the second boot, the module manager treats the
-- failed install as fatal, and refuses the module - so the module's effect classes
-- are never installed on the skill and every capture silently does nothing at all.
ALTER TABLE tamed_pet
  ADD COLUMN IF NOT EXISTS worn TINYINT(1) NOT NULL DEFAULT 0 AFTER synthetic_npc_id;

-- ---------------------------------------------------------------- AWAKENING2 ---
--
-- The technique the SECOND awakening opens.
--
-- Until now a beast's second awakening granted no technique of its own: it opened
-- the affinity proc, and it also re-opened the first awakening's family, so a
-- beast that had been through both awakenings showed its later techniques as still
-- locked and the second awakening looked like it had done nothing.
--
-- AWAKENING2 is now a real slot of its own. It is rolled into the deck at capture
-- time, stays locked through the first awakening, and is opened by the second -
-- which is also what makes the two stages mean different things to the player.
--
-- It is spelled without the underscore the numbered first-awakening techniques use
-- (AWAKENING, AWAKENING_2, AWAKENING_3) because the first awakening's grant matches
-- its family with LIKE 'AWAKENING%'. AWAKENING2 is inside that family, so the
-- underscore is the only thing separating the two in a LIKE pattern.
--
-- Beasts that already reached their second awakening under the old rules have no
-- AWAKENING2 row, and their extra technique is stuck in AWAKENING_2 or AWAKENING_3.
-- Rather than make them earn again what they already have, the highest-numbered
-- technique of each of those beasts is renamed into the new slot: same skill, same
-- state, now correctly described as what the second awakening opened. A beast with
-- only one technique has no spare row to promote and gains nothing - it keeps its
-- affinity proc, and its next capture will roll the new slot.
--
-- MAX() picks the right row because these names order correctly as text:
-- 'AWAKENING_2' < 'AWAKENING_3'.
--
-- The candidate set is the numbered rows only. It is tempting to let MAX() run
-- over 'AWAKENING%' and pick the highest name, because that expression reads as
-- though it excludes the plain row by virtue of sorting lower - and it does. That
-- is the problem. The COMMON rarity has exactly one awakening technique, in the
-- plain row, so it is the ONLY candidate its own beast has: a common beast would
-- have had that one technique renamed out from under it and ended up with no
-- first-awakening technique at all. Restricting to '_2' and '_3' means a beast
-- with no spare row produces no group at all, and is left alone - which is what
-- the paragraph above this one says it should be.
--
-- Idempotent: the NOT EXISTS guard means a beast that already has an AWAKENING2 row
-- is skipped, so a second run promotes nothing.
UPDATE tamed_pet_skill s
  INNER JOIN (
    SELECT p.tame_uuid AS uuid, MAX(s2.slot_type) AS promoted
      FROM tamed_pet p
      INNER JOIN tamed_pet_skill s2 ON s2.tame_uuid = p.tame_uuid
      WHERE p.active = 1
        AND p.awakening_stage >= 2
        AND s2.slot_type IN ('AWAKENING_2', 'AWAKENING_3')
        AND NOT EXISTS (
          SELECT 1 FROM tamed_pet_skill s3
            WHERE s3.tame_uuid = p.tame_uuid AND s3.slot_type = 'AWAKENING2')
      GROUP BY p.tame_uuid
  ) pick ON pick.uuid = s.tame_uuid AND pick.promoted = s.slot_type
  SET s.slot_type = 'AWAKENING2';

-- ------------------------------------------------------- EQUIPMENT VAULT LINK ---
--
-- Which physical item is installed in a slot.
--
-- Equipment used to be destroyed on install and rebuilt from (item_id, enchant)
-- on removal, which quietly threw away anything that is not expressed by those
-- two numbers: an augment, a Shadow item's remaining mana, elemental attributes.
-- The item is now kept whole in a private LEASE container owned by the player
-- (TameGearVault) and this column is the link between the slot row and the item
-- in that container. The item is moved out on removal exactly as it went in.
--
-- Defaults to 0, which is the tell for a row written before the vault existed.
-- Those rows have no item to hand back, so removal falls back to the old
-- rebuild-from-id behaviour for them only. Nothing needs migrating.
--
-- IF NOT EXISTS is load-bearing for the same reason as the 'worn' column above:
-- without it the second boot fails and the module is refused. See that note.
ALTER TABLE tamed_pet_equipment
  ADD COLUMN IF NOT EXISTS item_object_id INT NOT NULL DEFAULT 0 AFTER custom_data;
