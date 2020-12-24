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
package org.redisson.executor;

import org.redisson.RedissonExecutorService;
import org.redisson.api.RFuture;
import org.redisson.api.RMap;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.LongCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.executor.params.TaskParameters;
import org.redisson.misc.RPromise;
import org.redisson.misc.RedissonPromise;
import org.redisson.remote.*;

import java.util.Arrays;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class TasksService extends BaseRemoteService {

    protected String terminationTopicName;
    protected String tasksCounterName;
    protected String statusName;
    protected String tasksName;
    protected String schedulerQueueName;
    protected String schedulerChannelName;
    protected String tasksRetryIntervalName;
    protected String tasksExpirationTimeName;
    protected long tasksRetryInterval;
    
    public TasksService(Codec codec, String name, CommandAsyncExecutor commandExecutor, String executorId, ConcurrentMap<String, ResponseEntry> responses) {
        super(codec, name, commandExecutor, executorId, responses);
    }

    public void setTasksExpirationTimeName(String tasksExpirationTimeName) {
        this.tasksExpirationTimeName = tasksExpirationTimeName;
    }

    public void setTasksRetryIntervalName(String tasksRetryIntervalName) {
        this.tasksRetryIntervalName = tasksRetryIntervalName;
    }
    
    public void setTasksRetryInterval(long tasksRetryInterval) {
        this.tasksRetryInterval = tasksRetryInterval;
    }
    
    public void setTerminationTopicName(String terminationTopicName) {
        this.terminationTopicName = terminationTopicName;
    }
    
    public void setStatusName(String statusName) {
        this.statusName = statusName;
    }
    
    public void setTasksCounterName(String tasksCounterName) {
        this.tasksCounterName = tasksCounterName;
    }
    
    public void setTasksName(String tasksName) {
        this.tasksName = tasksName;
    }
    
    public void setSchedulerChannelName(String schedulerChannelName) {
        this.schedulerChannelName = schedulerChannelName;
    }
    
    public void setSchedulerQueueName(String scheduledQueueName) {
        this.schedulerQueueName = scheduledQueueName;
    }

    @Override
    protected final RFuture<Boolean> addAsync(String requestQueueName,
            RemoteServiceRequest request, RemotePromise<Object> result) {
        final RPromise<Boolean> promise = new RedissonPromise<Boolean>();
        RFuture<Boolean> future = addAsync(requestQueueName, request);
        result.setAddFuture(future);
        
        future.onComplete((res, e) -> {
            if (e != null) {
                promise.tryFailure(e);
                return;
            }
            
            if (!res) {
                promise.cancel(true);
                return;
            }
            
            promise.trySuccess(true);
        });
        
        return promise;
    }

    protected CommandAsyncExecutor getAddCommandExecutor() {
        return commandExecutor;
    }
    
    protected RFuture<Boolean> addAsync(String requestQueueName, RemoteServiceRequest request) {
        TaskParameters params = (TaskParameters) request.getArgs()[0];
        params.setRequestId(request.getId());

        long retryStartTime = 0;
        if (tasksRetryInterval > 0) {
            retryStartTime = System.currentTimeMillis() + tasksRetryInterval;
        }
        long expireTime = 0;
        if (params.getTtl() > 0) {
            expireTime = System.currentTimeMillis() + params.getTtl();
        }
        
        return getAddCommandExecutor().evalWriteAsync(name, StringCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                // check if executor service not in shutdown state
                "if redis.call('exists', KEYS[2]) == 0 then "
                    + "redis.call('hset', KEYS[5], ARGV[2], ARGV[3]);"
                    + "redis.call('rpush', KEYS[6], ARGV[2]); "
                    + "redis.call('incr', KEYS[1]);"

                    + "if tonumber(ARGV[5]) > 0 then "
                        + "redis.call('zadd', KEYS[8], ARGV[5], ARGV[2]);"
                    + "end; "

                    + "if tonumber(ARGV[1]) > 0 then "
                        + "redis.call('set', KEYS[7], ARGV[4]);"
                        + "redis.call('zadd', KEYS[3], ARGV[1], 'ff' .. ARGV[2]);"
                        + "local v = redis.call('zrange', KEYS[3], 0, 0); "
                        // if new task added to queue head then publish its startTime 
                        // to all scheduler workers 
                        + "if v[1] == ARGV[2] then "
                            + "redis.call('publish', KEYS[4], ARGV[1]); "
                        + "end; "
                    + "end;"
                    + "return 1;"
                + "end;"
                + "return 0;", 
                Arrays.<Object>asList(tasksCounterName, statusName, schedulerQueueName, schedulerChannelName,
                                    tasksName, requestQueueName, tasksRetryIntervalName, tasksExpirationTimeName),
                retryStartTime, request.getId(), encode(request), tasksRetryInterval, expireTime);
    }
    
    @Override
    protected RFuture<Boolean> removeAsync(String requestQueueName, RequestId taskId) {
        return commandExecutor.evalWriteAsync(name, LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                "redis.call('zrem', KEYS[2], 'ff' .. ARGV[1]); "
              + "redis.call('zrem', KEYS[8], ARGV[1]); "
              + "local task = redis.call('hget', KEYS[6], ARGV[1]); "
              + "redis.call('hdel', KEYS[6], ARGV[1]); "
               // remove from executor queue
              + "if task ~= false and redis.call('exists', KEYS[3]) == 1 and redis.call('lrem', KEYS[1], 1, ARGV[1]) > 0 then "
                  + "if redis.call('decr', KEYS[3]) == 0 then "
                     + "redis.call('del', KEYS[3]);"
                     + "if redis.call('get', KEYS[4]) == ARGV[2] then "
                        + "redis.call('del', KEYS[7]);"
                        + "redis.call('set', KEYS[4], ARGV[3]);"
                        + "redis.call('publish', KEYS[5], ARGV[3]);"
                     + "end;"
                  + "end;"
                  + "return 1;"
              + "end;"
              + "if task == false then "
                  + "return 1; "
              + "end;"
              + "return 0;",
          Arrays.<Object>asList(requestQueueName, schedulerQueueName, tasksCounterName, statusName, terminationTopicName,
                                tasksName, tasksRetryIntervalName, tasksExpirationTimeName),
          taskId.toString(), RedissonExecutorService.SHUTDOWN_STATE, RedissonExecutorService.TERMINATED_STATE);
    }

    @Override
    protected RequestId generateRequestId() {
        byte[] id = new byte[17];
        ThreadLocalRandom.current().nextBytes(id);
        id[0] = 00;
        return new RequestId(id);
    }

    public RFuture<Boolean> cancelExecutionAsync(final RequestId requestId) {
        final RPromise<Boolean> result = new RedissonPromise<Boolean>();
        
        String requestQueueName = getRequestQueueName(RemoteExecutorService.class);
        RFuture<Boolean> removeFuture = removeAsync(requestQueueName, requestId);
        removeFuture.onComplete((res, e) -> {
            if (e != null) {
                result.tryFailure(e);
                return;
            }
            
            if (res) {
                result.trySuccess(true);
                return;
            }
            
            RMap<String, RemoteServiceCancelRequest> canceledRequests = getMap(cancelRequestMapName);
            canceledRequests.putAsync(requestId.toString(), new RemoteServiceCancelRequest(true, true));
            canceledRequests.expireAsync(60, TimeUnit.SECONDS);
            
            final RPromise<RemoteServiceCancelResponse> response = new RedissonPromise<RemoteServiceCancelResponse>();
            scheduleCheck(cancelResponseMapName, requestId, response);
            response.onComplete((r, ex) -> {
                if (ex != null) {
                    result.tryFailure(ex);
                    return;
                }
                
                if (response.getNow() == null) {
                    result.trySuccess(false);
                    return;
                }
                result.trySuccess(response.getNow().isCanceled());
            });
        });

        return result;
    }
    
}
