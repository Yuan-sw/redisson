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
package org.redisson.transaction;

import io.netty.buffer.ByteBuf;
import org.redisson.RedissonMultiLock;
import org.redisson.RedissonObject;
import org.redisson.RedissonSet;
import org.redisson.api.*;
import org.redisson.client.RedisClient;
import org.redisson.client.protocol.decoder.ListScanResult;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.misc.Hash;
import org.redisson.misc.HashValue;
import org.redisson.misc.RPromise;
import org.redisson.misc.RedissonPromise;
import org.redisson.transaction.operation.DeleteOperation;
import org.redisson.transaction.operation.TouchOperation;
import org.redisson.transaction.operation.TransactionalOperation;
import org.redisson.transaction.operation.UnlinkOperation;
import org.redisson.transaction.operation.set.MoveOperation;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

/**
 * 
 * @author Nikita Koksharov
 *
 * @param <V> value type
 */
public abstract class BaseTransactionalSet<V> extends BaseTransactionalObject {

    static final Object NULL = new Object();
    
    private final long timeout;
    final Map<HashValue, Object> state = new HashMap<HashValue, Object>();
    final List<TransactionalOperation> operations;
    final RCollectionAsync<V> set;
    final RObject object;
    final String name;
    final CommandAsyncExecutor commandExecutor;
    Boolean deleted;
    
    public BaseTransactionalSet(CommandAsyncExecutor commandExecutor, long timeout, List<TransactionalOperation> operations, RCollectionAsync<V> set) {
        this.commandExecutor = commandExecutor;
        this.timeout = timeout;
        this.operations = operations;
        this.set = set;
        this.object = (RObject) set;
        this.name = object.getName();
    }

    private HashValue toHash(Object value) {
        ByteBuf state = ((RedissonObject) set).encode(value);
        try {
            return new HashValue(Hash.hash128(state));
        } finally {
            state.release();
        }
    }
    
    public RFuture<Boolean> isExistsAsync() {
        if (deleted != null) {
            return RedissonPromise.newSucceededFuture(!deleted);
        }
        
        return set.isExistsAsync();
    }
    
    public RFuture<Boolean> unlinkAsync(CommandAsyncExecutor commandExecutor) {
        return deleteAsync(commandExecutor, new UnlinkOperation(name));
    }
    
    public RFuture<Boolean> touchAsync(CommandAsyncExecutor commandExecutor) {
        RPromise<Boolean> result = new RedissonPromise<Boolean>();
        if (deleted != null && deleted) {
            operations.add(new TouchOperation(name));
            result.trySuccess(false);
            return result;
        }
        
        set.isExistsAsync().onComplete((exists, e) -> {
            if (e != null) {
                result.tryFailure(e);
                return;
            }
            
            operations.add(new TouchOperation(name));
            if (!exists) {
                for (Object value : state.values()) {
                    if (value != NULL) {
                        exists = true;
                        break;
                    }
                }
            }
            result.trySuccess(exists);
        });
                
        return result;
    }

    public RFuture<Boolean> deleteAsync(CommandAsyncExecutor commandExecutor) {
        return deleteAsync(commandExecutor, new DeleteOperation(name));
    }

    protected RFuture<Boolean> deleteAsync(CommandAsyncExecutor commandExecutor, TransactionalOperation operation) {
        RPromise<Boolean> result = new RedissonPromise<Boolean>();
        if (deleted != null) {
            operations.add(operation);
            result.trySuccess(!deleted);
            deleted = true;
            return result;
        }
        
        set.isExistsAsync().onComplete((res, e) -> {
            if (e != null) {
                result.tryFailure(e);
                return;
            }
            
            operations.add(operation);
            state.replaceAll((k, v) -> NULL);
            deleted = true;
            result.trySuccess(res);
        });
        return result;
    }
    
    public RFuture<Boolean> containsAsync(Object value) {
        for (Object val : state.values()) {
            if (val != NULL && isEqual(val, value)) {
                return RedissonPromise.newSucceededFuture(true);
            }
        }
        
        return set.containsAsync(value);
    }
    
