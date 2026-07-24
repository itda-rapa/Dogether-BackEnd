ALTER TABLE media_assets
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

DROP INDEX ix_media_assets_delete_requested;

CREATE INDEX ix_media_assets_delete_requested
    ON media_assets (expires_at, id)
    WHERE status = 'DELETE_REQUESTED';
