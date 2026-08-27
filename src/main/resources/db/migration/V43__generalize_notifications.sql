ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS notifications_target_pet_id_fkey,
    DROP CONSTRAINT IF EXISTS notifications_actor_pet_id_fkey,
    DROP CONSTRAINT IF EXISTS notifications_room_id_fkey,
    DROP CONSTRAINT IF EXISTS ck_notification_type;

ALTER TABLE notifications
    ALTER COLUMN room_id DROP NOT NULL,
    ADD COLUMN target_type VARCHAR(40),
    ADD COLUMN target_id BIGINT,
    ADD COLUMN post_id BIGINT,
    ADD COLUMN setlog_id BIGINT,
    ADD COLUMN actor_pet_nickname_snapshot VARCHAR(30),
    ADD COLUMN actor_profile_asset_id_snapshot BIGINT,
    ADD COLUMN comment_preview_snapshot VARCHAR(500);

UPDATE notifications notification
   SET target_type = 'OPEN_CHAT_ROOM',
       target_id = notification.room_id,
       actor_pet_nickname_snapshot = pet.nickname,
       actor_profile_asset_id_snapshot = pet.profile_asset_id
  FROM pets pet
 WHERE notification.actor_pet_id = pet.id;

ALTER TABLE notifications
    ALTER COLUMN target_type SET NOT NULL,
    ALTER COLUMN target_id SET NOT NULL,
    ADD CONSTRAINT ck_notification_type CHECK (type IN (
        'OPEN_CHAT_INVITE',
        'BOARD_POST_LIKE', 'BOARD_POST_HELPFUL',
        'BOARD_COMMENT_HELPFUL', 'BOARD_COMMENT_CREATED', 'BOARD_REPLY_CREATED',
        'SETLOG_LIKE', 'SETLOG_CUTE'
    )),
    ADD CONSTRAINT ck_notification_target_type CHECK (target_type IN (
        'OPEN_CHAT_ROOM', 'BOARD_POST', 'BOARD_COMMENT', 'SETLOG'
    ));

CREATE UNIQUE INDEX uk_notifications_reaction_once
    ON notifications (actor_pet_id, target_pet_id, type, target_type, target_id)
    WHERE type IN (
        'BOARD_POST_LIKE', 'BOARD_POST_HELPFUL', 'BOARD_COMMENT_HELPFUL',
        'SETLOG_LIKE', 'SETLOG_CUTE'
    );

CREATE INDEX idx_notifications_target_unread
    ON notifications (target_pet_id, created_at DESC, id DESC)
    WHERE read_at IS NULL;
