if redis.call('EXISTS', KEYS[1]) ~= 0 then
    return 0
end

redis.call('HSET', KEYS[1],
    'provider', ARGV[1],
    'codeVerifier', ARGV[2],
    'nonce', ARGV[3],
    'redirectUri', ARGV[4],
    'expiresAt', ARGV[5],
    'browserBindingHash', ARGV[6])
redis.call('PEXPIRE', KEYS[1], ARGV[7])
return 1