    protected abstract ListScanResult<Object> scanIteratorSource(String name, RedisClient client,
            long startPos, String pattern, int count);
    
    protected ListScanResult<Object> scanIterator(String name, RedisClient client,
            long startPos, String pattern, int count) {
        ListScanResult<Object> res = scanIteratorSource(name, client, startPos, pattern, count);
        Map<HashValue, Object> newstate = new HashMap<HashValue, Object>(state);
        for (Iterator<Object> iterator = res.getValues().iterator(); iterator.hasNext();) {
            Object entry = iterator.next();
            Object value = newstate.remove(toHash(entry));
            if (value == NULL) {
                iterator.remove();
            }
        }
        
        if (startPos == 0) {
            for (Entry<HashValue, Object> entry : newstate.entrySet()) {
                if (entry.getValue() == NULL) {
                    continue;
                }
                res.getValues().add(entry.getValue());
            }
        }
        
        return res;
    }
    
    protected abstract RFuture<Set<V>> readAllAsyncSource();
    
    public RFuture<Set<V>> readAllAsync() {
        RPromise<Set<V>> result = new RedissonPromise<Set<V>>();
        RFuture<Set<V>> future = readAllAsyncSource();
        future.onComplete((res, e) -> {
            if (e != null) {
                result.tryFailure(e);
                return;
            }
            
            Set<V> set = future.getNow();
            Map<HashValue, Object> newstate = new HashMap<HashValue, Object>(state);
            for (Iterator<V> iterator = set.iterator(); iterator.hasNext();) {
                V key = iterator.next();
                Object value = newstate.remove(toHash(key));
                if (value == NULL) {
                    iterator.remove();
                }
            }
            
            for (Object value : newstate.values()) {
                if (value == NULL) {
                    continue;
                }
                set.add((V) value);
            }
            
            result.trySuccess(set);
        });

        return result;
    }
    
    public RFuture<Boolean> addAsync(V value) {
        long threadId = Thread.currentThread().getId();
        TransactionalOperation operation = createAddOperation(value, threadId);
        return addAsync(value, operation);
    }
    
    public RFuture<Boolean> addAsync(V value, TransactionalOperation operation) {
        RPromise<Boolean> result = new RedissonPromise<Boolean>();
        executeLocked(result, value, new Runnable() {
            @Override
            public void run() {
                HashValue keyHash = toHash(value);
                Object entry = state.get(keyHash);
                if (entry != null) {
                    operations.add(operation);
                    state.put(keyHash, value);
                    if (deleted != null) {
                        deleted = false;
                    }
                    
                    result.trySuccess(entry == NULL);
                    return;
                }
                
                set.containsAsync(value).onComplete((res, e) -> {
                    if (e != null) {
                        result.tryFailure(e);
                        return;
                    }
                    
                    operations.add(operation);
                    state.put(keyHash, value);
                    if (deleted != null) {
                        deleted = false;
                    }
                    result.trySuccess(!res);
                });
            }
        });
        return result;
    }

    protected abstract TransactionalOperation createAddOperation(V value, long threadId);
    
    public RFuture<V> removeRandomAsync() {
        throw new UnsupportedOperationException();
    }
    
    public RFuture<Set<V>> removeRandomAsync(int amount) {
        throw new UnsupportedOperationException();
    }
    
    public RFuture<Boolean> moveAsync(String destination, V value) {
        RSet<V> destinationSet = new RedissonSet<V>(object.getCodec(), commandExecutor, destination, null);
        
        RPromise<Boolean> result = new RedissonPromise<Boolean>();
        RLock destinationLock = getLock(destinationSet, value);
        RLock lock = getLock(set, value);
        RedissonMultiLock multiLock = new RedissonMultiLock(destinationLock, lock);
        long threadId = Thread.currentThread().getId();
        multiLock.lockAsync(timeout, TimeUnit.MILLISECONDS).onComplete((res, e) -> {
            if (e != null) {
                multiLock.unlockAsync(threadId);
                result.tryFailure(e);
                return;
            }
            
            HashValue keyHash = toHash(value);
            Object currentValue = state.get(keyHash);
            if (currentValue != null) {
                operations.add(createMoveOperation(destination, value, threadId));
                if (currentValue == NULL) {
                    result.trySuccess(false);
                } else {
                    state.put(keyHash, NULL);
                    result.trySuccess(true);
                }
                return;
            }
            
            set.containsAsync(value).onComplete((r, ex) -> {
                if (ex != null) {
                    result.tryFailure(ex);
                    return;
                }
                
                operations.add(createMoveOperation(destination, value, threadId));
                if (r) {
                    state.put(keyHash, NULL);
                }

                result.trySuccess(r);
            });
        });
        
        return result;
    }

