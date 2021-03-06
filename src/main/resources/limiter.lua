-- 基于JAVA+LUA+Redis实现分布式限流
-- 1.数据结构：
-- 限流规则：Hash<rate_limiter:[FlowKey], [SECOND/MINUTE/HOUR/DAY/MONTH/YEAR/CUSTOM], [最大资源数]>
-- 限流统计：String<rate_limiter:[FlowKey]:[SECOND/MINUTE/HOUR/DAY/MONTH/YEAR/CUSTOM], [已用资源数量]>
-- 2.脚本返回数据结构：
-- List{NORULE/OK/FULL, [String/List], [Long/List]}

-- 获取redis机器上当前的毫秒级时间戳
local function get_now_time ()
  local now_time_array = redis.call('time')
  return now_time_array[1] * 1000 + math.floor(now_time_array[2] / 1000 + 0.5);
end

-- 使用Redis实现分布式限流的主函数
local NAMESPACE = '%s' -- 命名空间
local RULE_KEY = '%s' -- 限流资源KEY(拼装后的字符串)

local resule_key_table={} -- 统计资源KEY
local resule_value_table={} -- 统计资源VALUE
local max_res_key = 'limiter-rules@'..NAMESPACE..'?'..RULE_KEY
if redis.call('EXISTS', max_res_key) == 0 then -- 没有设置规则
  return {'NORULE', max_res_key, -1}
else
  local granularity_table_key = {}
  local granularity_table_value = {}
  
  -- 定义时间粒度(Time Granularity),CUSTOM表示自定义过期时间
  local time_granularity_table_key = {'SECOND', 'MINUTE', 'HOUR', 'DAY', 'MONTH', 'YEAR', 'CUSTOM'}
  local time_granularity_table_value = {1, 60, 3600, 86400, 2592000, 31104000, -1}
  
  -- 限流优先级排序:SECOND>MINUTE>HOUR>DAY>MONTH>YEAR>CUSTOM
  local fields = redis.call('hkeys', max_res_key) -- 获取所有时间粒度集合
  for k1, v1 in ipairs(time_granularity_table_key) do
     for k2, v2 in ipairs(fields) do
      if v1 == v2 then
        table.insert(granularity_table_key, v1)
        table.insert(granularity_table_value, time_granularity_table_value[k1])
      end
    end
  end
  
  for key, value in ipairs(granularity_table_key) do
    -- 获取过期时间,若为自定义时间粒度,则使用ARGV[1]进行填充
    local expire_time = granularity_table_value[key]
    if expire_time == -1 then
      expire_time = ARGV[1]
    end
    
    -- 获取最大允许资源数
    local max_res_num = redis.call('hget', max_res_key, value)
    local now_res_key = 'limiter-cluster@'..NAMESPACE..'?'..RULE_KEY..':'..value
    if redis.call('EXISTS', now_res_key) == 0 then -- 当前资源的第1次操作
      redis.call('INCR', now_res_key)
      redis.call('EXPIRE', now_res_key, expire_time) -- 设置过期时间
      -- 返回剩余资源数
      table.insert(resule_key_table, value)
      table.insert(resule_value_table, max_res_num)
    else
      local now_res_num = redis.call('get', now_res_key) -- 获取当前已用资源数
      local surplus_res_num = max_res_num - now_res_num -- 计算剩余资源数
      if surplus_res_num > 0 then
        redis.call('INCR', now_res_key) -- 资源占用数累积
        -- 返回剩余资源数
        table.insert(resule_key_table, value)
        table.insert(resule_value_table, surplus_res_num)
      else -- 资源已满
        return {'FULL', value, surplus_res_num}
      end
    end
  end-- for循环结束

  return {'OK', resule_key_table, resule_value_table}
end--if-else结束
