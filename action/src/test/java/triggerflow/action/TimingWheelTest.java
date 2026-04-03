package triggerflow.action;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TimingWheel}.
 *
 * Each test controls time explicitly — no real sleeping — making the suite
 * deterministic and fast.
 */
class TimingWheelTest {

    // Convenience factory: 100ms tick, 10 slots -> covers 1000ms
    private TimingWheel wheel(long startMs) {
        return new TimingWheel(100, 10, startMs);
    }

    // -------------------------------------------------------------------------
    // 1. taskFiresAtDeadline
    // -------------------------------------------------------------------------
    @Test
    void taskFiresAtDeadline() {
        long now = 1000L;
        TimingWheel tw = wheel(now);
        AtomicBoolean fired = new AtomicBoolean(false);

        TimingWheel.TimerTask task = tw.createTask("t1", now + 500, () -> fired.set(true));
        assertThat(tw.add(task)).isTrue();

        tw.advanceClock(now + 500);
        assertThat(fired.get()).as("Task should fire exactly at its deadline").isTrue();
    }

    // -------------------------------------------------------------------------
    // 2. taskDoesNotFireBeforeDeadline
    // -------------------------------------------------------------------------
    @Test
    void taskDoesNotFireBeforeDeadline() {
        long now = 1000L;
        TimingWheel tw = wheel(now);
        AtomicBoolean fired = new AtomicBoolean(false);

        TimingWheel.TimerTask task = tw.createTask("t1", now + 500, () -> fired.set(true));
        tw.add(task);

        tw.advanceClock(now + 499);
        assertThat(fired.get()).as("Task must not fire before its deadline").isFalse();
    }

    // -------------------------------------------------------------------------
    // 3. cancelledTaskDoesNotFire
    // -------------------------------------------------------------------------
    @Test
    void cancelledTaskDoesNotFire() {
        long now = 1000L;
        TimingWheel tw = wheel(now);
        AtomicBoolean fired = new AtomicBoolean(false);

        TimingWheel.TimerTask task = tw.createTask("t1", now + 500, () -> fired.set(true));
        tw.add(task);
        task.cancel();

        tw.advanceClock(now + 600);
        assertThat(fired.get()).as("Cancelled task must not fire").isFalse();
    }

