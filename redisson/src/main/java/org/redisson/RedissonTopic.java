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

import org.redisson.api.RFuture;
import org.redisson.api.RTopic;
import org.redisson.api.listener.MessageListener;
import org.redisson.api.listener.StatusListener;
import org.redisson.client.ChannelName;
import org.redisson.client.RedisPubSubListener;
import org.redisson.client.RedisTimeoutException;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.LongCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.config.MasterSlaveServersConfig;
import org.redisson.misc.RPromise;
import org.redisson.misc.RedissonPromise;
import org.redisson.misc.TransferListener;
import org.redisson.pubsub.AsyncSemaphore;
import org.redisson.pubsub.PubSubConnectionEntry;
import org.redisson.pubsub.PublishSubscribeService;

import java.util.Collections;
import java.util.List;

/**
 * Distributed topic implementation. Messages are delivered to all message listeners across Redis cluster.
 *
 * @author Nikita Koksharov
 *
 */
public class RedissonTopic implements RTopic {

    final PublishSubscribeService subscribeService;
    final CommandAsyncExecutor commandExecutor;
    private final String name;
    final ChannelName channelName;
    final Codec codec;

    public RedissonTopic(CommandAsyncExecutor commandExecutor, String name) {
        this(commandExecutor.getConnectionManager().getCodec(), commandExecutor, name);
    }

    public RedissonTopic(Codec codec, CommandAsyncExecutor commandExecutor, String name) {
        this.commandExecutor = commandExecutor;
        this.name = name;
        this.channelName = new ChannelName(name);
        this.codec = codec;
        this.subscribeService = commandExecutor.getConnectionManager().getSubscribeService();
    }

    @Override
    public List<String> getChannelNames() {
        return Collections.singletonList(name);
    }

    @Override
    public long publish(Object message) {
        return commandExecutor.get(publishAsync(message));
    }

    protected String getName() {
        return name;
    }

    protected String getName(Object o) {
        return name;
    }

    @Override
    public RFuture<Long> publishAsync(Object message) {
        String name = getName(message);
        return commandExecutor.writeAsync(name, StringCodec.INSTANCE, RedisCommands.PUBLISH, name, commandExecutor.encode(codec, message));
    }

    @Override
    public int addListener(StatusListener listener) {
        RFuture<Integer> future = addListenerAsync(listener);
        commandExecutor.syncSubscription(future);
        return future.getNow();
    };

    @Override
    public <M> int addListener(Class<M> type, MessageListener<? extends M> listener) {
        RFuture<Integer> future = addListenerAsync(type, (MessageListener<M>) listener);
        commandExecutor.syncSubscription(future);
        return future.getNow();
    }

    @Override
    public RFuture<Integer> addListenerAsync(StatusListener listener) {
        PubSubStatusListener pubSubListener = new PubSubStatusListener(listener, name);
        return addListenerAsync(pubSubListener);
    }

    @Override
    public <M> RFuture<Integer> addListenerAsync(Class<M> type, MessageListener<M> listener) {
        PubSubMessageListener<M> pubSubListener = new PubSubMessageListener<>(type, listener, name);
        return addListenerAsync(pubSubListener);
    }

    protected RFuture<Integer> addListenerAsync(RedisPubSubListener<?> pubSubListener) {
        RFuture<PubSubConnectionEntry> future = subscribeService.subscribe(codec, channelName, pubSubListener);
        RPromise<Integer> result = new RedissonPromise<>();
        result.onComplete((res, e) -> {
            if (e != null) {
                ((RPromise<PubSubConnectionEntry>) future).tryFailure(e);
            }
        });
        future.onComplete((res, e) -> {
            if (e != null) {
                result.tryFailure(e);
                return;
            }

            result.trySuccess(System.identityHashCode(pubSubListener));
        });
        return result;
    }

    @Override
    public void removeAllListeners() {
        AsyncSemaphore semaphore = subscribeService.getSemaphore(channelName);
        acquire(semaphore);

        PubSubConnectionEntry entry = subscribeService.getPubSubEntry(channelName);
        if (entry == null) {
            semaphore.release();
            return;
        }

        if (entry.hasListeners(channelName)) {
            subscribeService.unsubscribe(channelName, semaphore).syncUninterruptibly();
        } else {
            semaphore.release();
        }
    }

