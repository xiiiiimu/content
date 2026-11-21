-- Batch dispatch claims scan due PENDING and lease-expired SENDING separately.
-- Each scan is an equality on state plus an ordered range, so LIMIT n FOR UPDATE SKIP LOCKED
-- can walk the index in order without a filesort. V20's ix_sale_dispatch_due stays for the serial claim.
ALTER TABLE material_sale_dispatch
 ADD KEY ix_sale_dispatch_pending_due(state,available_at_ms,reservation_id),
 ADD KEY ix_sale_dispatch_sending_expired(state,lease_until_ms,reservation_id);
