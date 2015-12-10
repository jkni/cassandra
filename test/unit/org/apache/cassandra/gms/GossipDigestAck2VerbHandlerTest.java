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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


public class GossipDigestAck2VerbHandlerTest
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
        // Prevent echo message from going out after nodes are added to the cluster
        MessagingService.instance().addMessageSink(new IMessageSink()
        {
            public boolean allowOutgoingMessage(MessageOut message, int id, InetAddress to)
            {
                return false;
            }

            public boolean allowIncomingMessage(MessageIn message, int id)
            {
                return false;
            }
        });

        EndpointState endpointState = new EndpointState(new HeartBeatState(1,1));

        Map<InetAddress, EndpointState> badEndpointStateMap = new HashMap<>();
        badEndpointStateMap.put(InetAddress.getByName("127.0.0.3"), endpointState);

        Map<InetAddress, EndpointState> goodEndpointStateMap = new HashMap<>();
        goodEndpointStateMap.put(InetAddress.getByName("127.0.0.4"), endpointState);


        // Payload for bad ack2 message
        GossipDigestAck2 badPayload = new GossipDigestAck2(DatabaseDescriptor.getClusterName() + "1",
                                                           badEndpointStateMap);

        // Payload for good ack2 message
        GossipDigestAck2 goodPayload = new GossipDigestAck2(DatabaseDescriptor.getClusterName(),
                                                            goodEndpointStateMap);

        // Construct the bad ack2 message with delta for 127.0.0.3
        MessageIn<GossipDigestAck2> badAck2Message = MessageIn.create(InetAddress.getByName("127.0.0.2"),
                                                                      badPayload,
                                                                      Collections.emptyMap(),
                                                                      MessagingService.Verb.GOSSIP_DIGEST_ACK2,
                                                                      MessagingService.current_version,
                                                                      MessageIn.createTimestamp());

        // Construct the good ack2 message with delta for 127.0.0.4
        MessageIn<GossipDigestAck2> goodAck2Message = MessageIn.create(InetAddress.getByName("127.0.0.2"),
                                                                       goodPayload,
                                                                       Collections.emptyMap(),
                                                                       MessagingService.Verb.GOSSIP_DIGEST_ACK2,
                                                                       MessagingService.current_version,
                                                                       MessageIn.createTimestamp());

        GossipDigestAck2VerbHandler gda2vh = new GossipDigestAck2VerbHandler();

        gda2vh.doVerb(badAck2Message, 1);
        gda2vh.doVerb(goodAck2Message, 1);

        // We should only have applied state from the good ack2 message
        assertFalse(Gossiper.instance.isKnownEndpoint(InetAddress.getByName("127.0.0.3")));
        assertTrue(Gossiper.instance.isKnownEndpoint(InetAddress.getByName("127.0.0.4")));
    }
}