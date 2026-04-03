package triggerflow.action;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ActionDispatcher}.
 */
class ActionDispatcherTest {

    private ActionRequest request(String targetService) {
        return new ActionRequest(
                "act-1", ActionType.SEND_MESSAGE, targetService,
                Map.of("key", "value"), null, "camp-1", "user-1", null
        );
    }

    private ServiceRateLimiter unlimitedRateLimiter() {
        return new ServiceRateLimiter(Map.of(
                "svc-a", new ServiceRateLimiter.RateConfig(1000, 1000.0)
        ));
    }

    @Test
    void successfulDispatch_returnsSuccessResult() throws Exception {
        InMemoryDownstreamClient client = new InMemoryDownstreamClient(true, Duration.ZERO);
        ActionDispatcher dispatcher = new ActionDispatcher(client, unlimitedRateLimiter(), RetryPolicy.defaults());

        CompletableFuture<ActionResult> future = dispatcher.dispatch(request("svc-a"));
        ActionResult result = future.get();

        assertThat(result.success()).isTrue();
        assertThat(result.actionId()).isEqualTo("act-1");
        assertThat(client.getReceivedRequests()).hasSize(1);
    }

    @Test
    void failedDispatch_retriesUpToMaxRetries() throws Exception {
        InMemoryDownstreamClient client = new InMemoryDownstreamClient(false, Duration.ZERO);
        RetryPolicy policy = new RetryPolicy(2, Duration.ofMillis(10), 1.0, 0.0);
        ActionDispatcher dispatcher = new ActionDispatcher(client, unlimitedRateLimiter(), policy);

        CompletableFuture<ActionResult> future = dispatcher.dispatch(request("svc-a"));
        ActionResult result = future.get();

        assertThat(result.success()).isFalse();
        // 1 initial attempt + 2 retries = 3 total calls
        assertThat(client.getReceivedRequests()).hasSize(3);
    }

    @Test
    void rateLimited_returnsFailure() throws Exception {
        // Capacity of 0 is invalid; use capacity=1 and exhaust it
        ServiceRateLimiter limiter = new ServiceRateLimiter(Map.of(
                "svc-a", new ServiceRateLimiter.RateConfig(1, 0.001)
        ));
        // Exhaust the single token
        limiter.tryAcquire("svc-a");

        InMemoryDownstreamClient client = new InMemoryDownstreamClient(true, Duration.ZERO);
        ActionDispatcher dispatcher = new ActionDispatcher(client, limiter, RetryPolicy.defaults());

        CompletableFuture<ActionResult> future = dispatcher.dispatch(request("svc-a"));
        ActionResult result = future.get();

        assertThat(result.success()).isFalse();
        assertThat(result.response()).contains("RATE_LIMITED");
    }

    @Test
    void metricsTracking_dispatched() throws Exception {
        InMemoryDownstreamClient client = new InMemoryDownstreamClient(true, Duration.ZERO);
        ActionDispatcher dispatcher = new ActionDispatcher(client, unlimitedRateLimiter(), RetryPolicy.defaults());

        dispatcher.dispatch(request("svc-a")).get();
        dispatcher.dispatch(request("svc-a")).get();

        assertThat(dispatcher.getDispatched()).isEqualTo(2);
    }

    @Test
    void metricsTracking_succeeded() throws Exception {
        InMemoryDownstreamClient client = new InMemoryDownstreamClient(true, Duration.ZERO);
        ActionDispatcher dispatcher = new ActionDispatcher(client, unlimitedRateLimiter(), RetryPolicy.defaults());

        dispatcher.dispatch(request("svc-a")).get();

        assertThat(dispatcher.getSucceeded()).isEqualTo(1);
    }

    @Test
    void metricsTracking_failed() throws Exception {
        InMemoryDownstreamClient client = new InMemoryDownstreamClient(false, Duration.ZERO);
        RetryPolicy policy = new RetryPolicy(0, Duration.ofMillis(10), 1.0, 0.0);
        ActionDispatcher dispatcher = new ActionDispatcher(client, unlimitedRateLimiter(), policy);

        dispatcher.dispatch(request("svc-a")).get();

        assertThat(dispatcher.getFailed()).isEqualTo(1);
    }

    @Test
    void virtualThreadExecution_runsAsynchronously() {
        InMemoryDownstreamClient client = new InMemoryDownstreamClient(true, Duration.ofMillis(50));
        ActionDispatcher dispatcher = new ActionDispatcher(client, unlimitedRateLimiter(), RetryPolicy.defaults());

        long start = System.nanoTime();
        CompletableFuture<ActionResult> f1 = dispatcher.dispatch(request("svc-a"));
        CompletableFuture<ActionResult> f2 = dispatcher.dispatch(request("svc-a"));
        CompletableFuture.allOf(f1, f2).join();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Two 50ms tasks in parallel should take ~50ms, not ~100ms
        assertThat(elapsedMs).isLessThan(150);
        assertThat(f1.join().success()).isTrue();
        assertThat(f2.join().success()).isTrue();
    }

    @Test
    void resultIncludesRetryCount() throws Exception {
        InMemoryDownstreamClient client = new InMemoryDownstreamClient(true, Duration.ZERO);
        ActionDispatcher dispatcher = new ActionDispatcher(client, unlimitedRateLimiter(), RetryPolicy.defaults());

        ActionResult result = dispatcher.dispatch(request("svc-a")).get();

        assertThat(result.retryCount()).isEqualTo(0);
    }
}
