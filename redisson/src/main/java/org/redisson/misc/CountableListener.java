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
package org.redisson.misc;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class CountableListener<T> implements BiConsumer<Object, Throwable> {
    
    protected final AtomicInteger counter = new AtomicInteger();
    protected final RPromise<T> result;
    protected final T value;
    
    public CountableListener() {
        this(null, null);
    }
    
    public CountableListener(RPromise<T> result, T value) {
        this(result, value, 0);
    }
    
    public CountableListener(RPromise<T> result, T value, int count) {
        this.result = result;
        this.value = value;
        this.counter.set(count);
    }
    
    public void setCounter(int newValue) {
        counter.set(newValue);
    }
    
    public void decCounter() {
        if (counter.decrementAndGet() == 0) {
            onSuccess(value);
            if (result != null) {
                result.trySuccess(value);
            }
        }
    }
    
    protected void onSuccess(T value) {
    }

    @Override
    public void accept(Object t, Throwable u) {
        if (u != null) {
            if (result != null) {
                result.tryFailure(u);
            }
            return;
        }
        
        decCounter();
    }

}
