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

import java.util.BitSet;

/**
 * Reactive interface for BitSet object
 *
 * @author Nikita Koksharov
 *
 */
public interface RBitSetReactive extends RExpirableReactive {

    /**
     * Returns byte number at specified <code>offset</code>
     *
     * @param offset - offset of number
     * @return number
     */
    Mono<Byte> getByte(long offset);

    /**
     * Returns previous value of byte number and replaces it
     * with defined <code>value</code> at specified <code>offset</code>
     *
     * @param offset - offset of number
     * @param value - value of number
     * @return previous value of number
     */
    Mono<Byte> setByte(long offset, byte value);

    /**
     * Increments current byte value on defined <code>increment</code> value at specified <code>offset</code>
     * and returns result.
     *
     * @param offset - offset of number
     * @param increment - increment value
     * @return result value
     */
    Mono<Byte> incrementAndGetByte(long offset, byte increment);

    /**
     * Returns short number at specified <code>offset</code>
     *
     * @param offset - offset of number
     * @return number
     */
    Mono<Short> getShort(long offset);

    /**
     * Returns previous value of short number and replaces it
     * with defined <code>value</code> at specified <code>offset</code>
     *
     * @param offset - offset of number
     * @param value - value of number
     * @return previous value of number
     */
    Mono<Short> setShort(long offset, short value);

    /**
     * Increments current short value on defined <code>increment</code> value at specified <code>offset</code>
     * and returns result.
     *
     * @param offset - offset of number
     * @param increment - increment value
     * @return result value
     */
    Mono<Short> incrementAndGetShort(long offset, short increment);

    /**
     * Returns integer number at specified <code>offset</code>
     *
     * @param offset - offset of number
     * @return number
     */
    Mono<Integer> getInteger(long offset);

    /**
     * Returns previous value of integer number and replaces it
     * with defined <code>value</code> at specified <code>offset</code>
     *
     * @param offset - offset of number
     * @param value - value of number
     * @return previous value of number
     */
    Mono<Integer> setInteger(long offset, int value);

    /**
     * Increments current integer value on defined <code>increment</code> value at specified <code>offset</code>
     * and returns result.
     *
     * @param offset - offset of number
     * @param increment - increment value
     * @return result value
     */
    Mono<Integer> incrementAndGetInteger(long offset, int increment);

    /**
     * Returns long number at specified <code>offset</code>
     *
     * @param offset - offset of number
     * @return number
     */
    Mono<Long> getLong(long offset);

    /**
     * Returns previous value of long number and replaces it
     * with defined <code>value</code> at specified <code>offset</code>
     *
     * @param offset - offset of number
     * @param value - value of number
     * @return previous value of number
     */
    Mono<Long> setLong(long offset, long value);

    /**
     * Increments current long value on defined <code>increment</code> value at specified <code>offset</code>
     * and returns result.
     *
     * @param offset - offset of number
     * @param increment - increment value
     * @return result value
     */
    Mono<Long> incrementAndGetLong(long offset, long increment);

    Mono<byte[]> toByteArray();

    /**
     * Returns "logical size" = index of highest set bit plus one.
     * Returns zero if there are no any set bit.
     * 
     * @return "logical size" = index of highest set bit plus one
     */
    Mono<Long> length();

    /**
     * Set all bits to <code>value</code> from <code>fromIndex</code> (inclusive) to <code>toIndex</code> (exclusive)
     * 
     * @param fromIndex inclusive
     * @param toIndex exclusive
     * @param value true = 1, false = 0
     * @return void
     * 
     */
    Mono<Void> set(long fromIndex, long toIndex, boolean value);

    /**
     * Set all bits to zero from <code>fromIndex</code> (inclusive) to <code>toIndex</code> (exclusive)
     * 
     * @param fromIndex inclusive
     * @param toIndex exclusive
     * @return void
     * 
     */
    Mono<Void> clear(long fromIndex, long toIndex);

    /**
     * Copy bits state of source BitSet object to this object
     * 
     * @param bs - BitSet source
     * @return void
     */
    Mono<Void> set(BitSet bs);

    /**
     * Executes NOT operation over all bits
     * 
     * @return void
     */
    Mono<Void> not();

    /**
     * Set all bits to one from <code>fromIndex</code> (inclusive) to <code>toIndex</code> (exclusive)
     * 
     * @param fromIndex inclusive
     * @param toIndex exclusive
     * @return void
     */
    Mono<Void> set(long fromIndex, long toIndex);

    /**
     * Returns number of set bits.
     * 
     * @return number of set bits.
     */
    Mono<Long> size();

    /**
     * Returns <code>true</code> if bit set to one and <code>false</code> overwise.
     * 
     * @param bitIndex - index of bit
     * @return <code>true</code> if bit set to one and <code>false</code> overwise.
     */
    Mono<Boolean> get(long bitIndex);

    /**
     * Set bit to one at specified bitIndex
     * 
     * @param bitIndex - index of bit
     * @return <code>true</code> - if previous value was true, 
     * <code>false</code> - if previous value was false
     */
    Mono<Boolean> set(long bitIndex);

    /**
     * Set bit to <code>value</code> at specified <code>bitIndex</code>
     * 
     * @param bitIndex - index of bit
     * @param value true = 1, false = 0
     * @return <code>true</code> - if previous value was true, 
     * <code>false</code> - if previous value was false
     */
    Mono<Boolean> set(long bitIndex, boolean value);

    /**
     * Returns the number of bits set to one.
     * 
     * @return number of bits
     */
    Mono<Long> cardinality();

    /**
     * Set bit to zero at specified <code>bitIndex</code>
     *
     * @param bitIndex - index of bit
     * @return <code>true</code> - if previous value was true, 
     * <code>false</code> - if previous value was false
     */
    Mono<Boolean> clear(long bitIndex);

    /**
     * Set all bits to zero
     * 
     * @return void
     */
    Mono<Void> clear();

    /**
     * Executes OR operation over this object and specified bitsets.
     * Stores result into this object.
     * 
     * @param bitSetNames - name of stored bitsets
     * @return void
     */
    Mono<Void> or(String... bitSetNames);

    /**
     * Executes AND operation over this object and specified bitsets.
     * Stores result into this object.
     * 
     * @param bitSetNames - name of stored bitsets
     * @return void
     */
    Mono<Void> and(String... bitSetNames);

    /**
     * Executes XOR operation over this object and specified bitsets.
     * Stores result into this object.
     * 
     * @param bitSetNames - name of stored bitsets
     * @return void
     */
    Mono<Void> xor(String... bitSetNames);

}
