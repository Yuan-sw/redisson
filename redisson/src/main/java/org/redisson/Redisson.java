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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.redisson.api.*;
import org.redisson.api.redisnode.*;
import org.redisson.client.codec.Codec;
import org.redisson.command.CommandExecutor;
import org.redisson.config.Config;
import org.redisson.config.ConfigSupport;
import org.redisson.connection.ConnectionManager;
import org.redisson.eviction.EvictionScheduler;
import org.redisson.redisnode.RedissonClusterNodes;
import org.redisson.redisnode.RedissonMasterSlaveNodes;
import org.redisson.redisnode.RedissonSentinelMasterSlaveNodes;
import org.redisson.redisnode.RedissonSingleNode;
import org.redisson.remote.ResponseEntry;
import org.redisson.transaction.RedissonTransaction;

/**
 * Main infrastructure class allows to get access
 * to all Redisson objects on top of Redis server.
 *
 * @author Nikita Koksharov
 *
 */
public class Redisson implements RedissonClient {

    static {
        RedissonReference.warmUp();
    }

    protected final QueueTransferService queueTransferService = new QueueTransferService();
    protected final EvictionScheduler evictionScheduler;
    protected final WriteBehindService writeBehindService;
    protected final ConnectionManager connectionManager;

    protected final ConcurrentMap<Class<?>, Class<?>> liveObjectClassCache = new ConcurrentHashMap<>();
    protected final Config config;

    protected final ConcurrentMap<String, ResponseEntry> responses = new ConcurrentHashMap<>();

    protected Redisson(Config config) {
        this.config = config;
        Config configCopy = new Config(config);

        connectionManager = ConfigSupport.createConnectionManager(configCopy);
        evictionScheduler = new EvictionScheduler(connectionManager.getCommandExecutor());
        writeBehindService = new WriteBehindService(connectionManager.getCommandExecutor());
    }

    public EvictionScheduler getEvictionScheduler() {
        return evictionScheduler;
    }

