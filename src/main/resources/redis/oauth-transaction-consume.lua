if redis.call('EXISTS', KEYS[1]) == 0 then
    return {-1}
end

local expiresAt = redis.call('HGET', KEYS[1], 'expiresAt')
if not expiresAt or tonumber(ARGV[1]) >= tonumber(expiresAt) then
    redis.call('DEL', KEYS[1])
    return {-2}
end

if redis.call('HGET', KEYS[1], 'provider') ~= ARGV[2]
        or redis.call('HGET', KEYS[1], 'redirectUri') ~= ARGV[3] then
    return {-1}
end

local browserBindingHash = redis.call('HGET', KEYS[1], 'browserBindingHash')
if not browserBindingHash then
    redis.call('DEL', KEYS[1])
    return {-1}
end

if browserBindingHash ~= ARGV[4] then
    return {-1}
end

local codeVerifier = redis.call('HGET', KEYS[1], 'codeVerifier')
local nonce = redis.call('HGET', KEYS[1], 'nonce')
if not codeVerifier or (ARGV[2] == 'GOOGLE' and (not nonce or nonce == '')) then
    redis.call('DEL', KEYS[1])
    return {-1}
end

redis.call('DEL', KEYS[1])
return {1, codeVerifier, nonce or ''}
