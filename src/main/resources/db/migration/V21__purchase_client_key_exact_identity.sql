-- clientKey is an opaque request identity, matching Redis's UTF-8 SHA-256 lookup.
-- Default ai_ci would merge different case/accent keys after Redis accepted them.
-- 0900_bin is NO PAD: trailing spaces remain part of the identity as well.
ALTER TABLE material_purchase_requests
 MODIFY client_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL;
