-- Local demo flow:
-- home Setlogs (3 videos) -> greeting -> DIRECT chat.
--
-- Put the three video objects in the configured S3/RustFS bucket with
-- exactly these keys before opening the home feed:
--   demo/setlogs/bori.mp4
--   demo/setlogs/maru.mp4
--   demo/setlogs/kkoma.mp4
--
-- Login account: demo@dogether.local / demo1234!

INSERT INTO users (
    email,
    password_hash,
    nickname,
    public_tag,
    role,
    account_status,
    neighborhood_code
) VALUES
    (
        'demo@dogether.local',
        '$2a$10$4WlayPCKRfxJHdK3z4Eho./EvXY5vunnw5qKWNxO58SnTBcmjPJ0u',
        '데모사용자',
        '데모사용자#D001',
        'USER',
        'ACTIVE',
        '4113111500'
    ),
    (
        'bori@dogether.local',
        '$2a$10$4WlayPCKRfxJHdK3z4Eho./EvXY5vunnw5qKWNxO58SnTBcmjPJ0u',
        '보리보호자',
        '보리보호자#D002',
        'USER',
        'ACTIVE',
        '4113111500'
    ),
    (
        'maru@dogether.local',
        '$2a$10$4WlayPCKRfxJHdK3z4Eho./EvXY5vunnw5qKWNxO58SnTBcmjPJ0u',
        '마루보호자',
        '마루보호자#D003',
        'USER',
        'ACTIVE',
        '4113111600'
    ),
    (
        'kkoma@dogether.local',
        '$2a$10$4WlayPCKRfxJHdK3z4Eho./EvXY5vunnw5qKWNxO58SnTBcmjPJ0u',
        '꼬마보호자',
        '꼬마보호자#D004',
        'USER',
        'ACTIVE',
        '4113111700'
    )
ON CONFLICT DO NOTHING;

UPDATE users
SET password_hash = '$2a$10$4WlayPCKRfxJHdK3z4Eho./EvXY5vunnw5qKWNxO58SnTBcmjPJ0u',
    role = 'USER',
    account_status = 'ACTIVE',
    withdrawn_at = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE LOWER(email) IN (
    'demo@dogether.local',
    'bori@dogether.local',
    'maru@dogether.local',
    'kkoma@dogether.local'
);

INSERT INTO pets (
    owner_user_id,
    public_tag,
    nickname,
    breed_name,
    sex,
    neutered,
    birth_date,
    weight_kg,
    size_code,
    bio,
    personality_tags,
    care_note,
    status
) VALUES
    (
        (SELECT id FROM users WHERE LOWER(email) = 'demo@dogether.local'),
        '두부#D001',
        '두부',
        '말티즈',
        'MALE',
        TRUE,
        DATE '2022-05-12',
        4.20,
        'SMALL',
        '산책 친구를 찾고 있어요.',
        '["활발해요", "사람을 좋아해요"]'::jsonb,
        NULL,
        'ACTIVE'
    ),
    (
        (SELECT id FROM users WHERE LOWER(email) = 'bori@dogether.local'),
        '보리#D002',
        '보리',
        '골든 리트리버',
        'FEMALE',
        TRUE,
        DATE '2021-03-20',
        27.40,
        'LARGE',
        '공놀이와 긴 산책을 좋아해요.',
        '["다정해요", "공놀이를 좋아해요"]'::jsonb,
        NULL,
        'ACTIVE'
    ),
    (
        (SELECT id FROM users WHERE LOWER(email) = 'maru@dogether.local'),
        '마루#D003',
        '마루',
        '웰시 코기',
        'MALE',
        TRUE,
        DATE '2023-01-08',
        11.30,
        'MEDIUM',
        '새 친구에게 먼저 인사해요.',
        '["호기심이 많아요", "친화적이에요"]'::jsonb,
        NULL,
        'ACTIVE'
    ),
    (
        (SELECT id FROM users WHERE LOWER(email) = 'kkoma@dogether.local'),
        '꼬마#D004',
        '꼬마',
        '포메라니안',
        'FEMALE',
        FALSE,
        DATE '2023-09-14',
        3.10,
        'SMALL',
        '천천히 친해지면 애교가 많아요.',
        '["차분해요", "애교가 많아요"]'::jsonb,
        '처음에는 천천히 다가와 주세요.',
        'ACTIVE'
    )
