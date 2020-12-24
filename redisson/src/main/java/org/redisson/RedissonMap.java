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

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.redisson.api.MapOptions;
import org.redisson.api.MapOptions.WriteMode;
import org.redisson.api.RCountDownLatch;
import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RSemaphore;
import org.redisson.api.RedissonClient;
import org.redisson.api.mapreduce.RMapReduce;
import org.redisson.client.RedisClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.LongCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.RedisCommand;
import org.redisson.client.protocol.RedisCommand.ValueType;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.client.protocol.convertor.NumberConvertor;
import org.redisson.client.protocol.decoder.MapScanResult;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.connection.decoder.MapGetAllDecoder;
import org.redisson.iterator.RedissonMapIterator;
import org.redisson.mapreduce.RedissonMapReduce;
import org.redisson.misc.RPromise;
import org.redisson.misc.RedissonPromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;

/**
 * Distributed and concurrent implementation of {@link java.util.concurrent.ConcurrentMap}
 * and {@link java.util.Map}
 *
 * @author Nikita Koksharov
 *
 * @param <K> key
 * @param <V> value
 */
public class RedissonMap<K, V> extends RedissonExpirable implements RMap<K, V> {

    private final Logger log = LoggerFactory.getLogger(getClass());
    
    final RedissonClient redisson;
    final MapOptions<K, V> options;
    final WriteBehindService writeBehindService;
    final MapWriteBehindTask writeBehindTask;
    
    public RedissonMap(CommandAsyncExecutor commandExecutor, String name, RedissonClient redisson, MapOptions<K, V> options, WriteBehindService writeBehindService) {
        super(commandExecutor, name);
        this.redisson = redisson;
        this.options = options;
        if (options != null && options.getWriteMode() == WriteMode.WRITE_BEHIND) {
            this.writeBehindService = writeBehindService;
            writeBehindTask = writeBehindService.start(name, options);
        } else {
            this.writeBehindService = null;
            writeBehindTask = null;
        }
    }

    public RedissonMap(Codec codec, CommandAsyncExecutor commandExecutor, String name, RedissonClient redisson, MapOptions<K, V> options, WriteBehindService writeBehindService) {
        super(codec, commandExecutor, name);
        this.redisson = redisson;
        this.options = options;
        if (options != null && options.getWriteMode() == WriteMode.WRITE_BEHIND) {
            this.writeBehindService = writeBehindService;
            writeBehindTask = writeBehindService.start(name, options);
        } else {
            this.writeBehindService = null;
            writeBehindTask = null;
        }
    }
    
    @Override
    public <KOut, VOut> RMapReduce<K, V, KOut, VOut> mapReduce() {
        return new RedissonMapReduce<>(this, redisson, commandExecutor.getConnectionManager());
    }
    
    @Override
    public RPermitExpirableSemaphore getPermitExpirableSemaphore(K key) {
        String lockName = getLockByMapKey(key, "permitexpirablesemaphore");
        return new RedissonPermitExpirableSemaphore(commandExecutor, lockName);
    }

    @Override
    public RSemaphore getSemaphore(K key) {
        String lockName = getLockByMapKey(key, "semaphore");
        return new RedissonSemaphore(commandExecutor, lockName);
    }
    
    @Override
    public RCountDownLatch getCountDownLatch(K key) {
        String lockName = getLockByMapKey(key, "countdownlatch");
        return new RedissonCountDownLatch(commandExecutor, lockName);
    }
    
    @Override
    public RLock getFairLock(K key) {
        String lockName = getLockByMapKey(key, "fairlock");
        return new RedissonFairLock(commandExecutor, lockName);
    }
    
    @Override
    public RLock getLock(K key) {
        String lockName = getLockByMapKey(key, "lock");
        return new RedissonLock(commandExecutor, lockName);
    }
    
    @Override
    public RReadWriteLock getReadWriteLock(K key) {
        String lockName = getLockByMapKey(key, "rw_lock");
        return new RedissonReadWriteLock(commandExecutor, lockName);
    }
    
    @Override
    public int size() {
        return get(sizeAsync());
    }

    @Override
    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        checkKey(key);
        checkValue(value);
        Objects.requireNonNull(remappingFunction);

