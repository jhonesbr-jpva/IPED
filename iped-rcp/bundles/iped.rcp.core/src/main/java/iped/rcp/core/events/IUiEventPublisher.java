package iped.rcp.core.events;

import iped.rcp.api.UiEventTopics;

/**
 * Internal transport for the public UI event topics (task T012,
 * {@link UiEventTopics}). Headless-friendly: core services publish through
 * this interface and never touch e4 classes, so the parity harness can run
 * them in a plain JVM (events become no-ops there).
 */
public interface IUiEventPublisher {

    /**
     * Posts an event asynchronously on the given topic, in the exact format
     * the e4 {@code IEventBroker} uses, so {@code @UIEventTopic} subscribers
     * in parts receive the payload transparently.
     *
     * @param topic one of the {@link UiEventTopics} constants (or an
     *            internal topic)
     * @param data the payload, or null for signal-only events
     */
    void post(String topic, Object data);
}
