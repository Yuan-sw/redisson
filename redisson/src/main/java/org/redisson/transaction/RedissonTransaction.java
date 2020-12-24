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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.redisson.RedissonBatch;
import org.redisson.RedissonLocalCachedMap;
import org.redisson.RedissonObject;
import org.redisson.RedissonTopic;
import org.redisson.api.BatchOptions;
import org.redisson.api.BatchResult;
import org.redisson.api.RBucket;
import org.redisson.api.RBuckets;
import org.redisson.api.RFuture;
import org.redisson.api.RLocalCachedMap;
import org.redisson.api.RMap;
import org.redisson.api.RMapCache;
import org.redisson.api.RMultimapCacheAsync;
import org.redisson.api.RSet;
import org.redisson.api.RSetCache;
import org.redisson.api.RTopic;
import org.redisson.api.RTopicAsync;
import org.redisson.api.RTransaction;
import org.redisson.api.TransactionOptions;
import org.redisson.api.listener.MessageListener;
import org.redisson.cache.LocalCachedMapDisable;
import org.redisson.cache.LocalCachedMapDisabledKey;
import org.redisson.cache.LocalCachedMapEnable;
import org.redisson.cache.LocalCachedMessageCodec;
import org.redisson.client.codec.Codec;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.command.CommandBatchService;
import org.redisson.connection.MasterSlaveEntry;
import org.redisson.misc.CountableListener;
import org.redisson.misc.RPromise;
import org.redisson.misc.RedissonPromise;
import org.redisson.transaction.operation.TransactionalOperation;
import org.redisson.transaction.operation.map.MapOperation;