    public CommandExecutor getCommandExecutor() {
        return connectionManager.getCommandExecutor();
    }

    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }

    /**
     * Create sync/async Redisson instance with default config
     *
     * @return Redisson instance
     */
    public static RedissonClient create() {
        Config config = new Config();
        config.useSingleServer()
        .setTimeout(1000000)
        .setAddress("redis://127.0.0.1:6379");
//        config.useMasterSlaveConnection().setMasterAddress("127.0.0.1:6379").addSlaveAddress("127.0.0.1:6389").addSlaveAddress("127.0.0.1:6399");
//        config.useSentinelConnection().setMasterName("mymaster").addSentinelAddress("127.0.0.1:26389", "127.0.0.1:26379");
//        config.useClusterServers().addNodeAddress("127.0.0.1:7000");
        return create(config);
    }

    /**
     * Create sync/async Redisson instance with provided config
     *
     * @param config for Redisson
     * @return Redisson instance
     */
    public static RedissonClient create(Config config) {
        Redisson redisson = new Redisson(config);
        if (config.isReferenceEnabled()) {
            redisson.enableRedissonReferenceSupport();
        }
        return redisson;
    }

    /**
     * Create Reactive Redisson instance with default config
     *
     * @return Redisson instance
     */
    public static RedissonRxClient createRx() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        return createRx(config);
    }

    /**
     * Create Reactive Redisson instance with provided config
     *
     * @param config for Redisson
     * @return Redisson instance
     */
    public static RedissonRxClient createRx(Config config) {
        RedissonRx react = new RedissonRx(config);
        if (config.isReferenceEnabled()) {
            react.enableRedissonReferenceSupport();
        }
        return react;
    }

    
    /**
     * Create Reactive Redisson instance with default config
     *
     * @return Redisson instance
     */
    public static RedissonReactiveClient createReactive() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        return createReactive(config);
    }

    /**
     * Create Reactive Redisson instance with provided config
     *
     * @param config for Redisson
     * @return Redisson instance
     */
    public static RedissonReactiveClient createReactive(Config config) {
        RedissonReactive react = new RedissonReactive(config);
        if (config.isReferenceEnabled()) {
            react.enableRedissonReferenceSupport();
        }
        return react;
    }

    @Override
    public <V> RTimeSeries<V> getTimeSeries(String name) {
        return new RedissonTimeSeries<>(evictionScheduler, connectionManager.getCommandExecutor(), name);
    }

    @Override
    public <V> RTimeSeries<V> getTimeSeries(String name, Codec codec) {
        return new RedissonTimeSeries<>(codec, evictionScheduler, connectionManager.getCommandExecutor(), name);
    }

    @Override
    public <K, V> RStream<K, V> getStream(String name) {
        return new RedissonStream<K, V>(connectionManager.getCommandExecutor(), name);
    }

    @Override
    public <K, V> RStream<K, V> getStream(String name, Codec codec) {
        return new RedissonStream<K, V>(codec, connectionManager.getCommandExecutor(), name);
    }

    @Override
    public RBinaryStream getBinaryStream(String name) {
        return new RedissonBinaryStream(connectionManager.getCommandExecutor(), name);
    }

    @Override
    public <V> RGeo<V> getGeo(String name) {
        return new RedissonGeo<V>(connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RGeo<V> getGeo(String name, Codec codec) {
        return new RedissonGeo<V>(codec, connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RBucket<V> getBucket(String name) {
        return new RedissonBucket<V>(connectionManager.getCommandExecutor(), name);
    }

    @Override
    public RRateLimiter getRateLimiter(String name) {
        return new RedissonRateLimiter(connectionManager.getCommandExecutor(), name);
    }

    @Override
    public <V> RBucket<V> getBucket(String name, Codec codec) {
        return new RedissonBucket<V>(codec, connectionManager.getCommandExecutor(), name);
    }

    @Override
    public RBuckets getBuckets() {
        return new RedissonBuckets(connectionManager.getCommandExecutor());
    }

    @Override
    public RBuckets getBuckets(Codec codec) {
        return new RedissonBuckets(codec, connectionManager.getCommandExecutor());
    }

    @Override
    public <V> RHyperLogLog<V> getHyperLogLog(String name) {
        return new RedissonHyperLogLog<V>(connectionManager.getCommandExecutor(), name);
    }

    @Override
    public <V> RHyperLogLog<V> getHyperLogLog(String name, Codec codec) {
        return new RedissonHyperLogLog<V>(codec, connectionManager.getCommandExecutor(), name);
    }

    @Override
    public <V> RList<V> getList(String name) {
        return new RedissonList<V>(connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RList<V> getList(String name, Codec codec) {
        return new RedissonList<V>(codec, connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <K, V> RListMultimap<K, V> getListMultimap(String name) {
        return new RedissonListMultimap<K, V>(connectionManager.getCommandExecutor(), name);
    }

    @Override
    public <K, V> RListMultimap<K, V> getListMultimap(String name, Codec codec) {
        return new RedissonListMultimap<K, V>(codec, connectionManager.getCommandExecutor(), name);
    }

    @Override
    public <K, V> RLocalCachedMap<K, V> getLocalCachedMap(String name, LocalCachedMapOptions<K, V> options) {
        return new RedissonLocalCachedMap<K, V>(connectionManager.getCommandExecutor(), name, 
                options, evictionScheduler, this, writeBehindService);
    }

    @Override
    public <K, V> RLocalCachedMap<K, V> getLocalCachedMap(String name, Codec codec, LocalCachedMapOptions<K, V> options) {
        return new RedissonLocalCachedMap<K, V>(codec, connectionManager.getCommandExecutor(), name, 
                options, evictionScheduler, this, writeBehindService);
    }

    @Override
    public <K, V> RMap<K, V> getMap(String name) {
        return new RedissonMap<K, V>(connectionManager.getCommandExecutor(), name, this, null, null);
    }

    @Override
    public <K, V> RMap<K, V> getMap(String name, MapOptions<K, V> options) {
        return new RedissonMap<K, V>(connectionManager.getCommandExecutor(), name, this, options, writeBehindService);
    }

    @Override
    public <K, V> RSetMultimap<K, V> getSetMultimap(String name) {
        return new RedissonSetMultimap<K, V>(connectionManager.getCommandExecutor(), name);
    }

    @Override
    public <K, V> RSetMultimapCache<K, V> getSetMultimapCache(String name) {
        return new RedissonSetMultimapCache<K, V>(evictionScheduler, connectionManager.getCommandExecutor(), name);
    }

    @Override
    public <K, V> RSetMultimapCache<K, V> getSetMultimapCache(String name, Codec codec) {
        return new RedissonSetMultimapCache<K, V>(evictionScheduler, codec, connectionManager.getCommandExecutor(), name);
    }

    @Override
    public <K, V> RListMultimapCache<K, V> getListMultimapCache(String name) {
        return new RedissonListMultimapCache<K, V>(evictionScheduler, connectionManager.getCommandExecutor(), name);
    }

    @Override
    public <K, V> RListMultimapCache<K, V> getListMultimapCache(String name, Codec codec) {
        return new RedissonListMultimapCache<K, V>(evictionScheduler, codec, connectionManager.getCommandExecutor(), name);
    }

    @Override
    public <K, V> RSetMultimap<K, V> getSetMultimap(String name, Codec codec) {
        return new RedissonSetMultimap<K, V>(codec, connectionManager.getCommandExecutor(), name);
    }

    @Override
    public <V> RSetCache<V> getSetCache(String name) {
        return new RedissonSetCache<V>(evictionScheduler, connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RSetCache<V> getSetCache(String name, Codec codec) {
        return new RedissonSetCache<V>(codec, evictionScheduler, connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <K, V> RMapCache<K, V> getMapCache(String name) {
        return new RedissonMapCache<K, V>(evictionScheduler, connectionManager.getCommandExecutor(), name, this, null, null);
    }

    @Override
    public <K, V> RMapCache<K, V> getMapCache(String name, MapOptions<K, V> options) {
        return new RedissonMapCache<K, V>(evictionScheduler, connectionManager.getCommandExecutor(), name, this, options, writeBehindService);
    }

    @Override
    public <K, V> RMapCache<K, V> getMapCache(String name, Codec codec) {
        return new RedissonMapCache<K, V>(codec, evictionScheduler, connectionManager.getCommandExecutor(), name, this, null, null);
    }

    @Override
    public <K, V> RMapCache<K, V> getMapCache(String name, Codec codec, MapOptions<K, V> options) {
        return new RedissonMapCache<K, V>(codec, evictionScheduler, connectionManager.getCommandExecutor(), name, this, options, writeBehindService);
    }

    @Override
    public <K, V> RMap<K, V> getMap(String name, Codec codec) {
        return new RedissonMap<K, V>(codec, connectionManager.getCommandExecutor(), name, this, null, null);
    }

    @Override
    public <K, V> RMap<K, V> getMap(String name, Codec codec, MapOptions<K, V> options) {
        return new RedissonMap<K, V>(codec, connectionManager.getCommandExecutor(), name, this, options, writeBehindService);
    }

    @Override
    public RLock getLock(String name) {
        return new RedissonLock(connectionManager.getCommandExecutor(), name);
    }
    
    @Override
    public RLock getMultiLock(RLock... locks) {
        return new RedissonMultiLock(locks);
    }
    
    @Override
    public RLock getRedLock(RLock... locks) {
        return new RedissonRedLock(locks);
    }

    @Override
    public RLock getFairLock(String name) {
        return new RedissonFairLock(connectionManager.getCommandExecutor(), name);
    }

    @Override
    public RReadWriteLock getReadWriteLock(String name) {
        return new RedissonReadWriteLock(connectionManager.getCommandExecutor(), name);
    }

    @Override
    public <V> RSet<V> getSet(String name) {
        return new RedissonSet<V>(connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RSet<V> getSet(String name, Codec codec) {
        return new RedissonSet<V>(codec, connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public RScript getScript() {
        return new RedissonScript(connectionManager.getCommandExecutor());
    }
    
    @Override
    public RScript getScript(Codec codec) {
        return new RedissonScript(connectionManager.getCommandExecutor(), codec);
    }

    @Override
    public RScheduledExecutorService getExecutorService(String name) {
        return getExecutorService(name, connectionManager.getCodec());
    }

    @Override
    public RScheduledExecutorService getExecutorService(String name, ExecutorOptions options) {
        return getExecutorService(name, connectionManager.getCodec(), options);
    }

    @Override
    public RScheduledExecutorService getExecutorService(String name, Codec codec) {
        return getExecutorService(name, codec, ExecutorOptions.defaults());
    }

    @Override
    public RScheduledExecutorService getExecutorService(String name, Codec codec, ExecutorOptions options) {
        return new RedissonExecutorService(codec, connectionManager.getCommandExecutor(), this, name, queueTransferService, responses, options);
    }

    @Override
    public RRemoteService getRemoteService() {
        return getRemoteService("redisson_rs", connectionManager.getCodec());
    }

    @Override
    public RRemoteService getRemoteService(String name) {
        return getRemoteService(name, connectionManager.getCodec());
    }

    @Override
    public RRemoteService getRemoteService(Codec codec) {
        return getRemoteService("redisson_rs", codec);
    }

    @Override
    public RRemoteService getRemoteService(String name, Codec codec) {
        String executorId = connectionManager.getId();
        if (codec != connectionManager.getCodec()) {
            executorId = executorId + ":" + name;
        }
        return new RedissonRemoteService(codec, name, connectionManager.getCommandExecutor(), executorId, responses);
    }

    @Override
    public <V> RSortedSet<V> getSortedSet(String name) {
        return new RedissonSortedSet<V>(connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RSortedSet<V> getSortedSet(String name, Codec codec) {
        return new RedissonSortedSet<V>(codec, connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RScoredSortedSet<V> getScoredSortedSet(String name) {
        return new RedissonScoredSortedSet<V>(connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RScoredSortedSet<V> getScoredSortedSet(String name, Codec codec) {
        return new RedissonScoredSortedSet<V>(codec, connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public RLexSortedSet getLexSortedSet(String name) {
        return new RedissonLexSortedSet(connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public RTopic getTopic(String name) {
        return new RedissonTopic(connectionManager.getCommandExecutor(), name);
    }

    @Override
    public RTopic getTopic(String name, Codec codec) {
        return new RedissonTopic(codec, connectionManager.getCommandExecutor(), name);
    }

    @Override
    public RReliableTopic getReliableTopic(String name) {
        return new RedissonReliableTopic(connectionManager.getCommandExecutor(), name);
    }

    @Override
    public RReliableTopic getReliableTopic(String name, Codec codec) {
        return new RedissonReliableTopic(codec, connectionManager.getCommandExecutor(), name);
    }

    @Override
    public RPatternTopic getPatternTopic(String pattern) {
        return new RedissonPatternTopic(connectionManager.getCommandExecutor(), pattern);
    }

    @Override
    public RPatternTopic getPatternTopic(String pattern, Codec codec) {
        return new RedissonPatternTopic(codec, connectionManager.getCommandExecutor(), pattern);
    }

    @Override
    public <V> RDelayedQueue<V> getDelayedQueue(RQueue<V> destinationQueue) {
        if (destinationQueue == null) {
            throw new NullPointerException();
        }
        return new RedissonDelayedQueue<V>(queueTransferService, destinationQueue.getCodec(), connectionManager.getCommandExecutor(), destinationQueue.getName());
    }

    @Override
    public <V> RQueue<V> getQueue(String name) {
        return new RedissonQueue<V>(connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RQueue<V> getQueue(String name, Codec codec) {
        return new RedissonQueue<V>(codec, connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RTransferQueue<V> getTransferQueue(String name) {
        String remoteName = RedissonObject.suffixName(name, "remoteService");
        RRemoteService service = getRemoteService(remoteName);
        return new RedissonTransferQueue<V>(connectionManager.getCommandExecutor(), name, service);
    }

    @Override
    public <V> RTransferQueue<V> getTransferQueue(String name, Codec codec) {
        String remoteName = RedissonObject.suffixName(name, "remoteService");
        RRemoteService service = getRemoteService(remoteName);
        return new RedissonTransferQueue<V>(codec, connectionManager.getCommandExecutor(), name, service);
    }

    @Override
    public <V> RRingBuffer<V> getRingBuffer(String name) {
        return new RedissonRingBuffer<V>(connectionManager.getCommandExecutor(), name, this);
    }
    
    @Override
    public <V> RRingBuffer<V> getRingBuffer(String name, Codec codec) {
        return new RedissonRingBuffer<V>(codec, connectionManager.getCommandExecutor(), name, this);
    }
    
    @Override
    public <V> RBlockingQueue<V> getBlockingQueue(String name) {
        return new RedissonBlockingQueue<V>(connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RBlockingQueue<V> getBlockingQueue(String name, Codec codec) {
        return new RedissonBlockingQueue<V>(codec, connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RBoundedBlockingQueue<V> getBoundedBlockingQueue(String name) {
        return new RedissonBoundedBlockingQueue<V>(connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RBoundedBlockingQueue<V> getBoundedBlockingQueue(String name, Codec codec) {
        return new RedissonBoundedBlockingQueue<V>(codec, connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RDeque<V> getDeque(String name) {
        return new RedissonDeque<V>(connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RDeque<V> getDeque(String name, Codec codec) {
        return new RedissonDeque<V>(codec, connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RBlockingDeque<V> getBlockingDeque(String name) {
        return new RedissonBlockingDeque<V>(connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RBlockingDeque<V> getBlockingDeque(String name, Codec codec) {
        return new RedissonBlockingDeque<V>(codec, connectionManager.getCommandExecutor(), name, this);
    };

    @Override
    public RAtomicLong getAtomicLong(String name) {
        return new RedissonAtomicLong(connectionManager.getCommandExecutor(), name);
    }

    @Override
    public RLongAdder getLongAdder(String name) {
        return new RedissonLongAdder(connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public RDoubleAdder getDoubleAdder(String name) {
        return new RedissonDoubleAdder(connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public RAtomicDouble getAtomicDouble(String name) {
        return new RedissonAtomicDouble(connectionManager.getCommandExecutor(), name);
    }

    @Override
    public RCountDownLatch getCountDownLatch(String name) {
        return new RedissonCountDownLatch(connectionManager.getCommandExecutor(), name);
    }

    @Override
    public RBitSet getBitSet(String name) {
        return new RedissonBitSet(connectionManager.getCommandExecutor(), name);
    }

    @Override
    public RSemaphore getSemaphore(String name) {
        return new RedissonSemaphore(connectionManager.getCommandExecutor(), name);
    }

    @Override
    public RPermitExpirableSemaphore getPermitExpirableSemaphore(String name) {
        return new RedissonPermitExpirableSemaphore(connectionManager.getCommandExecutor(), name);
    }

    @Override
    public <V> RBloomFilter<V> getBloomFilter(String name) {
        return new RedissonBloomFilter<V>(connectionManager.getCommandExecutor(), name);
    }

    @Override
    public <V> RBloomFilter<V> getBloomFilter(String name, Codec codec) {
        return new RedissonBloomFilter<V>(codec, connectionManager.getCommandExecutor(), name);
    }

    @Override
    public RIdGenerator getIdGenerator(String name) {
        return new RedissonIdGenerator(connectionManager.getCommandExecutor(), name);
    }

    @Override
    public RKeys getKeys() {
        return new RedissonKeys(connectionManager.getCommandExecutor());
    }

    @Override
    public RTransaction createTransaction(TransactionOptions options) {
        return new RedissonTransaction(connectionManager.getCommandExecutor(), options);
    }

    @Override
    public RBatch createBatch(BatchOptions options) {
        RedissonBatch batch = new RedissonBatch(evictionScheduler, connectionManager, options);
        if (config.isReferenceEnabled()) {
            batch.enableRedissonReferenceSupport(this);
        }
        return batch;
    }

    @Override
    public RBatch createBatch() {
        return createBatch(BatchOptions.defaults());
    }

    @Override
    public RLiveObjectService getLiveObjectService() {
        return new RedissonLiveObjectService(liveObjectClassCache, connectionManager);
    }

    @Override
    public void shutdown() {
        connectionManager.shutdown();
    }


    @Override
    public void shutdown(long quietPeriod, long timeout, TimeUnit unit) {
        connectionManager.shutdown(quietPeriod, timeout, unit);
    }

    @Override
    public Config getConfig() {
        return config;
    }

    @Override
    public <T extends BaseRedisNodes> T getRedisNodes(org.redisson.api.redisnode.RedisNodes<T> nodes) {
        if (nodes.getClazz() == RedisSingle.class) {
            if (config.isSentinelConfig() || config.isClusterConfig()) {
                throw new IllegalArgumentException("Can't be used in non Redis single configuration");
            }
            return (T) new RedissonSingleNode(connectionManager);
        }
        if (nodes.getClazz() == RedisCluster.class) {
            if (!config.isClusterConfig()) {
                throw new IllegalArgumentException("Can't be used in non Redis Cluster configuration");
            }
            return (T) new RedissonClusterNodes(connectionManager);
        }
        if (nodes.getClazz() == RedisSentinelMasterSlave.class) {
            if (!config.isSentinelConfig()) {
                throw new IllegalArgumentException("Can't be used in non Redis Sentinel configuration");
            }
            return (T) new RedissonSentinelMasterSlaveNodes(connectionManager);
        }
        if (nodes.getClazz() == RedisMasterSlave.class) {
            if (config.isSentinelConfig() || config.isClusterConfig()) {
                throw new IllegalArgumentException("Can't be used in non Redis Master Slave configuration");
            }
            return (T) new RedissonMasterSlaveNodes(connectionManager);
        }
        throw new IllegalArgumentException();
    }

    @Override
    public NodesGroup<Node> getNodesGroup() {
        return new RedisNodes<Node>(connectionManager);
    }

    @Override
    public ClusterNodesGroup getClusterNodesGroup() {
        if (!connectionManager.isClusterMode()) {
            throw new IllegalStateException("Redisson is not in cluster mode!");
        }
        return new RedisClusterNodes(connectionManager);
    }

    @Override
    public boolean isShutdown() {
        return connectionManager.isShutdown();
    }

    @Override
    public boolean isShuttingDown() {
        return connectionManager.isShuttingDown();
    }

    protected void enableRedissonReferenceSupport() {
        this.connectionManager.getCommandExecutor().enableRedissonReferenceSupport(this);
    }

    @Override
    public <V> RPriorityQueue<V> getPriorityQueue(String name) {
        return new RedissonPriorityQueue<V>(connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RPriorityQueue<V> getPriorityQueue(String name, Codec codec) {
        return new RedissonPriorityQueue<V>(codec, connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RPriorityBlockingQueue<V> getPriorityBlockingQueue(String name) {
        return new RedissonPriorityBlockingQueue<V>(connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RPriorityBlockingQueue<V> getPriorityBlockingQueue(String name, Codec codec) {
        return new RedissonPriorityBlockingQueue<V>(codec, connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RPriorityBlockingDeque<V> getPriorityBlockingDeque(String name) {
        return new RedissonPriorityBlockingDeque<V>(connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RPriorityBlockingDeque<V> getPriorityBlockingDeque(String name, Codec codec) {
        return new RedissonPriorityBlockingDeque<V>(codec, connectionManager.getCommandExecutor(), name, this);
    }


    @Override
    public <V> RPriorityDeque<V> getPriorityDeque(String name) {
        return new RedissonPriorityDeque<V>(connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public <V> RPriorityDeque<V> getPriorityDeque(String name, Codec codec) {
        return new RedissonPriorityDeque<V>(codec, connectionManager.getCommandExecutor(), name, this);
    }

    @Override
    public String getId() {
        return connectionManager.getId();
    }

}
