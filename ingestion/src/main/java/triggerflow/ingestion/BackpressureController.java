package triggerflow.ingestion;

import java.util.concurrent.Semaphore;

/**
 * Semaphore-based admission controller that prevents unbounded virtual thread
 * creation under burst load.
 *
 * <p>Each unit of work must {@link #tryAcquire()} a permit before processing
 * and {@link #release()} it when done. When all permits are exhausted, new
 * work is rejected rather than queued, providing fast back-pressure feedback
 * to upstream producers.
 *
 * <p>This is intentionally simple: a fair {@link Semaphore} provides the
 * correct ordering guarantees and the JDK handles the low-level park/unpark
 * efficiently with virtual threads.
 */
public class BackpressureController {

    private final Semaphore semaphore;

    /**
     * Creates a controller with the given maximum number of concurrent permits.
     *
     * @param maxPermits maximum concurrent work units allowed
     */
    public BackpressureController(int maxPermits) {
        if (maxPermits <= 0) throw new IllegalArgumentException("maxPermits must be positive");
        this.semaphore = new Semaphore(maxPermits, true);
    }

    /**
     * Attempts to acquire a permit without blocking.
     *
     * @return true if a permit was acquired, false if all permits are in use
     */
    public boolean tryAcquire() {
        return semaphore.tryAcquire();
    }

    /**
     * Releases a previously acquired permit.
     */
    public void release() {
        semaphore.release();
    }

    /**
     * Returns the number of permits currently available.
     */
    public int availablePermits() {
        return semaphore.availablePermits();
    }
}
