CREATE INDEX ix_friend_request_pending_target_requested_id
    ON friend_requests (
        target_pet_id,
        requested_at DESC,
        id DESC
    )
    WHERE status = 'PENDING';

CREATE INDEX ix_friend_request_pending_requester_requested_id
    ON friend_requests (
        requester_pet_id,
        requested_at DESC,
        id DESC
    )
    WHERE status = 'PENDING';
