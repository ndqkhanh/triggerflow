package triggerflow.action;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double for {@link DownstreamClient} that records all received requests
 * and returns configurable results.
 *
 * <p>Useful for unit tests that need to verify dispatch behaviour without
 * making real network calls. Latency simulation uses {@link Thread#sleep}
 * so that timing-sensitive tests can observe realistic delays.
 */
public class InMemoryDownstreamClient implements DownstreamClient {

    private final boolean shouldSucceed;
    private final Duration simulatedLatency;
    private final CopyOnWriteArrayList<ActionRequest> receivedRequests = new CopyOnWriteArrayList<>();

    /**
     * @param shouldSucceed    if true, {@link #execute} returns a success result; otherwise failure
     * @param simulatedLatency how long to sleep before returning (may be {@link Duration#ZERO})
     */
    public InMemoryDownstreamClient(boolean shouldSucceed, Duration simulatedLatency) {
        this.shouldSucceed = shouldSucceed;
        this.simulatedLatency = simulatedLatency;
    }

    /** Convenience constructor that always succeeds with zero latency. */
    public InMemoryDownstreamClient() {
        this(true, Duration.ZERO);
    }

    @Override
    public ActionResult execute(ActionRequest request) {
        receivedRequests.add(request);

        if (!simulatedLatency.isZero()) {
            try {
                Thread.sleep(simulatedLatency.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String response = shouldSucceed ? "OK" : "FAILURE";
        return new ActionResult(request.actionId(), shouldSucceed, response, simulatedLatency, 0);
    }

    /** Returns an unmodifiable view of all requests received so far. */
    public List<ActionRequest> getReceivedRequests() {
        return List.copyOf(receivedRequests);
    }
}
