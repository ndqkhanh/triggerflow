package triggerflow.ingestion;

import org.junit.jupiter.api.Test;
import triggerflow.common.TriggerFlowEvent;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PartitionConsumer}.
 */
class PartitionConsumerTest {

    private TriggerFlowEvent event(String id) {
        return new TriggerFlowEvent(id, "TEST", "user-1", Map.of(), Instant.now(), "test-service");
    }

    @Test
    void start_processesEventsFromStream() throws InterruptedException {
        InMemoryEventStream stream = new InMemoryEventStream(0);
        stream.publish(event("e1"));
        stream.publish(event("e2"));

        CopyOnWriteArrayList<String> processed = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        PartitionConsumer consumer = new PartitionConsumer(stream, e -> {
            processed.add(e.eventId());
            latch.countDown();
        });
        consumer.start();

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        consumer.stop();

        assertThat(processed).containsExactly("e1", "e2");
    }

    @Test
    void stop_haltsProcessing() throws InterruptedException {
        InMemoryEventStream stream = new InMemoryEventStream(0);
        PartitionConsumer consumer = new PartitionConsumer(stream, e -> {});
        consumer.start();

        Thread.sleep(50);
        consumer.stop();
        Thread.sleep(100);

        assertThat(consumer.isRunning()).isFalse();
    }

    @Test
    void eventsConsumed_countAccurate() throws InterruptedException {
        InMemoryEventStream stream = new InMemoryEventStream(0);
        stream.publish(event("e1"));
        stream.publish(event("e2"));
        stream.publish(event("e3"));

        CountDownLatch latch = new CountDownLatch(3);
        PartitionConsumer consumer = new PartitionConsumer(stream, e -> latch.countDown());
        consumer.start();

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        consumer.stop();

        assertThat(consumer.eventsConsumed()).isEqualTo(3);
    }

    @Test
    void gracefulShutdown_completesPending() throws InterruptedException {
        InMemoryEventStream stream = new InMemoryEventStream(0);
        for (int i = 0; i < 10; i++) {
            stream.publish(event("e" + i));
        }

        CountDownLatch latch = new CountDownLatch(10);
        PartitionConsumer consumer = new PartitionConsumer(stream, e -> latch.countDown());
        consumer.start();

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        consumer.stop();

        assertThat(consumer.eventsConsumed()).isEqualTo(10);
    }

    @Test
    void emptyStream_consumerPollsButProcessesNothing() throws InterruptedException {
        InMemoryEventStream stream = new InMemoryEventStream(0);

        PartitionConsumer consumer = new PartitionConsumer(stream, e -> {});
        consumer.start();

        Thread.sleep(200);
        consumer.stop();

        assertThat(consumer.eventsConsumed()).isEqualTo(0);
    }

    @Test
    void latePublish_processedAfterStart() throws InterruptedException {
        InMemoryEventStream stream = new InMemoryEventStream(0);

        CountDownLatch latch = new CountDownLatch(1);
        PartitionConsumer consumer = new PartitionConsumer(stream, e -> latch.countDown());
        consumer.start();

        // Publish after consumer is running
        Thread.sleep(50);
        stream.publish(event("late-1"));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        consumer.stop();

        assertThat(consumer.eventsConsumed()).isEqualTo(1);
    }

    @Test
    void multipleStartStop_cycles() throws InterruptedException {
        InMemoryEventStream stream = new InMemoryEventStream(0);
        stream.publish(event("e1"));

        CountDownLatch latch = new CountDownLatch(1);
        PartitionConsumer consumer = new PartitionConsumer(stream, e -> latch.countDown());

        consumer.start();
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        consumer.stop();
        Thread.sleep(50);

        assertThat(consumer.eventsConsumed()).isEqualTo(1);
    }

    @Test
    void partitionNumber_matchesStream() throws InterruptedException {
        InMemoryEventStream stream = new InMemoryEventStream(5);

        CountDownLatch latch = new CountDownLatch(1);
        stream.publish(event("e1"));
        PartitionConsumer consumer = new PartitionConsumer(stream, e -> latch.countDown());
        consumer.start();

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        consumer.stop();

        // Verify the consumer processed events from partition 5
        assertThat(consumer.eventsConsumed()).isEqualTo(1);
    }

    @Test
    void highVolume_processesAllEvents() throws InterruptedException {
        InMemoryEventStream stream = new InMemoryEventStream(0);
        int eventCount = 500;
        for (int i = 0; i < eventCount; i++) {
            stream.publish(event("e" + i));
        }

        CountDownLatch latch = new CountDownLatch(eventCount);
        PartitionConsumer consumer = new PartitionConsumer(stream, e -> latch.countDown());
        consumer.start();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        consumer.stop();

        assertThat(consumer.eventsConsumed()).isEqualTo(eventCount);
    }

    @Test
    void consumerNotStarted_eventsConsumedIsZero() {
        InMemoryEventStream stream = new InMemoryEventStream(0);
        stream.publish(event("e1"));

        PartitionConsumer consumer = new PartitionConsumer(stream, e -> {});

        assertThat(consumer.eventsConsumed()).isEqualTo(0);
    }
}
