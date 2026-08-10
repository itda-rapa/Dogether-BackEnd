-- KEYS challenge, current, cooldown
-- ARGV mode, challengeId, emailHmac, purpose, codeHmac, challengeTtl, cooldownTtl, challengePrefix
local mode = ARGV[1]
local challengeKey = KEYS[1]
local currentKey = KEYS[2]
local cooldownKey = KEYS[3]
local challengeId = ARGV[2]

if mode == 'COMPENSATE' then
    redis.call('DEL', challengeKey)
    if redis.call('GET', currentKey) == challengeId then redis.call('DEL', currentKey) end
    if redis.call('GET', cooldownKey) == challengeId then redis.call('DEL', cooldownKey) end
    return { 1 }
end

if redis.call('EXISTS', cooldownKey) == 1 then return { 0 } end

local oldChallengeId = redis.call('GET', currentKey)
if oldChallengeId then redis.call('DEL', ARGV[8] .. oldChallengeId) end

redis.call('HSET', challengeKey,
    'emailHmac', ARGV[3],
    'purpose', ARGV[4],
    'codeHmac', ARGV[5],
    'failedAttempts', '0',
    'maxAttempts', ARGV[9])
redis.call('EXPIRE', challengeKey, ARGV[6])
redis.call('SET', currentKey, challengeId, 'EX', ARGV[6])
redis.call('SET', cooldownKey, challengeId, 'EX', ARGV[7])
return { 1 }
