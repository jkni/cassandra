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
package org.apache.cassandra.db.compaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.DataTracker;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.DeletionTime;
import org.apache.cassandra.io.sstable.SSTableReader;
import org.apache.cassandra.utils.AlwaysPresentFilter;

import org.apache.cassandra.utils.concurrent.Refs;

/**
 * Manage compaction options.
 */
public class CompactionController implements AutoCloseable
{
    private static final Logger logger = LoggerFactory.getLogger(CompactionController.class);
    static final boolean NEVER_PURGE_TOMBSTONES = Boolean.getBoolean("cassandra.never_purge_tombstones");

    public final ColumnFamilyStore cfs;

    // note that overlappingTree and overlappingSSTables will be null if NEVER_PURGE_TOMBSTONES is set - this is a
    // good thing so that noone starts using them and thinks that if overlappingSSTables is empty, there
    // is no overlap.
    private DataTracker.SSTableIntervalTree overlappingTree;
    private Refs<SSTableReader> overlappingSSTables;
    private final Iterable<SSTableReader> compacting;

    public final int gcBefore;

    protected CompactionController(ColumnFamilyStore cfs, int maxValue)
    {
        this(cfs, null, maxValue);
    }

    public CompactionController(ColumnFamilyStore cfs, Set<SSTableReader> compacting, int gcBefore)
    {
        assert cfs != null;
        this.cfs = cfs;
        this.gcBefore = gcBefore;
        this.compacting = compacting;
        refreshOverlaps();
        if (NEVER_PURGE_TOMBSTONES)
            logger.warn("You are running with -Dcassandra.never_purge_tombstones=true, this is dangerous!");
    }

    void maybeRefreshOverlaps()
    {
        if (NEVER_PURGE_TOMBSTONES)
        {
            logger.debug("not refreshing overlaps - running with -Dcassandra.never_purge_tombstones=true");
            return;
        }

        for (SSTableReader reader : overlappingSSTables)
        {
            if (reader.isMarkedCompacted())
            {
                refreshOverlaps();
                return;
            }
        }
    }

    private void refreshOverlaps()
    {
        if (NEVER_PURGE_TOMBSTONES)
            return;

        if (this.overlappingSSTables != null)
            overlappingSSTables.release();

        if (compacting == null)
            overlappingSSTables = Refs.tryRef(Collections.<SSTableReader>emptyList());
        else
            overlappingSSTables = cfs.getAndReferenceOverlappingSSTables(compacting);
        this.overlappingTree = DataTracker.buildIntervalTree(overlappingSSTables);
    }

    public Set<SSTableReader> getFullyExpiredSSTables()
    {
        return getFullyExpiredSSTables(cfs, compacting, overlappingSSTables, gcBefore);
    }

