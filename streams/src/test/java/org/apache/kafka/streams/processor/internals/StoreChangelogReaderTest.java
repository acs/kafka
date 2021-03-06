/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.processor.internals;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.StreamsException;
import org.apache.kafka.streams.processor.StateStore;
import org.apache.kafka.streams.processor.internals.ProcessorStateManager.StateStoreMetadata;
import org.apache.kafka.test.MockStateRestoreListener;
import org.apache.kafka.test.StreamsTestUtils;
import org.easymock.EasyMock;
import org.easymock.EasyMockRule;
import org.easymock.EasyMockSupport;
import org.easymock.Mock;
import org.easymock.MockType;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.apache.kafka.common.utils.Utils.mkSet;
import static org.apache.kafka.streams.processor.internals.Task.TaskType.ACTIVE;
import static org.apache.kafka.streams.processor.internals.Task.TaskType.STANDBY;
import static org.apache.kafka.streams.processor.internals.StoreChangelogReader.ChangelogReaderState.ACTIVE_RESTORING;
import static org.apache.kafka.streams.processor.internals.StoreChangelogReader.ChangelogReaderState.STANDBY_UPDATING;
import static org.apache.kafka.test.MockStateRestoreListener.RESTORE_BATCH;
import static org.apache.kafka.test.MockStateRestoreListener.RESTORE_END;
import static org.apache.kafka.test.MockStateRestoreListener.RESTORE_START;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class StoreChangelogReaderTest extends EasyMockSupport {

    @Rule
    public EasyMockRule rule = new EasyMockRule(this);

    @Mock(type = MockType.NICE)
    private ProcessorStateManager stateManager;
    @Mock(type = MockType.NICE)
    private ProcessorStateManager activeStateManager;
    @Mock(type = MockType.NICE)
    private ProcessorStateManager standbyStateManager;
    @Mock(type = MockType.NICE)
    private StateStoreMetadata storeMetadata;
    @Mock(type = MockType.NICE)
    private StateStoreMetadata storeMetadataOne;
    @Mock(type = MockType.NICE)
    private StateStoreMetadata storeMetadataTwo;
    @Mock(type = MockType.NICE)
    private StateStore store;

    @Parameterized.Parameters
    public static Object[] data() {
        return new Object[] {STANDBY, ACTIVE};
    }

    @Parameterized.Parameter
    public Task.TaskType type;

    private final String storeName = "store";
    private final String topicName = "topic";
    private final LogContext logContext = new LogContext("test-reader ");
    private final TopicPartition tp = new TopicPartition(topicName, 0);
    private final TopicPartition tp1 = new TopicPartition("one", 0);
    private final TopicPartition tp2 = new TopicPartition("two", 0);
    private final StreamsConfig config = new StreamsConfig(StreamsTestUtils.getStreamsConfig("test-reader"));
    private final MockStateRestoreListener callback = new MockStateRestoreListener();
    private final KafkaException kaboom = new KafkaException("KABOOM!");
    private final MockStateRestoreListener exceptionCallback = new MockStateRestoreListener() {
        @Override
        public void restore(final byte[] key, final byte[] value) {
            throw kaboom;
        }

        @Override
        public void onRestoreStart(final TopicPartition tp, final String store, final long stOffset, final long edOffset) {
            throw kaboom;
        }

        @Override
        public void onBatchRestored(final TopicPartition tp, final String store, final long bedOffset, final long numRestored) {
            throw kaboom;
        }

        @Override
        public void onRestoreEnd(final TopicPartition tp, final String store, final long totalRestored) {
            throw kaboom;
        }
    };

    private final MockConsumer<byte[], byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
    private final StoreChangelogReader changelogReader = new StoreChangelogReader(config, logContext, consumer, callback);

    @Before
    public void setUp() {
        EasyMock.expect(stateManager.storeMetadata(tp)).andReturn(storeMetadata).anyTimes();
        EasyMock.expect(stateManager.taskType()).andReturn(type).anyTimes();
        EasyMock.expect(activeStateManager.storeMetadata(tp)).andReturn(storeMetadata).anyTimes();
        EasyMock.expect(activeStateManager.taskType()).andReturn(ACTIVE).anyTimes();
        EasyMock.expect(standbyStateManager.storeMetadata(tp)).andReturn(storeMetadata).anyTimes();
        EasyMock.expect(standbyStateManager.taskType()).andReturn(STANDBY).anyTimes();

        EasyMock.expect(storeMetadata.changelogPartition()).andReturn(tp).anyTimes();
        EasyMock.expect(storeMetadata.store()).andReturn(store).anyTimes();
        EasyMock.expect(store.name()).andReturn(storeName).anyTimes();
    }

    @After
    public void tearDown() {
        EasyMock.reset(stateManager, activeStateManager, standbyStateManager, storeMetadata, storeMetadataOne, storeMetadataTwo, store);
    }

    @Test
    public void shouldNotRegisterSameStoreMultipleTimes() {
        EasyMock.replay(stateManager, storeMetadata);

        changelogReader.register(tp, stateManager);

        assertEquals(StoreChangelogReader.ChangelogState.REGISTERED, changelogReader.changelogMetadata(tp).state());
        assertNull(changelogReader.changelogMetadata(tp).endOffset());
        assertNull(changelogReader.changelogMetadata(tp).limitOffset());
        assertEquals(0L, changelogReader.changelogMetadata(tp).totalRestored());

        assertThrows(IllegalStateException.class, () -> changelogReader.register(tp, stateManager));
    }

    @Test
    public void shouldNotRegisterStoreWithoutMetadata() {
        EasyMock.replay(stateManager, storeMetadata);

        assertThrows(IllegalStateException.class, () ->
            changelogReader.register(new TopicPartition("ChangelogWithoutStoreMetadata", 0), stateManager));
    }

    @Test
    public void shouldInitializeChangelogAndCheckForCompletion() {
        EasyMock.expect(storeMetadata.offset()).andReturn(9L).anyTimes();
        EasyMock.replay(stateManager, storeMetadata, store);

        final MockConsumer<byte[], byte[]> consumer = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.EARLIEST) {
            @Override
            public Map<TopicPartition, Long> endOffsets(final Collection<TopicPartition> partitions) {
                return partitions.stream().collect(Collectors.toMap(Function.identity(), partition -> 10L));
            }
        };

        final StoreChangelogReader changelogReader = new StoreChangelogReader(config, logContext, consumer, callback);

        changelogReader.register(tp, stateManager);
        changelogReader.restore();

        assertEquals(type == ACTIVE ? StoreChangelogReader.ChangelogState.COMPLETED : StoreChangelogReader.ChangelogState.RESTORING,
                     changelogReader.changelogMetadata(tp).state());
        assertEquals(type == ACTIVE ? 10L : null, changelogReader.changelogMetadata(tp).endOffset());
        assertEquals(0L, changelogReader.changelogMetadata(tp).totalRestored());
        assertEquals(type == ACTIVE ? Collections.singleton(tp) : Collections.emptySet(), changelogReader.completedChangelogs());
        assertNull(changelogReader.changelogMetadata(tp).limitOffset());
        assertEquals(10L, consumer.position(tp));
        assertEquals(Collections.singleton(tp), consumer.paused());

        if (type == ACTIVE) {
            assertEquals(tp, callback.restoreTopicPartition);
            assertEquals(storeName, callback.storeNameCalledStates.get(RESTORE_START));
            assertEquals(storeName, callback.storeNameCalledStates.get(RESTORE_END));
            assertNull(callback.storeNameCalledStates.get(RESTORE_BATCH));
        }
    }

    @Test
    public void shouldRestoreFromPositionAndCheckForCompletion() {
        EasyMock.expect(storeMetadata.offset()).andReturn(5L).anyTimes();
        EasyMock.replay(stateManager, storeMetadata, store);

        final MockConsumer<byte[], byte[]> consumer = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.EARLIEST) {
            @Override
            public Map<TopicPartition, Long> endOffsets(final Collection<TopicPartition> partitions) {
                return partitions.stream().collect(Collectors.toMap(Function.identity(), partition -> 10L));
            }
        };

        final StoreChangelogReader changelogReader = new StoreChangelogReader(config, logContext, consumer, callback);

        changelogReader.register(tp, stateManager);

        if (type == STANDBY) {
            changelogReader.transitToUpdateStandby();
        }

        changelogReader.restore();

        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp).state());
        assertEquals(0L, changelogReader.changelogMetadata(tp).totalRestored());
        assertTrue(changelogReader.completedChangelogs().isEmpty());
        assertNull(changelogReader.changelogMetadata(tp).limitOffset());
        assertEquals(6L, consumer.position(tp));
        assertEquals(Collections.emptySet(), consumer.paused());

        if (type == ACTIVE) {
            assertEquals(10L, (long) changelogReader.changelogMetadata(tp).endOffset());

            assertEquals(tp, callback.restoreTopicPartition);
            assertEquals(storeName, callback.storeNameCalledStates.get(RESTORE_START));
            assertNull(callback.storeNameCalledStates.get(RESTORE_END));
            assertNull(callback.storeNameCalledStates.get(RESTORE_BATCH));
        } else {
            assertNull(changelogReader.changelogMetadata(tp).endOffset());

        }

        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 6L, "key".getBytes(), "value".getBytes()));
        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 7L, "key".getBytes(), "value".getBytes()));
        // null key should be ignored
        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 8L, null, "value".getBytes()));
        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 9L, "key".getBytes(), "value".getBytes()));
        // beyond end records should be skipped even when there's gap at the end offset
        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 11L, "key".getBytes(), "value".getBytes()));

        changelogReader.restore();

        assertEquals(12L, consumer.position(tp));

        if (type == ACTIVE) {
            assertEquals(StoreChangelogReader.ChangelogState.COMPLETED, changelogReader.changelogMetadata(tp).state());
            assertEquals(3L, changelogReader.changelogMetadata(tp).totalRestored());
            assertEquals(1, changelogReader.changelogMetadata(tp).bufferedRecords().size());
            assertEquals(Collections.singleton(tp), changelogReader.completedChangelogs());
            assertEquals(Collections.singleton(tp), consumer.paused());

            assertEquals(storeName, callback.storeNameCalledStates.get(RESTORE_BATCH));
            assertEquals(storeName, callback.storeNameCalledStates.get(RESTORE_END));
        } else {
            assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp).state());
            assertEquals(4L, changelogReader.changelogMetadata(tp).totalRestored());
            assertEquals(0, changelogReader.changelogMetadata(tp).bufferedRecords().size());
            assertEquals(Collections.emptySet(), changelogReader.completedChangelogs());
            assertEquals(Collections.emptySet(), consumer.paused());
        }
    }

    @Test
    public void shouldRestoreFromBeginningAndCheckCompletion() {
        EasyMock.expect(storeMetadata.offset()).andReturn(null).andReturn(9L).anyTimes();
        EasyMock.replay(stateManager, storeMetadata, store);

        final MockConsumer<byte[], byte[]> consumer = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.EARLIEST) {
            @Override
            public Map<TopicPartition, Long> endOffsets(final Collection<TopicPartition> partitions) {
                return partitions.stream().collect(Collectors.toMap(Function.identity(), partition -> 11L));
            }
        };
        consumer.updateBeginningOffsets(Collections.singletonMap(tp, 5L));

        final StoreChangelogReader changelogReader = new StoreChangelogReader(config, logContext, consumer, callback);

        changelogReader.register(tp, stateManager);

        if (type == STANDBY) {
            changelogReader.transitToUpdateStandby();
        }

        changelogReader.restore();

        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp).state());
        assertEquals(0L, changelogReader.changelogMetadata(tp).totalRestored());
        assertNull(changelogReader.changelogMetadata(tp).limitOffset());
        assertEquals(5L, consumer.position(tp));
        assertEquals(Collections.emptySet(), consumer.paused());

        if (type == ACTIVE) {
            assertEquals(11L, (long) changelogReader.changelogMetadata(tp).endOffset());

            assertEquals(tp, callback.restoreTopicPartition);
            assertEquals(storeName, callback.storeNameCalledStates.get(RESTORE_START));
            assertNull(callback.storeNameCalledStates.get(RESTORE_END));
            assertNull(callback.storeNameCalledStates.get(RESTORE_BATCH));
        } else {
            assertNull(changelogReader.changelogMetadata(tp).endOffset());
        }

        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 6L, "key".getBytes(), "value".getBytes()));
        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 7L, "key".getBytes(), "value".getBytes()));
        // null key should be ignored
        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 8L, null, "value".getBytes()));
        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 9L, "key".getBytes(), "value".getBytes()));

        changelogReader.restore();

        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp).state());
        assertEquals(3L, changelogReader.changelogMetadata(tp).totalRestored());
        assertEquals(0, changelogReader.changelogMetadata(tp).bufferedRecords().size());
        assertEquals(0, changelogReader.changelogMetadata(tp).bufferedLimitIndex());

        // consumer position bypassing the gap in the next poll
        consumer.seek(tp, 11L);

        changelogReader.restore();

        assertEquals(11L, consumer.position(tp));
        assertEquals(3L, changelogReader.changelogMetadata(tp).totalRestored());

        if (type == ACTIVE) {
            assertEquals(StoreChangelogReader.ChangelogState.COMPLETED, changelogReader.changelogMetadata(tp).state());
            assertEquals(3L, changelogReader.changelogMetadata(tp).totalRestored());
            assertEquals(Collections.singleton(tp), changelogReader.completedChangelogs());
            assertEquals(Collections.singleton(tp), consumer.paused());

            assertEquals(storeName, callback.storeNameCalledStates.get(RESTORE_BATCH));
            assertEquals(storeName, callback.storeNameCalledStates.get(RESTORE_END));
        } else {
            assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp).state());
            assertEquals(Collections.emptySet(), changelogReader.completedChangelogs());
            assertEquals(Collections.emptySet(), consumer.paused());
        }
    }

    @Test
    public void shouldCheckCompletionIfPositionLargerThanEndOffset() {
        EasyMock.expect(storeMetadata.offset()).andReturn(5L).anyTimes();
        EasyMock.replay(activeStateManager, storeMetadata, store);

        final MockConsumer<byte[], byte[]> consumer = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.EARLIEST) {
            @Override
            public Map<TopicPartition, Long> endOffsets(final Collection<TopicPartition> partitions) {
                return partitions.stream().collect(Collectors.toMap(Function.identity(), partition -> 0L));
            }
        };

        final StoreChangelogReader changelogReader = new StoreChangelogReader(config, logContext, consumer, callback);

        changelogReader.register(tp, activeStateManager);
        changelogReader.restore();

        assertEquals(StoreChangelogReader.ChangelogState.COMPLETED, changelogReader.changelogMetadata(tp).state());
        assertEquals(0L, (long) changelogReader.changelogMetadata(tp).endOffset());
        assertEquals(0L, changelogReader.changelogMetadata(tp).totalRestored());
        assertEquals(Collections.singleton(tp), changelogReader.completedChangelogs());
        assertNull(changelogReader.changelogMetadata(tp).limitOffset());
        assertEquals(6L, consumer.position(tp));
        assertEquals(Collections.singleton(tp), consumer.paused());
        assertEquals(tp, callback.restoreTopicPartition);
        assertEquals(storeName, callback.storeNameCalledStates.get(RESTORE_START));
        assertEquals(storeName, callback.storeNameCalledStates.get(RESTORE_END));
        assertNull(callback.storeNameCalledStates.get(RESTORE_BATCH));
    }

    @Test
    public void shouldRequestPositionAndHandleTimeoutException() {
        EasyMock.expect(storeMetadata.offset()).andReturn(10L).anyTimes();
        EasyMock.replay(activeStateManager, storeMetadata, store);

        final AtomicBoolean clearException = new AtomicBoolean(false);
        final MockConsumer<byte[], byte[]> consumer = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.EARLIEST) {
            @Override
            public long position(final TopicPartition partition) {
                if (clearException.get()) {
                    return 10L;
                } else {
                    throw new TimeoutException("KABOOM!");
                }
            }

            @Override
            public Map<TopicPartition, Long> endOffsets(final Collection<TopicPartition> partitions) {
                return partitions.stream().collect(Collectors.toMap(Function.identity(), partition -> 10L));
            }
        };

        final StoreChangelogReader changelogReader = new StoreChangelogReader(config, logContext, consumer, callback);

        changelogReader.register(tp, activeStateManager);
        changelogReader.restore();

        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp).state());
        assertTrue(changelogReader.completedChangelogs().isEmpty());
        assertEquals(10L, (long) changelogReader.changelogMetadata(tp).endOffset());
        assertNull(changelogReader.changelogMetadata(tp).limitOffset());

        clearException.set(true);
        changelogReader.restore();

        assertEquals(StoreChangelogReader.ChangelogState.COMPLETED, changelogReader.changelogMetadata(tp).state());
        assertEquals(10L, (long) changelogReader.changelogMetadata(tp).endOffset());
        assertNull(changelogReader.changelogMetadata(tp).limitOffset());
        assertEquals(Collections.singleton(tp), changelogReader.completedChangelogs());
        assertEquals(10L, consumer.position(tp));
    }

    @Test
    public void shouldThrowIfPositionFail() {
        EasyMock.expect(storeMetadata.offset()).andReturn(10L).anyTimes();
        EasyMock.replay(activeStateManager, storeMetadata, store);

        final MockConsumer<byte[], byte[]> consumer = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.EARLIEST) {
            @Override
            public long position(final TopicPartition partition) {
                throw kaboom;
            }

            @Override
            public Map<TopicPartition, Long> endOffsets(final Collection<TopicPartition> partitions) {
                return partitions.stream().collect(Collectors.toMap(Function.identity(), partition -> 10L));
            }
        };

        final StoreChangelogReader changelogReader = new StoreChangelogReader(config, logContext, consumer, callback);

        changelogReader.register(tp, activeStateManager);

        final StreamsException thrown = assertThrows(StreamsException.class, changelogReader::restore);
        assertEquals(kaboom, thrown.getCause());
    }

    @Test
    public void shouldRequestEndOffsetsAndHandleTimeoutException() {
        EasyMock.expect(storeMetadata.offset()).andReturn(5L).anyTimes();
        EasyMock.replay(activeStateManager, storeMetadata, store);

        final AtomicBoolean functionCalled = new AtomicBoolean(false);
        final MockConsumer<byte[], byte[]> consumer = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.EARLIEST) {
            @Override
            public Map<TopicPartition, Long> endOffsets(final Collection<TopicPartition> partitions) {
                if (functionCalled.get()) {
                    return partitions.stream().collect(Collectors.toMap(Function.identity(), partition -> 10L));
                } else {
                    functionCalled.set(true);
                    throw new TimeoutException("KABOOM!");
                }
            }

            @Override
            public Map<TopicPartition, OffsetAndMetadata> committed(final Set<TopicPartition> partitions) {
                throw new AssertionError("Should not trigger this function");
            }
        };

        final StoreChangelogReader changelogReader = new StoreChangelogReader(config, logContext, consumer, callback);

        changelogReader.register(tp, activeStateManager);
        changelogReader.restore();

        assertEquals(StoreChangelogReader.ChangelogState.REGISTERED, changelogReader.changelogMetadata(tp).state());
        assertNull(changelogReader.changelogMetadata(tp).endOffset());
        assertNull(changelogReader.changelogMetadata(tp).limitOffset());
        assertTrue(functionCalled.get());

        changelogReader.restore();

        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp).state());
        assertEquals(10L, (long) changelogReader.changelogMetadata(tp).endOffset());
        assertNull(changelogReader.changelogMetadata(tp).limitOffset());
        assertEquals(6L, consumer.position(tp));
    }

    @Test
    public void shouldThrowIfEndOffsetsFail() {
        EasyMock.expect(storeMetadata.offset()).andReturn(10L).anyTimes();
        EasyMock.replay(activeStateManager, storeMetadata, store);

        final MockConsumer<byte[], byte[]> consumer = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.EARLIEST) {
            @Override
            public Map<TopicPartition, Long> endOffsets(final Collection<TopicPartition> partitions) {
                throw kaboom;
            }
        };

        final StoreChangelogReader changelogReader = new StoreChangelogReader(config, logContext, consumer, callback);

        changelogReader.register(tp, activeStateManager);

        final StreamsException thrown = assertThrows(StreamsException.class, changelogReader::restore);
        assertEquals(kaboom, thrown.getCause());
    }

    @Test
    public void shouldRequestCommittedOffsetsAndHandleTimeoutException() {
        EasyMock.expect(stateManager.changelogAsSource(tp)).andReturn(true).anyTimes();
        EasyMock.expect(storeMetadata.offset()).andReturn(5L).anyTimes();
        EasyMock.replay(stateManager, storeMetadata, store);

        final AtomicBoolean functionCalled = new AtomicBoolean(false);
        final MockConsumer<byte[], byte[]> consumer = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.EARLIEST) {
            @Override
            public Map<TopicPartition, OffsetAndMetadata> committed(final Set<TopicPartition> partitions) {
                if (functionCalled.get()) {
                    return partitions.stream().collect(Collectors.toMap(Function.identity(), partition -> new OffsetAndMetadata(10L)));
                } else {
                    functionCalled.set(true);
                    throw new TimeoutException("KABOOM!");
                }
            }

            @Override
            public Map<TopicPartition, Long> endOffsets(final Collection<TopicPartition> partitions) {
                return partitions.stream().collect(Collectors.toMap(Function.identity(), partition -> 20L));
            }
        };

        final StoreChangelogReader changelogReader = new StoreChangelogReader(config, logContext, consumer, callback);
        changelogReader.setMainConsumer(consumer);

        changelogReader.register(tp, stateManager);
        changelogReader.restore();

        assertEquals(type == ACTIVE ? StoreChangelogReader.ChangelogState.REGISTERED : StoreChangelogReader.ChangelogState.RESTORING,
                     changelogReader.changelogMetadata(tp).state());
        assertNull(changelogReader.changelogMetadata(tp).endOffset());
        assertEquals(type == ACTIVE ? null : 0L, changelogReader.changelogMetadata(tp).limitOffset());
        assertTrue(functionCalled.get());

        changelogReader.restore();

        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp).state());
        assertEquals(type == ACTIVE ? 10L : null, changelogReader.changelogMetadata(tp).endOffset());
        assertEquals(type == ACTIVE ? null : 0L, changelogReader.changelogMetadata(tp).limitOffset());
        assertEquals(6L, consumer.position(tp));
    }

    @Test
    public void shouldThrowIfCommittedOffsetsFail() {
        EasyMock.expect(stateManager.changelogAsSource(tp)).andReturn(true).anyTimes();
        EasyMock.expect(storeMetadata.offset()).andReturn(10L).anyTimes();
        EasyMock.replay(stateManager, storeMetadata, store);

        final MockConsumer<byte[], byte[]> consumer = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.EARLIEST) {
            @Override
            public Map<TopicPartition, Long> endOffsets(final Collection<TopicPartition> partitions) {
                return partitions.stream().collect(Collectors.toMap(Function.identity(), partition -> 10L));
            }

            @Override
            public Map<TopicPartition, OffsetAndMetadata> committed(final Set<TopicPartition> partitions) {
                throw kaboom;
            }
        };
        final StoreChangelogReader changelogReader = new StoreChangelogReader(config, logContext, consumer, callback);
        changelogReader.setMainConsumer(consumer);

        changelogReader.register(tp, stateManager);

        final StreamsException thrown = assertThrows(StreamsException.class, changelogReader::restore);
        assertEquals(kaboom, thrown.getCause());
    }

    @Test
    public void shouldThrowIfUnsubscribeFail() {
        EasyMock.replay(stateManager, storeMetadata, store);

        final MockConsumer<byte[], byte[]> consumer = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.EARLIEST) {
            @Override
            public void unsubscribe() {
                throw kaboom;
            }
        };
        final StoreChangelogReader changelogReader = new StoreChangelogReader(config, logContext, consumer, callback);

        final StreamsException thrown = assertThrows(StreamsException.class, changelogReader::clear);
        assertEquals(kaboom, thrown.getCause());
    }

    @Test
    public void shouldOnlyRestoreStandbyChangelogInUpdateStandbyState() {
        EasyMock.replay(standbyStateManager, storeMetadata, store);

        consumer.updateBeginningOffsets(Collections.singletonMap(tp, 5L));
        changelogReader.register(tp, standbyStateManager);
        changelogReader.restore();

        assertNull(callback.restoreTopicPartition);
        assertNull(callback.storeNameCalledStates.get(RESTORE_START));
        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp).state());
        assertNull(changelogReader.changelogMetadata(tp).endOffset());
        assertNull(changelogReader.changelogMetadata(tp).limitOffset());
        assertEquals(0L, changelogReader.changelogMetadata(tp).totalRestored());

        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 6L, "key".getBytes(), "value".getBytes()));
        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 7L, "key".getBytes(), "value".getBytes()));
        // null key should be ignored
        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 8L, null, "value".getBytes()));
        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 9L, "key".getBytes(), "value".getBytes()));
        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 10L, "key".getBytes(), "value".getBytes()));
        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 11L, "key".getBytes(), "value".getBytes()));

        changelogReader.restore();
        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp).state());
        assertEquals(0L, changelogReader.changelogMetadata(tp).totalRestored());
        assertTrue(changelogReader.changelogMetadata(tp).bufferedRecords().isEmpty());

        assertEquals(Collections.singleton(tp), consumer.paused());

        changelogReader.transitToUpdateStandby();
        changelogReader.restore();
        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp).state());
        assertEquals(5L, changelogReader.changelogMetadata(tp).totalRestored());
        assertTrue(changelogReader.changelogMetadata(tp).bufferedRecords().isEmpty());
    }

    @Test
    public void shouldRestoreToLimitInStandbyState() {
        EasyMock.expect(standbyStateManager.changelogAsSource(tp)).andReturn(true).anyTimes();
        EasyMock.replay(standbyStateManager, storeMetadata, store);

        final AtomicLong offset = new AtomicLong(7L);
        final MockConsumer<byte[], byte[]> consumer = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.EARLIEST) {
            @Override
            public Map<TopicPartition, OffsetAndMetadata> committed(final Set<TopicPartition> partitions) {
                return partitions.stream().collect(Collectors.toMap(Function.identity(), partition -> new OffsetAndMetadata(offset.get())));
            }
        };

        final StoreChangelogReader changelogReader = new StoreChangelogReader(config, logContext, consumer, callback);
        changelogReader.setMainConsumer(consumer);
        changelogReader.transitToUpdateStandby();

        consumer.updateBeginningOffsets(Collections.singletonMap(tp, 5L));
        changelogReader.register(tp, standbyStateManager);
        changelogReader.restore();

        assertNull(callback.restoreTopicPartition);
        assertNull(callback.storeNameCalledStates.get(RESTORE_START));
        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp).state());
        assertNull(changelogReader.changelogMetadata(tp).endOffset());
        assertEquals(7L, (long) changelogReader.changelogMetadata(tp).limitOffset());
        assertEquals(0L, changelogReader.changelogMetadata(tp).totalRestored());

        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 5L, "key".getBytes(), "value".getBytes()));
        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 6L, "key".getBytes(), "value".getBytes()));
        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 7L, "key".getBytes(), "value".getBytes()));
        // null key should be ignored
        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 8L, null, "value".getBytes()));
        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 9L, "key".getBytes(), "value".getBytes()));
        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 10L, "key".getBytes(), "value".getBytes()));
        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 11L, "key".getBytes(), "value".getBytes()));

        changelogReader.restore();
        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp).state());
        assertNull(changelogReader.changelogMetadata(tp).endOffset());
        assertEquals(7L, (long) changelogReader.changelogMetadata(tp).limitOffset());
        assertEquals(2L, changelogReader.changelogMetadata(tp).totalRestored());
        assertEquals(4, changelogReader.changelogMetadata(tp).bufferedRecords().size());
        assertEquals(0, changelogReader.changelogMetadata(tp).bufferedLimitIndex());
        assertNull(callback.storeNameCalledStates.get(RESTORE_END));
        assertNull(callback.storeNameCalledStates.get(RESTORE_BATCH));

        offset.set(10L);
        changelogReader.updateLimitOffsets();
        assertEquals(10L, (long) changelogReader.changelogMetadata(tp).limitOffset());
        assertEquals(2L, changelogReader.changelogMetadata(tp).totalRestored());
        assertEquals(4, changelogReader.changelogMetadata(tp).bufferedRecords().size());
        assertEquals(2, changelogReader.changelogMetadata(tp).bufferedLimitIndex());

        changelogReader.restore();
        assertEquals(10L, (long) changelogReader.changelogMetadata(tp).limitOffset());
        assertEquals(4L, changelogReader.changelogMetadata(tp).totalRestored());
        assertEquals(2, changelogReader.changelogMetadata(tp).bufferedRecords().size());
        assertEquals(0, changelogReader.changelogMetadata(tp).bufferedLimitIndex());

        offset.set(15L);
        changelogReader.updateLimitOffsets();
        assertEquals(15L, (long) changelogReader.changelogMetadata(tp).limitOffset());
        assertEquals(4L, changelogReader.changelogMetadata(tp).totalRestored());
        assertEquals(2, changelogReader.changelogMetadata(tp).bufferedRecords().size());
        assertEquals(2, changelogReader.changelogMetadata(tp).bufferedLimitIndex());

        changelogReader.restore();
        assertEquals(15L, (long) changelogReader.changelogMetadata(tp).limitOffset());
        assertEquals(6L, changelogReader.changelogMetadata(tp).totalRestored());
        assertEquals(0, changelogReader.changelogMetadata(tp).bufferedRecords().size());
        assertEquals(0, changelogReader.changelogMetadata(tp).bufferedLimitIndex());

        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 12L, "key".getBytes(), "value".getBytes()));
        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 13L, "key".getBytes(), "value".getBytes()));
        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 14L, "key".getBytes(), "value".getBytes()));
        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 15L, "key".getBytes(), "value".getBytes()));

        changelogReader.restore();
        assertEquals(15L, (long) changelogReader.changelogMetadata(tp).limitOffset());
        assertEquals(9L, changelogReader.changelogMetadata(tp).totalRestored());
        assertEquals(1, changelogReader.changelogMetadata(tp).bufferedRecords().size());
        assertEquals(0, changelogReader.changelogMetadata(tp).bufferedLimitIndex());
    }

    @Test
    public void shouldRestoreMultipleChangelogs() {
        EasyMock.expect(storeMetadataOne.changelogPartition()).andReturn(tp1).anyTimes();
        EasyMock.expect(storeMetadataOne.store()).andReturn(store).anyTimes();
        EasyMock.expect(storeMetadataTwo.changelogPartition()).andReturn(tp2).anyTimes();
        EasyMock.expect(storeMetadataTwo.store()).andReturn(store).anyTimes();
        EasyMock.expect(storeMetadata.offset()).andReturn(0L).anyTimes();
        EasyMock.expect(storeMetadataOne.offset()).andReturn(0L).anyTimes();
        EasyMock.expect(storeMetadataTwo.offset()).andReturn(0L).anyTimes();
        EasyMock.expect(activeStateManager.storeMetadata(tp1)).andReturn(storeMetadataOne).anyTimes();
        EasyMock.expect(activeStateManager.storeMetadata(tp2)).andReturn(storeMetadataTwo).anyTimes();
        EasyMock.replay(activeStateManager, storeMetadata, store, storeMetadataOne, storeMetadataTwo);

        setupConsumer(10, tp);
        setupConsumer(5, tp1);
        setupConsumer(3, tp2);

        changelogReader.register(tp, activeStateManager);
        changelogReader.register(tp1, activeStateManager);
        changelogReader.register(tp2, activeStateManager);

        changelogReader.restore();

        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp).state());
        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp1).state());
        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp2).state());

        // should support removing and clearing changelogs
        changelogReader.remove(Collections.singletonList(tp));
        assertNull(changelogReader.changelogMetadata(tp));
        assertFalse(changelogReader.isEmpty());
        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp1).state());
        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp2).state());

        changelogReader.clear();
        assertTrue(changelogReader.isEmpty());
        assertNull(changelogReader.changelogMetadata(tp1));
        assertNull(changelogReader.changelogMetadata(tp2));
    }

    @Test
    public void shouldTransitState() {
        EasyMock.expect(storeMetadataOne.changelogPartition()).andReturn(tp1).anyTimes();
        EasyMock.expect(storeMetadataOne.store()).andReturn(store).anyTimes();
        EasyMock.expect(storeMetadataTwo.changelogPartition()).andReturn(tp2).anyTimes();
        EasyMock.expect(storeMetadataTwo.store()).andReturn(store).anyTimes();
        EasyMock.expect(storeMetadata.offset()).andReturn(5L).anyTimes();
        EasyMock.expect(storeMetadataOne.offset()).andReturn(5L).anyTimes();
        EasyMock.expect(storeMetadataTwo.offset()).andReturn(5L).anyTimes();
        EasyMock.expect(standbyStateManager.storeMetadata(tp1)).andReturn(storeMetadataOne).anyTimes();
        EasyMock.expect(standbyStateManager.storeMetadata(tp2)).andReturn(storeMetadataTwo).anyTimes();
        EasyMock.replay(activeStateManager, standbyStateManager, storeMetadata, store, storeMetadataOne, storeMetadataTwo);

        final MockConsumer<byte[], byte[]> consumer = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.EARLIEST) {
            @Override
            public Map<TopicPartition, Long> endOffsets(final Collection<TopicPartition> partitions) {
                return partitions.stream().collect(Collectors.toMap(Function.identity(), partition -> 10L));
            }
        };
        final StoreChangelogReader changelogReader = new StoreChangelogReader(config, logContext, consumer, callback);
        assertEquals(ACTIVE_RESTORING, changelogReader.state());

        changelogReader.register(tp, activeStateManager);
        changelogReader.register(tp1, standbyStateManager);
        changelogReader.register(tp2, standbyStateManager);
        assertEquals(StoreChangelogReader.ChangelogState.REGISTERED, changelogReader.changelogMetadata(tp).state());
        assertEquals(StoreChangelogReader.ChangelogState.REGISTERED, changelogReader.changelogMetadata(tp1).state());
        assertEquals(StoreChangelogReader.ChangelogState.REGISTERED, changelogReader.changelogMetadata(tp2).state());

        assertEquals(Collections.emptySet(), consumer.assignment());

        changelogReader.restore();

        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp).state());
        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp1).state());
        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp2).state());
        assertEquals(mkSet(tp, tp1, tp2), consumer.assignment());
        assertEquals(mkSet(tp1, tp2), consumer.paused());
        assertEquals(ACTIVE_RESTORING, changelogReader.state());

        // transition to restore active is idempotent
        changelogReader.transitToRestoreActive();
        assertEquals(ACTIVE_RESTORING, changelogReader.state());

        changelogReader.transitToUpdateStandby();
        assertEquals(STANDBY_UPDATING, changelogReader.state());

        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp).state());
        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp1).state());
        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp2).state());
        assertEquals(mkSet(tp, tp1, tp2), consumer.assignment());
        assertEquals(Collections.emptySet(), consumer.paused());

        // transition to update standby is NOT idempotent
        assertThrows(IllegalStateException.class, changelogReader::transitToUpdateStandby);

        changelogReader.remove(Collections.singletonList(tp));
        changelogReader.register(tp, activeStateManager);

        // if a new active is registered, we should immediately transit to standby updating
        assertThrows(IllegalStateException.class, changelogReader::restore);

        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp).state());
        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp1).state());
        assertEquals(StoreChangelogReader.ChangelogState.RESTORING, changelogReader.changelogMetadata(tp2).state());
        assertEquals(mkSet(tp, tp1, tp2), consumer.assignment());
        assertEquals(Collections.emptySet(), consumer.paused());
        assertEquals(STANDBY_UPDATING, changelogReader.state());

        changelogReader.transitToRestoreActive();
        assertEquals(ACTIVE_RESTORING, changelogReader.state());
        assertEquals(mkSet(tp, tp1, tp2), consumer.assignment());
        assertEquals(mkSet(tp1, tp2), consumer.paused());
    }

    @Test
    public void shouldThrowIfRestoreCallbackThrows() {
        EasyMock.expect(storeMetadata.offset()).andReturn(5L).anyTimes();
        EasyMock.replay(activeStateManager, storeMetadata, store);

        final MockConsumer<byte[], byte[]> consumer = new MockConsumer<byte[], byte[]>(OffsetResetStrategy.EARLIEST) {
            @Override
            public Map<TopicPartition, Long> endOffsets(final Collection<TopicPartition> partitions) {
                return partitions.stream().collect(Collectors.toMap(Function.identity(), partition -> 10L));
            }
        };
        final StoreChangelogReader changelogReader = new StoreChangelogReader(config, logContext, consumer, exceptionCallback);

        changelogReader.register(tp, activeStateManager);

        StreamsException thrown = assertThrows(StreamsException.class, changelogReader::restore);
        assertEquals(kaboom, thrown.getCause());

        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 6L, "key".getBytes(), "value".getBytes()));
        consumer.addRecord(new ConsumerRecord<>(topicName, 0, 7L, "key".getBytes(), "value".getBytes()));

        thrown = assertThrows(StreamsException.class, changelogReader::restore);
        assertEquals(kaboom, thrown.getCause());

        consumer.seek(tp, 10L);

        thrown = assertThrows(StreamsException.class, changelogReader::restore);
        assertEquals(kaboom, thrown.getCause());
    }

    private void setupConsumer(final long messages, final TopicPartition topicPartition) {
        assignPartition(messages, topicPartition);
        addRecords(messages, topicPartition);
        consumer.assign(Collections.emptyList());
    }

    private void addRecords(final long messages, final TopicPartition topicPartition) {
        for (int i = 0; i < messages; i++) {
            consumer.addRecord(new ConsumerRecord<>(
                topicPartition.topic(),
                topicPartition.partition(),
                i,
                new byte[0],
                new byte[0]));
        }
    }

    private void assignPartition(final long messages,
                                 final TopicPartition topicPartition) {
        consumer.updatePartitions(
            topicPartition.topic(),
            Collections.singletonList(new PartitionInfo(
                topicPartition.topic(),
                topicPartition.partition(),
                null,
                null,
                null)));
        consumer.updateBeginningOffsets(Collections.singletonMap(topicPartition, 0L));
        consumer.updateEndOffsets(Collections.singletonMap(topicPartition, Math.max(0, messages) + 1));
        consumer.assign(Collections.singletonList(topicPartition));
    }
}
