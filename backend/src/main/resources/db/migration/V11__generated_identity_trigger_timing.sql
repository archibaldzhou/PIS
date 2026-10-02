-- Stored generated columns are computed after BEFORE triggers. Comparing the whole
-- NEW row there incorrectly treats target_id/block_kind/slide_kind as changed.
-- AFTER still raises in the same statement/transaction, but sees computed values.
-- Keep the original comparison functions: all identity/snapshot columns, including
-- generated columns, remain protected; only the existing mutable fields are exempt.
DROP TRIGGER trg_label_snapshot ON label_job;
CREATE TRIGGER trg_label_snapshot AFTER UPDATE ON label_job
 FOR EACH ROW EXECUTE FUNCTION protect_label_snapshot();
DROP TRIGGER trg_material_identity ON material_entity;
CREATE TRIGGER trg_material_identity AFTER UPDATE ON material_entity
 FOR EACH ROW EXECUTE FUNCTION protect_gross_identity();
