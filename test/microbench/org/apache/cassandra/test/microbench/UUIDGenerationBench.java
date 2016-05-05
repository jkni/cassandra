/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.test.microbench;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Threads(1)
@Fork(1)
public class UUIDGenerationBench
{
    @State(Scope.Benchmark)
    public static class UUIDGenNew {
        private AtomicLong lastNanos = new AtomicLong();

        private final long START_EPOCH = -12219292800000L;

        private long createTimeSafe()
        {
            long newLastNanos;
            while (true)
            {
                //Generate a candidate value for new lastNanos
                newLastNanos = (System.currentTimeMillis() - START_EPOCH) * 10000;
                long originalLastNanos = lastNanos.get();
                if (newLastNanos > originalLastNanos)
                {
                    //Slow path once per millisecond do a CAS
                    if (lastNanos.compareAndSet(originalLastNanos, newLastNanos))
                    {
                        break;
                    }
                }
                else
                {
                    //Fast path do an atomic increment
                    //Or when falling behind this will move time forward past the clock if necessary
                    newLastNanos = lastNanos.incrementAndGet();
                    break;
                }
            }
            return createTime(newLastNanos);
        }

        private static long createTime(long nanosSince)
        {
            long msb = 0L;
            msb |= (0x00000000ffffffffL & nanosSince) << 32;
            msb |= (0x0000ffff00000000L & nanosSince) >>> 16;
            msb |= (0xffff000000000000L & nanosSince) >>> 48;
            msb |= 0x0000000000001000L; // sets the version to 1.
            return msb;
        }

    }

    @State(Scope.Benchmark)
    public static class UUIDGenOld {
        private long lastNanos;

        private final long START_EPOCH = -12219292800000L;

        private synchronized long createTimeSafe()
        {
            long nanosSince = (System.currentTimeMillis() - START_EPOCH) * 10000;
            if (nanosSince > lastNanos)
                lastNanos = nanosSince;
            else
                nanosSince = ++lastNanos;

            return createTime(nanosSince);
        }

        private static long createTime(long nanosSince)
        {
            long msb = 0L;
            msb |= (0x00000000ffffffffL & nanosSince) << 32;
            msb |= (0x0000ffff00000000L & nanosSince) >>> 16;
            msb |= (0xffff000000000000L & nanosSince) >>> 48;
            msb |= 0x0000000000001000L; // sets the version to 1.
            return msb;
        }
    }

    @State(Scope.Benchmark)
    public static class UUIDGenBaseline {
        private static long lastNanos;

        private final long START_EPOCH = -12219292800000L;

        private long createTimeSafe()
        {
            lastNanos = createTime(lastNanos);
            return lastNanos;
        }

        private static long createTime(long nanosSince)
        {
            return lastNanos++;
        }
    }

    @Benchmark
    public long testBaseline(UUIDGenBaseline uuidGen)
    {
        return uuidGen.createTimeSafe();
    }

    @Benchmark
    public long testOld(UUIDGenOld uuidGen)
    {
        return uuidGen.createTimeSafe();
    }

    @Benchmark
    public long testNew(UUIDGenNew uuidGen)
    {
        return uuidGen.createTimeSafe();
    }
}