    // -------------------------------------------------------------------------
    // 4. multipleTasksSameSlot
    // -------------------------------------------------------------------------
    @Test
    void multipleTasksSameSlot() {
        long now = 1000L;
        TimingWheel tw = wheel(now);
        AtomicInteger count = new AtomicInteger(0);

        // Both map to the same slot: deadline 1500 -> slot (1500/100) % 10 = 5
        tw.add(tw.createTask("t1", now + 500, count::incrementAndGet));
        tw.add(tw.createTask("t2", now + 500, count::incrementAndGet));

        tw.advanceClock(now + 500);
        assertThat(count.get()).as("Both tasks sharing a slot must fire").isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // 5. tasksInDifferentSlots_fireAtCorrectTimes
    // -------------------------------------------------------------------------
    @Test
    void tasksInDifferentSlots_fireAtCorrectTimes() {
        long now = 0L;
        TimingWheel tw = wheel(now);
        List<String> order = new ArrayList<>();

        tw.add(tw.createTask("a", now + 300, () -> order.add("a")));
        tw.add(tw.createTask("b", now + 100, () -> order.add("b")));
        tw.add(tw.createTask("c", now + 200, () -> order.add("c")));

        tw.advanceClock(now + 100);
        assertThat(order).containsExactly("b");

        tw.advanceClock(now + 200);
        assertThat(order).containsExactly("b", "c");

        tw.advanceClock(now + 300);
        assertThat(order).containsExactly("b", "c", "a");
    }

    // -------------------------------------------------------------------------
    // 6. overflowWheelHandlesLargeDeadlines
    // -------------------------------------------------------------------------
    @Test
    void overflowWheelHandlesLargeDeadlines() {
        // tickDuration=1ms, wheelSize=10 -> covers only 10ms
        // deadline of now+100ms requires the overflow wheel
        long now = 0L;
        TimingWheel tw = new TimingWheel(1, 10, now);
        AtomicBoolean fired = new AtomicBoolean(false);

        TimingWheel.TimerTask task = tw.createTask("big", now + 100, () -> fired.set(true));
        assertThat(tw.add(task)).as("Overflow task should be accepted").isTrue();

        tw.advanceClock(now + 100);
        assertThat(fired.get()).as("Task with large deadline must fire after overflow cascade").isTrue();
    }

    // -------------------------------------------------------------------------
    // 7. overflowTask_doesNotFireBeforeDeadline
    // -------------------------------------------------------------------------
    @Test
    void overflowTask_doesNotFireBeforeDeadline() {
        long now = 0L;
        TimingWheel tw = new TimingWheel(1, 10, now);
        AtomicBoolean fired = new AtomicBoolean(false);

        tw.add(tw.createTask("big", now + 100, () -> fired.set(true)));

        tw.advanceClock(now + 99);
        assertThat(fired.get()).as("Overflow task must not fire before deadline").isFalse();
    }

    // -------------------------------------------------------------------------
    // 8. pendingCountAccurate
    // -------------------------------------------------------------------------
    @Test
    void pendingCountAccurate() {
        long now = 0L;
        TimingWheel tw = wheel(now);
        AtomicInteger fired = new AtomicInteger(0);

        List<TimingWheel.TimerTask> tasks = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            TimingWheel.TimerTask t = tw.createTask("t" + i, now + i * 100L, fired::incrementAndGet);
            tw.add(t);
            tasks.add(t);
        }
        assertThat(tw.pendingTasks()).isEqualTo(5);

        // Advance past 2 deadlines (100ms and 200ms)
        tw.advanceClock(now + 200);
        assertThat(fired.get()).isEqualTo(2);
        assertThat(tw.pendingTasks()).isEqualTo(3);

        // Cancel one more
        tasks.get(2).cancel(); // deadline=300ms
        tw.advanceClock(now + 300);
        assertThat(fired.get()).as("Cancelled task must not increment fired count").isEqualTo(2);
        assertThat(tw.pendingTasks()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // 9. expiredTaskRejected
    // -------------------------------------------------------------------------
    @Test
    void expiredTaskRejected() {
        long now = 1000L;
        TimingWheel tw = wheel(now);

        TimingWheel.TimerTask task = tw.createTask("old", now - 1, () -> {});
        assertThat(tw.add(task)).as("Past-deadline task must be rejected").isFalse();
        assertThat(tw.pendingTasks()).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // 10. cancelledTaskRejected
    // -------------------------------------------------------------------------
    @Test
    void cancelledTaskRejected() {
        long now = 1000L;
        TimingWheel tw = wheel(now);

        TimingWheel.TimerTask task = tw.createTask("pre-cancelled", now + 500, () -> {});
        task.cancel();
        assertThat(tw.add(task)).as("Pre-cancelled task must be rejected by add()").isFalse();
        assertThat(tw.pendingTasks()).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // 11. hierarchicalCascade
    // -------------------------------------------------------------------------
    @Test
    void hierarchicalCascade() {
        // tickDuration=10ms, wheelSize=5 -> inner covers 50ms
        // Task at now+120ms goes to overflow; cascades into inner when overflow tick fires.
        long now = 0L;
        TimingWheel tw = new TimingWheel(10, 5, now);
        AtomicBoolean fired = new AtomicBoolean(false);

        TimingWheel.TimerTask task = tw.createTask("cascade", now + 120, () -> fired.set(true));
        assertThat(tw.add(task)).isTrue();

        tw.advanceClock(now + 100);
        assertThat(fired.get()).as("Task must not fire at 100ms").isFalse();

        tw.advanceClock(now + 120);
        assertThat(fired.get()).as("Task must fire at 120ms after cascade").isTrue();
    }

    // -------------------------------------------------------------------------
    // 12. taskExecutionOrder
    // -------------------------------------------------------------------------
    @Test
    void taskExecutionOrder() {
        long now = 0L;
        TimingWheel tw = wheel(now);
        List<String> order = new ArrayList<>();

        tw.add(tw.createTask("a", now + 300, () -> order.add("a")));
        tw.add(tw.createTask("b", now + 100, () -> order.add("b")));
        tw.add(tw.createTask("c", now + 200, () -> order.add("c")));

        tw.advanceClock(now + 300);

        assertThat(order).as("Tasks must fire in chronological deadline order")
                .containsExactly("b", "c", "a");
    }

    // -------------------------------------------------------------------------
    // 13. performanceO1Insert
    // -------------------------------------------------------------------------
    @Test
    void performanceO1Insert() {
        long now = System.currentTimeMillis();
        TimingWheel tw = new TimingWheel(1, 512, now);

        int count = 100_000;
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            tw.add(tw.createTask("t" + i, now + 1 + (i % 511), () -> {}));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("100K inserts should complete in < 200ms (O(1) per insert); took %dms", elapsedMs)
                .isLessThan(200);
        assertThat(tw.pendingTasks()).isEqualTo(count);
    }

    // -------------------------------------------------------------------------
    // 14. massScheduleAndFire_1000Tasks
    // -------------------------------------------------------------------------
    @Test
    void massScheduleAndFire_1000Tasks() {
        long now = 0L;
        TimingWheel tw = new TimingWheel(1, 512, now);
        AtomicInteger fired = new AtomicInteger(0);

        int taskCount = 1000;
        for (int i = 0; i < taskCount; i++) {
            long deadline = now + 1 + (i % 500);
            tw.add(tw.createTask("t" + i, deadline, fired::incrementAndGet));
        }
        assertThat(tw.pendingTasks()).isEqualTo(taskCount);

        tw.advanceClock(now + 600);
        assertThat(fired.get()).isEqualTo(taskCount);
        assertThat(tw.pendingTasks()).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // 15. deadlineAtExactCurrentTick_rejected
    // -------------------------------------------------------------------------
    @Test
    void deadlineAtExactCurrentTick_rejected() {
        long now = 1000L;
        TimingWheel tw = wheel(now);

        // Deadline == currentTickMs (aligned to 1000) should be rejected
        TimingWheel.TimerTask task = tw.createTask("exact", now, () -> {});
        assertThat(tw.add(task)).as("Task with deadline at current tick must be rejected").isFalse();
    }
}
