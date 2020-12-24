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
import io.netty.buffer.Unpooled;
import org.redisson.api.RBinaryStream;
import org.redisson.api.RFuture;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.LongCodec;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.misc.RPromise;
import org.redisson.misc.RedissonPromise;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousByteChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;
import java.util.concurrent.Future;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class RedissonBinaryStream extends RedissonBucket<byte[]> implements RBinaryStream {

    class RedissonOutputStream extends OutputStream {
        
        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b});
        }
        
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            byte[] dest;
            if (b.length == len && off == 0) {
                dest = b;
            } else {
                dest = new byte[len];
                System.arraycopy(b, off, dest, 0, len);
            }
            get(commandExecutor.writeAsync(getName(), codec, RedisCommands.APPEND, getName(), dest));
        }
        
    }
    
    class RedissonInputStream extends InputStream {

        private long index;
        private long mark;
        
        @Override
        public long skip(long n) throws IOException {
            long k = size() - index;
            if (n < k) {
                k = n;
                if (n < 0) {
                    k = 0;
                }
            }

            index += k;
            return k;
        }
        
        @Override
        public synchronized void mark(int readlimit) {
            mark = index;
        }
        
        @Override
        public synchronized void reset() throws IOException {
            index = mark;
        }
        
        @Override
        public int available() throws IOException {
            return (int) (size() - index);
        }
        
        @Override
        public boolean markSupported() {
            return true;
        }
        
        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int len = read(b);
            if (len == -1) {
                return -1;
            }
            return b[0] & 0xff;
        }
        
        @Override
        public int read(byte[] b, int off, final int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (b == null) {
                throw new NullPointerException();
            }
            if (off < 0 || len < 0 || len > b.length - off) {
                throw new IndexOutOfBoundsException();
            }

            byte[] data = get(commandExecutor.readAsync(getName(), codec, RedisCommands.GETRANGE, getName(), index, index+len-1));
            if (data.length == 0) {
                return -1;
            }
            index += len;
            System.arraycopy(data, 0, b, off, data.length);
            return data.length;
        }

    }

    class RedissonByteChannel implements SeekableByteChannel {

        int position;

        @Override
        public int read(ByteBuffer dst) throws IOException {
            byte[] data = get(commandExecutor.readAsync(getName(), codec, RedisCommands.GETRANGE,
                        getName(), position, position+dst.remaining()-1));
            if (data.length == 0) {
                return -1;
            }
            position += data.length;
            dst.put(data);
            return data.length;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            ByteBuf b = Unpooled.wrappedBuffer(src);
            get(commandExecutor.writeAsync(getName(), codec, RedisCommands.SETRANGE, getName(), position, b));

            position += b.readableBytes();
            return b.readableBytes();
        }

        @Override
        public long position() throws IOException {
            return position;
        }

        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            this.position = (int) newPosition;
            return this;
        }

        @Override
        public long size() throws IOException {
            return RedissonBinaryStream.this.size();
        }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            get(commandExecutor.evalWriteAsync(getName(), LongCodec.INSTANCE, RedisCommands.EVAL_VOID,
                "local len = redis.call('strlen', KEYS[1]); " +
                        "if tonumber(ARGV[1]) >= len then " +
                            "return;" +
                        "end;"
                      + "local limitedValue = redis.call('getrange', KEYS[1], 0, tonumber(ARGV[1])-1); "
                      + "redis.call('set', KEYS[1], limitedValue); ",
             Arrays.asList(getName()), size));
            return this;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() throws IOException {
        }
    }

    public class RedissonAsynchronousByteChannel implements AsynchronousByteChannel {

        volatile int position;

        public long position() {
            return position;
        }

        public void position(long newPosition) {
            this.position = (int) newPosition;
        }

        @Override
        public <A> void read(ByteBuffer dst, A attachment, CompletionHandler<Integer, ? super A> handler) {
            RFuture<Integer> res = (RFuture<Integer>) read(dst);
            res.onComplete((r, e) -> {
                if (e != null) {
                    handler.failed(e, attachment);
                } else {
                    handler.completed(r, attachment);
                }
            });
        }

        @Override
        public Future<Integer> read(ByteBuffer dst) {
            RPromise<Integer> result = new RedissonPromise<>();
            RFuture<byte[]> res = commandExecutor.readAsync(getName(), codec, RedisCommands.GETRANGE,
                        getName(), position, position + dst.remaining() - 1);
            res.onComplete((data, e) -> {
                if (e != null) {
                    result.tryFailure(e);
                    return;
                }
                if (data.length == 0) {
                    result.trySuccess(-1);
                    return;
                }

                position += data.length;
                dst.put(data);
                result.trySuccess(data.length);
            });

            return result;
        }

        @Override
        public <A> void write(ByteBuffer src, A attachment, CompletionHandler<Integer, ? super A> handler) {
            RFuture<Integer> res = (RFuture<Integer>) write(src);
            res.onComplete((r, e) -> {
                if (e != null) {
                    handler.failed(e, attachment);
                } else {
                    handler.completed(r, attachment);
                }
            });
        }

        @Override
        public Future<Integer> write(ByteBuffer src) {
            RPromise<Integer> result = new RedissonPromise<>();

            ByteBuf b = Unpooled.wrappedBuffer(src);
            RFuture<Long> res = commandExecutor.writeAsync(getName(), codec, RedisCommands.SETRANGE, getName(), position, b);
            res.onComplete((r, e) -> {
                if (e != null) {
                    result.tryFailure(e);
                    return;
                }

                position += b.readableBytes();
                result.trySuccess(b.readableBytes());
            });

            return result;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() throws IOException {
        }

    }

    protected RedissonBinaryStream(CommandAsyncExecutor connectionManager, String name) {
        super(ByteArrayCodec.INSTANCE, connectionManager, name);
    }
    
    @Override
    public InputStream getInputStream() {
        return new RedissonInputStream();
    }

    @Override
    public OutputStream getOutputStream() {
        return new RedissonOutputStream();
    }

    @Override
    public SeekableByteChannel getChannel() {
        return new RedissonByteChannel();
    }

    @Override
    public AsynchronousByteChannel getAsynchronousChannel() {
        return new RedissonAsynchronousByteChannel();
    }
}
