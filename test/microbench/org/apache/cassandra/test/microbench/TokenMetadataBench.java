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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.Util;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.Murmur3Partitioner;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.locator.TokenMetadata;
import org.apache.cassandra.service.StorageService;
import org.apache.tools.ant.types.resources.Tokens;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
@Threads(80)
@State(Scope.Benchmark)
public class TokenMetadataBench
{
    StorageService ss = StorageService.instance;
    TokenMetadata tmd = ss.getTokenMetadata();

    ArrayList<Set<Token>> endpointTokens = new ArrayList<>();
    List<InetAddress> hosts = new ArrayList<>();
    List<UUID> hostIds = new ArrayList<>();

    Collection<Token> updateTokens;
    InetAddress updateHost;

    @Setup
    public void setup() {
        SchemaLoader.prepareServer();

        tmd.clearUnsafe();
        try
        {
            Util.createInitialRingVnodes(ss, DatabaseDescriptor.getPartitioner(), 256, endpointTokens, hosts, hostIds, 500);
            updateTokens = endpointTokens.get(0);
            updateHost = hosts.get(0);


        }
        catch (UnknownHostException e)
        {
            e.printStackTrace();
        }
    }

    @Benchmark
    @Group("g")
    @GroupThreads(32)
    public void cachedOnlyTokenMap(Blackhole bh)
    {
        bh.consume(tmd.cachedOnlyTokenMap());
    }

    @Benchmark
    @Group("g")
    @GroupThreads(8)
    public void invalidate()
    {
        tmd.updateNormalTokens(updateTokens, updateHost);
    }
}
