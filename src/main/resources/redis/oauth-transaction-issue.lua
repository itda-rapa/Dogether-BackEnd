if redis.call('EXISTS', KEYS[1]) ~= 0 then
    return 0
end

if ARGV[1] == 'GOOGLE' and ARGV[3] == '' then
    return -1
end

redis.call('HSET', KEYS[1],
    'provider', ARGV[1],
    'codeVerifier', ARGV[2],
    'redirectUri', ARGV[4],
    'expiresAt', ARGV[5],
    'browserBindingHash', ARGV[6])
if ARGV[3] ~= '' then
    redis.call('HSET', KEYS[1], 'nonce', ARGV[3])
end
redis.call('PEXPIRE', KEYS[1], ARGV[7])
return 1
