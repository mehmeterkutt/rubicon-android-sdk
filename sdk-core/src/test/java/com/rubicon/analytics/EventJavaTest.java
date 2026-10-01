package com.rubicon.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rubicon.analytics.internal.event.SampleEvents;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

// Public API 7: hooks, observers and the event types used from Java.
class EventJavaTest {
    private final TrackEvent track = SampleEvents.INSTANCE.getTrack();

    @Test
    void aHookIsAJavaLambdaThatCopiesOrDrops() {
        BeforeSend removeEmail = event -> {
            if (event instanceof TrackEvent) {
                Map<String, Object> properties = new HashMap<>(((TrackEvent) event).getProperties());
                properties.remove("currency");
                return ((TrackEvent) event).copy(((TrackEvent) event).getName(), properties);
            }
            return event;
        };
        BeforeSend dropEmulators = event -> Boolean.TRUE.equals(event.getContext().getDevice().isEmulator()) ? null : event;

        TrackEvent cleaned = (TrackEvent) removeEmail.process(track);

        assertTrue(!cleaned.getProperties().containsKey("currency"));
        assertSame(track, dropEmulators.process(track));
    }

    @Test
    void copyHasJavaOverloads() {
        assertEquals("Order Placed", track.copy("Order Placed").getName());
        assertEquals(track.getProperties(), track.copy().getProperties());
        assertEquals(Map.of("plan", "free"), SampleEvents.INSTANCE.getIdentify().copy(Map.of("plan", "free")).getTraits());
    }

    @Test
    void anObserverIsAJavaLambda() {
        List<String> seen = new ArrayList<>();
        EventObserver observer = update -> {
            if (update instanceof EventUpdate.Queued) {
                seen.add("queued " + ((EventUpdate.Queued) update).getEvent().getMessageId());
            } else if (update instanceof EventUpdate.Dropped) {
                seen.add("dropped " + ((EventUpdate.Dropped) update).getReason());
            }
        };

        observer.onUpdate(new EventUpdate.Queued(track));
        observer.onUpdate(new EventUpdate.Dropped(List.of("x"), DropReason.OVERSIZE));

        assertEquals(List.of("queued " + track.getMessageId(), "dropped OVERSIZE"), seen);
    }

    @Test
    void contextIsReadThroughGetters() {
        EventContext context = track.getContext();

        assertEquals("Pixel 8", context.getDevice().getModel());
        assertEquals(Integer.valueOf(36), context.getOs().getApiLevel());
        assertEquals(1_759_147_082_000L, context.getSession().getStartedAt());
        assertEquals("tr-TR", context.getLocale());
        assertNull(SampleEvents.INSTANCE.getIdentify().getContext().getSession());
        assertEquals(EventSource.APP, track.getSource());
    }

    @Test
    void theConfigTakesHooksAndAnObserver() {
        AnalyticsConfig config = new AnalyticsConfig("rk_test");
        BeforeSend keep = event -> event;
        EventObserver ignore = update -> { };

        config.setBeforeSend(List.of(keep));
        config.getLogging().setEventObserver(ignore);

        assertEquals(List.of(keep), config.getBeforeSend());
        assertSame(ignore, config.getLogging().getEventObserver());
    }
}
