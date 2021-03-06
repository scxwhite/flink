/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.runtime.join;

import org.apache.flink.runtime.io.disk.iomanager.IOManager;
import org.apache.flink.runtime.io.disk.iomanager.IOManagerAsync;
import org.apache.flink.runtime.memory.MemoryManager;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.table.dataformat.BinaryRow;
import org.apache.flink.table.generated.GeneratedJoinCondition;
import org.apache.flink.table.generated.GeneratedNormalizedKeyComputer;
import org.apache.flink.table.generated.GeneratedProjection;
import org.apache.flink.table.generated.GeneratedRecordComparator;
import org.apache.flink.table.generated.JoinCondition;
import org.apache.flink.table.generated.NormalizedKeyComputer;
import org.apache.flink.table.generated.Projection;
import org.apache.flink.table.generated.RecordComparator;
import org.apache.flink.table.runtime.join.Int2HashJoinOperatorTest.MyProjection;
import org.apache.flink.table.runtime.join.SortMergeJoinOperator.SortMergeJoinType;
import org.apache.flink.table.runtime.sort.IntNormalizedKeyComputer;
import org.apache.flink.table.runtime.sort.IntRecordComparator;
import org.apache.flink.table.runtime.util.UniformBinaryRowGenerator;
import org.apache.flink.util.MutableObjectIterator;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static org.apache.flink.table.runtime.join.Int2HashJoinOperatorTest.joinAndAssert;
import static org.junit.Assert.fail;

/**
 * Random test for {@link SortMergeJoinOperator}.
 */
@RunWith(Parameterized.class)
public class Int2SortMergeJoinOperatorTest {

	private boolean leftIsSmaller;

	private MemoryManager memManager;
	private IOManager ioManager;

	public Int2SortMergeJoinOperatorTest(boolean leftIsSmaller) {
		this.leftIsSmaller = leftIsSmaller;
	}

	@Parameterized.Parameters
	public static Collection<Boolean> parameters() {
		return Arrays.asList(true, false);
	}

	@Before
	public void setup() {
		this.memManager = new MemoryManager(36 * 1024 * 1024, 1);
		this.ioManager = new IOManagerAsync();
	}

	@After
	public void tearDown() {
		// shut down I/O manager and Memory Manager and verify the correct shutdown
		this.ioManager.shutdown();
		if (!this.ioManager.isProperlyShutDown()) {
			fail("I/O manager was not property shut down.");
		}
		if (!this.memManager.verifyEmpty()) {
			fail("Not all memory was properly released to the memory manager --> Memory Leak.");
		}
	}

	@Test
	public void testInnerJoin() throws Exception {
		int numKeys = 100;
		int buildValsPerKey = 3;
		int probeValsPerKey = 10;

		MutableObjectIterator<BinaryRow> buildInput = new UniformBinaryRowGenerator(numKeys, buildValsPerKey, false);
		MutableObjectIterator<BinaryRow> probeInput = new UniformBinaryRowGenerator(numKeys, probeValsPerKey, true);

		buildJoin(buildInput, probeInput, SortMergeJoinType.INNER, numKeys * buildValsPerKey * probeValsPerKey,
				numKeys, 165);
	}

	@Test
	public void testLeftOutJoin() throws Exception {

		int numKeys1 = 9;
		int numKeys2 = 10;
		int buildValsPerKey = 3;
		int probeValsPerKey = 10;

		MutableObjectIterator<BinaryRow> buildInput = new UniformBinaryRowGenerator(numKeys1, buildValsPerKey, true);
		MutableObjectIterator<BinaryRow> probeInput = new UniformBinaryRowGenerator(numKeys2, probeValsPerKey, true);

		buildJoin(buildInput, probeInput, SortMergeJoinType.LEFT, numKeys1 * buildValsPerKey * probeValsPerKey,
				numKeys1, 165);
	}

	@Test
	public void testRightOutJoin() throws Exception {
		int numKeys1 = 9;
		int numKeys2 = 10;
		int buildValsPerKey = 3;
		int probeValsPerKey = 10;

		MutableObjectIterator<BinaryRow> buildInput = new UniformBinaryRowGenerator(numKeys1, buildValsPerKey, true);
		MutableObjectIterator<BinaryRow> probeInput = new UniformBinaryRowGenerator(numKeys2, probeValsPerKey, true);

		buildJoin(buildInput, probeInput, SortMergeJoinType.RIGHT, 280, numKeys2, -1);
	}

