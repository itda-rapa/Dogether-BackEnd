-- KEYS token
-- ARGV expectedEmailHmac, expectedPurpose
local tokenKey = KEYS[1]
if redis.call('EXISTS', tokenKey) == 0 then return 0 end
local data = redis.call('HMGET', tokenKey, 'emailHmac', 'purpose')
if data[1] ~= ARGV[1] or data[2] ~= ARGV[2] then return 0 end
redis.call('DEL', tokenKey)
return 1