    protected abstract MoveOperation createMoveOperation(String destination, V value, long threadId);

    protected abstract RLock getLock(RCollectionAsync<V> set, V value);
    
    public RFuture<Boolean> removeAsync(Object value) {
        RPromise<Boolean> result = new RedissonPromise<Boolean>();
        long threadId = Thread.currentThread().getId();
        executeLocked(result, (V) value, new Runnable() {
            @Override
            public void run() {
                HashValue keyHash = toHash(value);
                Object currentValue = state.get(keyHash);
                if (currentValue != null) {
                    operations.add(createRemoveOperation(value, threadId));
                    if (currentValue == NULL) {
                        result.trySuccess(false);
                    } else {
                        state.put(keyHash, NULL);
                        result.trySuccess(true);
                    }
                    return;
                }

                set.containsAsync(value).onComplete((res, e) -> {
                    if (e != null) {
                        result.tryFailure(e);
                        return;
                    }
                    
                    operations.add(createRemoveOperation(value, threadId));
                    if (res) {
                        state.put(keyHash, NULL);
                    }

                    result.trySuccess(res);
                });
            }

        });
        return result;
        
    }

    protected abstract TransactionalOperation createRemoveOperation(Object value, long threadId);
    
    public RFuture<Boolean> containsAllAsync(Collection<?> c) {
        List<Object> coll = new ArrayList<Object>(c);
        for (Iterator<Object> iterator = coll.iterator(); iterator.hasNext();) {
            Object value = iterator.next();
            for (Object val : state.values()) {
                if (val != NULL && isEqual(val, value)) {
                    iterator.remove();
                    break;
                }
            }
        }
        
        return set.containsAllAsync(coll);
    }

    public RFuture<Boolean> addAllAsync(Collection<? extends V> c) {
        RPromise<Boolean> result = new RedissonPromise<Boolean>();
        long threadId = Thread.currentThread().getId();
        executeLocked(result, new Runnable() {
            @Override
            public void run() {
                containsAllAsync(c).onComplete((res, e) -> {
                    if (e != null) {
                        result.tryFailure(e);
                        return;
                    }

                    for (V value : c) {
                        operations.add(createAddOperation(value, threadId));
                        HashValue keyHash = toHash(value);
                        state.put(keyHash, value);
                    }
                    
                    if (deleted != null) {
                        deleted = false;
                    }
                    
                    result.trySuccess(!res);
                });
            }
        }, c);
        return result;
    }
    
    public RFuture<Boolean> retainAllAsync(Collection<?> c) {
        throw new UnsupportedOperationException();
    }
    
    public RFuture<Boolean> removeAllAsync(Collection<?> c) {
        RPromise<Boolean> result = new RedissonPromise<Boolean>();
        long threadId = Thread.currentThread().getId();
        executeLocked(result, new Runnable() {
            @Override
            public void run() {
                containsAllAsync(c).onComplete((res, e) -> {
                    if (e != null) {
                        result.tryFailure(e);
                        return;
                    }

                    for (Object value : c) {
                        operations.add(createRemoveOperation(value, threadId));
                        HashValue keyHash = toHash(value);
                        state.put(keyHash, NULL);
                    }
                    
                    result.trySuccess(!res);
                });
            }
        }, c);
        return result;
    }
    
