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
package org.redisson.cluster;

import io.netty.resolver.AddressResolver;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.ScheduledFuture;
import org.redisson.api.NatMapper;
import org.redisson.api.NodeType;
import org.redisson.api.RFuture;
import org.redisson.client.*;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.client.protocol.RedisStrictCommand;
import org.redisson.cluster.ClusterNodeInfo.Flag;
import org.redisson.cluster.ClusterPartition.Type;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.MasterSlaveServersConfig;
import org.redisson.config.ReadMode;
import org.redisson.connection.CRC16;
import org.redisson.connection.ClientConnectionsEntry.FreezeReason;
import org.redisson.connection.MasterSlaveConnectionManager;
import org.redisson.connection.MasterSlaveEntry;
import org.redisson.connection.SingleEntry;
import org.redisson.misc.RPromise;
import org.redisson.misc.RedisURI;
import org.redisson.misc.RedissonPromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.Collectors;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class ClusterConnectionManager extends MasterSlaveConnectionManager {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final ConcurrentMap<Integer, ClusterPartition> lastPartitions = new ConcurrentHashMap<>();

    private ScheduledFuture<?> monitorFuture;
    
    private volatile RedisURI lastClusterNode;
    
    private RedisStrictCommand<List<ClusterNodeInfo>> clusterNodesCommand;
    
    private String configEndpointHostName;
    
    private final NatMapper natMapper;

    private final AtomicReferenceArray<MasterSlaveEntry> slot2entry = new AtomicReferenceArray<>(MAX_SLOT);

    private final Map<RedisClient, MasterSlaveEntry> client2entry = new ConcurrentHashMap<>();

    public ClusterConnectionManager(ClusterServersConfig cfg, Config config, UUID id) {
        super(config, id);

        if (cfg.getNodeAddresses().isEmpty()) {
            throw new IllegalArgumentException("At least one cluster node should be defined!");
        }

        this.natMapper = cfg.getNatMapper();
        this.config = create(cfg);
        initTimer(this.config);
        
        Throwable lastException = null;
        List<String> failedMasters = new ArrayList<String>();
        for (String address : cfg.getNodeAddresses()) {
            RedisURI addr = new RedisURI(address);
            RFuture<RedisConnection> connectionFuture = connectToNode(cfg, addr, null, addr.getHost());
            try {
                RedisConnection connection = connectionFuture.syncUninterruptibly().getNow();

                if (cfg.getNodeAddresses().size() == 1 && NetUtil.createByteArrayFromIpAddressString(addr.getHost()) == null) {
                    configEndpointHostName = addr.getHost();
                }
                
                clusterNodesCommand = RedisCommands.CLUSTER_NODES;
                if (addr.isSsl()) {
                    clusterNodesCommand = RedisCommands.CLUSTER_NODES_SSL;
                }
                
                List<ClusterNodeInfo> nodes = connection.sync(clusterNodesCommand);
                
                StringBuilder nodesValue = new StringBuilder();
                for (ClusterNodeInfo clusterNodeInfo : nodes) {
                    nodesValue.append(clusterNodeInfo.getNodeInfo()).append("\n");
                }
                log.info("Redis cluster nodes configuration got from {}:\n{}", connection.getRedisClient().getAddr(), nodesValue);

                lastClusterNode = addr;
                
                Collection<ClusterPartition> partitions = parsePartitions(nodes);
                List<RFuture<Void>> masterFutures = new ArrayList<>();
                for (ClusterPartition partition : partitions) {
                    if (partition.isMasterFail()) {
                        failedMasters.add(partition.getMasterAddress().toString());
                        continue;
                    }
                    if (partition.getMasterAddress() == null) {
                        throw new IllegalStateException("Master node: " + partition.getNodeId() + " doesn't have address.");
                    }

                    RFuture<Void> masterFuture = addMasterEntry(partition, cfg);
                    masterFutures.add(masterFuture);
                }

                for (RFuture<Void> masterFuture : masterFutures) {
                    masterFuture.awaitUninterruptibly();
                    if (!masterFuture.isSuccess()) {
                        lastException = masterFuture.cause();
                    }
                }
                break;
            } catch (Exception e) {
                lastException = e;
                log.warn(e.getMessage());
            }
        }

        if (lastPartitions.isEmpty()) {
            stopThreads();
            if (failedMasters.isEmpty()) {
                throw new RedisConnectionException("Can't connect to servers!", lastException);
            } else {
                throw new RedisConnectionException("Can't connect to servers! Failed masters according to cluster status: " + failedMasters, lastException);
            }
        }

        if (cfg.isCheckSlotsCoverage() && lastPartitions.size() != MAX_SLOT) {
            stopThreads();
            if (failedMasters.isEmpty()) {
                throw new RedisConnectionException("Not all slots covered! Only " + lastPartitions.size() + " slots are available. Set checkSlotsCoverage = false to avoid this check.", lastException);
            } else {
                throw new RedisConnectionException("Not all slots covered! Only " + lastPartitions.size() + " slots are available. Set checkSlotsCoverage = false to avoid this check. Failed masters according to cluster status: " + failedMasters, lastException);
            }
        }
        
        scheduleClusterChangeCheck(cfg);
    }

    @Override
    public Collection<MasterSlaveEntry> getEntrySet() {
        return client2entry.values();
    }

    protected MasterSlaveEntry getEntry(RedisURI addr) {
        for (MasterSlaveEntry entry : client2entry.values()) {
            if (RedisURI.compare(entry.getClient().getAddr(), addr)) {
                return entry;
            }
            if (entry.hasSlave(addr)) {
                return entry;
            }
        }
        return null;
    }

    @Override
    public MasterSlaveEntry getEntry(RedisClient redisClient) {
        MasterSlaveEntry entry = client2entry.get(redisClient);
        if (entry != null) {
            return entry;
        }

        for (MasterSlaveEntry mentry : client2entry.values()) {
            if (mentry.hasSlave(redisClient)) {
                return mentry;
            }
        }
        return null;
    }

    @Override
    public MasterSlaveEntry getEntry(InetSocketAddress address) {
        for (MasterSlaveEntry entry : client2entry.values()) {
            InetSocketAddress addr = entry.getClient().getAddr();
            if (addr.getAddress().equals(address.getAddress()) && addr.getPort() == address.getPort()) {
                return entry;
            }
        }
        return null;
    }

    @Override
    protected RFuture<RedisClient> changeMaster(int slot, RedisURI address) {
        MasterSlaveEntry entry = getEntry(slot);
        RedisClient oldClient = entry.getClient();
        RFuture<RedisClient> future = super.changeMaster(slot, address);
        future.onComplete((res, e) -> {
            if (e == null) {
                client2entry.remove(oldClient);
                client2entry.put(entry.getClient(), entry);
            }
        });
        return future;
    }

    @Override
    public MasterSlaveEntry getEntry(int slot) {
        return slot2entry.get(slot);
    }

    private void addEntry(Integer slot, MasterSlaveEntry entry) {
        MasterSlaveEntry oldEntry = slot2entry.getAndSet(slot, entry);
        if (oldEntry != entry) {
            entry.incReference();
            shutdownEntry(oldEntry);
        }
        client2entry.put(entry.getClient(), entry);
    }

    private void removeEntry(Integer slot) {
        MasterSlaveEntry entry = slot2entry.getAndSet(slot, null);
        shutdownEntry(entry);
    }

    private void shutdownEntry(MasterSlaveEntry entry) {
        if (entry != null && entry.decReference() == 0) {
            client2entry.remove(entry.getClient());
            entry.getAllEntries().forEach(e -> entry.nodeDown(e));
            entry.masterDown();
            entry.shutdownAsync();
            subscribeService.remove(entry);

            String slaves = entry.getAllEntries().stream()
                    .filter(e -> !e.getClient().getAddr().equals(entry.getClient().getAddr()))
                    .map(e -> e.getClient().toString())
                    .collect(Collectors.joining(","));
            log.info("{} master and related slaves: {} removed", entry.getClient().getAddr(), slaves);
        }
    }

    @Override
    protected RedisClientConfig createRedisConfig(NodeType type, RedisURI address, int timeout, int commandTimeout, String sslHostname) {
        RedisClientConfig result = super.createRedisConfig(type, address, timeout, commandTimeout, sslHostname);
        result.setReadOnly(type == NodeType.SLAVE && config.getReadMode() != ReadMode.MASTER);
        return result;
    }
    
    private RFuture<Void> addMasterEntry(ClusterPartition partition, ClusterServersConfig cfg) {
        if (partition.isMasterFail()) {
            RedisException e = new RedisException("Failed to add master: " +
                    partition.getMasterAddress() + " for slot ranges: " +
                    partition.getSlotRanges() + ". Reason - server has FAIL flag");

            if (partition.getSlotsAmount() == 0) {
                e = new RedisException("Failed to add master: " +
                        partition.getMasterAddress() + ". Reason - server has FAIL flag");
            }
            return RedissonPromise.newFailedFuture(e);
        }

        RPromise<Void> result = new RedissonPromise<>();
        RFuture<RedisConnection> connectionFuture = connectToNode(cfg, partition.getMasterAddress(), null, configEndpointHostName);
        connectionFuture.onComplete((connection, ex1) -> {
            if (ex1 != null) {
                log.error("Can't connect to master: {} with slot ranges: {}", partition.getMasterAddress(), partition.getSlotRanges());
                result.tryFailure(ex1);
                return;
            }

            MasterSlaveServersConfig config = create(cfg);
            config.setMasterAddress(partition.getMasterAddress().toString());

            MasterSlaveEntry entry;
            if (config.checkSkipSlavesInit()) {
                entry = new SingleEntry(ClusterConnectionManager.this, config, configEndpointHostName);
            } else {
                Set<String> slaveAddresses = partition.getSlaveAddresses().stream().map(r -> r.toString()).collect(Collectors.toSet());
                config.setSlaveAddresses(slaveAddresses);

                entry = new MasterSlaveEntry(ClusterConnectionManager.this, config, configEndpointHostName);
            }

            RFuture<RedisClient> f = entry.setupMasterEntry(new RedisURI(config.getMasterAddress()));
            f.onComplete((masterClient, ex3) -> {
                if (ex3 != null) {
                    log.error("Can't add master: " + partition.getMasterAddress() + " for slot ranges: " + partition.getSlotRanges(), ex3);
                    result.tryFailure(ex3);
                    return;
                }

                for (Integer slot : partition.getSlots()) {
                    addEntry(slot, entry);
                    lastPartitions.put(slot, partition);
                }

                if (!config.checkSkipSlavesInit()) {
                    List<RFuture<Void>> fs = entry.initSlaveBalancer(partition.getFailedSlaveAddresses(), masterClient);
                    AtomicInteger counter = new AtomicInteger(fs.size());
                    for (RFuture<Void> future : fs) {
                        future.onComplete((r, ex) -> {
                            if (ex != null) {
                                log.error("unable to add slave for: " + partition.getMasterAddress()
                                                + " slot ranges: " + partition.getSlotRanges(), ex);
                            }

                            if (counter.decrementAndGet() == 0) {
                                if (!partition.getSlaveAddresses().isEmpty()) {
                                    log.info("slaves: {} added for slot ranges: {}", partition.getSlaveAddresses(), partition.getSlotRanges());
                                    if (!partition.getFailedSlaveAddresses().isEmpty()) {
                                        log.warn("slaves: {} are down for slot ranges: {}", partition.getFailedSlaveAddresses(), partition.getSlotRanges());
                                    }
                                }

                                if (result.trySuccess(null)) {
                                    log.info("master: {} added for slot ranges: {}", partition.getMasterAddress(), partition.getSlotRanges());
                                } else {
                                    log.error("unable to add master: {} for slot ranges: {}", partition.getMasterAddress(), partition.getSlotRanges());
                                }
                            }
                        });
                    }
                } else {
                    if (result.trySuccess(null)) {
                        log.info("master: {} added for slot ranges: {}", partition.getMasterAddress(), partition.getSlotRanges());
                    } else {
                        log.error("unable to add master: {} for slot ranges: {}", partition.getMasterAddress(), partition.getSlotRanges());
                    }
                }

            });
        });

        return result;
    }

    private void scheduleClusterChangeCheck(ClusterServersConfig cfg) {
        monitorFuture = group.schedule(new Runnable() {
            @Override
            public void run() {
                if (configEndpointHostName != null) {
                    String address = cfg.getNodeAddresses().iterator().next();
                    RedisURI uri = new RedisURI(address);
                    AddressResolver<InetSocketAddress> resolver = resolverGroup.getResolver(getGroup().next());
                    Future<List<InetSocketAddress>> allNodes = resolver.resolveAll(InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort()));
                    allNodes.addListener(new FutureListener<List<InetSocketAddress>>() {
                        @Override
                        public void operationComplete(Future<List<InetSocketAddress>> future) throws Exception {
                            AtomicReference<Throwable> lastException = new AtomicReference<Throwable>(future.cause());
                            if (!future.isSuccess()) {
                                checkClusterState(cfg, Collections.emptyIterator(), lastException);
                                return;
                            }
                            
                            List<RedisURI> nodes = new ArrayList<>();
                            for (InetSocketAddress addr : future.getNow()) {
                                RedisURI node = new RedisURI(uri.getScheme() + "://" + addr.getAddress().getHostAddress() + ":" + addr.getPort());
                                RedisURI address = applyNatMap(node);
                                nodes.add(address);
                            }
                            
                            Iterator<RedisURI> nodesIterator = nodes.iterator();
                            checkClusterState(cfg, nodesIterator, lastException);
                        }
                    });
                } else {
                    AtomicReference<Throwable> lastException = new AtomicReference<Throwable>();
                    List<RedisURI> nodes = new ArrayList<>();
                    List<RedisURI> slaves = new ArrayList<>();

                    for (ClusterPartition partition : getLastPartitions()) {
                        if (!partition.isMasterFail()) {
                            nodes.add(partition.getMasterAddress());
                        }

                        Set<RedisURI> partitionSlaves = new HashSet<>(partition.getSlaveAddresses());
                        partitionSlaves.removeAll(partition.getFailedSlaveAddresses());
                        slaves.addAll(partitionSlaves);
                    }
                    Collections.shuffle(nodes);
                    Collections.shuffle(slaves);
                    
                    // master nodes first
                    nodes.addAll(slaves);

                    Iterator<RedisURI> nodesIterator = nodes.iterator();

                    checkClusterState(cfg, nodesIterator, lastException);
                }
            }

        }, cfg.getScanInterval(), TimeUnit.MILLISECONDS);
    }

    private void checkClusterState(ClusterServersConfig cfg, Iterator<RedisURI> iterator, AtomicReference<Throwable> lastException) {
        if (!iterator.hasNext()) {
            if (lastException.get() != null) {
                log.error("Can't update cluster state", lastException.get());
            }
            scheduleClusterChangeCheck(cfg);
            return;
        }
        if (!getShutdownLatch().acquire()) {
            return;
        }
        RedisURI uri = iterator.next();
        RFuture<RedisConnection> connectionFuture = connectToNode(cfg, uri, null, configEndpointHostName);
        connectionFuture.onComplete((connection, e) -> {
            if (e != null) {
                lastException.set(e);
                getShutdownLatch().release();
                checkClusterState(cfg, iterator, lastException);
                return;
            }

            updateClusterState(cfg, connection, iterator, uri, lastException);
        });
    }

    private void updateClusterState(ClusterServersConfig cfg, RedisConnection connection, 
            Iterator<RedisURI> iterator, RedisURI uri, AtomicReference<Throwable> lastException) {
        RFuture<List<ClusterNodeInfo>> future = connection.async(clusterNodesCommand);
        future.onComplete((nodes, e) -> {
                if (e != null) {
                    log.error("Can't execute CLUSTER_NODES with " + connection.getRedisClient().getAddr(), e);
                    closeNodeConnection(connection);
                    lastException.set(e);
                    getShutdownLatch().release();
                    checkClusterState(cfg, iterator, lastException);
                    return;
                }

                lastClusterNode = uri;

                StringBuilder nodesValue = new StringBuilder();
                if (log.isDebugEnabled()) {
                    for (ClusterNodeInfo clusterNodeInfo : nodes) {
                        nodesValue.append(clusterNodeInfo.getNodeInfo()).append("\n");
                    }
                    log.debug("cluster nodes state got from {}:\n{}", connection.getRedisClient().getAddr(), nodesValue);
                }

                Collection<ClusterPartition> newPartitions = parsePartitions(nodes);
                RFuture<Void> masterFuture = checkMasterNodesChange(cfg, newPartitions);
                checkSlaveNodesChange(newPartitions);
                masterFuture.onComplete((res, ex) -> {
                    checkSlotsMigration(newPartitions);
                    checkSlotsChange(newPartitions);
                    getShutdownLatch().release();
                    scheduleClusterChangeCheck(cfg);
                });
        });
    }

    private void checkSlaveNodesChange(Collection<ClusterPartition> newPartitions) {
        Set<ClusterPartition> lastPartitions = getLastPartitions();
        for (ClusterPartition newPart : newPartitions) {
            for (ClusterPartition currentPart : lastPartitions) {
                if (!newPart.getMasterAddress().equals(currentPart.getMasterAddress())) {
                    continue;
                }

                MasterSlaveEntry entry = getEntry(currentPart.slots().nextSetBit(0));
                // should be invoked first in order to remove stale failedSlaveAddresses
                Set<RedisURI> addedSlaves = addRemoveSlaves(entry, currentPart, newPart);
                // Do some slaves have changed state from failed to alive?
                upDownSlaves(entry, currentPart, newPart, addedSlaves);

                break;
            }
        }
    }

    private void upDownSlaves(MasterSlaveEntry entry, ClusterPartition currentPart, ClusterPartition newPart, Set<RedisURI> addedSlaves) {
        Set<RedisURI> aliveSlaves = new HashSet<>(currentPart.getFailedSlaveAddresses());
        aliveSlaves.removeAll(addedSlaves);
        aliveSlaves.removeAll(newPart.getFailedSlaveAddresses());
        for (RedisURI uri : aliveSlaves) {
            currentPart.removeFailedSlaveAddress(uri);
            if (entry.hasSlave(uri) && entry.slaveUp(uri, FreezeReason.MANAGER)) {
                log.info("slave: {} is up for slot ranges: {}", uri, currentPart.getSlotRanges());
            }
        }

        Set<RedisURI> failedSlaves = new HashSet<>(newPart.getFailedSlaveAddresses());
        failedSlaves.removeAll(currentPart.getFailedSlaveAddresses());
        for (RedisURI uri : failedSlaves) {
            currentPart.addFailedSlaveAddress(uri);
            if (entry.slaveDown(uri, FreezeReason.MANAGER)) {
                disconnectNode(uri);
                log.warn("slave: {} has down for slot ranges: {}", uri, currentPart.getSlotRanges());
            }
        }
    }

    private Set<RedisURI> addRemoveSlaves(MasterSlaveEntry entry, ClusterPartition currentPart, ClusterPartition newPart) {
        Set<RedisURI> removedSlaves = new HashSet<>(currentPart.getSlaveAddresses());
        removedSlaves.removeAll(newPart.getSlaveAddresses());

        for (RedisURI uri : removedSlaves) {
            currentPart.removeSlaveAddress(uri);

            if (entry.slaveDown(uri, FreezeReason.MANAGER)) {
                log.info("slave {} removed for slot ranges: {}", uri, currentPart.getSlotRanges());
            }
        }

        Set<RedisURI> addedSlaves = new HashSet<>(newPart.getSlaveAddresses());
        addedSlaves.removeAll(currentPart.getSlaveAddresses());
        for (RedisURI uri : addedSlaves) {
            RFuture<Void> future = entry.addSlave(uri);
            future.onComplete((res, ex) -> {
                if (ex != null) {
                    log.error("Can't add slave: " + uri, ex);
                    return;
                }

                currentPart.addSlaveAddress(uri);
                entry.slaveUp(uri, FreezeReason.MANAGER);
                log.info("slave: {} added for slot ranges: {}", uri, currentPart.getSlotRanges());
            });
        }
        return addedSlaves;
    }

    private int slotsAmount(Collection<ClusterPartition> partitions) {
        int result = 0;
        for (ClusterPartition clusterPartition : partitions) {
            result += clusterPartition.getSlotsAmount();
        }
        return result;
    }

    private ClusterPartition find(Collection<ClusterPartition> partitions, Integer slot) {
        for (ClusterPartition clusterPartition : partitions) {
            if (clusterPartition.hasSlot(slot)) {
                return clusterPartition;
            }
        }
        return null;
    }

    private RFuture<Void> checkMasterNodesChange(ClusterServersConfig cfg, Collection<ClusterPartition> newPartitions) {
        Set<ClusterPartition> lastPartitions = getLastPartitions();
        Map<RedisURI, ClusterPartition> addedPartitions = new HashMap<>();
        Set<RedisURI> mastersElected = new HashSet<>();
        for (ClusterPartition newPart : newPartitions) {
            boolean masterFound = false;
            for (ClusterPartition currentPart : lastPartitions) {
                if (!newPart.getMasterAddress().equals(currentPart.getMasterAddress())) {
                    continue;
                }
                masterFound = true;
                // current master marked as failed
                if (!newPart.isMasterFail() || newPart.getSlotsAmount() == 0) {
                    continue;
                }
                for (Integer slot : currentPart.getSlots()) {
                    ClusterPartition newMasterPart = find(newPartitions, slot);
                    // does partition has a new master?
                    if (!newMasterPart.getMasterAddress().equals(currentPart.getMasterAddress())) {
                        RedisURI newUri = newMasterPart.getMasterAddress();
                        RedisURI oldUri = currentPart.getMasterAddress();

                        mastersElected.add(newUri);

                        RFuture<RedisClient> future = changeMaster(slot, newUri);
                        currentPart.setMasterAddress(newUri);
                        future.onComplete((res, e) -> {
                            if (e != null) {
                                currentPart.setMasterAddress(oldUri);
                            } else {
                                disconnectNode(oldUri);
                            }
                        });
                    }
                }
                break;
            }

            if (!masterFound && newPart.getSlotsAmount() > 0) {
                addedPartitions.put(newPart.getMasterAddress(), newPart);
            }
        }

        addedPartitions.keySet().removeAll(mastersElected);
        if (addedPartitions.isEmpty()) {
            return RedissonPromise.newSucceededFuture(null);
        }

        RPromise<Void> result = new RedissonPromise<>();
        AtomicInteger masters = new AtomicInteger(addedPartitions.size());
        for (ClusterPartition newPart : addedPartitions.values()) {
            RFuture<Void> future = addMasterEntry(newPart, cfg);
            future.onComplete((res, e) -> {
                if (masters.decrementAndGet() == 0) {
                    result.trySuccess(null);
                }
            });
        }
        return result;
    }

    private void checkSlotsChange(Collection<ClusterPartition> newPartitions) {
        int newSlotsAmount = slotsAmount(newPartitions);
        if (newSlotsAmount == lastPartitions.size() && lastPartitions.size() == MAX_SLOT) {
            return;
        }

        Set<Integer> removedSlots = new HashSet<>();
        for (Integer slot : lastPartitions.keySet()) {
            boolean found = false;
            for (ClusterPartition clusterPartition : newPartitions) {
                if (clusterPartition.hasSlot(slot)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                removedSlots.add(slot);
            }
        }
        lastPartitions.keySet().removeAll(removedSlots);
        if (!removedSlots.isEmpty()) {
            log.info("{} slots found to remove", removedSlots.size());
        }

        for (Integer slot : removedSlots) {
            removeEntry(slot);
        }

        Integer addedSlots = 0;
        for (ClusterPartition clusterPartition : newPartitions) {
            MasterSlaveEntry entry = getEntry(clusterPartition.getMasterAddress());
            for (Integer slot : clusterPartition.getSlots()) {
                if (lastPartitions.containsKey(slot)) {
                    continue;
                }

                if (entry != null) {
                    addEntry(slot, entry);
                    lastPartitions.put(slot, clusterPartition);
                    addedSlots++;
                }
            }
        }
        if (addedSlots > 0) {
            log.info("{} slots found to add", addedSlots);
        }
    }
    
    private void checkSlotsMigration(Collection<ClusterPartition> newPartitions) {
        Set<Integer> changedSlots = new HashSet<>();
        for (ClusterPartition currentPartition : getLastPartitions()) {
            for (ClusterPartition newPartition : newPartitions) {
                if (!currentPartition.getNodeId().equals(newPartition.getNodeId())) {
                    continue;
                }

                MasterSlaveEntry entry = getEntry(currentPartition.slots().nextSetBit(0));
                BitSet addedSlots = newPartition.copySlots();
                addedSlots.andNot(currentPartition.slots());
                currentPartition.addSlots(addedSlots);

                addedSlots.stream().forEach(slot -> {
                    addEntry(slot, entry);
                    lastPartitions.put(slot, currentPartition);
                    changedSlots.add(slot);
                });
                if (!addedSlots.isEmpty()) {
                    log.info("{} slots added to {}", addedSlots.cardinality(), currentPartition.getMasterAddress());
                }

                BitSet removedSlots = currentPartition.copySlots();
                removedSlots.andNot(newPartition.slots());
                currentPartition.removeSlots(removedSlots);

                removedSlots.stream().forEach(slot -> {
                    if (lastPartitions.remove(slot, currentPartition)) {
                        removeEntry(slot);
                        changedSlots.add(slot);
                    }
                });
                if (!removedSlots.isEmpty()) {
                    log.info("{} slots removed from {}", removedSlots.cardinality(), currentPartition.getMasterAddress());
                }
                break;
            }
        }

        changedSlots.forEach(subscribeService::reattachPubSub);
    }
    
    private int indexOf(byte[] array, byte element) {
        for (int i = 0; i < array.length; ++i) {
            if (array[i] == element) {
                return i;
            }
        }
        return -1;
    }  
    
    @Override
    public int calcSlot(byte[] key) {
        if (key == null) {
            return 0;
        }

        int start = indexOf(key, (byte) '{');
        if (start != -1) {
            int end = indexOf(key, (byte) '}');
            if (end != -1 && start + 1 < end) {
                key = Arrays.copyOfRange(key, start + 1, end);
            }
        }
        
        int result = CRC16.crc16(key) % MAX_SLOT;
        return result;
    }
    
    @Override
    public int calcSlot(String key) {
        if (key == null) {
            return 0;
        }

        int start = key.indexOf('{');
        if (start != -1) {
            int end = key.indexOf('}');
            if (end != -1 && start + 1 < end) {
                key = key.substring(start + 1, end);
            }
        }

        int result = CRC16.crc16(key.getBytes()) % MAX_SLOT;
        log.debug("slot {} for {}", result, key);
        return result;
    }

    @Override
    public RedisURI applyNatMap(RedisURI address) {
        return natMapper.map(address);
    }
    
    private Collection<ClusterPartition> parsePartitions(List<ClusterNodeInfo> nodes) {
        Map<String, ClusterPartition> partitions = new HashMap<String, ClusterPartition>();
        for (ClusterNodeInfo clusterNodeInfo : nodes) {
            if (clusterNodeInfo.containsFlag(Flag.NOADDR) || clusterNodeInfo.containsFlag(Flag.HANDSHAKE)) {
                // skip it
                continue;
            }

            String masterId = clusterNodeInfo.getNodeId();
            if (clusterNodeInfo.containsFlag(Flag.SLAVE)) {
                masterId = clusterNodeInfo.getSlaveOf();
            }

            if (masterId == null) {
                // skip it
                continue;
            }

            RedisURI address = applyNatMap(clusterNodeInfo.getAddress());
            if (clusterNodeInfo.containsFlag(Flag.SLAVE)) {
                ClusterPartition masterPartition = getPartition(partitions, masterId);
                ClusterPartition slavePartition = getPartition(partitions, clusterNodeInfo.getNodeId());
                slavePartition.setType(Type.SLAVE);
                slavePartition.setParent(masterPartition);
                
                masterPartition.addSlaveAddress(address);
                if (clusterNodeInfo.containsFlag(Flag.FAIL)) {
                    masterPartition.addFailedSlaveAddress(address);
                }
            } else if (clusterNodeInfo.containsFlag(Flag.MASTER)) {
                ClusterPartition masterPartition = getPartition(partitions, masterId);
                masterPartition.addSlotRanges(clusterNodeInfo.getSlotRanges());
                masterPartition.setMasterAddress(address);
                masterPartition.setType(Type.MASTER);
                if (clusterNodeInfo.containsFlag(Flag.FAIL)) {
                    masterPartition.setMasterFail(true);
                }
            }
        }
        
        addCascadeSlaves(partitions);
        
        return partitions.values();
    }

    private void addCascadeSlaves(Map<String, ClusterPartition> partitions) {
        Iterator<ClusterPartition> iter = partitions.values().iterator();
        while (iter.hasNext()) {
            ClusterPartition cp = iter.next();
            if (cp.getType() != Type.SLAVE) {
                continue;
            }
            
            if (cp.getParent() != null && cp.getParent().getType() == Type.MASTER) {
                ClusterPartition parent = cp.getParent();
                for (RedisURI addr : cp.getSlaveAddresses()) {
                    parent.addSlaveAddress(addr);
                }
                for (RedisURI addr : cp.getFailedSlaveAddresses()) {
                    parent.addFailedSlaveAddress(addr);
                }
            }
            iter.remove();
        }
    }

    private ClusterPartition getPartition(Map<String, ClusterPartition> partitions, String id) {
        ClusterPartition partition = partitions.get(id);
        if (partition == null) {
            partition = new ClusterPartition(id);
            partitions.put(id, partition);
        }
        return partition;
    }

    @Override
    public void shutdown() {
        if (monitorFuture != null) {
            monitorFuture.cancel(true);
        }
        
        closeNodeConnections();
        super.shutdown();
    }

    private Set<ClusterPartition> getLastPartitions() {
        return new HashSet<>(lastPartitions.values());
    }
    
    @Override
    public RedisURI getLastClusterNode() {
        return lastClusterNode;
    }
    
    @Override
    public boolean isClusterMode() {
        return true;
    }
    
}

