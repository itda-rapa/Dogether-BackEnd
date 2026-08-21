ALTER TABLE board_post_comments
    ADD COLUMN parent_comment_id BIGINT,
    ADD COLUMN root_comment_id BIGINT,
    ADD COLUMN depth SMALLINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT fk_board_post_comments_parent
        FOREIGN KEY (parent_comment_id) REFERENCES board_post_comments(id),
    ADD CONSTRAINT fk_board_post_comments_root
        FOREIGN KEY (root_comment_id) REFERENCES board_post_comments(id),
    ADD CONSTRAINT ck_board_post_comments_hierarchy
        CHECK (
            (parent_comment_id IS NULL AND root_comment_id IS NULL AND depth = 0)
            OR
            (parent_comment_id IS NOT NULL AND root_comment_id IS NOT NULL AND depth BETWEEN 1 AND 3)
        );

CREATE INDEX ix_board_post_comments_root_cursor
    ON board_post_comments (post_id, created_at ASC, id ASC)
    WHERE parent_comment_id IS NULL;

CREATE INDEX ix_board_post_comments_reply_root_created_id
    ON board_post_comments (root_comment_id, created_at ASC, id ASC)
    WHERE parent_comment_id IS NOT NULL;
