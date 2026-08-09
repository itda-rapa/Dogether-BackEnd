-- KEYS challenge, token
-- ARGV submittedCodeHmac, challengeId, tokenTtl, tokenEmailHmac, tokenPurpose, currentPrefix
local challengeKey = KEYS[1]
local tokenKey = KEYS[2]

if redis.call('EXISTS', challengeKey) == 0 then return { -1, 0 } end

local data = redis.call('HMGET', challengeKey, 'emailHmac', 'purpose', 'codeHmac', 'failedAttempts', 'maxAttempts')
local emailHmac, purpose, codeHmac = data[1], data[2], data[3]
local failedAttempts, maxAttempts = tonumber(data[4]), tonumber(data[5])
if not emailHmac or not purpose or not codeHmac or not failedAttempts or not maxAttempts then
    redis.call('DEL', challengeKey)
    return { -4, 0 }
end

local currentKey = ARGV[6] .. purpose .. ':' .. emailHmac
if codeHmac ~= ARGV[1] then
    failedAttempts = failedAttempts + 1
    if failedAttempts >= maxAttempts then
        redis.call('DEL', challengeKey)
        if redis.call('GET', currentKey) == ARGV[2] then redis.call('DEL', currentKey) end
        return { -2, 0 }
    end
    redis.call('HSET', challengeKey, 'failedAttempts', tostring(failedAttempts))
    return { 0, maxAttempts - failedAttempts }
end

if redis.call('EXISTS', tokenKey) == 1 then return { -3, 0 } end
redis.call('HSET', tokenKey, 'emailHmac', emailHmac, 'purpose', purpose)
redis.call('EXPIRE', tokenKey, ARGV[3])
redis.call('DEL', challengeKey)
if redis.call('GET', currentKey) == ARGV[2] then redis.call('DEL', currentKey) end
return { 1, 0 }
