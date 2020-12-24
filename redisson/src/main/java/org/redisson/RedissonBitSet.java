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

import org.redisson.api.RBitSet;
import org.redisson.api.RFuture;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.LongCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.command.CommandBatchService;

import java.util.*;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class RedissonBitSet extends RedissonExpirable implements RBitSet {

    public RedissonBitSet(CommandAsyncExecutor connectionManager, String name) {
        super(null, connectionManager, name);
    }

    @Override
    public byte getByte(long offset) {
        return get(getByteAsync(offset));
    }

    @Override
    public byte setByte(long offset, byte value) {
        return get(setByteAsync(offset, value));
    }

    @Override
    public byte incrementAndGetByte(long offset, byte increment) {
        return get(incrementAndGetByteAsync(offset, increment));
    }

    @Override
    public short getShort(long offset) {
        return get(getShortAsync(offset));
    }

    @Override
    public short setShort(long offset, short value) {
        return get(setShortAsync(offset, value));
    }

    @Override
    public short incrementAndGetShort(long offset, short increment) {
        return get(incrementAndGetShortAsync(offset, increment));
    }

    @Override
    public int getInteger(long offset) {
        return get(getIntegerAsync(offset));
    }

    @Override
    public int setInteger(long offset, int value) {
        return get(setIntegerAsync(offset, value));
    }

    @Override
    public int incrementAndGetInteger(long offset, int increment) {
        return get(incrementAndGetIntegerAsync(offset, increment));
    }

    @Override
    public long getLong(long offset) {
        return get(getLongAsync(offset));
    }

    @Override
    public long setLong(long offset, long value) {
        return get(setLongAsync(offset, value));
    }

    @Override
    public long incrementAndGetLong(long offset, long increment) {
        return get(incrementAndGetLongAsync(offset, increment));
    }

    @Override
    public RFuture<Byte> getByteAsync(long offset) {
        return commandExecutor.readAsync(getName(), LongCodec.INSTANCE, RedisCommands.BITFIELD_BYTE,
                                            getName(), "GET", "i8", offset);
    }

    @Override
    public RFuture<Byte> setByteAsync(long offset, byte value) {
        return commandExecutor.writeAsync(getName(), LongCodec.INSTANCE, RedisCommands.BITFIELD_BYTE,
                                            getName(), "SET", "i8", offset, value);
    }

    @Override
    public RFuture<Byte> incrementAndGetByteAsync(long offset, byte increment) {
        return commandExecutor.writeAsync(getName(), LongCodec.INSTANCE, RedisCommands.BITFIELD_BYTE,
                                            getName(), "INCRBY", "i8", offset, increment);
    }

    @Override
    public RFuture<Short> getShortAsync(long offset) {
        return commandExecutor.readAsync(getName(), LongCodec.INSTANCE, RedisCommands.BITFIELD_SHORT,
                                            getName(), "GET", "i16", offset);
    }

    @Override
    public RFuture<Short> setShortAsync(long offset, short value) {
        return commandExecutor.writeAsync(getName(), LongCodec.INSTANCE, RedisCommands.BITFIELD_SHORT,
                                            getName(), "SET", "i16", offset, value);
    }

    @Override
    public RFuture<Short> incrementAndGetShortAsync(long offset, short increment) {
        return commandExecutor.writeAsync(getName(), LongCodec.INSTANCE, RedisCommands.BITFIELD_SHORT,
                                            getName(), "INCRBY", "i16", offset, increment);
    }

    @Override
    public RFuture<Integer> getIntegerAsync(long offset) {
        return commandExecutor.readAsync(getName(), LongCodec.INSTANCE, RedisCommands.BITFIELD_INT,
                                            getName(), "GET", "i32", offset);
    }

    @Override
    public RFuture<Integer> setIntegerAsync(long offset, int value) {
        return commandExecutor.writeAsync(getName(), LongCodec.INSTANCE, RedisCommands.BITFIELD_INT,
                                            getName(), "SET", "i32", offset, value);
    }

    @Override
    public RFuture<Integer> incrementAndGetIntegerAsync(long offset, int increment) {
        return commandExecutor.writeAsync(getName(), LongCodec.INSTANCE, RedisCommands.BITFIELD_INT,
                                            getName(), "INCRBY", "i32", offset, increment);
    }

    @Override
    public RFuture<Long> getLongAsync(long offset) {
        return commandExecutor.readAsync(getName(), LongCodec.INSTANCE, RedisCommands.BITFIELD_LONG,
                                            getName(), "GET", "i64", offset);
    }

    @Override
    public RFuture<Long> setLongAsync(long offset, long value) {
        return commandExecutor.writeAsync(getName(), LongCodec.INSTANCE, RedisCommands.BITFIELD_LONG,
                                            getName(), "SET", "i64", offset, value);
    }

    @Override
    public RFuture<Long> incrementAndGetLongAsync(long offset, long increment) {
        return commandExecutor.writeAsync(getName(), LongCodec.INSTANCE, RedisCommands.BITFIELD_LONG,
                                            getName(), "INCRBY", "i64", offset, increment);
    }

    @Override
    public long length() {
        return get(lengthAsync());
    }

    @Override
    public void set(BitSet bs) {
        get(setAsync(bs));
    }

    @Override
    public boolean get(long bitIndex) {
        return get(getAsync(bitIndex));
    }

    @Override
    public RFuture<Boolean> getAsync(long bitIndex) {
        return commandExecutor.readAsync(getName(), LongCodec.INSTANCE, RedisCommands.GETBIT, getName(), bitIndex);
    }

    @Override
    public boolean set(long bitIndex) {
        return get(setAsync(bitIndex, true));
    }

    @Override
    public void set(long fromIndex, long toIndex, boolean value) {
        get(setAsync(fromIndex, toIndex, value));
    }

    @Override
    public void set(long fromIndex, long toIndex) {
        get(setAsync(fromIndex, toIndex));
    }

    @Override
    public boolean set(long bitIndex, boolean value) {
        return get(setAsync(bitIndex, value));
    }

    @Override
    public RFuture<Boolean> setAsync(long bitIndex, boolean value) {
        int val = toInt(value);
        return commandExecutor.writeAsync(getName(), LongCodec.INSTANCE, RedisCommands.SETBIT, getName(), bitIndex, val);
    }

    protected int toInt(boolean value) {
        int val = 0;
        if (value) {
            val = 1;
        }
        return val;
    }

    @Override
    public byte[] toByteArray() {
        return get(toByteArrayAsync());
    }

    @Override
    public RFuture<byte[]> toByteArrayAsync() {
        return commandExecutor.readAsync(getName(), ByteArrayCodec.INSTANCE, RedisCommands.GET, getName());
    }

    @Override
    public long cardinality() {
        return get(cardinalityAsync());
    }

    @Override
    public long size() {
        return get(sizeAsync());
    }

    @Override
    public void clear(long fromIndex, long toIndex) {
        get(clearAsync(fromIndex, toIndex));
    }

    @Override
    public boolean clear(long bitIndex) {
        return get(clearAsync(bitIndex));
    }

    @Override
    public void clear() {
        get(clearAsync());
    }

    @Override
    public void or(String... bitSetNames) {
        get(orAsync(bitSetNames));
    }

    @Override
    public void and(String... bitSetNames) {
        get(andAsync(bitSetNames));
    }

    @Override
    public void xor(String... bitSetNames) {
        get(xorAsync(bitSetNames));
    }

    @Override
    public void not() {
        get(notAsync());
    }

    private RFuture<Void> opAsync(String op, String... bitSetNames) {
        List<Object> params = new ArrayList<Object>(bitSetNames.length + 3);
        params.add(op);
        params.add(getName());
        params.add(getName());
        params.addAll(Arrays.asList(bitSetNames));
        return commandExecutor.writeAsync(getName(), StringCodec.INSTANCE, RedisCommands.BITOP, params.toArray());
    }

    @Override
    public BitSet asBitSet() {
        return fromByteArrayReverse(toByteArray());
    }

    //Copied from: https://github.com/xetorthio/jedis/issues/301
    private static BitSet fromByteArrayReverse(byte[] bytes) {
        if (bytes == null) {
            return new BitSet();
        }

        BitSet bits = new BitSet();
        for (int i = 0; i < bytes.length * 8; i++) {
            if ((bytes[i / 8] & (1 << (7 - (i % 8)))) != 0) {
                bits.set(i);
            }
        }
        return bits;
    }

    //Copied from: https://github.com/xetorthio/jedis/issues/301
    private static byte[] toByteArrayReverse(BitSet bits) {
        byte[] bytes = new byte[bits.length() / 8 + 1];
        for (int i = 0; i < bits.length(); i++) {
            if (bits.get(i)) {
                final int value = bytes[i / 8] | (1 << (7 - (i % 8)));
                bytes[i / 8] = (byte) value;
            }
        }
        return bytes;
    }

    @Override
    public String toString() {
        return asBitSet().toString();
    }

    @Override
    public RFuture<Long> lengthAsync() {
        return commandExecutor.evalReadAsync(getName(), LongCodec.INSTANCE, RedisCommands.EVAL_LONG,
                "local fromBit = redis.call('bitpos', KEYS[1], 1, -1);"
                + "local toBit = 8*(fromBit/8 + 1) - fromBit % 8;"
                        + "for i = toBit, fromBit, -1 do "
                            + "if redis.call('getbit', KEYS[1], i) == 1 then "
                                + "return i+1;"
                            + "end;"
                       + "end;" +
                     "return fromBit+1",
                Collections.<Object>singletonList(getName()));
    }

    @Override
    public RFuture<Void> setAsync(long fromIndex, long toIndex, boolean value) {
        int val = toInt(value);
        CommandBatchService executorService = new CommandBatchService(commandExecutor.getConnectionManager());
        for (long i = fromIndex; i < toIndex; i++) {
            executorService.writeAsync(getName(), LongCodec.INSTANCE, RedisCommands.SETBIT_VOID, getName(), i, val);
        }
        return executorService.executeAsyncVoid();
    }

    @Override
    public RFuture<Void> clearAsync(long fromIndex, long toIndex) {
        return setAsync(fromIndex, toIndex, false);
    }

    @Override
    public RFuture<Void> setAsync(BitSet bs) {
        return commandExecutor.writeAsync(getName(), ByteArrayCodec.INSTANCE, RedisCommands.SET, getName(), toByteArrayReverse(bs));
    }

    @Override
    public RFuture<Void> notAsync() {
        return opAsync("NOT");
    }

    @Override
    public RFuture<Void> setAsync(long fromIndex, long toIndex) {
        return setAsync(fromIndex, toIndex, true);
    }

    @Override
    public RFuture<Long> sizeAsync() {
        return commandExecutor.readAsync(getName(), LongCodec.INSTANCE, RedisCommands.BITS_SIZE, getName());
    }

    @Override
    public RFuture<Boolean> setAsync(long bitIndex) {
        return setAsync(bitIndex, true);
    }

    @Override
    public RFuture<Long> cardinalityAsync() {
        return commandExecutor.readAsync(getName(), LongCodec.INSTANCE, RedisCommands.BITCOUNT, getName());
    }

    @Override
    public RFuture<Boolean> clearAsync(long bitIndex) {
        return setAsync(bitIndex, false);
    }

    @Override
    public RFuture<Void> clearAsync() {
        return commandExecutor.writeAsync(getName(), RedisCommands.DEL_VOID, getName());
    }

    @Override
    public RFuture<Void> orAsync(String... bitSetNames) {
        return opAsync("OR", bitSetNames);
    }

    @Override
    public RFuture<Void> andAsync(String... bitSetNames) {
        return opAsync("AND", bitSetNames);
    }

    @Override
    public RFuture<Void> xorAsync(String... bitSetNames) {
        return opAsync("XOR", bitSetNames);
    }

}
