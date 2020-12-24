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

import io.netty.buffer.ByteBuf;
import org.redisson.api.*;
import org.redisson.api.mapreduce.RCollectionMapReduce;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.command.CommandExecutor;
import org.redisson.mapreduce.RedissonCollectionMapReduce;
import org.redisson.misc.RPromise;
import org.redisson.misc.RedissonPromise;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.*;

/**
 *
 * @author Nikita Koksharov
 *
 * @param <V> value type
 */
public class RedissonSortedSet<V> extends RedissonObject implements RSortedSet<V> {

    public static class BinarySearchResult<V> {

        private V value;
        private Integer index;

        public BinarySearchResult(V value) {
            super();
            this.value = value;
        }

        public BinarySearchResult() {
        }

        public void setIndex(Integer index) {
            this.index = index;
        }
        public Integer getIndex() {
            return index;
        }

        public V getValue() {
            return value;
        }


    }

    private Comparator comparator = Comparator.naturalOrder();

    CommandExecutor commandExecutor;
    
    private RLock lock;
    private RedissonList<V> list;
    private RBucket<String> comparatorHolder;
    private RedissonClient redisson;

    protected RedissonSortedSet(CommandExecutor commandExecutor, String name, RedissonClient redisson) {
        super(commandExecutor, name);
        this.commandExecutor = commandExecutor;
        this.redisson = redisson;

        comparatorHolder = redisson.getBucket(getComparatorKeyName(), StringCodec.INSTANCE);
        lock = redisson.getLock("redisson_sortedset_lock:{" + getName() + "}");
        list = (RedissonList<V>) redisson.<V>getList(getName());
    }

    public RedissonSortedSet(Codec codec, CommandExecutor commandExecutor, String name, Redisson redisson) {
        super(codec, commandExecutor, name);
        this.commandExecutor = commandExecutor;

        comparatorHolder = redisson.getBucket(getComparatorKeyName(), StringCodec.INSTANCE);
        lock = redisson.getLock("redisson_sortedset_lock:{" + getName() + "}");
        list = (RedissonList<V>) redisson.<V>getList(getName(), codec);
    }
    
    @Override
    public <KOut, VOut> RCollectionMapReduce<V, KOut, VOut> mapReduce() {
        return new RedissonCollectionMapReduce<V, KOut, VOut>(this, redisson, commandExecutor.getConnectionManager());
    }

    private void loadComparator() {
        try {
            String comparatorSign = comparatorHolder.get();
            if (comparatorSign != null) {
                String[] parts = comparatorSign.split(":");
                String className = parts[0];
                String sign = parts[1];

                String result = calcClassSign(className);
                if (!result.equals(sign)) {
                    throw new IllegalStateException("Local class signature of " + className + " differs from used by this SortedSet!");
                }

                Class<?> clazz = Class.forName(className);
                comparator = (Comparator<V>) clazz.newInstance();
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // TODO cache result
    private static String calcClassSign(String name) {
        try {
            Class<?> clazz = Class.forName(name);

            ByteArrayOutputStream result = new ByteArrayOutputStream();
            ObjectOutputStream outputStream = new ObjectOutputStream(result);
            outputStream.writeObject(clazz);
            outputStream.close();

            MessageDigest crypt = MessageDigest.getInstance("SHA-1");
            crypt.reset();
            crypt.update(result.toByteArray());

            return new BigInteger(1, crypt.digest()).toString(16);
        } catch (Exception e) {
            throw new IllegalStateException("Can't calculate sign of " + name, e);
        }
    }

    @Override
    public Collection<V> readAll() {
        return get(readAllAsync());
    }

    @Override
    public RFuture<Collection<V>> readAllAsync() {
        return commandExecutor.readAsync(getName(), codec, RedisCommands.LRANGE_SET, getName(), 0, -1);
    }
    
    @Override
    public int size() {
        return list.size();
    }

    @Override
    public boolean isEmpty() {
        return list.isEmpty();
    }

    @Override
    public boolean contains(final Object o) {
        return binarySearch((V) o, codec).getIndex() >= 0;
    }

    @Override
    public Iterator<V> iterator() {
        return list.iterator();
    }

    @Override
    public Object[] toArray() {
        return list.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return list.toArray(a);
    }

    @Override
    public boolean add(V value) {
        lock.lock();
        
        try {
            checkComparator();
    
            BinarySearchResult<V> res = binarySearch(value, codec);
            if (res.getIndex() < 0) {
                int index = -(res.getIndex() + 1);
                
                ByteBuf encodedValue = encode(value);
                
                commandExecutor.evalWrite(getName(), RedisCommands.EVAL_VOID, 
                   "local len = redis.call('llen', KEYS[1]);"
                    + "if tonumber(ARGV[1]) < len then "
                        + "local pivot = redis.call('lindex', KEYS[1], ARGV[1]);"
                        + "redis.call('linsert', KEYS[1], 'before', pivot, ARGV[2]);"
                        + "return;"
                    + "end;"
                    + "redis.call('rpush', KEYS[1], ARGV[2]);", Arrays.<Object>asList(getName()), index, encodedValue);
                return true;
            } else {
                return false;
            }
        } finally {
            lock.unlock();
        }
    }

    private void checkComparator() {
        String comparatorSign = comparatorHolder.get();
        if (comparatorSign != null) {
            String[] vals = comparatorSign.split(":");
            String className = vals[0];
            if (!comparator.getClass().getName().equals(className)) {
                loadComparator();
            }
        }
    }

    public RFuture<Boolean> addAsync(final V value) {
        final RPromise<Boolean> promise = new RedissonPromise<Boolean>();
        commandExecutor.getConnectionManager().getExecutor().execute(new Runnable() {
            public void run() {
                try {
                    boolean res = add(value);
                    promise.trySuccess(res);
                } catch (Exception e) {
                    promise.tryFailure(e);
                }
            }
        });
        return promise;
    }

    @Override
    public RFuture<Boolean> removeAsync(final Object value) {
        final RPromise<Boolean> promise = new RedissonPromise<Boolean>();
        commandExecutor.getConnectionManager().getExecutor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean result = remove(value);
                    promise.trySuccess(result);
                } catch (Exception e) {
                    promise.tryFailure(e);
                }
            }
        });

