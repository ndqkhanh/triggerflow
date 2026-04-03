package triggerflow.campaign;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * O(1) lookup index mapping event types to active campaigns.
 *
 * <p>Uses a volatile reference for atomic swap: {@link #rebuild(Collection)} builds
 * a new immutable index and swaps it in atomically, while {@link #getCampaigns(String)}
 * reads the current snapshot without locking.</p>
 */
public class EventPrefilter {

    private volatile Map<String, List<Campaign>> index = Map.of();

    /**
     * Rebuilds the index from the given campaigns. Only ACTIVE campaigns are included.
     * The new index is swapped in atomically via volatile write.
     */
    public void rebuild(Collection<Campaign> campaigns) {
        var newIndex = new HashMap<String, List<Campaign>>();
        for (Campaign c : campaigns) {
            if (c.status() == CampaignStatus.ACTIVE) {
                newIndex.computeIfAbsent(c.triggerEventType(), k -> new ArrayList<>()).add(c);
            }
        }
        // Make lists unmodifiable
        var immutable = new HashMap<String, List<Campaign>>();
        newIndex.forEach((k, v) -> immutable.put(k, List.copyOf(v)));
        this.index = Map.copyOf(immutable);
    }

    /**
     * O(1) lookup of campaigns triggered by this event type.
     *
     * @return unmodifiable list of matching campaigns, or empty list if none
     */
    public List<Campaign> getCampaigns(String eventType) {
        return index.getOrDefault(eventType, List.of());
    }

    /**
     * Returns the number of distinct event types currently indexed.
     */
    public int indexedEventTypes() {
        return index.size();
    }
}
