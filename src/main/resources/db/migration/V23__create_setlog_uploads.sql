CREATE TABLE setlog_uploads (
    id UUID PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    pet_id BIGINT NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    expected_size BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_setlog_uploads_owner
        FOREIGN KEY (owner_user_id) REFERENCES users (id),
    CONSTRAINT fk_setlog_uploads_pet
        FOREIGN KEY (pet_id) REFERENCES pets (id),
    CONSTRAINT uk_setlog_uploads_object_key UNIQUE (object_key),
    CONSTRAINT ck_setlog_uploads_content_type
        CHECK (content_type IN ('video/mp4', 'video/webm')),
    CONSTRAINT ck_setlog_uploads_expected_size
        CHECK (expected_size BETWEEN 1 AND 209715200),
    CONSTRAINT ck_setlog_uploads_status
        CHECK (status IN ('PRESIGNED', 'COMPLETED', 'EXPIRED', 'REJECTED', 'CANCELED'))
);

CREATE INDEX ix_setlog_uploads_presigned_expires
    ON setlog_uploads (expires_at, id)
    WHERE status = 'PRESIGNED';
