ALTER TABLE media
    ADD COLUMN content_type VARCHAR(100),
    ADD COLUMN etag VARCHAR(512),
    ADD COLUMN object_version_id VARCHAR(1024),
    ADD COLUMN storage_last_modified TIMESTAMPTZ,
    ADD COLUMN verified_at TIMESTAMPTZ;

ALTER TABLE setlog_uploads
    ADD COLUMN completion_request_id UUID,
    ADD COLUMN media_id BIGINT,
    ADD COLUMN setlog_id BIGINT,
    ADD CONSTRAINT fk_setlog_uploads_media
        FOREIGN KEY (media_id) REFERENCES media (id),
    ADD CONSTRAINT fk_setlog_uploads_setlog
        FOREIGN KEY (setlog_id) REFERENCES setlogs (id),
    ADD CONSTRAINT uk_setlog_uploads_media UNIQUE (media_id),
    ADD CONSTRAINT uk_setlog_uploads_setlog UNIQUE (setlog_id),
    ADD CONSTRAINT ck_setlog_uploads_completed_links CHECK (
        (
            status = 'COMPLETED'
            AND completed_at IS NOT NULL
            AND completion_request_id IS NOT NULL
            AND media_id IS NOT NULL
            AND setlog_id IS NOT NULL
        )
        OR (
            status <> 'COMPLETED'
            AND completed_at IS NULL
            AND completion_request_id IS NULL
            AND media_id IS NULL
            AND setlog_id IS NULL
        )
    );
