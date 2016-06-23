.. Licensed to the Apache Software Foundation (ASF) under one
.. or more contributor license agreements.  See the NOTICE file
.. distributed with this work for additional information
.. regarding copyright ownership.  The ASF licenses this file
.. to you under the Apache License, Version 2.0 (the
.. "License"); you may not use this file except in compliance
.. with the License.  You may obtain a copy of the License at
..
..     http://www.apache.org/licenses/LICENSE-2.0
..
.. Unless required by applicable law or agreed to in writing, software
.. distributed under the License is distributed on an "AS IS" BASIS,
.. WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
.. See the License for the specific language governing permissions and
.. limitations under the License.

Dynamo
------

Gossip
^^^^^^

Cassandra employs a peer-to-peer metadata sharing protocol called ``gossip`` in order to distribute information about
node state and membership throughout the cluster. The gossip protocol ensures that every node eventually knows
this information through periodic anti-entropy exchanges.

Each node maintains an ``EndpointState`` for every node in the
cluster, which is used to track both a ``HeartBeatState`` and a map from ``ApplicationState``s to
``VersionedValue``s. A ``HeartBeatState`` consists of a generation which is set on gossip service initialization and a
version that is incremented before each gossip exchange. An ``ApplicationState`` is an enum value representing the type
of information being stored, such as the node's datacenter or the node's tokens. A ``VersionedValue`` consists of some
state and a version number. This version number is drawn from the same source as the ``HeartBeatState``'s version, such
that the version number is monotonically increasing across all values and heartbeats for a given generation.
A node only directly modifies its own ``EndpointState``, which it then disseminates through gossip. Correspondingly, it
only updates ``EndpointState`` components for other nodes through gossip.

By default, each node initiates a new gossip round every second. This round starts by incrementing the local heartbeat
version. Then, the node picks a random live member with which to gossip. Next, the node will gossip to a down member
with some probability, where the probability of this exchange increases with the ratio of down nodes to live nodes.
Lastly, if the node did not gossip with a live member who is a seed or if there are fewer live endpoints than seeds, the
node will gossip with a seed with some probability, where the probability of this exchanges increases with the ratio
of seeds to total node count. These node selections aim to accelerate convergence of gossip and avoid partitions in
gossip.

With each gossip target selected, the node initiates a 3 way handshake consisting of a ``GossipDigestSyn`` message, a
``GossipDigestAck`` message in reply, and a final ``GossipDigestAck2`` message. The ``GossipDigestSyn`` message contains
the cluster identifier, the partitioner in use, and a ``GossipDigest`` for each ``EndpointState``. The cluster
identifier prevents gossip between misconfigured clusters. The partitioner enabled a historical upgrade path. Lastly,
each digest consists of the endpoint address, generation, and the highest version across all ``ApplicationState``s and
the ``HeartBeatState`` for the given ``EndpointState``. In response to this ``GossipDigestSynMessage``, the receiving
node replies with a ``GossipDigestAck``. This ``GossipDigestAck`` contains a list of gossip digests for endpoints which
the originating node has newer values (determined by the ``GossipDigestSyn`` digests) and a map of endpoints to
``EndpointState``s. These ``EndpointStates`` contain the states for which the receiving node has newer values. Note that
these states are totally ordered first by the generation and then by the version, and when a node has an
``EndpointState`` with a newer generation, it must send all ``ApplicationState``s. Lastly, in response to this
``GossipDigestAck`` message, the originating node will send a ``GossipDigestAck2`` message, which contains the
``EndpointState`` components newer than the digests in the ``GossipDigestAckMessage``.


ApplicationState
~~~~~~~~~~~~~~~~
Cassandra distributes many kinds of metadata as ``ApplicationState``s through gossip.

====================== =====================================
Name                   Description
====================== =====================================
STATUS                 The node's membership status in the cluster, such as whether the node is bootstrapping or removed.
LOAD                   Disk space used by SSTables on the node
SCHEMA                 UUID of the node's current schema
DC                     Node's datacenter, used by certain snitches
RACK                   Node's rack, used by certain snitches
RELEASE_VERSION        The release of Cassandra running on the node
REMOVAL_COORDINATOR    The coordinator for removal of a node through ``nodetool removenode``
INTERNAL_IP            Datacenter-local address used with reconnectable snitches for intra-datacenter communication
RPC_ADDRESS            The configured broadcast_rpc_address for the node
SEVERITY               A heuristic approximation of compaction activity on the node, used for dynamic snitch routing
NET_VERSION            The ``MessagingService`` version of the node
HOST_ID                UUID identifier for the node
TOKENS                 Tokens the node claims in the token ring
RPC_READY              Whether the node is ready to receive RPC connections
====================== =====================================

Tuning Gossip
~~~~~~~~~~~~~
To clear saved gossip state from a node, the node can be started with ``-Dcassandra.load_ring_state=true``.

To prevent a node from joining the ring, a node can be started with ``-Dcassandra.join_ring=false``. This node will still
participate in gossip.

