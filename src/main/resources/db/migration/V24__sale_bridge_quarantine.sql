-- A durable fence exists independently of a request, including rejected orphan reservations.
CREATE TABLE material_sale_quarantine_clock (id TINYINT PRIMARY KEY,revision BIGINT NOT NULL DEFAULT 0) ENGINE=InnoDB;
INSERT INTO material_sale_quarantine_clock(id) VALUES(1);
CREATE TABLE material_sale_reservation_fences (
 reservation_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 state VARCHAR(16) NOT NULL DEFAULT 'ALLOW',
 CHECK (state IN ('ALLOW','BLOCKED'))
) ENGINE=InnoDB;
INSERT INTO material_sale_reservation_fences(reservation_id)
 SELECT reservation_id FROM material_purchase_requests;

CREATE TABLE material_sale_stream_quarantine (
 stream_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 payload LONGTEXT NOT NULL,
 reason VARCHAR(80) NOT NULL,
 canonical_reservation_id VARCHAR(64) NULL,
 trusted_json LONGTEXT NULL,
 trusted_generation BIGINT NULL,
 reservation_id VARCHAR(64) NULL,
 campaign_id BIGINT NULL,
 disposition VARCHAR(24) NOT NULL,
 created_at_ms BIGINT NOT NULL,
 updated_at_ms BIGINT NOT NULL,
 KEY ix_sale_quarantine_disposition(disposition,campaign_id),
 KEY ix_sale_quarantine_reservation(reservation_id),
 CHECK (disposition IN ('HOLD','RELEASE_PENDING','RELEASED','NO_RELEASE'))
) ENGINE=InnoDB;
