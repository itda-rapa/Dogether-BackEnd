local key = KEYS[1]
if redis.call('EXISTS', key) == 0 then return 0 end

local status = redis.call('HGET', key, 'status')
if status == false then return -1 end
if status == 'RESERVED' then return 0 end
if status ~= 'AVAILABLE' then return -1 end

local requesterUserId = redis.call('HGET', key, 'requesterUserId')
if requesterUserId == false or string.match(requesterUserId, '%S') == nil then return -1 end
if requesterUserId ~= ARGV[1] then return 0 end

local flowType = redis.call('HGET', key, 'flowType')
if flowType == false or string.match(flowType, '%S') == nil then return -1 end
if flowType ~= 'PET_CREATE' and flowType ~= 'EXISTING_PET_VERIFY' then return -1 end
if flowType ~= ARGV[2] then return 0 end

local storedTargetPetId = redis.call('HGET', key, 'targetPetId')
local requestHasTargetPetId = string.match(ARGV[3], '%S') ~= nil
if flowType == 'PET_CREATE' then
    if storedTargetPetId ~= false then return -1 end
    if requestHasTargetPetId then return 0 end
else
    if storedTargetPetId == false or string.match(storedTargetPetId, '%S') == nil then return -1 end
    if not requestHasTargetPetId or storedTargetPetId ~= ARGV[3] then return 0 end
end
redis.call('HSET', key, 'status', 'RESERVED', 'reservationId', ARGV[4])

local result = {1, 'reservationId', ARGV[4]}
local fields = {
    'provider', 'registrationNumberHmac', 'deviceType', 'registeredName',
    'birthDate', 'sex', 'breedName', 'neutered'
}
for _, field in ipairs(fields) do
    local value = redis.call('HGET', key, field)
    if value ~= false then
        table.insert(result, field)
        table.insert(result, value)
    end
end
return result
