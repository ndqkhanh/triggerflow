package triggerflow.campaign;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import triggerflow.rules.ComparisonOp;
import triggerflow.rules.DataSource;
import triggerflow.rules.RuleNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventPrefilterTest {

    private EventPrefilter prefilter;
    private RuleNode sampleCondition;

    @BeforeEach
    void setUp() {
        prefilter = new EventPrefilter();
        sampleCondition = new RuleNode.ConditionNode("payload.amount", ComparisonOp.GT, 100, DataSource.MEMORY);
    }

    private Campaign activeCampaign(String id, String eventType) {
        return new Campaign(id, "Campaign " + id, CampaignStatus.ACTIVE, eventType,
                sampleCondition, List.of(), null, null, null, null, null, null);
    }

    private Campaign draftCampaign(String id, String eventType) {
        return new Campaign(id, "Campaign " + id, CampaignStatus.DRAFT, eventType,
                sampleCondition, List.of(), null, null, null, null, null, null);
    }

    private Campaign pausedCampaign(String id, String eventType) {
        return new Campaign(id, "Campaign " + id, CampaignStatus.PAUSED, eventType,
                sampleCondition, List.of(), null, null, null, null, null, null);
    }

    @Test
    @DisplayName("Empty prefilter returns empty list")
    void empty_returnsEmptyList() {
        assertThat(prefilter.getCampaigns("order.completed")).isEmpty();
    }

    @Test
    @DisplayName("Single campaign matching event type returns it")
    void singleCampaign_matchingEventType() {
        prefilter.rebuild(List.of(activeCampaign("c-1", "order.completed")));

        List<Campaign> result = prefilter.getCampaigns("order.completed");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().campaignId()).isEqualTo("c-1");
    }

    @Test
    @DisplayName("Non-matching event type returns empty list")
    void nonMatchingEventType_returnsEmpty() {
        prefilter.rebuild(List.of(activeCampaign("c-1", "order.completed")));

        assertThat(prefilter.getCampaigns("user.registered")).isEmpty();
    }

    @Test
    @DisplayName("Multiple campaigns with same event type all returned")
    void multipleCampaigns_sameEventType() {
        prefilter.rebuild(List.of(
                activeCampaign("c-1", "order.completed"),
                activeCampaign("c-2", "order.completed")
        ));

        List<Campaign> result = prefilter.getCampaigns("order.completed");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(Campaign::campaignId).containsExactlyInAnyOrder("c-1", "c-2");
    }

    @Test
    @DisplayName("Multiple event types route to correct campaigns")
    void multipleEventTypes_correctRouting() {
        prefilter.rebuild(List.of(
                activeCampaign("c-1", "order.completed"),
                activeCampaign("c-2", "user.registered"),
                activeCampaign("c-3", "order.completed")
        ));

        assertThat(prefilter.getCampaigns("order.completed")).hasSize(2);
        assertThat(prefilter.getCampaigns("user.registered")).hasSize(1);
        assertThat(prefilter.getCampaigns("payment.failed")).isEmpty();
    }

    @Test
    @DisplayName("Rebuild replaces old data completely")
    void rebuild_replacesOldData() {
        prefilter.rebuild(List.of(activeCampaign("c-1", "order.completed")));
        assertThat(prefilter.getCampaigns("order.completed")).hasSize(1);

        prefilter.rebuild(List.of(activeCampaign("c-2", "user.registered")));
        assertThat(prefilter.getCampaigns("order.completed")).isEmpty();
        assertThat(prefilter.getCampaigns("user.registered")).hasSize(1);
    }

    @Test
    @DisplayName("Only ACTIVE campaigns are included in index")
    void onlyActiveCampaigns_included() {
        prefilter.rebuild(List.of(
                activeCampaign("c-1", "order.completed"),
                draftCampaign("c-2", "order.completed"),
                pausedCampaign("c-3", "order.completed")
        ));

        List<Campaign> result = prefilter.getCampaigns("order.completed");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().campaignId()).isEqualTo("c-1");
    }

    @Test
    @DisplayName("getCampaigns returns unmodifiable list")
    void getCampaigns_returnsUnmodifiable() {
        prefilter.rebuild(List.of(activeCampaign("c-1", "order.completed")));

        List<Campaign> result = prefilter.getCampaigns("order.completed");

        assertThatThrownBy(() -> result.add(activeCampaign("c-2", "order.completed")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("indexedEventTypes count is correct")
    void indexedEventTypes_correctCount() {
        prefilter.rebuild(List.of(
                activeCampaign("c-1", "order.completed"),
                activeCampaign("c-2", "user.registered"),
                activeCampaign("c-3", "order.completed")
        ));

        assertThat(prefilter.indexedEventTypes()).isEqualTo(2);
    }

    @Test
    @DisplayName("Rebuild with empty collection clears index")
    void rebuild_emptyCollection_clearsIndex() {
        prefilter.rebuild(List.of(activeCampaign("c-1", "order.completed")));
        assertThat(prefilter.indexedEventTypes()).isEqualTo(1);

        prefilter.rebuild(List.of());

        assertThat(prefilter.indexedEventTypes()).isZero();
        assertThat(prefilter.getCampaigns("order.completed")).isEmpty();
    }
}
