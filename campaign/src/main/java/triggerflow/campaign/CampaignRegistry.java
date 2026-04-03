package triggerflow.campaign;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thread-safe campaign registry using copy-on-write with volatile swap.
 *
 * <p>All mutations ({@link #register}, {@link #reload}) create a new immutable map
 * and swap it atomically via a volatile reference. Reads are lock-free.</p>
 */
public class CampaignRegistry {

    private volatile Map<String, Campaign> campaigns = Map.of();

    /**
     * Registers a single campaign. Uses copy-on-write: copies the current map,
     * adds the campaign, and swaps in the new map.
     */
    public synchronized void register(Campaign campaign) {
        var updated = new HashMap<>(campaigns);
        updated.put(campaign.campaignId(), campaign);
        this.campaigns = Map.copyOf(updated);
    }

    /**
     * Atomically replaces all campaigns with the given list.
     */
    public synchronized void reload(List<Campaign> campaignList) {
        var newMap = new HashMap<String, Campaign>();
        for (Campaign c : campaignList) {
            newMap.put(c.campaignId(), c);
        }
        this.campaigns = Map.copyOf(newMap);
    }

    /**
     * Retrieves a campaign by ID.
     */
    public Optional<Campaign> get(String campaignId) {
        return Optional.ofNullable(campaigns.get(campaignId));
    }

    /**
     * Returns all campaigns with ACTIVE status.
     */
    public Collection<Campaign> getActive() {
        return campaigns.values().stream()
                .filter(c -> c.status() == CampaignStatus.ACTIVE)
                .toList();
    }

    /**
     * Returns the total number of registered campaigns.
     */
    public int size() {
        return campaigns.size();
    }
}
