package triggerflow.campaign;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import triggerflow.rules.ComparisonOp;
import triggerflow.rules.DataSource;
import triggerflow.rules.RuleNode;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignRegistryTest {

    private CampaignRegistry registry;
    private RuleNode sampleCondition;

    @BeforeEach
    void setUp() {
        registry = new CampaignRegistry();
        sampleCondition = new RuleNode.ConditionNode("payload.country", ComparisonOp.EQ, "VN", DataSource.MEMORY);
    }

    private Campaign campaign(String id, CampaignStatus status) {
        return new Campaign(id, "Campaign " + id, status, "order.completed",
                sampleCondition, List.of(), null, null, null, null, null, null);
    }

    @Test
    @DisplayName("Register and get retrieves campaign")
    void registerAndGet_retrievesCampaign() {
        Campaign c = campaign("c-1", CampaignStatus.ACTIVE);
        registry.register(c);

        Optional<Campaign> result = registry.get("c-1");

        assertThat(result).isPresent();
        assertThat(result.get().campaignId()).isEqualTo("c-1");
    }

    @Test
    @DisplayName("Get non-existent returns empty Optional")
    void get_nonExistent_returnsEmpty() {
        assertThat(registry.get("does-not-exist")).isEmpty();
    }

    @Test
    @DisplayName("Reload replaces all campaigns")
    void reload_replacesAllCampaigns() {
        registry.register(campaign("c-1", CampaignStatus.ACTIVE));
        registry.register(campaign("c-2", CampaignStatus.ACTIVE));
        assertThat(registry.size()).isEqualTo(2);

        registry.reload(List.of(campaign("c-3", CampaignStatus.DRAFT)));

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.get("c-1")).isEmpty();
        assertThat(registry.get("c-2")).isEmpty();
        assertThat(registry.get("c-3")).isPresent();
    }

    @Test
    @DisplayName("getActive filters only ACTIVE status")
    void getActive_filtersActiveOnly() {
        registry.register(campaign("c-1", CampaignStatus.ACTIVE));
        registry.register(campaign("c-2", CampaignStatus.DRAFT));
        registry.register(campaign("c-3", CampaignStatus.PAUSED));
        registry.register(campaign("c-4", CampaignStatus.ACTIVE));
        registry.register(campaign("c-5", CampaignStatus.COMPLETED));

        Collection<Campaign> active = registry.getActive();

        assertThat(active).hasSize(2);
        assertThat(active).extracting(Campaign::campaignId)
                .containsExactlyInAnyOrder("c-1", "c-4");
    }

    @Test
    @DisplayName("Size returns correct count")
    void size_returnsCorrectCount() {
        assertThat(registry.size()).isZero();

        registry.register(campaign("c-1", CampaignStatus.ACTIVE));
        assertThat(registry.size()).isEqualTo(1);

        registry.register(campaign("c-2", CampaignStatus.DRAFT));
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Register overwrites existing with same ID")
    void register_overwritesExisting() {
        registry.register(campaign("c-1", CampaignStatus.DRAFT));
        assertThat(registry.get("c-1").get().status()).isEqualTo(CampaignStatus.DRAFT);

        registry.register(campaign("c-1", CampaignStatus.ACTIVE));
        assertThat(registry.get("c-1").get().status()).isEqualTo(CampaignStatus.ACTIVE);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("Thread-safe: concurrent register operations")
    void concurrentRegister_threadSafe() throws InterruptedException {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            int idx = i;
            executor.submit(() -> {
                try {
                    registry.register(campaign("c-" + idx, CampaignStatus.ACTIVE));
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertThat(registry.size()).isEqualTo(threadCount);
    }

    @Test
    @DisplayName("Reload with empty list clears registry")
    void reload_emptyList_clearsRegistry() {
        registry.register(campaign("c-1", CampaignStatus.ACTIVE));
        registry.register(campaign("c-2", CampaignStatus.ACTIVE));
        assertThat(registry.size()).isEqualTo(2);

        registry.reload(List.of());

        assertThat(registry.size()).isZero();
        assertThat(registry.getActive()).isEmpty();
    }
}
