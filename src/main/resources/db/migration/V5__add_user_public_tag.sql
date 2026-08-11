ALTER TABLE users
    ADD COLUMN public_tag VARCHAR(30);

UPDATE users
SET public_tag =
        LEFT(nickname, GREATEST(1, 29 - LENGTH(id::text)))
        || '#'
        || id::text
WHERE public_tag IS NULL;

ALTER TABLE users
    ALTER COLUMN public_tag SET NOT NULL;

ALTER TABLE users
    ADD CONSTRAINT ck_users_public_tag_format
        CHECK (POSITION('#' IN public_tag) > 1);

CREATE UNIQUE INDEX uk_users_public_tag ON users (public_tag);