        return promise;
    }

    @Override
    public boolean remove(Object value) {
        lock.lock();

        try {
            checkComparator();
            
            BinarySearchResult<V> res = binarySearch((V) value, codec);
            if (res.getIndex() < 0) {
                return false;
            }

            list.remove((int) res.getIndex());
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        for (Object object : c) {
            if (!contains(object)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends V> c) {
        boolean changed = false;
        for (V v : c) {
            if (add(v)) {
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        boolean changed = false;
        for (Iterator<?> iterator = iterator(); iterator.hasNext();) {
            Object object = (Object) iterator.next();
            if (!c.contains(object)) {
                iterator.remove();
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        boolean changed = false;
        for (Object obj : c) {
            if (remove(obj)) {
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public void clear() {
        delete();
    }

    @Override
    public Comparator<? super V> comparator() {
        return comparator;
    }

    @Override
    public SortedSet<V> subSet(V fromElement, V toElement) {
        throw new UnsupportedOperationException();
//        return new RedissonSubSortedSet<V>(this, connectionManager, fromElement, toElement);
    }

    @Override
    public SortedSet<V> headSet(V toElement) {
        return subSet(null, toElement);
    }

    @Override
    public SortedSet<V> tailSet(V fromElement) {
        return subSet(fromElement, null);
    }

    @Override
    public V first() {
        V res = list.getValue(0);
        if (res == null) {
            throw new NoSuchElementException();
        }
        return res;
    }

    @Override
    public V last() {
        V res = list.getValue(-1);
        if (res == null) {
            throw new NoSuchElementException();
        }
        return res;
    }

    private String getComparatorKeyName() {
        return "redisson_sortedset_comparator:{" + getName() + "}";
    }

    @Override
    public boolean trySetComparator(Comparator<? super V> comparator) {
        String className = comparator.getClass().getName();
        final String comparatorSign = className + ":" + calcClassSign(className);

        Boolean res = commandExecutor.evalWrite(getName(), StringCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                "if redis.call('llen', KEYS[1]) == 0 then "
                + "redis.call('set', KEYS[2], ARGV[1]); "
                + "return 1; "
                + "else "
                + "return 0; "
                + "end",
                Arrays.<Object>asList(getName(), getComparatorKeyName()), comparatorSign);
        if (res) {
            this.comparator = comparator;
        }
        return res;
    }
    
    // TODO optimize: get three values each time instead of single
    public BinarySearchResult<V> binarySearch(V value, Codec codec) {
        int size = list.size();
        int upperIndex = size - 1;
        int lowerIndex = 0;
        while (lowerIndex <= upperIndex) {
            int index = lowerIndex + (upperIndex - lowerIndex) / 2;

            V res = list.getValue(index);
            if (res == null) {
                return new BinarySearchResult<V>();
            }
            int cmp = comparator.compare(value, res);

            if (cmp == 0) {
                BinarySearchResult<V> indexRes = new BinarySearchResult<V>();
                indexRes.setIndex(index);
                return indexRes;
            } else if (cmp < 0) {
                upperIndex = index - 1;
            } else {
                lowerIndex = index + 1;
            }
        }

        BinarySearchResult<V> indexRes = new BinarySearchResult<V>();
        indexRes.setIndex(-(lowerIndex + 1));
        return indexRes;
    }

    @SuppressWarnings("AvoidInlineConditionals")
    public String toString() {
        Iterator<V> it = iterator();
        if (! it.hasNext())
            return "[]";

        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (;;) {
            V e = it.next();
            sb.append(e == this ? "(this Collection)" : e);
            if (! it.hasNext())
                return sb.append(']').toString();
            sb.append(',').append(' ');
        }
    }

}
