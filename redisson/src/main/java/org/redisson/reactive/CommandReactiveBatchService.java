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
package org.redisson.reactive;

import java.util.concurrent.Callable;

import org.redisson.api.BatchOptions;
import org.redisson.api.BatchResult;
import org.redisson.api.RFuture;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.Codec;
import org.redisson.client.protocol.RedisCommand;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.command.CommandBatchService;
import org.redisson.connection.ConnectionManager;
import org.redisson.connection.NodeSource;
import org.redisson.misc.RPromise;

import reactor.core.publisher.Mono;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class CommandReactiveBatchService extends CommandReactiveService {

    private final CommandBatchService batchService;

    public CommandReactiveBatchService(ConnectionManager connectionManager, BatchOptions options) {
        super(connectionManager);
        batchService = new CommandBatchService(connectionManager, options);
    }

    @Override
    public <R> Mono<R> reactive(Callable<RFuture<R>> supplier) {
        Mono<R> mono = super.reactive(new Callable<RFuture<R>>() {
            volatile RFuture<R> future;
            @Override
            public RFuture<R> call() throws Exception {
                if (future == null) {
                    synchronized (this) {
                        if (future == null) {
                            future = supplier.call();
                        }
                    }
                }
                return future;
            }
        });
        mono.subscribe();
        return mono;
    }
    
    @Override
    protected <R> RPromise<R> createPromise() {
        return batchService.createPromise();
    }
    
    @Override
    public <V, R> void async(boolean readOnlyMode, NodeSource nodeSource,
            Codec codec, RedisCommand<V> command, Object[] params, RPromise<R> mainPromise, boolean ignoreRedirect) {
        batchService.async(readOnlyMode, nodeSource, codec, command, params, mainPromise, ignoreRedirect);
    }

    public RFuture<BatchResult<?>> executeAsync() {
        return batchService.executeAsync();
    }

    @Override
    public CommandAsyncExecutor enableRedissonReferenceSupport(RedissonReactiveClient redissonReactive) {
        batchService.enableRedissonReferenceSupport(redissonReactive);
        return super.enableRedissonReferenceSupport(redissonReactive);
    }
    
}