    /**
     * Finds expired sstables
     *
     * works something like this;
     * 1. find "global" minTimestamp of overlapping sstables and compacting sstables containing any non-expired data
     * 2. build a list of fully expired candidates
     * 3. check if the candidates to be dropped actually can be dropped (maxTimestamp < global minTimestamp)
     *    - if not droppable, remove from candidates
     * 4. return candidates.
     *
     * @param cfStore
     * @param compacting we take the drop-candidates from this set, it is usually the sstables included in the compaction
     * @param overlapping the sstables that overlap the ones in compacting.
     * @param gcBefore
     * @return
     */
    public static Set<SSTableReader> getFullyExpiredSSTables(ColumnFamilyStore cfStore, Iterable<SSTableReader> compacting, Iterable<SSTableReader> overlapping, int gcBefore)
    {
        logger.debug("Checking droppable sstables in {}", cfStore);

        if (compacting == null || NEVER_PURGE_TOMBSTONES)
            return Collections.<SSTableReader>emptySet();

        List<SSTableReader> candidates = new ArrayList<SSTableReader>();

        long minTimestamp = Long.MAX_VALUE;

        for (SSTableReader sstable : overlapping)
        {
            // Overlapping might include fully expired sstables. What we care about here is
            // the min timestamp of the overlapping sstables that actually contain live data.
            if (sstable.getSSTableMetadata().maxLocalDeletionTime >= gcBefore)
                minTimestamp = Math.min(minTimestamp, sstable.getMinTimestamp());
        }

        for (SSTableReader candidate : compacting)
        {
            if (candidate.getSSTableMetadata().maxLocalDeletionTime < gcBefore)
                candidates.add(candidate);
            else
                minTimestamp = Math.min(minTimestamp, candidate.getMinTimestamp());
        }

        // At this point, minTimestamp denotes the lowest timestamp of any relevant
        // SSTable that contains a constructive value. candidates contains all the
        // candidates with no constructive values. The ones out of these that have
        // (getMaxTimestamp() < minTimestamp) serve no purpose anymore.

        Iterator<SSTableReader> iterator = candidates.iterator();
        while (iterator.hasNext())
        {
            SSTableReader candidate = iterator.next();
            if (candidate.getMaxTimestamp() >= minTimestamp)
            {
                iterator.remove();
            }
            else
            {
               logger.debug("Dropping expired SSTable {} (maxLocalDeletionTime={}, gcBefore={})",
                        candidate, candidate.getSSTableMetadata().maxLocalDeletionTime, gcBefore);
            }
        }
        return new HashSet<>(candidates);
    }

    public String getKeyspace()
    {
        return cfs.keyspace.getName();
    }

    public String getColumnFamily()
    {
        return cfs.name;
    }

    /**
     * @param key
     * @param markedForDeletionAt
     * @return whether tombstones marked for deletion at the given time for the given partition are purgeable;
     * we calculate this by checking whether the deletion time is less than the min timestamp of all SSTables containing
     * this partition and not participating in the compaction. This means there isn't any data in those sstables that
     * might still need to be suppressed by a tombstone at this timestamp.
     */
    public PurgeEvaluator getPurgeEvaluator(DecoratedKey key)
    {
        if (NEVER_PURGE_TOMBSTONES)
            return AlwaysFalsePurgeEvaluator.instance;

        List<SSTableReader> filteredSSTables = overlappingTree.search(key);

        if (filteredSSTables.isEmpty())
        {
            return AlwaysTruePurgeEvaluator.instance;
        }
        else
        {
            long minTimestamp = Long.MAX_VALUE;
            for (SSTableReader sstable: filteredSSTables)
            {
                // if we don't have bloom filter(bf_fp_chance=1.0 or filter file is missing),
                // we check index file instead.
                if ((sstable.getBloomFilter() instanceof AlwaysPresentFilter && sstable.getPosition(key, SSTableReader.Operator.EQ, false) != null)
                    || (sstable.getBloomFilter().isPresent(key.getKey())))
                    minTimestamp = Math.min(minTimestamp, sstable.getMinTimestamp());

            }

            return new TimestampedPurgeEvaluator(minTimestamp);
        }
    }

    interface PurgeEvaluator
    {
        public boolean isPurgeable(long time);
    }

    static class TimestampedPurgeEvaluator implements PurgeEvaluator
    {
        private long comparisonTime;

        TimestampedPurgeEvaluator(long comparisonTime)
        {
            this.comparisonTime = comparisonTime;
        }

        public boolean isPurgeable(long time)
        {
            return time < comparisonTime;
        }
    }

    static class AlwaysTruePurgeEvaluator implements PurgeEvaluator
    {
        public static PurgeEvaluator instance = new AlwaysTruePurgeEvaluator();

        private AlwaysTruePurgeEvaluator()
        {
        }

        public boolean isPurgeable(long time)
        {
            return true;
        }
    }

    static class AlwaysFalsePurgeEvaluator implements PurgeEvaluator
    {
        public static PurgeEvaluator instance = new AlwaysFalsePurgeEvaluator();

        private AlwaysFalsePurgeEvaluator()
        {
        }

        public boolean isPurgeable(long time)
        {
            return false;
        }
    }

    public void close()
    {
        if (overlappingSSTables != null)
            overlappingSSTables.release();
    }
}
