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
package org.redisson.api;

import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;


/**
 * Reactive implementation of object holder. Max size of object is 512MB
 *
 * @author Nikita Koksharov
 *
 * @param <V> - the type of object
 */
public interface RBucketReactive<V> extends RExpirableReactive {

    /**
     * Returns size of object in bytes
     * 
     * @return object size
     */
    Mono<Long> size();
    
    /**
     * Tries to set element atomically into empty holder.
     * 
     * @param value - value to set
     * @return {@code true} if successful, or {@code false} if
     *         element was already set
     */
    Mono<Boolean> trySet(V value);

    /**
     * Tries to set element atomically into empty holder with defined <code>timeToLive</code> interval.
     * 
     * @param value - value to set
     * @param timeToLive - time to live interval
     * @param timeUnit - unit of time to live interval
     * @return {@code true} if successful, or {@code false} if
     *         element was already set
     */
    Mono<Boolean> trySet(V value, long timeToLive, TimeUnit timeUnit);

    /**
     * Sets value only if it's already exists.
     *
     * @param value - value to set
     * @return {@code true} if successful, or {@code false} if
     *         element wasn't set
     */
    Mono<Boolean> setIfExists(V value);

    /**
     * Sets value only if it's already exists.
     *
     * @param value - value to set
     * @param timeToLive - time to live interval
     * @param timeUnit - unit of time to live interval
     * @return {@code true} if successful, or {@code false} if
     *         element wasn't set
     */
    Mono<Boolean> setIfExists(V value, long timeToLive, TimeUnit timeUnit);

    /**
     * Atomically sets the value to the given updated value
     * only if serialized state of the current value equals 
     * to serialized state of the expected value.
     *
     * @param expect the expected value
     * @param update the new value
     * @return {@code true} if successful; or {@code false} if the actual value
     *         was not equal to the expected value.
     */
    Mono<Boolean> compareAndSet(V expect, V update);

    /**
     * Retrieves current element in the holder and replaces it with <code>newValue</code>. 
     * 
     * @param newValue - value to set
     * @return previous value
     */
    Mono<V> getAndSet(V newValue);

    /**
     * Retrieves current element in the holder and replaces it with <code>newValue</code> with defined <code>timeToLive</code> interval. 
     * 
     * @param value - value to set
     * @param timeToLive - time to live interval
     * @param timeUnit - unit of time to live interval
     * @return previous value
     */
    Mono<V> getAndSet(V value, long timeToLive, TimeUnit timeUnit);
    
    /**
     * Retrieves element stored in the holder.
     * 
     * @return element
     */
    Mono<V> get();
    
    /**
     * Retrieves element in the holder and removes it.
     * 
     * @return element
     */
    Mono<V> getAndDelete();

    /**
     * Stores element into the holder. 
     * 
     * @param value - value to set
     * @return void
     */
    Mono<Void> set(V value);

    /**
     * Stores element into the holder with defined <code>timeToLive</code> interval.
     * 
     * @param value - value to set
     * @param timeToLive - time to live interval
     * @param timeUnit - unit of time to live interval
     * @return void
     */
    Mono<Void> set(V value, long timeToLive, TimeUnit timeUnit);

}