    protected void acquire(AsyncSemaphore semaphore) {
        MasterSlaveServersConfig config = commandExecutor.getConnectionManager().getConfig();
        int timeout = config.getTimeout() + config.getRetryInterval() * config.getRetryAttempts();
        if (!semaphore.tryAcquire(timeout)) {
            throw new RedisTimeoutException("Remove listeners operation timeout: (" + timeout + "ms) for " + name + " topic");
        }
    }

    @Override
    public void removeListener(MessageListener<?> listener) {
        RFuture<Void> future = removeListenerAsync(listener);
        MasterSlaveServersConfig config = commandExecutor.getConnectionManager().getConfig();
        int timeout = config.getTimeout() + config.getRetryInterval() * config.getRetryAttempts();
        if (!future.awaitUninterruptibly(timeout)) {
            throw new RedisTimeoutException("Remove listeners operation timeout: (" + timeout + "ms) for " + name + " topic");
        }
    }

    @Override
    public RFuture<Void> removeListenerAsync(MessageListener<?> listener) {
        return removeListenerAsync(channelName, listener);
    }

    protected RFuture<Void> removeListenerAsync(ChannelName channelName, MessageListener<?> listener) {
        RPromise<Void> promise = new RedissonPromise<Void>();
        AsyncSemaphore semaphore = subscribeService.getSemaphore(channelName);
        semaphore.acquire(new Runnable() {
            @Override
            public void run() {
                PubSubConnectionEntry entry = subscribeService.getPubSubEntry(channelName);
                if (entry == null) {
                    semaphore.release();
                    promise.trySuccess(null);
                    return;
                }

                entry.removeListener(channelName, listener);
                if (!entry.hasListeners(channelName)) {
                    subscribeService.unsubscribe(channelName, semaphore)
                        .onComplete(new TransferListener<Void>(promise));
                } else {
                    semaphore.release();
                    promise.trySuccess(null);
                }

            }
        });
        return promise;
    }

    @Override
    public RFuture<Void> removeListenerAsync(Integer... listenerIds) {
        RPromise<Void> promise = new RedissonPromise<Void>();
        AsyncSemaphore semaphore = subscribeService.getSemaphore(channelName);
        semaphore.acquire(new Runnable() {
            @Override
            public void run() {
                PubSubConnectionEntry entry = subscribeService.getPubSubEntry(channelName);
                if (entry == null) {
                    semaphore.release();
                    promise.trySuccess(null);
                    return;
                }

                for (int id : listenerIds) {
                    entry.removeListener(channelName, id);
                }
                if (!entry.hasListeners(channelName)) {
                    subscribeService.unsubscribe(channelName, semaphore)
                        .onComplete(new TransferListener<Void>(promise));
                } else {
                    semaphore.release();
                    promise.trySuccess(null);
                }
            }
        });
        return promise;
    }

    @Override
    public void removeListener(Integer... listenerIds) {
        AsyncSemaphore semaphore = subscribeService.getSemaphore(channelName);
        acquire(semaphore);

        PubSubConnectionEntry entry = subscribeService.getPubSubEntry(channelName);
        if (entry == null) {
            semaphore.release();
            return;
        }

        for (int id : listenerIds) {
            entry.removeListener(channelName, id);
        }
        if (!entry.hasListeners(channelName)) {
            subscribeService.unsubscribe(channelName, semaphore).syncUninterruptibly();
        } else {
            semaphore.release();
        }
    }

    @Override
    public int countListeners() {
        PubSubConnectionEntry entry = subscribeService.getPubSubEntry(channelName);
        if (entry != null) {
            return entry.countListeners(channelName);
        }
        return 0;
    }

    @Override
    public RFuture<Long> countSubscribersAsync() {
        return commandExecutor.writeAsync(name, LongCodec.INSTANCE, RedisCommands.PUBSUB_NUMSUB, name);
    }

    @Override
    public long countSubscribers() {
        return commandExecutor.get(countSubscribersAsync());
    }

}