    public RFuture<Integer> unionAsync(String... names) {
        throw new UnsupportedOperationException();
    }
    
    public RFuture<Integer> diffAsync(String... names) {
        throw new UnsupportedOperationException();
    }
    
    public RFuture<Integer> intersectionAsync(String... names) {
        throw new UnsupportedOperationException();
    }
    
    public RFuture<Set<V>> readSortAsync(SortOrder order) {
        throw new UnsupportedOperationException();
    }
    
    public RFuture<Set<V>> readSortAsync(SortOrder order, int offset, int count) {
        throw new UnsupportedOperationException();
    }
    
    public RFuture<Set<V>> readSortAsync(String byPattern, SortOrder order) {
        throw new UnsupportedOperationException();
    }

    public <T> RFuture<Collection<T>> readSortAsync(String byPattern, List<String> getPatterns, SortOrder order, int offset, int count) {
        throw new UnsupportedOperationException();
    }

    public RFuture<Set<V>> readSortAlphaAsync(SortOrder order) {
        throw new UnsupportedOperationException();
    }

    public RFuture<Set<V>> readSortAlphaAsync(SortOrder order, int offset, int count) {
        throw new UnsupportedOperationException();
    }

    public RFuture<Set<V>>  readSortAlphaAsync(String byPattern, SortOrder order) {
        throw new UnsupportedOperationException();
    }

    public RFuture<Set<V>>  readSortAlphaAsync(String byPattern, SortOrder order, int offset, int count) {
        throw new UnsupportedOperationException();
    }

    public <T> RFuture<Collection<T>> readSortAlphaAsync(String byPattern, List<String> getPatterns, SortOrder order) {
        throw new UnsupportedOperationException();
    }

    public <T> RFuture<Collection<T>> readSortAlphaAsync(String byPattern, List<String> getPatterns, SortOrder order, int offset, int count) {
        throw new UnsupportedOperationException();
    }

    public RFuture<Integer> sortToAsync(String destName, String byPattern, List<String> getPatterns, SortOrder order, int offset, int count) {
        throw new UnsupportedOperationException();
    }

    public RFuture<Set<V>> readUnionAsync(String... names) {
        throw new UnsupportedOperationException();
    }
    
    public RFuture<Set<V>> readDiffAsync(String... names) {
        throw new UnsupportedOperationException();
    }
    
    public RFuture<Set<V>> readIntersectionAsync(String... names) {
        throw new UnsupportedOperationException();
    }
    
    private boolean isEqual(Object value, Object oldValue) {
        ByteBuf valueBuf = ((RedissonObject) set).encode(value);
        ByteBuf oldValueBuf = ((RedissonObject) set).encode(oldValue);
        
        try {
            return valueBuf.equals(oldValueBuf);
        } finally {
            valueBuf.readableBytes();
            oldValueBuf.readableBytes();
        }
    }
    
    protected <R> void executeLocked(RPromise<R> promise, Object value, Runnable runnable) {
        RLock lock = getLock(set, (V) value);
        executeLocked(promise, runnable, lock);
    }

    protected <R> void executeLocked(RPromise<R> promise, Runnable runnable, RLock lock) {
        lock.lockAsync(timeout, TimeUnit.MILLISECONDS).onComplete((res, e) -> {
            if (e == null) {
                runnable.run();
            } else {
                promise.tryFailure(e);
            }
        });
    }
    
    protected <R> void executeLocked(RPromise<R> promise, Runnable runnable, Collection<?> values) {
        List<RLock> locks = new ArrayList<RLock>(values.size());
        for (Object value : values) {
            RLock lock = getLock(set, (V) value);
            locks.add(lock);
        }
        RedissonMultiLock multiLock = new RedissonMultiLock(locks.toArray(new RLock[locks.size()]));
        long threadId = Thread.currentThread().getId();
        multiLock.lockAsync(timeout, TimeUnit.MILLISECONDS).onComplete((res, e) -> {
            if (e == null) {
                runnable.run();
            } else {
                multiLock.unlockAsync(threadId);
                promise.tryFailure(e);
            }
        });
    }
    
}