To adjust the ring delay, a node can be started with ``-Dcassandra.ring_delay_ms``. Since ``ring_delay`` is used to wait to
estimate when gossip states have been fully propagated, this may need to be tuned for larger clusters.



.. todo:: todo

Failure Detection
^^^^^^^^^^^^^^^^^

.. todo:: todo

Token Ring/Ranges
^^^^^^^^^^^^^^^^^

Cassandra achieves
.. todo:: todo

.. _replication-strategy:

Replication
^^^^^^^^^^^

The replication strategy of a keyspace determines which nodes are replicas for a given token range. The two main
replication strategies are :ref:`simple-strategy` and :ref:`network-topology-strategy`.

.. _simple-strategy:

SimpleStrategy
~~~~~~~~~~~~~~

SimpleStrategy allows a single integer ``replication_factor`` to be defined. This determines the number of nodes that
should contain a copy of each row.  For example, if ``replication_factor`` is 3, then three different nodes should store
a copy of each row.

SimpleStrategy treats all nodes identically, ignoring any configured datacenters or racks.  To determine the replicas
for a token range, Cassandra iterates through the tokens in the ring, starting with the token range of interest.  For
each token, it checks whether the owning node has been added to the set of replicas, and if it has not, it is added to
the set.  This process continues until ``replication_factor`` distinct nodes have been added to the set of replicas.

.. _network-topology-strategy:

NetworkTopologyStrategy
~~~~~~~~~~~~~~~~~~~~~~~

NetworkTopologyStrategy allows a replication factor to be specified for each datacenter in the cluster.  Even if your
cluster only uses a single datacenter, NetworkTopologyStrategy should be prefered over SimpleStrategy to make it easier
to add new physical or virtual datacenters to the cluster later.

In addition to allowing the replication factor to be specified per-DC, NetworkTopologyStrategy also attempts to choose
replicas within a datacenter from different racks.  If the number of racks is greater than or equal to the replication
factor for the DC, each replica will be chosen from a different rack.  Otherwise, each rack will hold at least one
replica, but some racks may hold more than one. Note that this rack-aware behavior has some potentially `surprising
implications <https://issues.apache.org/jira/browse/CASSANDRA-3810>`_.  For example, if there are not an even number of
nodes in each rack, the data load on the smallest rack may be much higher.  Similarly, if a single node is bootstrapped
into a new rack, it will be considered a replica for the entire ring.  For this reason, many operators choose to
configure all nodes on a single "rack".

Tunable Consistency
^^^^^^^^^^^^^^^^^^^

Cassandra supports a per-operation tradeoff between consistency and availability through *Consistency Levels*.
Essentially, an operation's consistency level specifies how many of the replicas need to respond to the coordinator in
order to consider the operation a success.

The following consistency levels are available:

``ONE``
  Only a single replica must respond.

``TWO``
  Two replicas must respond.

``THREE``
  Three replicas must respond.

``QUORUM``
  A majority (n/2 + 1) of the replicas must respond.

``ALL``
  All of the replicas must respond.

``LOCAL_QUORUM``
  A majority of the replicas in the local datacenter (whichever datacenter the coordinator is in) must respond.

``EACH_QUORUM``
  A majority of the replicas in each datacenter must respond.

``LOCAL_ONE``
  Only a single replica must respond.  In a multi-datacenter cluster, this also gaurantees that read requests are not
  sent to replicas in a remote datacenter.

``ANY``
  A single replica may respond, or the coordinator may store a hint. If a hint is stored, the coordinator will later
  attempt to replay the hint and deliver the mutation to the replicas.  This consistency level is only accepted for
  write operations.

Write operations are always sent to all replicas, regardless of consistency level. The consistency level simply
controls how many responses the coordinator waits for before responding to the client.

For read operations, the coordinator generally only issues read commands to enough replicas to satisfy the consistency
level. There are a couple of exceptions to this:

- Speculative retry may issue a redundant read request to an extra replica if the other replicas have not responded
  within a specified time window.
- Based on ``read_repair_chance`` and ``dclocal_read_repair_chance`` (part of a table's schema), read requests may be
  randomly sent to all replicas in order to repair potentially inconsistent data.

Picking Consistency Levels
~~~~~~~~~~~~~~~~~~~~~~~~~~

It is common to pick read and write consistency levels that are high enough to overlap, resulting in "strong"
consistency.  This is typically expressed as ``W + R > RF``, where ``W`` is the write consistency level, ``R`` is the
read consistency level, and ``RF`` is the replication factor.  For example, if ``RF = 3``, a ``QUORUM`` request will
require responses from at least two of the three replicas.  If ``QUORUM`` is used for both writes and reads, at least
one of the replicas is guaranteed to participate in *both* the write and the read request, which in turn guarantees that
the latest write will be read. In a multi-datacenter environment, ``LOCAL_QUORUM`` can be used to provide a weaker but
still useful guarantee: reads are guaranteed to see the latest write from within the same datacenter.

If this type of strong consistency isn't required, lower consistency levels like ``ONE`` may be used to improve
throughput, latency, and availability.