import io.netty.buffer.ByteBufUtil;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class RedissonTransaction implements RTransaction {

    private final CommandAsyncExecutor commandExecutor;
    private final AtomicBoolean executed = new AtomicBoolean();
    
    private final TransactionOptions options;
    private List<TransactionalOperation> operations = new CopyOnWriteArrayList<>();
    private Set<String> localCaches = new HashSet<>();
    private final long startTime = System.currentTimeMillis();
    
    private final String id = generateId();
    
    public RedissonTransaction(CommandAsyncExecutor commandExecutor, TransactionOptions options) {
        super();
        this.options = options;
        this.commandExecutor = commandExecutor;
    }
    
    public RedissonTransaction(CommandAsyncExecutor commandExecutor, TransactionOptions options,
            List<TransactionalOperation> operations, Set<String> localCaches) {
        super();
        this.commandExecutor = commandExecutor;
        this.options = options;
        this.operations = operations;
        this.localCaches = localCaches;
    }

    @Override
    public <K, V> RLocalCachedMap<K, V> getLocalCachedMap(RLocalCachedMap<K, V> fromInstance) {
        checkState();

        localCaches.add(fromInstance.getName());
        return new RedissonTransactionalLocalCachedMap<K, V>(commandExecutor,
                operations, options.getTimeout(), executed, fromInstance, id);
    }
    
    @Override
    public <V> RBucket<V> getBucket(String name) {
        checkState();
        
        return new RedissonTransactionalBucket<V>(commandExecutor, options.getTimeout(), name, operations, executed, id);
    }
    
    @Override
    public <V> RBucket<V> getBucket(String name, Codec codec) {
        checkState();

        return new RedissonTransactionalBucket<V>(codec, commandExecutor, options.getTimeout(), name, operations, executed, id);
    }

    @Override
    public RBuckets getBuckets() {
        checkState();
        
        return new RedissonTransactionalBuckets(commandExecutor, options.getTimeout(), operations, executed, id);
    }

    @Override
    public RBuckets getBuckets(Codec codec) {
        checkState();
        
        return new RedissonTransactionalBuckets(codec, commandExecutor, options.getTimeout(), operations, executed, id);
    }
    
    @Override
    public <V> RSet<V> getSet(String name) {
        checkState();
        
        return new RedissonTransactionalSet<V>(commandExecutor, name, operations, options.getTimeout(), executed, id);        
    }
    
    @Override
    public <V> RSet<V> getSet(String name, Codec codec) {
        checkState();
        
        return new RedissonTransactionalSet<V>(codec, commandExecutor, name, operations, options.getTimeout(), executed, id);
    }
    
    @Override
    public <V> RSetCache<V> getSetCache(String name) {
        checkState();
        
        return new RedissonTransactionalSetCache<V>(commandExecutor, name, operations, options.getTimeout(), executed, id);        
    }
    
    @Override
    public <V> RSetCache<V> getSetCache(String name, Codec codec) {
        checkState();
        
        return new RedissonTransactionalSetCache<V>(codec, commandExecutor, name, operations, options.getTimeout(), executed, id);
    }

    @Override
    public <K, V> RMap<K, V> getMap(String name) {
        checkState();
        
        return new RedissonTransactionalMap<K, V>(commandExecutor, name, operations, options.getTimeout(), executed, id);
    }

    @Override
    public <K, V> RMap<K, V> getMap(String name, Codec codec) {
        checkState();
        
        return new RedissonTransactionalMap<K, V>(codec, commandExecutor, name, operations, options.getTimeout(), executed, id);
    }

    @Override
    public <K, V> RMapCache<K, V> getMapCache(String name) {
        checkState();
        
        return new RedissonTransactionalMapCache<K, V>(commandExecutor, name, operations, options.getTimeout(), executed, id);
    }

    @Override
    public <K, V> RMapCache<K, V> getMapCache(String name, Codec codec) {
        checkState();
        
        return new RedissonTransactionalMapCache<K, V>(codec, commandExecutor, name, operations, options.getTimeout(), executed, id);
    }
    
    @Override
    public RFuture<Void> commitAsync() {
        checkState();
        
        checkTimeout();
        
        BatchOptions batchOptions = createOptions();
        
        CommandBatchService transactionExecutor = new CommandBatchService(commandExecutor.getConnectionManager(), batchOptions);
        for (TransactionalOperation transactionalOperation : operations) {
            transactionalOperation.commit(transactionExecutor);
        }

        String id = generateId();
        RPromise<Void> result = new RedissonPromise<Void>();
        RFuture<Map<HashKey, HashValue>> future = disableLocalCacheAsync(id, localCaches, operations);
        future.onComplete((res, ex) -> {
            if (ex != null) {
                result.tryFailure(new TransactionException("Unable to execute transaction", ex));
                return;
            }
            
            Map<HashKey, HashValue> hashes = future.getNow();
            try {
                checkTimeout();
            } catch (TransactionTimeoutException e) {
                enableLocalCacheAsync(id, hashes);
                result.tryFailure(e);
                return;
            }
                            
            RFuture<BatchResult<?>> transactionFuture = transactionExecutor.executeAsync();
            transactionFuture.onComplete((r, exc) -> {
                if (exc != null) {
                    result.tryFailure(new TransactionException("Unable to execute transaction", exc));
                    return;
                }
                
                enableLocalCacheAsync(id, hashes);
                executed.set(true);
                
                result.trySuccess(null);
            });
        });
        return result;
    }

    private BatchOptions createOptions() {
        MasterSlaveEntry entry = commandExecutor.getConnectionManager().getEntrySet().iterator().next();
        int syncSlaves = entry.getAvailableSlaves();

        BatchOptions batchOptions = BatchOptions.defaults()
                .syncSlaves(syncSlaves, options.getSyncTimeout(), TimeUnit.MILLISECONDS)
                .responseTimeout(options.getResponseTimeout(), TimeUnit.MILLISECONDS)
                .retryAttempts(options.getRetryAttempts())
                .retryInterval(options.getRetryInterval(), TimeUnit.MILLISECONDS)
                .executionMode(BatchOptions.ExecutionMode.IN_MEMORY_ATOMIC);
        return batchOptions;
    }

    @Override
    public void commit() {
        commit(localCaches, operations);
    }
    
    public void commit(Set<String> localCaches, List<TransactionalOperation> operations) {
        checkState();
        
        checkTimeout();
        
        BatchOptions batchOptions = createOptions();
        
        CommandBatchService transactionExecutor = new CommandBatchService(commandExecutor.getConnectionManager(), batchOptions);
        for (TransactionalOperation transactionalOperation : operations) {
            transactionalOperation.commit(transactionExecutor);
        }

        String id = generateId();
        Map<HashKey, HashValue> hashes = disableLocalCache(id, localCaches, operations);
        
        try {
            checkTimeout();
        } catch (TransactionTimeoutException e) {
            enableLocalCache(id, hashes);
            throw e;
        }

        try {
            
            transactionExecutor.execute();
        } catch (Exception e) {
            throw new TransactionException("Unable to execute transaction", e);
        }
        
        enableLocalCache(id, hashes);
        
        executed.set(true);
    }

    private void checkTimeout() {
        if (options.getTimeout() != -1 && System.currentTimeMillis() - startTime > options.getTimeout()) {
            rollbackAsync();
            throw new TransactionTimeoutException("Transaction was discarded due to timeout " + options.getTimeout() + " milliseconds");
        }
    }

    private RFuture<BatchResult<?>> enableLocalCacheAsync(String requestId, Map<HashKey, HashValue> hashes) {
        if (hashes.isEmpty()) {
            return RedissonPromise.newSucceededFuture(null);
        }
        
        RedissonBatch publishBatch = createBatch();
        for (Entry<HashKey, HashValue> entry : hashes.entrySet()) {
            String name = RedissonObject.suffixName(entry.getKey().getName(), RedissonLocalCachedMap.TOPIC_SUFFIX);
            RTopicAsync topic = publishBatch.getTopic(name, LocalCachedMessageCodec.INSTANCE);
            LocalCachedMapEnable msg = new LocalCachedMapEnable(requestId, entry.getValue().getKeyIds().toArray(new byte[entry.getValue().getKeyIds().size()][]));
            topic.publishAsync(msg);
        }
        
        return publishBatch.executeAsync();
    }
    
    private void enableLocalCache(String requestId, Map<HashKey, HashValue> hashes) {
        if (hashes.isEmpty()) {
            return;
        }
        
        RedissonBatch publishBatch = createBatch();
        for (Entry<HashKey, HashValue> entry : hashes.entrySet()) {
            String name = RedissonObject.suffixName(entry.getKey().getName(), RedissonLocalCachedMap.TOPIC_SUFFIX);
            RTopicAsync topic = publishBatch.getTopic(name, LocalCachedMessageCodec.INSTANCE);
            LocalCachedMapEnable msg = new LocalCachedMapEnable(requestId, entry.getValue().getKeyIds().toArray(new byte[entry.getValue().getKeyIds().size()][]));
            topic.publishAsync(msg);
        }
        
        try {
            publishBatch.execute();
        } catch (Exception e) {
            // skip it. Disabled local cache entries are enabled once reach timeout.
        }
    }
    
    private Map<HashKey, HashValue> disableLocalCache(String requestId, Set<String> localCaches, List<TransactionalOperation> operations) {
        if (localCaches.isEmpty()) {
            return Collections.emptyMap();
        }
        
        Map<HashKey, HashValue> hashes = new HashMap<>(localCaches.size());
        RedissonBatch batch = createBatch();
        for (TransactionalOperation transactionalOperation : operations) {
            if (localCaches.contains(transactionalOperation.getName())) {
                MapOperation mapOperation = (MapOperation) transactionalOperation;
                RedissonLocalCachedMap<?, ?> map = (RedissonLocalCachedMap<?, ?>) mapOperation.getMap();
                
                HashKey hashKey = new HashKey(transactionalOperation.getName(), transactionalOperation.getCodec());
                byte[] key = map.getLocalCacheView().toCacheKey(mapOperation.getKey()).getKeyHash();
                HashValue value = hashes.get(hashKey);
                if (value == null) {
                    value = new HashValue();
                    hashes.put(hashKey, value);
                }
                value.getKeyIds().add(key);

                String disabledKeysName = RedissonObject.suffixName(transactionalOperation.getName(), RedissonLocalCachedMap.DISABLED_KEYS_SUFFIX);
                RMultimapCacheAsync<LocalCachedMapDisabledKey, String> multimap = batch.getListMultimapCache(disabledKeysName, transactionalOperation.getCodec());
                LocalCachedMapDisabledKey localCacheKey = new LocalCachedMapDisabledKey(requestId, options.getResponseTimeout());
                multimap.putAsync(localCacheKey, ByteBufUtil.hexDump(key));
                multimap.expireKeyAsync(localCacheKey, options.getResponseTimeout(), TimeUnit.MILLISECONDS);
            }
        }

        try {
            batch.execute();
        } catch (Exception e) {
            throw new TransactionException("Unable to execute transaction over local cached map objects: " + localCaches, e);
        }
        
        CountDownLatch latch = new CountDownLatch(hashes.size());
        List<RTopic> topics = new ArrayList<>();
        for (Entry<HashKey, HashValue> entry : hashes.entrySet()) {
            RTopic topic = new RedissonTopic(LocalCachedMessageCodec.INSTANCE, 
                    commandExecutor, RedissonObject.suffixName(entry.getKey().getName(), requestId + RedissonLocalCachedMap.DISABLED_ACK_SUFFIX));
            topics.add(topic);
            topic.addListener(Object.class, new MessageListener<Object>() {
                @Override
                public void onMessage(CharSequence channel, Object msg) {
                    AtomicInteger counter = entry.getValue().getCounter();
                    if (counter.decrementAndGet() == 0) {
                        latch.countDown();
                    }
                }
            });
        }
        
        RedissonBatch publishBatch = createBatch();
        for (Entry<HashKey, HashValue> entry : hashes.entrySet()) {
            String disabledKeysName = RedissonObject.suffixName(entry.getKey().getName(), RedissonLocalCachedMap.DISABLED_KEYS_SUFFIX);
            RMultimapCacheAsync<LocalCachedMapDisabledKey, String> multimap = publishBatch.getListMultimapCache(disabledKeysName, entry.getKey().getCodec());
            LocalCachedMapDisabledKey localCacheKey = new LocalCachedMapDisabledKey(requestId, options.getResponseTimeout());
            multimap.removeAllAsync(localCacheKey);
            
            RTopicAsync topic = publishBatch.getTopic(RedissonObject.suffixName(entry.getKey().getName(), RedissonLocalCachedMap.TOPIC_SUFFIX), LocalCachedMessageCodec.INSTANCE);
            RFuture<Long> future = topic.publishAsync(new LocalCachedMapDisable(requestId, 
                    entry.getValue().getKeyIds().toArray(new byte[entry.getValue().getKeyIds().size()][]), options.getResponseTimeout()));
            future.onComplete((res, e) -> {
                if (e != null) {
                    return;
                }
                
                int receivers = res.intValue();
                AtomicInteger counter = entry.getValue().getCounter();
                if (counter.addAndGet(receivers) == 0) {
                    latch.countDown();
                }
            });
        }

        try {
            publishBatch.execute();
        } catch (Exception e) {
            throw new TransactionException("Unable to execute transaction over local cached map objects: " + localCaches, e);
        }
        
        for (RTopic topic : topics) {
            topic.removeAllListeners();
        }
        
        try {
            latch.await(options.getResponseTimeout(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return hashes;
    }
    
    private RFuture<Map<HashKey, HashValue>> disableLocalCacheAsync(String requestId, Set<String> localCaches, List<TransactionalOperation> operations) {
        if (localCaches.isEmpty()) {
            return RedissonPromise.newSucceededFuture(Collections.emptyMap());
        }
        
        RPromise<Map<HashKey, HashValue>> result = new RedissonPromise<>();
        Map<HashKey, HashValue> hashes = new HashMap<>(localCaches.size());
        RedissonBatch batch = createBatch();
        for (TransactionalOperation transactionalOperation : operations) {
            if (localCaches.contains(transactionalOperation.getName())) {
                MapOperation mapOperation = (MapOperation) transactionalOperation;
                RedissonLocalCachedMap<?, ?> map = (RedissonLocalCachedMap<?, ?>) mapOperation.getMap();
                
                HashKey hashKey = new HashKey(transactionalOperation.getName(), transactionalOperation.getCodec());
                byte[] key = map.getLocalCacheView().toCacheKey(mapOperation.getKey()).getKeyHash();
                HashValue value = hashes.get(hashKey);
                if (value == null) {
                    value = new HashValue();
                    hashes.put(hashKey, value);
                }
                value.getKeyIds().add(key);

                String disabledKeysName = RedissonObject.suffixName(transactionalOperation.getName(), RedissonLocalCachedMap.DISABLED_KEYS_SUFFIX);
                RMultimapCacheAsync<LocalCachedMapDisabledKey, String> multimap = batch.getListMultimapCache(disabledKeysName, transactionalOperation.getCodec());
                LocalCachedMapDisabledKey localCacheKey = new LocalCachedMapDisabledKey(requestId, options.getResponseTimeout());
                multimap.putAsync(localCacheKey, ByteBufUtil.hexDump(key));
                multimap.expireKeyAsync(localCacheKey, options.getResponseTimeout(), TimeUnit.MILLISECONDS);
            }
        }

        RFuture<BatchResult<?>> batchListener = batch.executeAsync();
        batchListener.onComplete((res, e) -> {
                if (e != null) {
                    result.tryFailure(e);
                    return;
                }
                
                CountableListener<Map<HashKey, HashValue>> listener = 
                                new CountableListener<>(result, hashes, hashes.size());
                RPromise<Void> subscriptionFuture = new RedissonPromise<>();
                CountableListener<Void> subscribedFutures = new CountableListener<>(subscriptionFuture, null, hashes.size());
                
                List<RTopic> topics = new ArrayList<>();
                for (Entry<HashKey, HashValue> entry : hashes.entrySet()) {
                    String disabledAckName = RedissonObject.suffixName(entry.getKey().getName(), requestId + RedissonLocalCachedMap.DISABLED_ACK_SUFFIX);
                    RTopic topic = new RedissonTopic(LocalCachedMessageCodec.INSTANCE, 
                            commandExecutor, disabledAckName);
                    topics.add(topic);
                    RFuture<Integer> topicFuture = topic.addListenerAsync(Object.class, new MessageListener<Object>() {
                        @Override
                        public void onMessage(CharSequence channel, Object msg) {
                            AtomicInteger counter = entry.getValue().getCounter();
                            if (counter.decrementAndGet() == 0) {
                                listener.decCounter();
                            }
                        }
                    });
                    topicFuture.onComplete((r, ex) -> {
                        subscribedFutures.decCounter();
                    });
                }
                
                subscriptionFuture.onComplete((r, ex) -> {
                        RedissonBatch publishBatch = createBatch();
                        for (Entry<HashKey, HashValue> entry : hashes.entrySet()) {
                            String disabledKeysName = RedissonObject.suffixName(entry.getKey().getName(), RedissonLocalCachedMap.DISABLED_KEYS_SUFFIX);
                            RMultimapCacheAsync<LocalCachedMapDisabledKey, String> multimap = publishBatch.getListMultimapCache(disabledKeysName, entry.getKey().getCodec());
                            LocalCachedMapDisabledKey localCacheKey = new LocalCachedMapDisabledKey(requestId, options.getResponseTimeout());
                            multimap.removeAllAsync(localCacheKey);
                            
                            RTopicAsync topic = publishBatch.getTopic(RedissonObject.suffixName(entry.getKey().getName(), RedissonLocalCachedMap.TOPIC_SUFFIX), LocalCachedMessageCodec.INSTANCE);
                            RFuture<Long> publishFuture = topic.publishAsync(new LocalCachedMapDisable(requestId, 
                                    entry.getValue().getKeyIds().toArray(new byte[entry.getValue().getKeyIds().size()][]), options.getResponseTimeout()));
                            publishFuture.onComplete((receivers, exc) -> {
                                if (ex != null) {
                                    return;
                                }
                                
                                AtomicInteger counter = entry.getValue().getCounter();
                                if (counter.addAndGet(receivers.intValue()) == 0) {
                                    listener.decCounter();
                                }
                            });
                        }
                        
                        RFuture<BatchResult<?>> publishFuture = publishBatch.executeAsync();
                        publishFuture.onComplete((res2, ex2) -> {
                            result.onComplete((res3, ex3) -> {
                                for (RTopic topic : topics) {
                                    topic.removeAllListeners();
                                }
                            });
                            
                            if (ex2 != null) {
                                result.tryFailure(ex2);
                                return;
                            }
                            
                            commandExecutor.getConnectionManager().newTimeout(new TimerTask() {
                                @Override
                                public void run(Timeout timeout) throws Exception {
                                    result.tryFailure(new TransactionTimeoutException("Unable to execute transaction within " + options.getResponseTimeout() + "ms"));
                                }
                            }, options.getResponseTimeout(), TimeUnit.MILLISECONDS);
                        });
                });
        });
        
        return result;
    }

    private RedissonBatch createBatch() {
        return new RedissonBatch(null, commandExecutor.getConnectionManager(),
                                    BatchOptions.defaults().executionMode(BatchOptions.ExecutionMode.IN_MEMORY_ATOMIC));
    }

    protected static String generateId() {
        byte[] id = new byte[16];
        ThreadLocalRandom.current().nextBytes(id);
        return ByteBufUtil.hexDump(id);
    }

    @Override
    public void rollback() {
        rollback(operations);
    }
    
    public void rollback(List<TransactionalOperation> operations) {
        checkState();

        CommandBatchService executorService = new CommandBatchService(commandExecutor.getConnectionManager());
        for (TransactionalOperation transactionalOperation : operations) {
            transactionalOperation.rollback(executorService);
        }

        try {
            executorService.execute();
        } catch (Exception e) {
            throw new TransactionException("Unable to rollback transaction", e);
        }

        operations.clear();
        executed.set(true);
    }
    
    @Override
    public RFuture<Void> rollbackAsync() {
        checkState();

        CommandBatchService executorService = new CommandBatchService(commandExecutor.getConnectionManager());
        for (TransactionalOperation transactionalOperation : operations) {
            transactionalOperation.rollback(executorService);
        }

        RPromise<Void> result = new RedissonPromise<>();
        RFuture<BatchResult<?>> future = executorService.executeAsync();
        future.onComplete((res, e) -> {
            if (e != null) {
                result.tryFailure(new TransactionException("Unable to rollback transaction", e));
                return;
            }
            
            operations.clear();
            executed.set(true);
            result.trySuccess(null);
        });
        return result;
    }
    
    public Set<String> getLocalCaches() {
        return localCaches;
    }
    
    public List<TransactionalOperation> getOperations() {
        return operations;
    }

    protected void checkState() {
        if (executed.get()) {
            throw new IllegalStateException("Unable to execute operation. Transaction was finished!");
        }
    }
    
}
