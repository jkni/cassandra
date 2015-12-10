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

package org.apache.cassandra.gms;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.net.IMessageSink;
import org.apache.cassandra.net.MessageIn;
import org.apache.cassandra.net.MessageOut;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.concurrent.SimpleCondition;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GossipDigestAckVerbHandlerTest
{
    @BeforeClass
    public static void setupClass() throws ConfigurationException
    {
        SchemaLoader.loadSchema();
    }

    @After
    public void tearDown()
    {
        MessagingService.instance().clearMessageSinks();
    }

    @Test
    public void forbidInterclusterGossip() throws UnknownHostException, InterruptedException
    {
        final SimpleCondition lock = new SimpleCondition();

        List<GossipDigest> gDigestList = new ArrayList<>();

        gDigestList.add(new GossipDigest(FBUtilities.getBroadcastAddress(), -1, -1));

        // Construct the bad ack message with a bad cluster name and empty digest list
        MessageIn<GossipDigestAck> badAckMessage = MessageIn.create(InetAddress.getByName("127.0.0.2"),
                                                                    new GossipDigestAck(DatabaseDescriptor.getClusterName() + "1",
                                                                                        Collections.emptyList(),
                                                                                        Collections.emptyMap()),
                                                                    Collections.emptyMap(),
                                                                    MessagingService.Verb.GOSSIP_DIGEST_ACK,
                                                                    MessagingService.current_version,
                                                                    MessageIn.createTimestamp());

        // Construct the good ack message with matching cluster name and digest list asking for non-empty response
        MessageIn<GossipDigestAck> goodAckMessage = MessageIn.create(InetAddress.getByName("127.0.0.2"),
                                                                     new GossipDigestAck(DatabaseDescriptor.getClusterName(),
                                                                                         gDigestList,
                                                                                         Collections.emptyMap()),
                                                                     Collections.emptyMap(),
                                                                     MessagingService.Verb.GOSSIP_DIGEST_ACK,
                                                                     MessagingService.current_version,
                                                                     MessageIn.createTimestamp());

        MessagingService.instance().addMessageSink(new IMessageSink()
        {
            public boolean allowOutgoingMessage(MessageOut message, int id, InetAddress to)
            {
                if (message.verb == MessagingService.Verb.GOSSIP_DIGEST_ACK2)
                {
                    GossipDigestAck2 payload = ((MessageOut<GossipDigestAck2>) message).payload;

                    // Verify we send out a matching cluster name
                    assertTrue(payload.clusterId == DatabaseDescriptor.getClusterName());

                    // If the payload is empty, we're replying to the bad ack message
                    assertFalse(payload.getEndpointStateMap().isEmpty());

                    lock.signalAll();
                }

                return false;
            }

            public boolean allowIncomingMessage(MessageIn message, int id)
            {
                return false;
            }
        });

        GossipDigestAckVerbHandler gdavh = new GossipDigestAckVerbHandler();
        gdavh.doVerb(badAckMessage, 1);
        gdavh.doVerb(goodAckMessage, 1);
        lock.await();
    }
}
