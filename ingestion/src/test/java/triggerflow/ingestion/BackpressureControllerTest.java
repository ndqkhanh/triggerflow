package triggerflow.ingestion;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BackpressureController}.
 */
class BackpressureControllerTest {

    @Test
    void acquireWithinLimit_returnsTrue() {
        BackpressureController controller = new BackpressureController(5);

        assertThat(controller.tryAcquire()).isTrue();
    }

    @Test
    void acquireBeyondLimit_returnsFalse() {
        BackpressureController controller = new BackpressureController(2);

        assertThat(controller.tryAcquire()).isTrue();
        assertThat(controller.tryAcquire()).isTrue();
        assertThat(controller.tryAcquire()).isFalse();
    }

    @Test
    void releaseIncreasesAvailable() {
        BackpressureController controller = new BackpressureController(1);

        controller.tryAcquire();
        assertThat(controller.availablePermits()).isEqualTo(0);

        controller.release();
        assertThat(controller.availablePermits()).isEqualTo(1);
    }

    @Test
    void availablePermits_accurate() {
        BackpressureController controller = new BackpressureController(10);

        assertThat(controller.availablePermits()).isEqualTo(10);

        controller.tryAcquire();
        controller.tryAcquire();
        controller.tryAcquire();

        assertThat(controller.availablePermits()).isEqualTo(7);
    }

    @Test
    void concurrentAcquireRelease_threadSafe() throws InterruptedException {
        int maxPermits = 10;
        BackpressureController controller = new BackpressureController(maxPermits);
        int threadCount = 50;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger acquired = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    if (controller.tryAcquire()) {
                        acquired.incrementAndGet();
                        // Hold the permit briefly
                        Thread.sleep(10);
                        controller.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();

        // After all threads complete, all permits should be returned
        assertThat(controller.availablePermits()).isEqualTo(maxPermits);
    }

    @Test
    void invalidMaxPermits_throws() {
        assertThatThrownBy(() -> new BackpressureController(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackpressureController(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acquireAndReleaseCycle_permitsRestored() {
        BackpressureController controller = new BackpressureController(3);

        // Exhaust all permits
        controller.tryAcquire();
        controller.tryAcquire();
        controller.tryAcquire();
        assertThat(controller.tryAcquire()).isFalse();

        // Release one
        controller.release();
        assertThat(controller.tryAcquire()).isTrue();
        assertThat(controller.tryAcquire()).isFalse();
    }
}