        RLock lock = getLock(key);
        lock.lock();
        try {
            V oldValue = get(key);
            V newValue = value;
            if (oldValue != null) {
                newValue = remappingFunction.apply(oldValue, value);
            }

            if (newValue == null) {
                fastRemove(key);
            } else {
                fastPut(key, newValue);
            }
            return newValue;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public RFuture<V> mergeAsync(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        checkKey(key);
        checkValue(value);
        Objects.requireNonNull(remappingFunction);

        RLock lock = getLock(key);
        RPromise<V> result = new RedissonPromise<>();
        long threadId = Thread.currentThread().getId();
        lock.lockAsync(threadId).onComplete((r, e) -> {
            if (e != null) {
                result.tryFailure(e);
                return;
            }

            RFuture<V> oldValueFuture = getAsync(key);
            oldValueFuture.onComplete((oldValue, ex) -> {
                if (ex != null) {
                    lock.unlockAsync(threadId);
                    result.tryFailure(ex);
                    return;
                }

                RPromise<V> newValuePromise = new RedissonPromise<>();
                if (oldValue != null) {
                    commandExecutor.getConnectionManager().getExecutor().execute(() -> {
                        V newValue;
                        try {
                            newValue = remappingFunction.apply(oldValue, value);
                        } catch (Exception exception) {
                            lock.unlockAsync(threadId);
                            result.tryFailure(exception);
                            return;
                        }
                        newValuePromise.trySuccess(newValue);
                    });
                } else {
                    newValuePromise.trySuccess(value);
                }
                newValuePromise.onComplete((newValue, ee) -> {
                    RFuture<?> future;
                    if (newValue != null) {
                        future = fastPutAsync(key, newValue);
                    } else {
                        future = fastRemoveAsync(key);
                    }
                    future.onComplete((res, exc) -> {
                        lock.unlockAsync(threadId);
                        if (exc != null) {
                            result.tryFailure(exc);
                            return;
                        }

                        result.trySuccess(newValue);
                    });
                });
            });
        });
        return result;
    }

    @Override
    public RFuture<V> computeAsync(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        checkKey(key);
        Objects.requireNonNull(remappingFunction);

        RLock lock = getLock(key);
        RPromise<V> result = new RedissonPromise<>();
        long threadId = Thread.currentThread().getId();
        lock.lockAsync(threadId).onComplete((r, e) -> {
            if (e != null) {
                result.tryFailure(e);
                return;
            }

            RFuture<V> oldValueFuture = getAsync(key);
            oldValueFuture.onComplete((oldValue, ex) -> {
                if (ex != null) {
                    lock.unlockAsync(threadId);
                    result.tryFailure(ex);
                    return;
                }

                commandExecutor.getConnectionManager().getExecutor().execute(() -> {
                    V newValue;
                    try {
                        newValue = remappingFunction.apply(key, oldValue);
                    } catch (Exception exception) {
                        lock.unlockAsync(threadId);
                        result.tryFailure(exception);
                        return;
                    }

                    if (newValue == null) {
                        if (oldValue != null) {
                            fastRemoveAsync(key).onComplete((res, exc) -> {
                                lock.unlockAsync(threadId);
                                if (exc != null) {
                                    result.tryFailure(exc);
                                    return;
                                }

                                result.trySuccess(newValue);
                            });
                            return;
                        }
                    } else {
                        fastPutAsync(key, newValue).onComplete((res, exc) -> {
                            lock.unlockAsync(threadId);
                            if (exc != null) {
                                result.tryFailure(exc);
                                return;
                            }

                            result.trySuccess(newValue);
                        });
                        return;
                    }

                    lock.unlockAsync(threadId);
                    result.trySuccess(newValue);
                });
            });
        });
        return result;

    }

    @Override
    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        checkKey(key);
        Objects.requireNonNull(remappingFunction);

        RLock lock = getLock(key);
        lock.lock();
        try {
            V oldValue = get(key);

            V newValue = remappingFunction.apply(key, oldValue);
            if (newValue == null) {
                if (oldValue != null) {
                    fastRemove(key);
                }
            } else {
                fastPut(key, newValue);
            }
            return newValue;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public RFuture<V> computeIfAbsentAsync(K key, Function<? super K, ? extends V> mappingFunction) {
        checkKey(key);
        Objects.requireNonNull(mappingFunction);

        RLock lock = getLock(key);
        RPromise<V> result = new RedissonPromise<>();
        long threadId = Thread.currentThread().getId();
        lock.lockAsync(threadId).onComplete((r, e) -> {
            if (e != null) {
                result.tryFailure(e);
                return;
            }

            RFuture<V> oldValueFuture = getAsync(key);
            oldValueFuture.onComplete((oldValue, ex) -> {
                if (ex != null) {
                    lock.unlockAsync(threadId);
                    result.tryFailure(ex);
                    return;
                }

                if (oldValue != null) {
                    lock.unlockAsync(threadId);
                    result.trySuccess(oldValue);
                    return;
                }

                commandExecutor.getConnectionManager().getExecutor().execute(() -> {
                    V newValue;
                    try {
                        newValue = mappingFunction.apply(key);
                    } catch (Exception exception) {
                        lock.unlockAsync(threadId);
                        result.tryFailure(exception);
                        return;
                    }
                    if (newValue != null) {
                        fastPutAsync(key, newValue).onComplete((res, exc) -> {
                            lock.unlockAsync(threadId);
                            if (exc != null) {
                                result.tryFailure(exc);
                                return;
                            }

                            result.trySuccess(newValue);
                        });
                        return;
                    }

                    lock.unlockAsync(threadId);
                    result.trySuccess(null);
                });
            });
        });
        return result;
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        checkKey(key);
        Objects.requireNonNull(mappingFunction);

        RLock lock = getLock(key);
        lock.lock();
        try {
            V value = get(key);
            if (value == null) {
                V newValue = mappingFunction.apply(key);
                if (newValue != null) {
                    fastPut(key, newValue);
                    return newValue;
                }
                return null;
            }
            return value;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public RFuture<V> computeIfPresentAsync(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        checkKey(key);
        Objects.requireNonNull(remappingFunction);

        RLock lock = getLock(key);
        RPromise<V> result = new RedissonPromise<>();
        long threadId = Thread.currentThread().getId();
        lock.lockAsync(threadId).onComplete((r, e) -> {
            if (e != null) {
                result.tryFailure(e);
                return;
            }
            RFuture<V> oldValueFuture = getAsync(key);
            oldValueFuture.onComplete((oldValue, ex) -> {
                if (ex != null) {
                    lock.unlockAsync(threadId);
                    result.tryFailure(e);
                    return;
                }

                if (oldValue == null) {
                    lock.unlockAsync(threadId);
                    result.trySuccess(null);
                    return;
                }

                commandExecutor.getConnectionManager().getExecutor().execute(() -> {
                    V newValue;
                    try {
                        newValue = remappingFunction.apply(key, oldValue);
                    } catch (Exception exception) {
                        lock.unlockAsync(threadId);
                        result.tryFailure(exception);
                        return;
                    }
                    if (newValue != null) {
                        RFuture<Boolean> replaceFuture = replaceAsync(key, oldValue, newValue);
                        replaceFuture.onComplete((re, ex1) -> {
                            lock.unlockAsync(threadId);
                            if (ex1 != null) {
                                result.tryFailure(ex1);
                                return;
                            }

                            if (re) {
                                result.trySuccess(newValue);
                            } else {
                                result.trySuccess(oldValue);
                            }
                        });
                    } else if (remove(key, oldValue)) {
                        RFuture<Boolean> removeFuture = removeAsync(key, oldValue);
                        removeFuture.onComplete((re, ex1) -> {
                            lock.unlockAsync(threadId);
                            if (ex1 != null) {
                                result.tryFailure(ex1);
                                return;
                            }

                            if (re) {
                                result.trySuccess(null);
                            } else {
                                result.trySuccess(oldValue);
                            }
                        });
                    }
                });
            });
        });
        return result;
    }

    @Override
    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        checkKey(key);
        Objects.requireNonNull(remappingFunction);

        RLock lock = getLock(key);
        lock.lock();
        try {
            V oldValue = get(key);
            if (oldValue == null) {
                return null;
            }

            V newValue = remappingFunction.apply(key, oldValue);
            if (newValue != null) {
                if (replace(key, oldValue, newValue)) {
                    return newValue;
                }
            } else if (remove(key, oldValue)) {
                return null;
            }
            return oldValue;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public RFuture<Integer> sizeAsync() {
        return commandExecutor.readAsync(getName(), codec, RedisCommands.HLEN, getName());
    }

    @Override
    public int valueSize(K key) {
        return get(valueSizeAsync(key));
    }
    
    @Override
    public RFuture<Integer> valueSizeAsync(K key) {
        checkKey(key);

        String name = getName(key);
        return commandExecutor.readAsync(name, codec, RedisCommands.HSTRLEN, name, encodeMapKey(key));
    }

    protected void checkKey(Object key) {
        if (key == null) {
            throw new NullPointerException("map key can't be null");
        }
    }
    
    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public boolean containsKey(Object key) {
        return get(containsKeyAsync(key));
    }

    @Override
    public RFuture<Boolean> containsKeyAsync(Object key) {
        checkKey(key);

        String name = getName(key);
        return commandExecutor.readAsync(name, codec, RedisCommands.HEXISTS, name, encodeMapKey(key));
    }

    @Override
    public boolean containsValue(Object value) {
        return get(containsValueAsync(value));
    }

    @Override
    public RFuture<Boolean> containsValueAsync(Object value) {
        checkValue(value);
        
        return commandExecutor.evalReadAsync(getName(), codec, RedisCommands.EVAL_BOOLEAN,
                "local s = redis.call('hvals', KEYS[1]);" +
                        "for i = 1, #s, 1 do "
                            + "if ARGV[1] == s[i] then "
                                + "return 1 "
                            + "end "
                       + "end;" +
                     "return 0",
                Collections.singletonList(getName()), encodeMapValue(value));
    }

    @Override
    public Map<K, V> getAll(Set<K> keys) {
        return get(getAllAsync(keys));
    }

    @Override
    public RFuture<Map<K, V>> getAllAsync(Set<K> keys) {
        if (keys.isEmpty()) {
            return RedissonPromise.newSucceededFuture(Collections.emptyMap());
        }

        RFuture<Map<K, V>> future = getAllOperationAsync(keys);
        if (hasNoLoader()) {
            return future;
        }

        RPromise<Map<K, V>> result = new RedissonPromise<>();
        future.onComplete((res, e) -> {
            if (e != null) {
                result.tryFailure(e);
                return;
            }
            
            if (!res.keySet().containsAll(keys)) {
                Set<K> newKeys = new HashSet<K>(keys);
                newKeys.removeAll(res.keySet());
                
                loadAllAsync(newKeys, false, 1, res).onComplete((r, ex) -> {
                    result.trySuccess(res);
                });
            } else {
                result.trySuccess(res);
            }
        });
        return result;
    }

    protected boolean hasNoLoader() {
        return options == null || options.getLoader() == null;
    }

    public RFuture<Map<K, V>> getAllOperationAsync(Set<K> keys) {
        List<Object> args = new ArrayList<>(keys.size() + 1);
        args.add(getName());
        encodeMapKeys(args, keys);
        RFuture<Map<K, V>> future = commandExecutor.readAsync(getName(), codec, new RedisCommand<>("HMGET", new MapGetAllDecoder(new ArrayList<>(keys), 0), ValueType.MAP_VALUE), 
                args.toArray());
        return future;
    }
    
    @Override
    public V get(Object key) {
        return get(getAsync((K) key));
    }

    @Override
    public V put(K key, V value) {
        return get(putAsync(key, value));
    }

    @Override
    public V remove(Object key) {
        return get(removeAsync((K) key));
    }

    @Override
    public final void putAll(Map<? extends K, ? extends V> map) {
        get(putAllAsync(map));
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map, int batchSize) {
        get(putAllAsync(map, batchSize));
    }
    
    @Override
    public RFuture<Void> putAllAsync(Map<? extends K, ? extends V> map, int batchSize) {
        Map<K, V> batch = new HashMap<K, V>();
        AtomicInteger counter = new AtomicInteger();
        Iterator<Entry<K, V>> iter = ((Map<K, V>) map).entrySet().iterator();
        
        RPromise<Void> promise = new RedissonPromise<>();
        putAllAsync(batch, iter, counter, batchSize, promise);
        return promise;
    }
    
    private void putAllAsync(Map<K, V> batch, Iterator<Entry<K, V>> iter, 
                                AtomicInteger counter, int batchSize, RPromise<Void> promise) {
        batch.clear();
        
        while (iter.hasNext()) {
            Entry<K, V> entry = iter.next();
            batch.put(entry.getKey(), entry.getValue());
            counter.incrementAndGet();
            if (counter.get() % batchSize == 0) {
                RFuture<Void> future = putAllAsync(batch);
                future.onComplete((res, e) -> {
                    if (e != null) {
                        promise.tryFailure(e);
                        return;
                    }
                    
                    putAllAsync(batch, iter, counter, batchSize, promise);
                });
                return;
            }
        }
        
        if (batch.isEmpty()) {
            promise.trySuccess(null);
            return;
        }
        
        RFuture<Void> future = putAllAsync(batch);
        future.onComplete((res, e) -> {
            if (e != null) {
                promise.tryFailure(e);
                return;
            }
            
            promise.trySuccess(null);
        });
    }
    
    @Override
    public final RFuture<Void> putAllAsync(Map<? extends K, ? extends V> map) {
        if (map.isEmpty()) {
            return RedissonPromise.newSucceededFuture(null);
        }

        RFuture<Void> future = putAllOperationAsync(map);
        if (hasNoWriter()) {
            return future;
        }
        
        return mapWriterFuture(future, new MapWriterTask.Add(map));
    }

    protected final <M> RFuture<M> mapWriterFuture(RFuture<M> future, MapWriterTask task) {
        return mapWriterFuture(future, task, r -> true);
    }
    
    protected final <M> RFuture<M> mapWriterFuture(RFuture<M> future, MapWriterTask task, Function<M, Boolean> condition) {
        if (options != null && options.getWriteMode() == WriteMode.WRITE_BEHIND) {
            future.onComplete((res, e) -> {
                if (e == null && condition.apply(res)) {
                    writeBehindTask.addTask(task);
                }
            });
            return future;
        }        

        final RPromise<M> promise = new RedissonPromise<>();
        future.onComplete((res, e) -> {
            if (e != null) {
                promise.tryFailure(e);
                return;
            }

            if (condition.apply(res)) {
                commandExecutor.getConnectionManager().getExecutor().execute(() -> {
                    try {
                        if (task instanceof MapWriterTask.Add) {
                            options.getWriter().write(task.getMap());
                        } else {
                            options.getWriter().delete(task.getKeys());
                        }
                    } catch (Exception ex) {
                        promise.tryFailure(ex);
                        return;
                    }
                    promise.trySuccess(res);
                });
            } else {
                promise.trySuccess(res);
            }
        });

        return promise;
    }

    protected RFuture<Void> putAllOperationAsync(Map<? extends K, ? extends V> map) {
        List<Object> params = new ArrayList<>(map.size()*2 + 1);
        params.add(getName());
        for (java.util.Map.Entry<? extends K, ? extends V> t : map.entrySet()) {
            checkKey(t.getKey());
            checkValue(t.getValue());

            params.add(encodeMapKey(t.getKey()));
            params.add(encodeMapValue(t.getValue()));
        }

        RFuture<Void> future = commandExecutor.writeAsync(getName(), codec, RedisCommands.HMSET, params.toArray());
        return future;
    }

    @Override
    public void clear() {
        delete();
    }

    @Override
    public Set<K> keySet() {
        return keySet(null);
    }
    
    @Override
    public Set<K> keySet(String pattern) {
        return keySet(pattern, 10);
    }
    
    @Override
    public Set<K> keySet(String pattern, int count) {
        return new KeySet(pattern, count);
    }

    @Override
    public Set<K> keySet(int count) {
        return keySet(null, count);
    }

    @Override
    public Collection<V> values() {
        return values(null);
    }

    @Override
    public Collection<V> values(String keyPattern, int count) {
        return new Values(keyPattern, count);
    }
    
    @Override
    public Collection<V> values(String keyPattern) {
        return values(keyPattern, 10);
    }

    @Override
    public Collection<V> values(int count) {
        return values(null, count);
    }
    
    @Override
    public Set<java.util.Map.Entry<K, V>> entrySet() {
        return entrySet(null);
    }
    
    @Override
    public Set<java.util.Map.Entry<K, V>> entrySet(String keyPattern) {
        return entrySet(keyPattern, 10);
    }

    @Override
    public Set<java.util.Map.Entry<K, V>> entrySet(String keyPattern, int count) {
        return new EntrySet(keyPattern, count);
    }

    @Override
    public Set<java.util.Map.Entry<K, V>> entrySet(int count) {
        return entrySet(null, count);
    }
    
    @Override
    public Set<K> readAllKeySet() {
        return get(readAllKeySetAsync());
    }

    @Override
    public RFuture<Set<K>> readAllKeySetAsync() {
        return commandExecutor.readAsync(getName(), codec, RedisCommands.HKEYS, getName());
    }

    @Override
    public Collection<V> readAllValues() {
        return get(readAllValuesAsync());
    }

    @Override
    public RFuture<Collection<V>> readAllValuesAsync() {
        return commandExecutor.readAsync(getName(), codec, RedisCommands.HVALS, getName());
    }

    @Override
    public Set<Entry<K, V>> readAllEntrySet() {
        return get(readAllEntrySetAsync());
    }

    @Override
    public RFuture<Set<Entry<K, V>>> readAllEntrySetAsync() {
        return commandExecutor.readAsync(getName(), codec, RedisCommands.HGETALL_ENTRY, getName());
    }

    @Override
    public Map<K, V> readAllMap() {
        return get(readAllMapAsync());
    }

    @Override
    public RFuture<Map<K, V>> readAllMapAsync() {
        return commandExecutor.readAsync(getName(), codec, RedisCommands.HGETALL, getName());
    }

    
    @Override
    public V putIfAbsent(K key, V value) {
        return get(putIfAbsentAsync(key, value));
    }

    @Override
    public RFuture<V> putIfAbsentAsync(K key, V value) {
        checkKey(key);
        checkValue(value);
        
        RFuture<V> future = putIfAbsentOperationAsync(key, value);
        if (hasNoWriter()) {
            return future;
        }
        
        MapWriterTask.Add task = new MapWriterTask.Add(key, value);
        return mapWriterFuture(future, task, Objects::isNull);
    }

    protected boolean hasNoWriter() {
        return options == null || options.getWriter() == null;
    }

    protected RFuture<V> putIfAbsentOperationAsync(K key, V value) {
        String name = getName(key);
        return commandExecutor.evalWriteAsync(name, codec, RedisCommands.EVAL_MAP_VALUE,
                 "if redis.call('hsetnx', KEYS[1], ARGV[1], ARGV[2]) == 1 then "
                    + "return nil "
                + "else "
                    + "return redis.call('hget', KEYS[1], ARGV[1]) "
                + "end",
                Collections.singletonList(name), encodeMapKey(key), encodeMapValue(value));
    }

    @Override
    public boolean fastPutIfAbsent(K key, V value) {
        return get(fastPutIfAbsentAsync(key, value));
    }

    @Override
    public RFuture<Boolean> fastPutIfAbsentAsync(K key, V value) {
        checkKey(key);
        checkValue(value);
        
        RFuture<Boolean> future = fastPutIfAbsentOperationAsync(key, value);
        if (hasNoWriter()) {
            return future;
        }
        
        MapWriterTask.Add task = new MapWriterTask.Add(key, value);
        return mapWriterFuture(future, task, Function.identity());
    }

    protected RFuture<Boolean> fastPutIfAbsentOperationAsync(K key, V value) {
        String name = getName(key);
        return commandExecutor.writeAsync(name, codec, RedisCommands.HSETNX, name, encodeMapKey(key), encodeMapValue(value));
    }

    @Override
    public boolean remove(Object key, Object value) {
        return get(removeAsync(key, value));
    }

    @Override
    public RFuture<Boolean> removeAsync(Object key, Object value) {
        checkKey(key);
        checkValue(value);
        
        RFuture<Boolean> future = removeOperationAsync(key, value);
        if (hasNoWriter()) {
            return future;
        }
        
        MapWriterTask.Remove listener = new MapWriterTask.Remove(key);
        return mapWriterFuture(future, listener, Function.identity());
    }

    protected RFuture<Boolean> removeOperationAsync(Object key, Object value) {
        String name = getName(key);
        RFuture<Boolean> future = commandExecutor.evalWriteAsync(name, codec, RedisCommands.EVAL_BOOLEAN,
                "if redis.call('hget', KEYS[1], ARGV[1]) == ARGV[2] then "
                        + "return redis.call('hdel', KEYS[1], ARGV[1]) "
                + "else "
                    + "return 0 "
                + "end",
            Collections.singletonList(name), encodeMapKey(key), encodeMapValue(value));
        return future;
    }

    protected void checkValue(Object value) {
        if (value == null) {
            throw new NullPointerException("map value can't be null");
        }
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        return get(replaceAsync(key, oldValue, newValue));
    }

    @Override
    public RFuture<Boolean> replaceAsync(K key, V oldValue, V newValue) {
        checkKey(key);
        if (oldValue == null) {
            throw new NullPointerException("map oldValue can't be null");
        }
        if (newValue == null) {
            throw new NullPointerException("map newValue can't be null");
        }

        RFuture<Boolean> future = replaceOperationAsync(key, oldValue, newValue);
        if (hasNoWriter()) {
            return future;
        }
        
        MapWriterTask.Add task = new MapWriterTask.Add(key, newValue);
        return mapWriterFuture(future, task, Function.identity());
    }

    protected RFuture<Boolean> replaceOperationAsync(K key, V oldValue, V newValue) {
        String name = getName(key);
        return commandExecutor.evalWriteAsync(name, codec, RedisCommands.EVAL_BOOLEAN,
                "if redis.call('hget', KEYS[1], ARGV[1]) == ARGV[2] then "
                    + "redis.call('hset', KEYS[1], ARGV[1], ARGV[3]); "
                    + "return 1; "
                + "else "
                    + "return 0; "
                + "end",
                Collections.singletonList(name), encodeMapKey(key), encodeMapValue(oldValue), encodeMapValue(newValue));
    }

    @Override
    public V replace(K key, V value) {
        return get(replaceAsync(key, value));
    }

    @Override
    public RFuture<V> replaceAsync(K key, V value) {
        checkKey(key);
        checkValue(value);
        
        RFuture<V> future = replaceOperationAsync(key, value);
        if (hasNoWriter()) {
            return future;
        }
        
        MapWriterTask.Add task = new MapWriterTask.Add(key, value);
        return mapWriterFuture(future, task, r -> r != null);
    }

    protected RFuture<V> replaceOperationAsync(K key, V value) {
        String name = getName(key);
        return commandExecutor.evalWriteAsync(name, codec, RedisCommands.EVAL_MAP_VALUE,
                "if redis.call('hexists', KEYS[1], ARGV[1]) == 1 then "
                    + "local v = redis.call('hget', KEYS[1], ARGV[1]); "
                    + "redis.call('hset', KEYS[1], ARGV[1], ARGV[2]); "
                    + "return v; "
                + "else "
                    + "return nil; "
                + "end",
            Collections.singletonList(name), encodeMapKey(key), encodeMapValue(value));
    }
    
    @Override
    public boolean fastReplace(K key, V value) {
        return get(fastReplaceAsync(key, value));
    }

    @Override
    public RFuture<Boolean> fastReplaceAsync(K key, V value) {
        checkKey(key);
        checkValue(value);
        
        RFuture<Boolean> future = fastReplaceOperationAsync(key, value);
        if (hasNoWriter()) {
            return future;
        }
        
        MapWriterTask.Add task = new MapWriterTask.Add(key, value);
        return mapWriterFuture(future, task, Function.identity());
    }

    protected RFuture<Boolean> fastReplaceOperationAsync(K key, V value) {
        String name = getName(key);
        return commandExecutor.evalWriteAsync(name, codec, RedisCommands.EVAL_BOOLEAN,
                "if redis.call('hexists', KEYS[1], ARGV[1]) == 1 then "
                    + "redis.call('hset', KEYS[1], ARGV[1], ARGV[2]); "
                    + "return 1; "
                + "else "
                    + "return 0; "
                + "end",
            Collections.singletonList(name), encodeMapKey(key), encodeMapValue(value));
    }
    

    public RFuture<V> getOperationAsync(K key) {
        String name = getName(key);
        return commandExecutor.readAsync(name, codec, RedisCommands.HGET, name, encodeMapKey(key));
    }
    
    @Override
    public RFuture<V> getAsync(K key) {
        checkKey(key);

        RFuture<V> future = getOperationAsync(key);
        if (hasNoLoader()) {
            return future;
        }
        
        RPromise<V> result = new RedissonPromise<>();
        future.onComplete((res, e) -> {
            if (e != null) {
                result.tryFailure(e);
                return;
            }
            
            if (res == null) {
                loadValue(key, result, false);
            } else {
                result.trySuccess(res);
            }
        });
        return result;
    }
    
    @Override
    public void loadAll(boolean replaceExistingValues, int parallelism) {
        get(loadAllAsync(replaceExistingValues, parallelism));
    }
    
    @Override
    public RFuture<Void> loadAllAsync(boolean replaceExistingValues, int parallelism) {
        Iterable<K> keys;
        try {
            keys = options.getLoader().loadAllKeys();
        } catch (Exception e) {
            log.error("Unable to load keys for map " + getName(), e);
            return RedissonPromise.newFailedFuture(e);
        }
        return loadAllAsync(keys, replaceExistingValues, parallelism, null);
    }
    
    @Override
    public void loadAll(Set<? extends K> keys, boolean replaceExistingValues, int parallelism) {
        get(loadAllAsync(keys, replaceExistingValues, parallelism));
    }
    
    @Override
    public RFuture<Void> loadAllAsync(Set<? extends K> keys, boolean replaceExistingValues, int parallelism) {
        return loadAllAsync((Iterable<K>) keys, replaceExistingValues, parallelism, null);
    }
    
    private RFuture<Void> loadAllAsync(Iterable<? extends K> keys, boolean replaceExistingValues, int parallelism, Map<K, V> loadedEntires) {
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism can't be lower than 1");
        }

        for (K key : keys) {
            checkKey(key);
        }
 
        RPromise<Void> result = new RedissonPromise<>();
        AtomicInteger counter = new AtomicInteger();
        try {
            Iterator<? extends K> iter = keys.iterator();
            for (int i = 0; i < parallelism; i++) {
                if (!iter.hasNext()) {
                    if (counter.get() == 0) {
                        result.trySuccess(null);
                    }
                    break;
                }
                
                counter.incrementAndGet();
                K key = iter.next();
                if (replaceExistingValues) {
                    loadValue(result, counter, iter, key, loadedEntires);
                } else {
                    checkAndLoadValue(result, counter, iter, key, loadedEntires);
                }
            }
        } catch (Exception e) {
            log.error("Unable to load keys for map " + getName(), e);
            return RedissonPromise.newFailedFuture(e);
        }
        
        return result;
    }

    private void checkAndLoadValue(RPromise<Void> result, AtomicInteger counter, Iterator<? extends K> iter,
            K key, Map<K, V> loadedEntires) {
        containsKeyAsync(key).onComplete((res, e) -> {
            if (e != null) {
                result.tryFailure(e);
                return;
            }
            
            if (!res) {
                RPromise<V> promise = new RedissonPromise<>();
                promise.onComplete((r, ex) -> {
                    if (ex != null) {
                        result.tryFailure(ex);
                        return;
                    }
                    
                    if (loadedEntires != null && r != null) {
                        loadedEntires.put(key, r);
                    }
                    
                    checkAndLoadValue(result, counter, iter, loadedEntires);
                });
                loadValue(key, promise, false);
            } else {
                checkAndLoadValue(result, counter, iter, loadedEntires);
            }
        });
    }
    
    private void checkAndLoadValue(RPromise<Void> result, AtomicInteger counter, Iterator<? extends K> iter, Map<K, V> loadedEntires) {
        K key = null;
        synchronized (iter) {
            if (iter.hasNext()) {
                key = iter.next();
            }
        }
        
        if (key == null) {
            if (counter.decrementAndGet() == 0) {
                result.trySuccess(null);
            }
        } else if (!result.isDone()) {
            checkAndLoadValue(result, counter, iter, key, loadedEntires);
        }
    }
    
    private void loadValue(RPromise<Void> result, AtomicInteger counter, Iterator<? extends K> iter,
                                K k, Map<K, V> loadedEntires) {
        RPromise<V> promise = new RedissonPromise<>();
        promise.onComplete((res, e) -> {
            if (e != null) {
                result.tryFailure(e);
                return;
            }
            
            if (loadedEntires != null && res != null) {
                loadedEntires.put(k, res);
            }
            
            K key = null;
            synchronized (iter) {
                if (iter.hasNext()) {
                    key = iter.next();
                }
            }
            
            if (key == null) {
                if (counter.decrementAndGet() == 0) {
                    result.trySuccess(null);
                }
            } else if (!result.isDone()) {
                loadValue(result, counter, iter, key, loadedEntires);
            }
        });
        loadValue(k, promise, true);
    }
    
    @Override
    public RFuture<V> putAsync(K key, V value) {
        checkKey(key);
        checkValue(value);
        
        RFuture<V> future = putOperationAsync(key, value);
        if (hasNoWriter()) {
            return future;
        }
        
        return mapWriterFuture(future, new MapWriterTask.Add(key, value));
    }

    protected RFuture<V> putOperationAsync(K key, V value) {
        String name = getName(key);
        return commandExecutor.evalWriteAsync(name, codec, RedisCommands.EVAL_MAP_VALUE,
                "local v = redis.call('hget', KEYS[1], ARGV[1]); "
                + "redis.call('hset', KEYS[1], ARGV[1], ARGV[2]); "
                + "return v",
                Collections.singletonList(name), encodeMapKey(key), encodeMapValue(value));
    }

    @Override
    public RFuture<V> removeAsync(K key) {
        checkKey(key);

        RFuture<V> future = removeOperationAsync(key);
        if (hasNoWriter()) {
            return future;
        }
        
        return mapWriterFuture(future, new MapWriterTask.Remove(key));
    }

    protected RFuture<V> removeOperationAsync(K key) {
        String name = getName(key);
        return commandExecutor.evalWriteAsync(name, codec, RedisCommands.EVAL_MAP_VALUE,
                "local v = redis.call('hget', KEYS[1], ARGV[1]); "
                + "redis.call('hdel', KEYS[1], ARGV[1]); "
                + "return v",
                Collections.singletonList(name), encodeMapKey(key));
    }

    @Override
    public RFuture<Boolean> fastPutAsync(K key, V value) {
        checkKey(key);
        checkValue(value);
        
        RFuture<Boolean> future = fastPutOperationAsync(key, value);
        if (hasNoWriter()) {
            return future;
        }
        
        return mapWriterFuture(future, new MapWriterTask.Add(key, value));
    }

    protected RFuture<Boolean> fastPutOperationAsync(K key, V value) {
        String name = getName(key);
        return commandExecutor.writeAsync(name, codec, RedisCommands.HSET, name, encodeMapKey(key), encodeMapValue(value));
    }

    @Override
    public boolean fastPut(K key, V value) {
        return get(fastPutAsync(key, value));
    }

    @Override
    public RFuture<Long> fastRemoveAsync(K... keys) {
        if (keys == null) {
            throw new NullPointerException();
        }

        if (keys.length == 0) {
            return RedissonPromise.newSucceededFuture(0L);
        }

        if (hasNoWriter()) {
            return fastRemoveOperationAsync(keys);
        }

        RFuture<List<Long>> future = fastRemoveOperationBatchAsync(keys);            
        RPromise<Long> result = new RedissonPromise<>();
        future.onComplete((res, e) -> {
            if (e != null) {
                result.tryFailure(e);
                return;
            }
            
            if (res.isEmpty()) {
                result.trySuccess(0L);
                return;
            }
            
            List<K> deletedKeys = new ArrayList<K>();
            for (int i = 0; i < res.size(); i++) {
                if (res.get(i) == 1) {
                    deletedKeys.add(keys[i]);
                }
            }
            
            if (options.getWriteMode() == WriteMode.WRITE_BEHIND) {
                result.trySuccess((long) deletedKeys.size());
                
                MapWriterTask.Remove task = new MapWriterTask.Remove(deletedKeys);
                writeBehindTask.addTask(task);
            } else {
                commandExecutor.getConnectionManager().getExecutor().execute(() -> {
                    options.getWriter().delete(deletedKeys);
                    result.trySuccess((long) deletedKeys.size());
                });
            }
        });
        return result;
    }

    protected RFuture<List<Long>> fastRemoveOperationBatchAsync(K... keys) {
        List<Object> args = new ArrayList<>(keys.length);
        for (K key : keys) {
            args.add(encodeMapKey(key));
        }

        RFuture<List<Long>> future = commandExecutor.evalWriteAsync(getName(), LongCodec.INSTANCE, RedisCommands.EVAL_LIST,
                        "local result = {}; " + 
                        "for i = 1, #ARGV, 1 do " 
                        + "local val = redis.call('hdel', KEYS[1], ARGV[i]); "
                        + "table.insert(result, val); "
                      + "end;"
                      + "return result;",
                        Arrays.asList(getName()), 
                        args.toArray());
        return future;
    }

    protected RFuture<Long> fastRemoveOperationAsync(K... keys) {
        List<Object> args = new ArrayList<>(keys.length + 1);
        args.add(getName());
        for (K key : keys) {
            args.add(encodeMapKey(key));
        }
        return commandExecutor.writeAsync(getName(), codec, RedisCommands.HDEL, args.toArray());
    }

    @Override
    public long fastRemove(K... keys) {
        return get(fastRemoveAsync(keys));
    }

    public MapScanResult<Object, Object> scanIterator(String name, RedisClient client, long startPos, String pattern, int count) {
        RFuture<MapScanResult<Object, Object>> f = scanIteratorAsync(name, client, startPos, pattern, count);
        return get(f);
    }
    
    public RFuture<MapScanResult<Object, Object>> scanIteratorAsync(String name, RedisClient client, long startPos, String pattern, int count) {
        if (pattern == null) {
            RFuture<MapScanResult<Object, Object>> f 
                                    = commandExecutor.readAsync(client, name, codec, RedisCommands.HSCAN, name, startPos, "COUNT", count);
            return f;
        }
        RFuture<MapScanResult<Object, Object>> f 
                                    = commandExecutor.readAsync(client, name, codec, RedisCommands.HSCAN, name, startPos, "MATCH", pattern, "COUNT", count);
        return f;
    }

    @Override
    public V addAndGet(K key, Number value) {
        return get(addAndGetAsync(key, value));
    }

    @Override
    public RFuture<V> addAndGetAsync(K key, Number value) {
        checkKey(key);
        checkValue(value);
        
        RFuture<V> future = addAndGetOperationAsync(key, value);
        if (hasNoWriter()) {
            return future;
        }

        return mapWriterFuture(future, new MapWriterTask.Add() {
            @Override
            public Map<K, V> getMap() {
                return Collections.singletonMap(key, future.getNow());
            }
        });
    }

    protected RFuture<V> addAndGetOperationAsync(K key, Number value) {
        ByteBuf keyState = encodeMapKey(key);
        String name = getName(key);
        RFuture<V> future = commandExecutor.writeAsync(name, StringCodec.INSTANCE,
                new RedisCommand<>("HINCRBYFLOAT", new NumberConvertor(value.getClass())),
                name, keyState, new BigDecimal(value.toString()).toPlainString());
        return future;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this)
            return true;

        if (!(o instanceof Map))
            return false;
        Map<?, ?> m = (Map<?, ?>) o;
        if (m.size() != size())
            return false;

        try {
            Iterator<Entry<K, V>> i = entrySet().iterator();
            while (i.hasNext()) {
                Entry<K, V> e = i.next();
                K key = e.getKey();
                V value = e.getValue();
                if (value == null) {
                    if (!(m.get(key)==null && m.containsKey(key)))
                        return false;
                } else {
                    if (!value.equals(m.get(key)))
                        return false;
                }
            }
        } catch (ClassCastException unused) {
            return false;
        } catch (NullPointerException unused) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int h = 0;
        Iterator<Entry<K, V>> i = entrySet().iterator();
        while (i.hasNext()) {
            h += i.next().hashCode();
        }
        return h;
    }

    protected Iterator<K> keyIterator(String pattern, int count) {
        return new RedissonMapIterator<K>(RedissonMap.this, pattern, count) {
            @Override
            protected K getValue(java.util.Map.Entry<Object, Object> entry) {
                return (K) entry.getKey();
            }
        };
    }
    
    final class KeySet extends AbstractSet<K> {

        private final String pattern;
        private final int count;
        
        KeySet(String pattern, int count) {
            this.pattern = pattern;
            this.count = count;
        }

        @Override
        public boolean isEmpty() {
            return !iterator().hasNext();
        }

        @Override
        public Iterator<K> iterator() {
            return keyIterator(pattern, count);
        }

        @Override
        public boolean contains(Object o) {
            return RedissonMap.this.containsKey(o);
        }

        @Override
        public boolean remove(Object o) {
            return RedissonMap.this.fastRemove((K) o) == 1;
        }

        @Override
        public int size() {
            if (pattern != null) {
                int size = 0;
                for (K val : this) {
                    size++;
                }
                return size;
            }
            return RedissonMap.this.size();
        }

        @Override
        public void clear() {
            RedissonMap.this.clear();
        }

    }

    protected Iterator<V> valueIterator(String pattern, int count) {
        return new RedissonMapIterator<V>(RedissonMap.this, pattern, count) {
            @Override
            protected V getValue(java.util.Map.Entry<Object, Object> entry) {
                return (V) entry.getValue();
            }
        };
    }

    final class Values extends AbstractCollection<V> {

        private final String keyPattern;
        private final int count;
        
        Values(String keyPattern, int count) {
            this.keyPattern = keyPattern;
            this.count = count;
        }

        @Override
        public boolean isEmpty() {
            return !iterator().hasNext();
        }

        @Override
        public Iterator<V> iterator() {
            return valueIterator(keyPattern, count);
        }

        @Override
        public boolean contains(Object o) {
            return RedissonMap.this.containsValue(o);
        }

        @Override
        public int size() {
            if (keyPattern != null) {
                int size = 0;
                for (V val : this) {
                    size++;
                }
                return size;
            }

            return RedissonMap.this.size();
        }

        @Override
        public void clear() {
            RedissonMap.this.clear();
        }

    }

    protected Iterator<Map.Entry<K, V>> entryIterator(String pattern, int count) {
        return new RedissonMapIterator<>(RedissonMap.this, pattern, count);
    }

    private void loadValue(K key, RPromise<V> result, boolean replaceValue) {
        RLock lock = getLock(key);
        long threadId = Thread.currentThread().getId();
        lock.lockAsync(threadId).onComplete((res, e) -> {
            if (e != null) {
                lock.unlockAsync(threadId);
                result.tryFailure(e);
                return;
            }
            
            if (replaceValue) {
                loadValue(key, result, lock, threadId);
                return;
            }
            
            getOperationAsync(key).onComplete((r, ex) -> {
                if (ex != null) {
                    lock.unlockAsync(threadId);
                    result.tryFailure(ex);
                    return;
                }
                
                if (r != null) {
                    unlock(result, lock, threadId, r);
                    return;
                }
                
                loadValue(key, result, lock, threadId);
            });
        });
    }
    
    private void loadValue(K key, RPromise<V> result, RLock lock,
            long threadId) {
        commandExecutor.getConnectionManager().getExecutor().execute(new Runnable() {
            @Override
            public void run() {
                V value;
                try {
                    value = options.getLoader().load(key);
                    if (value == null) {
                        unlock(result, lock, threadId, value);
                        return;
                    }
                } catch (Exception e) {
                    log.error("Unable to load value by key " + key + " for map " + getName(), e);
                    unlock(result, lock, threadId, null);
                    return;
                }
                    
                putOperationAsync(key, value).onComplete((res, e) -> {
                    if (e != null) {
                        lock.unlockAsync(threadId);
                        result.tryFailure(e);
                        return;
                    }
                    
                    unlock(result, lock, threadId, value);
                });
            }
        });
    }

    private void unlock(RPromise<V> result, RLock lock, long threadId,
            V value) {
        lock.unlockAsync(threadId).onComplete((res, e) -> {
            if (e != null) {
                result.tryFailure(e);
                return;
            }
            
            result.trySuccess(value);
        });
    }

    final class EntrySet extends AbstractSet<Map.Entry<K, V>> {

        private final String keyPattern;
        private final int count;
        
        EntrySet(String keyPattern, int count) {
            this.keyPattern = keyPattern;
            this.count = count;
        }

        @Override
        public Iterator<Map.Entry<K, V>> iterator() {
            return entryIterator(keyPattern, count);
        }

        @Override
        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            Object key = e.getKey();
            V value = get(key);
            return value != null && value.equals(e);
        }

        @Override
        public boolean remove(Object o) {
            if (o instanceof Map.Entry) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                Object key = e.getKey();
                Object value = e.getValue();
                return RedissonMap.this.remove(key, value);
            }
            return false;
        }

        @Override
        public int size() {
            if (keyPattern != null) {
                int size = 0;
                for (Entry val : this) {
                    size++;
                }
                return size;
            }
            
            return RedissonMap.this.size();
        }

        @Override
        public void clear() {
            RedissonMap.this.clear();
        }

    }

}
