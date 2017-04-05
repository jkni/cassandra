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

package org.apache.cassandra.stress.generate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import org.junit.Test;

import org.apache.cassandra.schema.CompactionParams;
import org.apache.cassandra.stress.generate.values.GeneratorConfig;
import org.apache.cassandra.stress.generate.values.Integers;
import org.apache.cassandra.stress.settings.OptionDistribution;
import org.apache.cassandra.stress.settings.StressSettings;

import static org.junit.Assert.assertEquals;

public class PartitionIteratorTest
{
    @Test
    public void multiRowIteratorWithMultipleClustering()
    {
        GeneratorConfig gc = new GeneratorConfig("seed", OptionDistribution.get("fixed(3)"), null, null);

        StressSettings settings = StressSettings.parse(new String[]{ "write"});

        SeedManager sm = new SeedManager(settings);

        PartitionGenerator generator =
        new PartitionGenerator(ImmutableList.of(new Integers("PartitionKey", new GeneratorConfig("abc",null, null,null))),
                               ImmutableList.of(new Integers("ClusteringKey1", gc), new Integers("ClusteringKey2", gc), new Integers("ClusteringKey3", gc)),
                               ImmutableList.of(new Integers("Value", gc)),
                               PartitionGenerator.Order.SORTED);

        PartitionIterator.MultiRowIterator mri = new PartitionIterator.MultiRowIterator(generator, sm);

        mri.reset(new Seed(Long.MIN_VALUE, 1), 27, 1.0, true);

        List<Set<Object>> groupMembers = new ArrayList<Set<Object>>();
        for (int i = 0; i < 5; i++)
        {
            groupMembers.add(new HashSet<>());
        }

        int iteratorSize = 0;

        while (mri.hasNext())
        {
            String rows = "";
            iteratorSize++;
            Row row = mri.next();
            for (int i = -1; i < 4; i++)
            {
                groupMembers.get(i+1).add(row.get(i));
                rows = rows + " " + row.get(i);
            }
            System.out.println(rows);
        }

        System.out.println("Iterator size " + iteratorSize);

        int size = 1;
        for (int i = 0; i < 4; i++)
        {

            assertEquals(size, groupMembers.get(i).size());
            size = size * 3;
        }

        assertEquals(27, groupMembers.get(4).size());
    }
}
