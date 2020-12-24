/**
 * Copyright (c) 2013-2020 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.redisson.api.RFuture;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.client.codec.LongCodec;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.misc.RPromise;
import org.redisson.misc.RedissonPromise;
import org.redisson.pubsub.SemaphorePubSub;

import io.netty.buffer.ByteBufUtil;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class RedissonPermitExpirableSemaphore extends RedissonExpirable implements RPermitExpirableSemaphore {

    private final SemaphorePubSub semaphorePubSub;

    final CommandAsyncExecutor commandExecutor;

    private final String timeoutName;
    
    private final long nonExpirableTimeout = 922337203685477L;

    public RedissonPermitExpirableSemaphore(CommandAsyncExecutor commandExecutor, String name) {
        super(commandExecutor, name);
        this.timeoutName = suffixName(name, "timeout");
        this.commandExecutor = commandExecutor;
        this.semaphorePubSub = commandExecutor.getConnectionManager().getSubscribeService().getSemaphorePubSub();
    }

    String getChannelName() {
        return getChannelName(getName());
    }
    
    public static String getChannelName(String name) {
        if (name.contains("{")) {
            return "redisson_sc:" + name;
        }
        return "redisson_sc:{" + name + "}";
    }

    @Override
    public String acquire() throws InterruptedException {
        return acquire(1, -1, TimeUnit.MILLISECONDS);
    }

    @Override
    public String acquire(long leaseTime, TimeUnit timeUnit) throws InterruptedException {
        return acquire(1, leaseTime, timeUnit);
    }
    
    @Override
    public RFuture<String> acquireAsync(long leaseTime, TimeUnit timeUnit) {
        return acquireAsync(1, leaseTime, timeUnit);
    }

    private String acquire(int permits, long ttl, TimeUnit timeUnit) throws InterruptedException {
        String permitId = tryAcquire(permits, ttl, timeUnit);
        if (permitId != null && !permitId.startsWith(":")) {
            return permitId;
        }

        RFuture<RedissonLockEntry> future = subscribe();
        commandExecutor.syncSubscriptionInterrupted(future);
        try {
            while (true) {
                Long nearestTimeout;
                permitId = tryAcquire(permits, ttl, timeUnit);
                if (permitId != null) {
                    if (!permitId.startsWith(":")) {
                        return permitId;
                    } else {
                        nearestTimeout = Long.valueOf(permitId.substring(1)) - System.currentTimeMillis();
                    }
                } else {
                    nearestTimeout = null;
                }
                
                if (nearestTimeout != null) {
                    future.getNow().getLatch().tryAcquire(permits, nearestTimeout, TimeUnit.MILLISECONDS);
                } else {
                    future.getNow().getLatch().acquire(permits);
                }
            }
        } finally {
            unsubscribe(future);
        }
//        return get(acquireAsync(permits, ttl, timeUnit));
    }
    
    public RFuture<String> acquireAsync() {
        return acquireAsync(1, -1, TimeUnit.MILLISECONDS);
    }
    
    private RFuture<String> acquireAsync(int permits, long ttl, TimeUnit timeUnit) {
        RPromise<String> result = new RedissonPromise<String>();
        long timeoutDate = calcTimeout(ttl, timeUnit);
        RFuture<String> tryAcquireFuture = tryAcquireAsync(permits, timeoutDate);
        tryAcquireFuture.onComplete((permitId, e) -> {
            if (e != null) {
                result.tryFailure(e);
                return;
            }

            if (permitId != null && !permitId.startsWith(":")) {
                if (!result.trySuccess(permitId)) {
                    releaseAsync(permitId);
                }
                return;
            }

            RFuture<RedissonLockEntry> subscribeFuture = subscribe();
            subscribeFuture.onComplete((res, ex) -> {
                if (ex != null) {
                    result.tryFailure(ex);
                    return;
                }
                
                acquireAsync(permits, subscribeFuture, result, ttl, timeUnit);
            });
        });
        return result;
    }
    
    private void tryAcquireAsync(AtomicLong time, int permits, RFuture<RedissonLockEntry> subscribeFuture, RPromise<String> result, long ttl, TimeUnit timeUnit) {
        if (result.isDone()) {
            unsubscribe(subscribeFuture);
            return;
        }
        
        if (time.get() <= 0) {
            unsubscribe(subscribeFuture);
            result.trySuccess(null);
            return;
        }
        
        long timeoutDate = calcTimeout(ttl, timeUnit);
        long curr = System.currentTimeMillis();
        RFuture<String> tryAcquireFuture = tryAcquireAsync(permits, timeoutDate);
        tryAcquireFuture.onComplete((permitId, e) -> {
            if (e != null) {
                unsubscribe(subscribeFuture);
                result.tryFailure(e);
                return;
            }

            Long nearestTimeout;
            if (permitId != null) {
                if (!permitId.startsWith(":")) {
                    unsubscribe(subscribeFuture);
                    if (!result.trySuccess(permitId)) {
                        releaseAsync(permitId);
                    }
                    return;
                } else {
                    nearestTimeout = Long.valueOf(permitId.substring(1)) - System.currentTimeMillis();
                }
            } else {
                nearestTimeout = null;
            }

            long el = System.currentTimeMillis() - curr;
            time.addAndGet(-el);
            
            if (time.get() <= 0) {
                unsubscribe(subscribeFuture);
                result.trySuccess(null);
                return;
            }

            // waiting for message
            long current = System.currentTimeMillis();
            RedissonLockEntry entry = subscribeFuture.getNow();
            if (entry.getLatch().tryAcquire()) {
                tryAcquireAsync(time, permits, subscribeFuture, result, ttl, timeUnit);
            } else {
                AtomicReference<Timeout> waitTimeoutFutureRef = new AtomicReference<Timeout>();

                Timeout scheduledFuture;
                if (nearestTimeout != null) {
                    scheduledFuture = commandExecutor.getConnectionManager().newTimeout(new TimerTask() {
                        @Override
                        public void run(Timeout timeout) throws Exception {
                            if (waitTimeoutFutureRef.get() != null && !waitTimeoutFutureRef.get().cancel()) {
                                return;
                            }
                            
                            long elapsed = System.currentTimeMillis() - current;
                            time.addAndGet(-elapsed);

                            tryAcquireAsync(time, permits, subscribeFuture, result, ttl, timeUnit);
                        }
                    }, nearestTimeout, TimeUnit.MILLISECONDS);
                } else {
                    scheduledFuture = null;
                }

                Runnable listener = () -> {
                    if (waitTimeoutFutureRef.get() != null && !waitTimeoutFutureRef.get().cancel()) {
                        entry.getLatch().release();
                        return;
                    }
                    if (scheduledFuture != null && !scheduledFuture.cancel()) {
                        entry.getLatch().release();
                        return;
                    }
                    
                    long elapsed = System.currentTimeMillis() - current;
                    time.addAndGet(-elapsed);

                    tryAcquireAsync(time, permits, subscribeFuture, result, ttl, timeUnit);
                };
                entry.addListener(listener);

                long t = time.get();
                Timeout waitTimeoutFuture = commandExecutor.getConnectionManager().newTimeout(new TimerTask() {
                    @Override
                    public void run(Timeout timeout) throws Exception {
                        if (scheduledFuture != null && !scheduledFuture.cancel()) {
                            return;
                        }

                        if (entry.removeListener(listener)) {
                            long elapsed = System.currentTimeMillis() - current;
                            time.addAndGet(-elapsed);
                            
                            tryAcquireAsync(time, permits, subscribeFuture, result, ttl, timeUnit);
                        }
                    }
                }, t, TimeUnit.MILLISECONDS);
                waitTimeoutFutureRef.set(waitTimeoutFuture);
            }
        });
        
    }
    
    private void acquireAsync(int permits, RFuture<RedissonLockEntry> subscribeFuture, RPromise<String> result, long ttl, TimeUnit timeUnit) {
        if (result.isDone()) {
            unsubscribe(subscribeFuture);
            return;
        }
        
        long timeoutDate = calcTimeout(ttl, timeUnit);
        RFuture<String> tryAcquireFuture = tryAcquireAsync(permits, timeoutDate);
        tryAcquireFuture.onComplete((permitId, e) -> {
            if (e != null) {
                unsubscribe(subscribeFuture);
                result.tryFailure(e);
                return;
            }

            Long nearestTimeout;
            if (permitId != null) {
                if (!permitId.startsWith(":")) {
                    unsubscribe(subscribeFuture);
                    if (!result.trySuccess(permitId)) {
                        releaseAsync(permitId);
                    }
                    return;
                } else {
                    nearestTimeout = Long.valueOf(permitId.substring(1)) - System.currentTimeMillis();
                }
            } else {
                nearestTimeout = null;
            }

            RedissonLockEntry entry = subscribeFuture.getNow();
            if (entry.getLatch().tryAcquire(permits)) {
                acquireAsync(permits, subscribeFuture, result, ttl, timeUnit);
            } else {
                Timeout scheduledFuture;
                if (nearestTimeout != null) {
                    scheduledFuture = commandExecutor.getConnectionManager().newTimeout(new TimerTask() {
                        @Override
                        public void run(Timeout timeout) throws Exception {
                            acquireAsync(permits, subscribeFuture, result, ttl, timeUnit);
                        }
                    }, nearestTimeout, TimeUnit.MILLISECONDS);
                } else {
                    scheduledFuture = null;
                }
                
                Runnable listener = () -> {
                    if (scheduledFuture != null && !scheduledFuture.cancel()) {
                        entry.getLatch().release();
                        return;
                    }
                    acquireAsync(permits, subscribeFuture, result, ttl, timeUnit);
                };
                entry.addListener(listener);
            }
        });
    }

    @Override
    public String tryAcquire() {
        String res = tryAcquire(1, -1, TimeUnit.MILLISECONDS);
        if (res != null && res.startsWith(":")) {
            return null;
        }
        return res;
    }

    private String tryAcquire(int permits, long ttl, TimeUnit timeUnit) {
        long timeoutDate = calcTimeout(ttl, timeUnit);
        return get(tryAcquireAsync(permits, timeoutDate));
    }

    private long calcTimeout(long ttl, TimeUnit timeUnit) {
        if (ttl != -1) {
            return System.currentTimeMillis() + timeUnit.toMillis(ttl);
        }
        return nonExpirableTimeout;
    }
    
    public RFuture<String> tryAcquireAsync() {
        RPromise<String> result = new RedissonPromise<String>();
        RFuture<String> future = tryAcquireAsync(1, nonExpirableTimeout);
        future.onComplete((permitId, e) -> {
            if (e != null) {
                result.tryFailure(e);
                return;
            }
            
            if (permitId != null && !permitId.startsWith(":")) {
                if (!result.trySuccess(permitId)) {
                    releaseAsync(permitId);
                }
            } else {
                result.trySuccess(null);
            }
        });
        return result;
    }

    protected String generateId() {
        byte[] id = new byte[16];
        ThreadLocalRandom.current().nextBytes(id);
        return ByteBufUtil.hexDump(id);
    }
    
    public RFuture<String> tryAcquireAsync(int permits, long timeoutDate) {
        if (permits < 0) {
            throw new IllegalArgumentException("Permits amount can't be negative");
        }

        String id = generateId();
        return commandExecutor.evalWriteAsync(getName(), LongCodec.INSTANCE, RedisCommands.EVAL_STRING_DATA, 
                  "local expiredIds = redis.call('zrangebyscore', KEYS[2], 0, ARGV[4], 'limit', 0, ARGV[1]); " +
                  "if #expiredIds > 0 then " +
                      "redis.call('zrem', KEYS[2], unpack(expiredIds)); " +
                      "local value = redis.call('incrby', KEYS[1], #expiredIds); " + 
                      "if tonumber(value) > 0 then " +
                          "redis.call('publish', KEYS[3], value); " +
                      "end;" +
                  "end; " +
                  "local value = redis.call('get', KEYS[1]); " +
                  "if (value ~= false and tonumber(value) >= tonumber(ARGV[1])) then " +
                      "redis.call('decrby', KEYS[1], ARGV[1]); " +
                      "redis.call('zadd', KEYS[2], ARGV[2], ARGV[3]); " +

                      "local ttl = redis.call('pttl', KEYS[1]); " +
                      "if ttl > 0 then " +
                          "redis.call('pexpire', KEYS[2], ttl); " +
                      "end; " +

                      "return ARGV[3]; " +
                  "end; " +
                  "local v = redis.call('zrange', KEYS[2], 0, 0, 'WITHSCORES'); " + 
                  "if v[1] ~= nil and v[2] ~= ARGV[5] then " + 
                      "return ':' .. tostring(v[2]); " + 
                  "end " +
                  "return nil;",
                  Arrays.<Object>asList(getName(), timeoutName, getChannelName()), permits, timeoutDate, id, System.currentTimeMillis(), nonExpirableTimeout);
    }

    public RFuture<String> tryAcquireAsync(long waitTime, TimeUnit unit) {
        return tryAcquireAsync(1, waitTime, -1, unit);
    }
    
    @Override
    public String tryAcquire(long waitTime, long ttl, TimeUnit unit) throws InterruptedException {
        return tryAcquire(1, waitTime, ttl, unit);
    }
    
    @Override
    public RFuture<String> tryAcquireAsync(long waitTime, long ttl, TimeUnit unit) {
        return tryAcquireAsync(1, waitTime, ttl, unit);
    }

    private String tryAcquire(int permits, long waitTime, long ttl, TimeUnit unit) throws InterruptedException {
        long time = unit.toMillis(waitTime);
        long current = System.currentTimeMillis();

        String permitId = tryAcquire(permits, ttl, unit);
        if (permitId != null && !permitId.startsWith(":")) {
            return permitId;
        }

        time -= System.currentTimeMillis() - current;
        if (time <= 0) {
            return null;
        }
        
        current = System.currentTimeMillis();
        RFuture<RedissonLockEntry> future = subscribe();
        if (!future.await(time, TimeUnit.MILLISECONDS)) {
            return null;
        }

        try {
            time -= System.currentTimeMillis() - current;
            if (time <= 0) {
                return null;
            }
            
            while (true) {
                current = System.currentTimeMillis();
                Long nearestTimeout;
                permitId = tryAcquire(permits, ttl, unit);
                if (permitId != null) {
                    if (!permitId.startsWith(":")) {
                        return permitId;
                    } else {
                        nearestTimeout = Long.valueOf(permitId.substring(1)) - System.currentTimeMillis();
                    }
                } else {
                    nearestTimeout = null;
                }
                
                time -= System.currentTimeMillis() - current;
                if (time <= 0) {
                    return null;
                }

                // waiting for message
                current = System.currentTimeMillis();

                if (nearestTimeout != null) {
                    future.getNow().getLatch().tryAcquire(permits, Math.min(time, nearestTimeout), TimeUnit.MILLISECONDS);
                } else {
                    future.getNow().getLatch().tryAcquire(permits, time, TimeUnit.MILLISECONDS);
                }
                
                long elapsed = System.currentTimeMillis() - current;
                time -= elapsed;
                if (time <= 0) {
                    return null;
                }
            }
        } finally {
            unsubscribe(future);
        }
//        return get(tryAcquireAsync(permits, waitTime, ttl, unit));
    }

    private RFuture<String> tryAcquireAsync(int permits, long waitTime, long ttl, TimeUnit timeUnit) {
        RPromise<String> result = new RedissonPromise<String>();
        AtomicLong time = new AtomicLong(timeUnit.toMillis(waitTime));
        long curr = System.currentTimeMillis();
        long timeoutDate = calcTimeout(ttl, timeUnit);
        RFuture<String> tryAcquireFuture = tryAcquireAsync(permits, timeoutDate);
        tryAcquireFuture.onComplete((permitId, e) -> {
            if (e != null) {
                result.tryFailure(e);
                return;
            }

            if (permitId != null && !permitId.startsWith(":")) {
                if (!result.trySuccess(permitId)) {
                    releaseAsync(permitId);
                }
                return;
            }
            
            long el = System.currentTimeMillis() - curr;
            time.addAndGet(-el);
            
            if (time.get() <= 0) {
                result.trySuccess(null);
                return;
            }
            
            long current = System.currentTimeMillis();
            AtomicReference<Timeout> futureRef = new AtomicReference<Timeout>();
            RFuture<RedissonLockEntry> subscribeFuture = subscribe();
            subscribeFuture.onComplete((r, ex) -> {
                if (ex != null) {
                    result.tryFailure(ex);
                    return;
                }
                
                if (futureRef.get() != null) {
                    futureRef.get().cancel();
                }

                long elapsed = System.currentTimeMillis() - current;
                time.addAndGet(-elapsed);
                
                tryAcquireAsync(time, permits, subscribeFuture, result, ttl, timeUnit);
            });
            
            if (!subscribeFuture.isDone()) {
                Timeout scheduledFuture = commandExecutor.getConnectionManager().newTimeout(new TimerTask() {
                    @Override
                    public void run(Timeout timeout) throws Exception {
                        if (!subscribeFuture.isDone()) {
                            result.trySuccess(null);
                        }
                    }
                }, time.get(), TimeUnit.MILLISECONDS);
                futureRef.set(scheduledFuture);
            }
        });
        
        return result;
    }

    private RFuture<RedissonLockEntry> subscribe() {
        return semaphorePubSub.subscribe(getName(), getChannelName());
    }

    private void unsubscribe(RFuture<RedissonLockEntry> future) {
        semaphorePubSub.unsubscribe(future.getNow(), getName(), getChannelName());
    }

    @Override
    public String tryAcquire(long waitTime, TimeUnit unit) throws InterruptedException {
        String res = tryAcquire(1, waitTime, -1, unit);
        if (res != null && res.startsWith(":")) {
            return null;
        }
        return res;
    }

    @Override
    public void release(String permitId) {
        get(releaseAsync(permitId));
    }

    @Override
    public boolean tryRelease(String permitId) {
        return get(tryReleaseAsync(permitId));
    }
    
    @Override
    public RFuture<Boolean> tryReleaseAsync(String permitId) {
        if (permitId == null) {
            throw new IllegalArgumentException("permitId can't be null");
        }

        return commandExecutor.evalWriteAsync(getName(), LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                "local removed = redis.call('zrem', KEYS[3], ARGV[1]);" + 
                "if tonumber(removed) ~= 1 then " + 
                    "return 0;" + 
                "end;" +
                "local value = redis.call('incrby', KEYS[1], ARGV[2]); " +
                "redis.call('publish', KEYS[2], value); " + 
                "return 1;",
                Arrays.<Object>asList(getName(), getChannelName(), timeoutName), permitId, 1);
    }
    
    @Override
    public RFuture<Long> sizeInMemoryAsync() {
        List<Object> keys = Arrays.<Object>asList(getName(), timeoutName);
        return super.sizeInMemoryAsync(keys);
    }
    
    @Override
    public RFuture<Boolean> deleteAsync() {
        return deleteAsync(getName(), timeoutName);
    }

    @Override
    public RFuture<Boolean> expireAsync(long timeToLive, TimeUnit timeUnit) {
        return expireAsync(timeToLive, timeUnit, getName(), timeoutName);
    }

    @Override
    public RFuture<Boolean> expireAtAsync(long timestamp) {
        return expireAtAsync(timestamp, getName(), timeoutName);
    }

    @Override
    public RFuture<Boolean> clearExpireAsync() {
        return clearExpireAsync(getName(), timeoutName);
    }

    @Override
    public RFuture<Void> releaseAsync(String permitId) {
        RPromise<Void> result = new RedissonPromise<Void>();
        tryReleaseAsync(permitId).onComplete((res, e) -> {
            if (e != null) {
                result.tryFailure(e);
                return;
            }
            
            if (res) {
                result.trySuccess(null);
            } else {
                result.tryFailure(new IllegalArgumentException("Permit with id " + permitId + " has already been released or doesn't exist"));
            }
        });
        return result;
    }

    @Override
    public int availablePermits() {
        return get(availablePermitsAsync());
    }
    
    @Override
    public RFuture<Integer> availablePermitsAsync() {
        return commandExecutor.evalWriteAsync(getName(), LongCodec.INSTANCE, RedisCommands.EVAL_INTEGER, 
                "local expiredIds = redis.call('zrangebyscore', KEYS[2], 0, ARGV[1], 'limit', 0, -1); " +
                "if #expiredIds > 0 then " +
                    "redis.call('zrem', KEYS[2], unpack(expiredIds)); " +
                    "local value = redis.call('incrby', KEYS[1], #expiredIds); " + 
                    "if tonumber(value) > 0 then " +
                        "redis.call('publish', KEYS[3], value); " +
                    "end;" + 
                    "return value; " +
                "end; " +
                "local ret = redis.call('get', KEYS[1]); " + 
                "return ret == false and 0 or ret;",
                Arrays.<Object>asList(getName(), timeoutName, getChannelName()), System.currentTimeMillis());
    }

    @Override
    public boolean trySetPermits(int permits) {
        return get(trySetPermitsAsync(permits));
    }

    @Override
    public RFuture<Boolean> trySetPermitsAsync(int permits) {
        return commandExecutor.evalWriteAsync(getName(), LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                "local value = redis.call('get', KEYS[1]); " +
                "if (value == false or value == 0) then "
                    + "redis.call('set', KEYS[1], ARGV[1]); "
                    + "redis.call('publish', KEYS[2], ARGV[1]); "
                    + "return 1;"
                + "end;"
                + "return 0;",
                Arrays.<Object>asList(getName(), getChannelName()), permits);
    }

    @Override
    public void addPermits(int permits) {
        get(addPermitsAsync(permits));
    }
    
    @Override
    public RFuture<Void> addPermitsAsync(int permits) {
        return commandExecutor.evalWriteAsync(getName(), LongCodec.INSTANCE, RedisCommands.EVAL_VOID,
                "local value = redis.call('get', KEYS[1]); " +
                "if (value == false) then "
                  + "value = 0;"
              + "end;"
              + "redis.call('set', KEYS[1], tonumber(value) + tonumber(ARGV[1])); "
              + "if tonumber(ARGV[1]) > 0 then "
                  + "redis.call('publish', KEYS[2], ARGV[1]); "
              + "end;",
                Arrays.<Object>asList(getName(), getChannelName()), permits);
    }

    @Override
    public RFuture<Boolean> updateLeaseTimeAsync(String permitId, long leaseTime, TimeUnit unit) {
        long timeoutDate = calcTimeout(leaseTime, unit);
        return commandExecutor.evalWriteAsync(getName(), LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                "local expiredIds = redis.call('zrangebyscore', KEYS[2], 0, ARGV[3], 'limit', 0, -1); " +
                "if #expiredIds > 0 then " +
                    "redis.call('zrem', KEYS[2], unpack(expiredIds)); " +
                    "local value = redis.call('incrby', KEYS[1], #expiredIds); " + 
                    "if tonumber(value) > 0 then " +
                        "redis.call('publish', KEYS[3], value); " +
                    "end;" + 
                "end; " +

                  "local value = redis.call('zscore', KEYS[2], ARGV[1]); " +
                  "if (value ~= false) then "
                    + "redis.call('zadd', KEYS[2], ARGV[2], ARGV[1]); "
                    + "return 1;"
                + "end;"
                + "return 0;",
                Arrays.<Object>asList(getName(), timeoutName, getChannelName()),
                permitId, timeoutDate, System.currentTimeMillis());
    }

    @Override
    public boolean updateLeaseTime(String permitId, long leaseTime, TimeUnit unit) {
        return get(updateLeaseTimeAsync(permitId, leaseTime, unit));
    }

}