ON CONFLICT (public_tag) DO UPDATE
SET owner_user_id = EXCLUDED.owner_user_id,
    nickname = EXCLUDED.nickname,
    breed_name = EXCLUDED.breed_name,
    sex = EXCLUDED.sex,
    neutered = EXCLUDED.neutered,
    birth_date = EXCLUDED.birth_date,
    weight_kg = EXCLUDED.weight_kg,
    size_code = EXCLUDED.size_code,
    bio = EXCLUDED.bio,
    personality_tags = EXCLUDED.personality_tags,
    care_note = EXCLUDED.care_note,
    status = 'ACTIVE',
    deleted_at = NULL,
    updated_at = CURRENT_TIMESTAMP;

UPDATE users AS demo_user
SET active_pet_id = demo_pet.id,
    updated_at = CURRENT_TIMESTAMP
FROM pets AS demo_pet,
     (VALUES
         ('demo@dogether.local', '두부#D001'),
         ('bori@dogether.local', '보리#D002'),
         ('maru@dogether.local', '마루#D003'),
         ('kkoma@dogether.local', '꼬마#D004')
     ) AS active_pet(email, public_tag)
WHERE LOWER(demo_user.email) = active_pet.email
  AND demo_pet.public_tag = active_pet.public_tag;

INSERT INTO media (
    media_type,
    path,
    status,
    user_id,
    file_size,
    attributes,
    created_at,
    updated_at
) VALUES
    (
        'VIDEO',
        'demo/setlogs/bori.mp4',
        'UPLOADED',
        (SELECT id FROM users WHERE LOWER(email) = 'bori@dogether.local'),
        1,
        '{"seed": true, "contentType": "video/mp4"}'::jsonb,
        CURRENT_TIMESTAMP - INTERVAL '3 minutes',
        CURRENT_TIMESTAMP
    ),
    (
        'VIDEO',
        'demo/setlogs/maru.mp4',
        'UPLOADED',
        (SELECT id FROM users WHERE LOWER(email) = 'maru@dogether.local'),
        1,
        '{"seed": true, "contentType": "video/mp4"}'::jsonb,
        CURRENT_TIMESTAMP - INTERVAL '2 minutes',
        CURRENT_TIMESTAMP
    ),
    (
        'VIDEO',
        'demo/setlogs/kkoma.mp4',
        'UPLOADED',
        (SELECT id FROM users WHERE LOWER(email) = 'kkoma@dogether.local'),
        1,
        '{"seed": true, "contentType": "video/mp4"}'::jsonb,
        CURRENT_TIMESTAMP - INTERVAL '1 minute',
        CURRENT_TIMESTAMP
    )
ON CONFLICT (path) DO UPDATE
SET media_type = 'VIDEO',
    status = 'UPLOADED',
    user_id = EXCLUDED.user_id,
    deleted_at = NULL,
    attributes = EXCLUDED.attributes,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO setlogs (
    author_pet_id,
    media_id,
    caption,
    status,
    reaction_cute_count,
    reaction_like_count,
    is_seed,
    created_at,
    updated_at
) VALUES
    (
        (SELECT id FROM pets WHERE public_tag = '보리#D002'),
        (SELECT id FROM media WHERE path = 'demo/setlogs/bori.mp4'),
        '오늘도 공원에서 신나게 뛰었어요! 같이 산책할 친구 있나요?',
        'VISIBLE',
        12,
        7,
        TRUE,
        CURRENT_TIMESTAMP - INTERVAL '3 minutes',
        CURRENT_TIMESTAMP
    ),
    (
        (SELECT id FROM pets WHERE public_tag = '마루#D003'),
        (SELECT id FROM media WHERE path = 'demo/setlogs/maru.mp4'),
        '짧은 다리로 전력 질주하는 중이에요.',
        'VISIBLE',
        9,
        5,
        TRUE,
        CURRENT_TIMESTAMP - INTERVAL '2 minutes',
        CURRENT_TIMESTAMP
    ),
    (
        (SELECT id FROM pets WHERE public_tag = '꼬마#D004'),
        (SELECT id FROM media WHERE path = 'demo/setlogs/kkoma.mp4'),
        '햇살 좋은 날에는 잔디밭이 최고예요.',
        'VISIBLE',
        15,
        11,
        TRUE,
        CURRENT_TIMESTAMP - INTERVAL '1 minute',
        CURRENT_TIMESTAMP
    )
ON CONFLICT (media_id) DO UPDATE
SET author_pet_id = EXCLUDED.author_pet_id,
    caption = EXCLUDED.caption,
    status = 'VISIBLE',
    is_seed = TRUE,
    updated_at = CURRENT_TIMESTAMP;
