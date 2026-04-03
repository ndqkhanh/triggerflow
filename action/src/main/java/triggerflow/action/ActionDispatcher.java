package triggerflow.action;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central dispatcher that sends {@link ActionRequest}s to downstream services
 * with rate limiting and automatic retry.
 *
 * <h2>Dispatch Flow</h2>
 * <ol>
 *   <li>Check {@link ServiceRateLimiter#tryAcquire} for the target service.</li>
 *   <li>If rate-limited, back off and retry acquisition.</li>
 *   <li>Execute via {@link DownstreamClient#execute}.</li>
 *   <li>On failure, retry per {@link RetryPolicy}.</li>
 *   <li>Return {@link ActionResult}.</li>
 * </ol>
 *
 * <p>Each dispatch runs on a virtual thread, allowing the caller to dispatch
 * thousands of actions concurrently without exhausting platform threads.
 */
public class ActionDispatcher {

    private final DownstreamClient client;
    private final ServiceRateLimiter rateLimiter;
    private final RetryPolicy retryPolicy;
    private final ExecutorService executor;

    // Metrics
    private final AtomicLong dispatched = new AtomicLong();
    private final AtomicLong succeeded = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong rateLimited = new AtomicLong();

    /**
     * @param client      the downstream client to execute actions against
     * @param rateLimiter per-service rate limiter
     * @param retryPolicy retry configuration for transient failures
     */
    public ActionDispatcher(DownstreamClient client, ServiceRateLimiter rateLimiter, RetryPolicy retryPolicy) {
        this.client = client;
        this.rateLimiter = rateLimiter;
        this.retryPolicy = retryPolicy;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Dispatches an action request asynchronously on a virtual thread.
     *
     * @param request the action to dispatch
     * @return a future that completes with the action result
     */
    public CompletableFuture<ActionResult> dispatch(ActionRequest request) {
        dispatched.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> executeWithRetry(request), executor);
    }

    // -------------------------------------------------------------------------
    // Metrics
    // -------------------------------------------------------------------------

    public long getDispatched() { return dispatched.get(); }
    public long getSucceeded() { return succeeded.get(); }
    public long getFailed() { return failed.get(); }
    public long getRateLimited() { return rateLimited.get(); }

    // -------------------------------------------------------------------------
    // Private
    // -------------------------------------------------------------------------

    private ActionResult executeWithRetry(ActionRequest request) {
        // Acquire rate limit token, retrying up to maxRetries times
        if (!acquireRateLimit(request.targetService())) {
            rateLimited.incrementAndGet();
            failed.incrementAndGet();
            return new ActionResult(request.actionId(), false, "RATE_LIMITED", Duration.ZERO, 0);
        }

        int attempt = 0;
        ActionResult lastResult = null;

        while (attempt <= retryPolicy.maxRetries()) {
            Instant start = Instant.now();
            try {
                lastResult = client.execute(request);
                Duration latency = Duration.between(start, Instant.now());
                lastResult = new ActionResult(
                        lastResult.actionId(),
                        lastResult.success(),
                        lastResult.response(),
                        latency,
                        attempt
                );

                if (lastResult.success()) {
                    succeeded.incrementAndGet();
                    return lastResult;
                }
            } catch (Exception e) {
                Duration latency = Duration.between(start, Instant.now());
                lastResult = new ActionResult(request.actionId(), false, e.getMessage(), latency, attempt);
            }

            // Don't sleep after the last attempt
            if (attempt < retryPolicy.maxRetries()) {
                sleepQuietly(retryPolicy.delayForAttempt(attempt));
            }
            attempt++;
        }

        failed.incrementAndGet();
        return lastResult;
    }

    /**
     * Tries to acquire a rate-limit token, retrying a few times with short sleeps.
     */
    private boolean acquireRateLimit(String serviceName) {
        for (int i = 0; i < 3; i++) {
            if (rateLimiter.tryAcquire(serviceName)) {
                return true;
            }
            sleepQuietly(Duration.ofMillis(50));
        }
        return false;
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
