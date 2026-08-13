local key = KEYS[1]
if redis.call('EXISTS', key) == 0 then return 0 end
if redis.call('HGET', key, 'status') ~= 'RESERVED' then return 0 end
if redis.call('HGET', key, 'reservationId') ~= ARGV[1] then return 0 end
redis.call('HSET', key, 'status', 'AVAILABLE')
redis.call('HDEL', key, 'reservationId')
return 1
