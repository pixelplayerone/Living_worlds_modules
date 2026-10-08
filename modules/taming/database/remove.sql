-- Removes every table owned by the Beast Collar taming module.
--
-- This is destructive. It deletes every tamed pet, collar record, skill record,
-- equipment record and history entry. Back up the database first, and note that
-- the collars themselves live in the normal item table and are not removed here:
-- delete those by hand if you want a clean slate.

DROP TABLE IF EXISTS tamed_pet_history;
DROP TABLE IF EXISTS tamed_pet_equipment;
DROP TABLE IF EXISTS tamed_pet_skill;
DROP TABLE IF EXISTS tamed_pet_bestiary;
DROP TABLE IF EXISTS tamed_pet;
