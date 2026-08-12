CREATE INDEX ix_setlogs_visible_created_desc
    ON setlogs (created_at DESC, id DESC)
    WHERE status = 'VISIBLE';
