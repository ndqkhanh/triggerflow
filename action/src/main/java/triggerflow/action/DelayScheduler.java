package triggerflow.action;

/**
 * Wrapper around {@link TimingWheel} for scheduling delayed action execution.
 *
 * <p>The scheduler provides a simple API for the action pipeline: schedule an
 * {@link ActionRequest} to fire a callback after its configured delay, then
 * call {@link #tick()} periodically to advance the clock and fire expired tasks.
 *
 * <p>Uses a 100ms tick / 512-slot wheel, covering ~51 seconds of delay at the
 * finest granularity. Longer delays are handled by the overflow wheel hierarchy.
 */
public class DelayScheduler {

    private final TimingWheel wheel;

    /** Creates a scheduler starting at the current system time. */
    public DelayScheduler() {
        this.wheel = new TimingWheel(100, 512, System.currentTimeMillis());
    }

    /**
     * Schedules an action to fire after its configured delay.
     *
     * @param action the action request whose {@link ActionRequest#delay()} determines the deadline
     * @param onFire callback invoked when the delay expires
     */
    public void schedule(ActionRequest action, Runnable onFire) {
        if (action.delay() == null || action.delay().isZero() || action.delay().isNegative()) {
            // No delay — fire immediately without touching the wheel
            onFire.run();
            return;
        }
        long deadlineMs = System.currentTimeMillis() + action.delay().toMillis();
        TimingWheel.TimerTask task = wheel.createTask(action.actionId(), deadlineMs, onFire);
        boolean added = wheel.add(task);
        if (!added) {
            // Deadline already passed or equals current tick — fire immediately
            onFire.run();
        }
    }

    /**
     * Advances the clock to now and fires all expired tasks.
     * Should be called periodically (e.g. every 100ms) from a ticker thread.
     */
    public void tick() {
        wheel.advanceClock(System.currentTimeMillis());
    }

    /**
     * Returns the number of actions currently waiting to fire.
     */
    public int pendingActions() {
        return wheel.pendingTasks();
    }
}
