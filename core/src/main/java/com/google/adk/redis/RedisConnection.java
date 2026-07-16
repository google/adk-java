package com.google.adk.redis;

import java.util.List;
import java.util.Map;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class RedisConnection {

  JedisPool jedisPool;

  public RedisConnection(String RedisDBUrl) {
    initPool(RedisDBUrl);
  }

  void initPool(String RedisDBUrl) {

    JedisPoolConfig poolConfig = new JedisPoolConfig();
    poolConfig.setMaxIdle(30);
    poolConfig.setMinIdle(10);
    jedisPool = new JedisPool(poolConfig, RedisDBUrl);
  }

  public String set(String key, String value) {
    Jedis rc = jedisPool.getResource();
    try {
      return rc.set(key, value);
    } catch (Exception ex) {
      return "";
    } finally {
      if (rc != null) rc.close();
    }
  }

  public Long del(String key, String field) {
    Jedis rc = jedisPool.getResource();
    try {
      return rc.hdel(key, field);
    } catch (Exception ex) {
      return null;
    } finally {
      if (rc != null) rc.close();
    }
  }

  public String get(String key) {
    Jedis rc = jedisPool.getResource();
    try {

      return rc.get(key);
    } catch (Exception ex) {
      return "";
    } finally {
      if (rc != null) rc.close();
    }
  }

  /**
   * @param Key
   * @param sec
   * @return
   */
  public long expireAfter(String Key, int sec) {
    Jedis rc = jedisPool.getResource();
    try {
      return rc.expire(Key, sec);
    } catch (Exception ex) {
      return 0;
    } finally {
      if (rc != null) rc.close();
    }
  }

  public Object HashSet(String key, String field, String value) {
    Jedis rc = jedisPool.getResource();
    Object result;
    try {
      result = rc.hset(key, field, value);
    } catch (Exception ex) {
      // System.out.println(ex);
      return null;
    } finally {
      if (rc != null) rc.close();
    }
    return result;
  }

  /**
   * @param Key
   * @param sec
   * @return
   */
  public long expire(String Key, long sec) {
    Jedis rc = jedisPool.getResource();
    try {
      return rc.expireAt(Key, sec);
    } catch (Exception ex) {
      return 0;
    } finally {
      if (rc != null) rc.close();
    }
  }

  public Object HashGet(String key, String field) {
    Jedis rc = jedisPool.getResource();
    Object result;
    try {
      result = rc.hget(key, field);
    } catch (Exception ex) {
      // ex.printStackTrace();
      return null;
    } finally {
      if (rc != null) rc.close();
    }
    return result;
  }

  public String HashMSet(String cache, Map map) {
    Jedis rc = jedisPool.getResource();
    String result = "";
    try {
      result = rc.hmset(cache, map);
    } catch (Exception ex) {
    } finally {
      if (rc != null) rc.close();
    }
    return result;
  }

  // SCRIPT Functions

  public List<String> HashMGet(String key, String fields) {
    Jedis rc = jedisPool.getResource();
    List<String> result;
    try {
      result = rc.hmget(key, fields);
    } catch (Exception ex) {
      // System.out.println(ex);
      return null;
    } finally {
      if (rc != null) rc.close();
    }
    return result;
  }
}
