package io.github.mebsic.core.manager;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class RedisManager {
    private static final int REDIS_TIMEOUT_MILLIS = 2000;
    private static final long REDIS_POOL_MAX_WAIT_MILLIS = 2000L;

    private final JedisPool pool;
    private final String host;
    private final int port;
    private final String password;
    private final int database;

    public RedisManager(String host, int port, String password, int database) {
        this.host = host;
        this.port = port;
        this.password = password == null ? null : password.trim();
        this.database = database;

        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(64);
        config.setMaxIdle(16);
        config.setMinIdle(2);
        config.setBlockWhenExhausted(true);
        config.setMaxWaitMillis(REDIS_POOL_MAX_WAIT_MILLIS);
        if (this.password == null || this.password.isEmpty()) {
            this.pool = new JedisPool(config, this.host, this.port, REDIS_TIMEOUT_MILLIS, null, this.database);
        } else {
            this.pool = new JedisPool(config, this.host, this.port, REDIS_TIMEOUT_MILLIS, this.password, this.database);
        }
    }

    public JedisPool getPool() {
        return pool;
    }

    public Jedis createSubscriberClient() {
        Jedis jedis = new Jedis(host, port, REDIS_TIMEOUT_MILLIS);
        try {
            if (password != null && !password.isEmpty()) {
                jedis.auth(password);
            }
            if (database != 0) {
                jedis.select(database);
            }
            return jedis;
        } catch (RuntimeException ex) {
            jedis.close();
            throw ex;
        }
    }

    public void close() {
        pool.close();
    }
}
