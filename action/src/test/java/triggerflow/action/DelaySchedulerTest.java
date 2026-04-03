package triggerflow.action;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DelayScheduler}.
 */
class DelaySchedulerTest {

    private ActionRequest delayedAction(Duration delay) {
        return new ActionRequest(
                null, ActionType.SEND_MESSAGE, "svc-a",
                Map.of(), delay, "campaign-1", "user-1", null
        );
    }

    @Test
    void scheduleAndTick_firesAction() throws InterruptedException {
        DelayScheduler scheduler = new DelayScheduler();
        AtomicBoolean fired = new AtomicBoolean(false);

        scheduler.schedule(delayedAction(Duration.ofMillis(150)), () -> fired.set(true));
        assertThat(fired.get()).isFalse();

        Thread.sleep(300);
        scheduler.tick();

        assertThat(fired.get()).isTrue();
    }

    @Test
    void pendingCount_tracksScheduledActions() {
        DelayScheduler scheduler = new DelayScheduler();

        scheduler.schedule(delayedAction(Duration.ofSeconds(10)), () -> {});
        scheduler.schedule(delayedAction(Duration.ofSeconds(20)), () -> {});

        assertThat(scheduler.pendingActions()).isEqualTo(2);
    }

    @Test
    void multipleDelays_allFire() throws InterruptedException {
        DelayScheduler scheduler = new DelayScheduler();
        AtomicInteger count = new AtomicInteger(0);

        scheduler.schedule(delayedAction(Duration.ofMillis(150)), count::incrementAndGet);
        scheduler.schedule(delayedAction(Duration.ofMillis(200)), count::incrementAndGet);
        scheduler.schedule(delayedAction(Duration.ofMillis(250)), count::incrementAndGet);

        Thread.sleep(400);
        scheduler.tick();

        assertThat(count.get()).isEqualTo(3);
    }

    @Test
    void tickBeforeDeadline_doesNotFire() {
        DelayScheduler scheduler = new DelayScheduler();
        AtomicBoolean fired = new AtomicBoolean(false);

        scheduler.schedule(delayedAction(Duration.ofSeconds(60)), () -> fired.set(true));
        scheduler.tick(); // immediately — delay hasn't elapsed

        assertThat(fired.get()).isFalse();
        assertThat(scheduler.pendingActions()).isEqualTo(1);
    }

    @Test
    void nullDelay_firesImmediately() throws InterruptedException {
        DelayScheduler scheduler = new DelayScheduler();
        AtomicBoolean fired = new AtomicBoolean(false);

        ActionRequest noDelay = new ActionRequest(
                null, ActionType.SEND_MESSAGE, "svc-a",
                Map.of(), null, "campaign-1", "user-1", null
        );
        scheduler.schedule(noDelay, () -> fired.set(true));

        Thread.sleep(10);
        scheduler.tick();

        // With null delay, deadline was set to ~now, so it should fire very quickly
        assertThat(fired.get()).isTrue();
    }
}
