-- KEYS[1] = refresh:token:<jti>
-- ARGV[1] = now (epoch seconds)
-- ARGV[2] = grace seconds
-- ARGV[3] = newRefreshJti (预生成，原子写入 replacedBy)
-- return: {verdict[, payload]}
local key = KEYS[1]
local status = redis.call('HGET', key, 'status')
if not status then return {'unknown'} end

if status == 'ACTIVE' then
  -- 原子翻成 USED 并记后继；只改字段、不动 TTL
  redis.call('HSET', key, 'status', 'USED', 'replacedBy', ARGV[3], 'rotatedAt', ARGV[1])
  local pttl = redis.call('PTTL', key)
  return {'rotate', tostring(pttl)}
end

if status == 'USED' then
  local rotatedAt = tonumber(redis.call('HGET', key, 'rotatedAt')) or 0
  local age = tonumber(ARGV[1]) - rotatedAt
  if age >= 0 and age < tonumber(ARGV[2]) then
    local rep = redis.call('HGET', key, 'replacedBy')
    return {'grace', rep or ''}          -- 合法重试：沿 replacedBy 续换
  end
  return {'reuse'}                       -- 超窗口 / 被盗
end
return {'unknown'}
