local key = KEYS[1]
if redis.call('EXISTS', key) ~= 0 then return 0 end
for index = 1, #ARGV - 1, 2 do
    redis.call('HSET', key, ARGV[index], ARGV[index + 1])
end
if redis.call('EXPIRE', key, ARGV[#ARGV]) ~= 1 then
    redis.call('DEL', key)
    return -1
end
return 1