	@Test
	public void testFullOutJoin() throws Exception {
		int numKeys1 = 9;
		int numKeys2 = 10;
		int buildValsPerKey = 3;
		int probeValsPerKey = 10;

		MutableObjectIterator<BinaryRow> buildInput = new UniformBinaryRowGenerator(numKeys1, buildValsPerKey, true);
		MutableObjectIterator<BinaryRow> probeInput = new UniformBinaryRowGenerator(numKeys2, probeValsPerKey, true);

		buildJoin(buildInput, probeInput, SortMergeJoinType.FULL, 280, numKeys2, -1);
	}

	@Test
	public void testSemiJoin() throws Exception {

		int numKeys1 = 10;
		int numKeys2 = 9;
		int buildValsPerKey = 10;
		int probeValsPerKey = 3;
		MutableObjectIterator<BinaryRow> buildInput = new UniformBinaryRowGenerator(numKeys1, buildValsPerKey, true);
		MutableObjectIterator<BinaryRow> probeInput = new UniformBinaryRowGenerator(numKeys2, probeValsPerKey, true);

		StreamOperator operator = newOperator(SortMergeJoinType.SEMI, false);
		joinAndAssert(operator, buildInput, probeInput, 90, 9, 45, true, false);
	}

	@Test
	public void testAntiJoin() throws Exception {

		int numKeys1 = 10;
		int numKeys2 = 9;
		int buildValsPerKey = 10;
		int probeValsPerKey = 3;
		MutableObjectIterator<BinaryRow> buildInput = new UniformBinaryRowGenerator(numKeys1, buildValsPerKey, true);
		MutableObjectIterator<BinaryRow> probeInput = new UniformBinaryRowGenerator(numKeys2, probeValsPerKey, true);

		StreamOperator operator = newOperator(SortMergeJoinType.ANTI, false);
		joinAndAssert(operator, buildInput, probeInput, 10, 1, 45, true, false);
	}

	private void buildJoin(
			MutableObjectIterator<BinaryRow> input1,
			MutableObjectIterator<BinaryRow> input2,
			SortMergeJoinType type,
			int expertOutSize, int expertOutKeySize, int expertOutVal) throws Exception {

		joinAndAssert(
				getOperator(type),
				input1, input2, expertOutSize, expertOutKeySize, expertOutVal, false, false);
	}

	private StreamOperator getOperator(SortMergeJoinType type) {
		return newOperator(type, leftIsSmaller);
	}

	static StreamOperator newOperator(SortMergeJoinType type, boolean leftIsSmaller) {
		return new SortMergeJoinOperator(
				32 * 32 * 1024, 1024 * 1024, type, leftIsSmaller,
				new GeneratedJoinCondition("", "", new Object[0]) {
					@Override
					public JoinCondition newInstance(ClassLoader classLoader) {
						return (in1, in2) -> true;
					}
				},
				new GeneratedProjection("", "", new Object[0]) {
					@Override
					public Projection newInstance(ClassLoader classLoader) {
						return new MyProjection();
					}
				},
				new GeneratedProjection("", "", new Object[0]) {
					@Override
					public Projection newInstance(ClassLoader classLoader) {
						return new MyProjection();
					}
				},
				new GeneratedNormalizedKeyComputer("", "") {
					@Override
					public NormalizedKeyComputer newInstance(ClassLoader classLoader) {
						return new IntNormalizedKeyComputer();
					}
				},
				new GeneratedRecordComparator("", "", new Object[0]) {
					@Override
					public RecordComparator newInstance(ClassLoader classLoader) {
						return new IntRecordComparator();
					}
				},
				new GeneratedNormalizedKeyComputer("", "") {
					@Override
					public NormalizedKeyComputer newInstance(ClassLoader classLoader) {
						return new IntNormalizedKeyComputer();
					}
				},
				new GeneratedRecordComparator("", "", new Object[0]) {
					@Override
					public RecordComparator newInstance(ClassLoader classLoader) {
						return new IntRecordComparator();
					}
				},
				new GeneratedRecordComparator("", "", new Object[0]) {
					@Override
					public RecordComparator newInstance(ClassLoader classLoader) {
						return new IntRecordComparator();
					}
				},
				new boolean[]{true});
	}
}
