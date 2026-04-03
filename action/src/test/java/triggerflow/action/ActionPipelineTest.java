package triggerflow.action;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ActionPipeline}.
 */
class ActionPipelineTest {

    private InMemoryDownstreamClient client;
    private ServiceRateLimiter rateLimiter;
    private DelayScheduler delayScheduler;
    private ActionDispatcher dispatcher;
    private ActionPipeline pipeline;

    @BeforeEach
    void setUp() {
        client = new InMemoryDownstreamClient(true, Duration.ZERO);
        rateLimiter = new ServiceRateLimiter(Map.of(
                "svc-a", new ServiceRateLimiter.RateConfig(100, 100.0)
        ));
        delayScheduler = new DelayScheduler();
        dispatcher = new ActionDispatcher(client, rateLimiter, RetryPolicy.defaults());
        pipeline = new ActionPipeline(dispatcher, rateLimiter, delayScheduler);
    }

    private ActionRequest immediateAction() {
        return new ActionRequest(
                null, ActionType.SEND_MESSAGE, "svc-a",
                Map.of("msg", "hello"), null, "camp-1", "user-1", null
        );
    }

    private ActionRequest delayedAction(Duration delay) {
        return new ActionRequest(
                null, ActionType.GRANT_REWARD, "svc-a",
                Map.of("reward", "50"), delay, "camp-2", "user-2", null
        );
    }

    @Test
    void immediateAction_flowsThroughPipeline() {
        ActionResult result = pipeline.process(immediateAction());

        assertThat(result.success()).isTrue();
        assertThat(client.getReceivedRequests()).hasSize(1);
    }

    @Test
    void delayedAction_scheduledCorrectly() {
        ActionResult result = pipeline.process(delayedAction(Duration.ofSeconds(10)));

        assertThat(result.success()).isTrue();
        assertThat(result.response()).isEqualTo("SCHEDULED");
        assertThat(delayScheduler.pendingActions()).isEqualTo(1);
        // The client should NOT have received the request yet
        assertThat(client.getReceivedRequests()).isEmpty();
    }

    @Test
    void rateLimitedAction_handled() {
        // Use a limiter with very low capacity
        ServiceRateLimiter tightLimiter = new ServiceRateLimiter(Map.of(
                "svc-a", new ServiceRateLimiter.RateConfig(1, 0.001)
        ));
        ActionPipeline tightPipeline = new ActionPipeline(dispatcher, tightLimiter, delayScheduler);

        // First one should pass
        ActionResult first = tightPipeline.process(immediateAction());
        assertThat(first.success()).isTrue();

        // Second one should be rate-limited at the pipeline level
        ActionResult second = tightPipeline.process(immediateAction());
        assertThat(second.success()).isFalse();
        assertThat(second.response()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void missingTargetService_validationFails() {
        ActionRequest invalid = new ActionRequest(
                null, ActionType.SEND_MESSAGE, "",
                Map.of(), null, "camp-1", "user-1", null
        );

        ActionResult result = pipeline.process(invalid);

        assertThat(result.success()).isFalse();
        assertThat(result.response()).contains("INVALID");
    }
}
