package triggerflow.ingestion;

import org.junit.jupiter.api.Test;
import triggerflow.common.TriggerFlowEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ConsumerCoordinator}.
 */
class ConsumerCoordinatorTest {

    private TriggerFlowEvent event(String id) {
        return new TriggerFlowEvent(id, "TEST", "user-1", Map.of(), Instant.now(), "test-service");
    }

    @Test
    void startWithMultipleStreams_allConsuming() throws InterruptedException {
        InMemoryEventStream s1 = new InMemoryEventStream(0);
        InMemoryEventStream s2 = new InMemoryEventStream(1);
        s1.publish(event("e1"));
        s2.publish(event("e2"));

        CountDownLatch latch = new CountDownLatch(2);
        ConsumerCoordinator coordinator = new ConsumerCoordinator(
                List.of(s1, s2),
                e -> latch.countDown()
        );
        coordinator.start();

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        coordinator.stop();

        assertThat(coordinator.totalEventsConsumed()).isEqualTo(2);
    }

    @Test
    void stop_haltsAllConsumers() throws InterruptedException {
        InMemoryEventStream s1 = new InMemoryEventStream(0);
        InMemoryEventStream s2 = new InMemoryEventStream(1);

        ConsumerCoordinator coordinator = new ConsumerCoordinator(
                List.of(s1, s2),
                e -> {}
        );
        coordinator.start();
        Thread.sleep(50);
        coordinator.stop();
        Thread.sleep(100);

        for (PartitionConsumer consumer : coordinator.consumers()) {
            assertThat(consumer.isRunning()).isFalse();
        }
    }

    @Test
    void totalEventsConsumed_isSumOfAll() throws InterruptedException {
        InMemoryEventStream s1 = new InMemoryEventStream(0);
        InMemoryEventStream s2 = new InMemoryEventStream(1);
        InMemoryEventStream s3 = new InMemoryEventStream(2);

        s1.publish(event("e1"));
        s1.publish(event("e2"));
        s2.publish(event("e3"));
        s3.publish(event("e4"));
        s3.publish(event("e5"));
        s3.publish(event("e6"));

        CountDownLatch latch = new CountDownLatch(6);
        ConsumerCoordinator coordinator = new ConsumerCoordinator(
                List.of(s1, s2, s3),
                e -> latch.countDown()
        );
        coordinator.start();

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        coordinator.stop();

        assertThat(coordinator.totalEventsConsumed()).isEqualTo(6);
    }

    @Test
    void singleStream_works() throws InterruptedException {
        InMemoryEventStream stream = new InMemoryEventStream(0);
        stream.publish(event("e1"));

        CountDownLatch latch = new CountDownLatch(1);
        ConsumerCoordinator coordinator = new ConsumerCoordinator(
                List.of(stream),
                e -> latch.countDown()
        );
        coordinator.start();

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        coordinator.stop();

        assertThat(coordinator.totalEventsConsumed()).isEqualTo(1);
    }

    @Test
    void eventsFromAllPartitions_areProcessed() throws InterruptedException {
        int partitions = 5;
        CopyOnWriteArrayList<String> processedIds = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(partitions);

        List<InMemoryEventStream> streams = new java.util.ArrayList<>();
        for (int i = 0; i < partitions; i++) {
            InMemoryEventStream s = new InMemoryEventStream(i);
            s.publish(event("e-p" + i));
            streams.add(s);
        }

        ConsumerCoordinator coordinator = new ConsumerCoordinator(
                List.copyOf(streams),
                e -> {
                    processedIds.add(e.eventId());
                    latch.countDown();
                }
        );
        coordinator.start();

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        coordinator.stop();

        assertThat(processedIds).hasSize(partitions);
        for (int i = 0; i < partitions; i++) {
            assertThat(processedIds).contains("e-p" + i);
        }
    }

    @Test
    void emptyStreams_noErrors() throws InterruptedException {
        InMemoryEventStream s1 = new InMemoryEventStream(0);
        InMemoryEventStream s2 = new InMemoryEventStream(1);

        ConsumerCoordinator coordinator = new ConsumerCoordinator(
                List.of(s1, s2),
                e -> {}
        );
        coordinator.start();
        Thread.sleep(100);
        coordinator.stop();

        assertThat(coordinator.totalEventsConsumed()).isEqualTo(0);
    }

    @Test
    void coordinatorCreatesCorrectNumberOfConsumers() {
        InMemoryEventStream s1 = new InMemoryEventStream(0);
        InMemoryEventStream s2 = new InMemoryEventStream(1);
        InMemoryEventStream s3 = new InMemoryEventStream(2);

        ConsumerCoordinator coordinator = new ConsumerCoordinator(
                List.of(s1, s2, s3),
                e -> {}
        );
        coordinator.start();
        coordinator.stop();

        assertThat(coordinator.consumers()).hasSize(3);
    }
}
