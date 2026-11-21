-- Hardening A11: an assistant command that fails Java-side validation ends in a terminal REJECTED state
-- (one reply giving the reason) instead of being retried until FAILED with no reply.
-- One statement, so the table is never left without the constraint if the migration fails halfway.
ALTER TABLE assistant_tasks
  DROP CHECK chk_assistant_state,
  ADD CONSTRAINT chk_assistant_state CHECK (state IN ('NEW','RUNNING','DONE','FAILED','CLARIFY','REJECTED'));
