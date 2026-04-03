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

class CampaignStateMachineTest {

    private CampaignStateMachine stateMachine;
    private RuleNode sampleCondition;

    @BeforeEach
    void setUp() {
        stateMachine = new CampaignStateMachine();
        sampleCondition = new RuleNode.ConditionNode("payload.country", ComparisonOp.EQ, "VN", DataSource.MEMORY);
    }

    private Campaign createSampleCampaign(String id) {
        return stateMachine.createCampaign(
                id, "Test Campaign", "order.completed",
                sampleCondition, List.of(new ActionDefinition("send_push", "notif-svc", null)),
                null, null, null, null, null, null
        );
    }

    @Test
    @DisplayName("createCampaign puts campaign in DRAFT status")
    void createCampaign_draftStatus() {
        Campaign campaign = createSampleCampaign("c-1");

        assertThat(campaign.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(campaign.campaignId()).isEqualTo("c-1");
        assertThat(campaign.name()).isEqualTo("Test Campaign");
    }

    @Test
    @DisplayName("DRAFT -> ACTIVE transition succeeds")
    void draftToActive_succeeds() {
        createSampleCampaign("c-1");

        Campaign active = stateMachine.transition("c-1", CampaignStatus.ACTIVE);

        assertThat(active.status()).isEqualTo(CampaignStatus.ACTIVE);
        assertThat(active.campaignId()).isEqualTo("c-1");
    }

    @Test
    @DisplayName("ACTIVE -> PAUSED transition succeeds")
    void activeToPaused_succeeds() {
        createSampleCampaign("c-1");
        stateMachine.transition("c-1", CampaignStatus.ACTIVE);

        Campaign paused = stateMachine.transition("c-1", CampaignStatus.PAUSED);

        assertThat(paused.status()).isEqualTo(CampaignStatus.PAUSED);
    }

    @Test
    @DisplayName("PAUSED -> ACTIVE transition succeeds")
    void pausedToActive_succeeds() {
        createSampleCampaign("c-1");
        stateMachine.transition("c-1", CampaignStatus.ACTIVE);
        stateMachine.transition("c-1", CampaignStatus.PAUSED);

        Campaign reactivated = stateMachine.transition("c-1", CampaignStatus.ACTIVE);

        assertThat(reactivated.status()).isEqualTo(CampaignStatus.ACTIVE);
    }

    @Test
    @DisplayName("ACTIVE -> COMPLETED transition succeeds")
    void activeToCompleted_succeeds() {
        createSampleCampaign("c-1");
        stateMachine.transition("c-1", CampaignStatus.ACTIVE);

        Campaign completed = stateMachine.transition("c-1", CampaignStatus.COMPLETED);

        assertThat(completed.status()).isEqualTo(CampaignStatus.COMPLETED);
    }

    @Test
    @DisplayName("ACTIVE -> CANCELLED transition succeeds")
    void activeToCancelled_succeeds() {
        createSampleCampaign("c-1");
        stateMachine.transition("c-1", CampaignStatus.ACTIVE);

        Campaign cancelled = stateMachine.transition("c-1", CampaignStatus.CANCELLED);

        assertThat(cancelled.status()).isEqualTo(CampaignStatus.CANCELLED);
    }

    @Test
    @DisplayName("COMPLETED -> ACTIVE throws IllegalStateException")
    void completedToActive_throws() {
        createSampleCampaign("c-1");
        stateMachine.transition("c-1", CampaignStatus.ACTIVE);
        stateMachine.transition("c-1", CampaignStatus.COMPLETED);

        assertThatThrownBy(() -> stateMachine.transition("c-1", CampaignStatus.ACTIVE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED")
                .hasMessageContaining("ACTIVE");
    }

    @Test
    @DisplayName("CANCELLED -> ACTIVE throws IllegalStateException")
    void cancelledToActive_throws() {
        createSampleCampaign("c-1");
        stateMachine.transition("c-1", CampaignStatus.ACTIVE);
        stateMachine.transition("c-1", CampaignStatus.CANCELLED);

        assertThatThrownBy(() -> stateMachine.transition("c-1", CampaignStatus.ACTIVE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CANCELLED")
                .hasMessageContaining("ACTIVE");
    }

    @Test
    @DisplayName("DRAFT -> COMPLETED throws IllegalStateException (not allowed)")
    void draftToCompleted_throws() {
        createSampleCampaign("c-1");

        assertThatThrownBy(() -> stateMachine.transition("c-1", CampaignStatus.COMPLETED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRAFT")
                .hasMessageContaining("COMPLETED");
    }

    @Test
    @DisplayName("DRAFT -> PAUSED throws IllegalStateException")
    void draftToPaused_throws() {
        createSampleCampaign("c-1");

        assertThatThrownBy(() -> stateMachine.transition("c-1", CampaignStatus.PAUSED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRAFT")
                .hasMessageContaining("PAUSED");
    }

    @Test
    @DisplayName("Duplicate campaignId throws IllegalStateException")
    void duplicateCampaignId_throws() {
        createSampleCampaign("c-1");

        assertThatThrownBy(() -> createSampleCampaign("c-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("getCampaign for non-existent campaign throws")
    void getCampaign_nonExistent_throws() {
        assertThatThrownBy(() -> stateMachine.getCampaign("does-not-exist"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("activeCampaignCount reflects only ACTIVE campaigns")
    void activeCampaignCount_filtersCorrectly() {
        createSampleCampaign("c-1");
        createSampleCampaign("c-2");
        createSampleCampaign("c-3");

        assertThat(stateMachine.activeCampaignCount()).isZero();

        stateMachine.transition("c-1", CampaignStatus.ACTIVE);
        stateMachine.transition("c-2", CampaignStatus.ACTIVE);
        assertThat(stateMachine.activeCampaignCount()).isEqualTo(2);

        stateMachine.transition("c-1", CampaignStatus.PAUSED);
        assertThat(stateMachine.activeCampaignCount()).isEqualTo(1);
    }
}
