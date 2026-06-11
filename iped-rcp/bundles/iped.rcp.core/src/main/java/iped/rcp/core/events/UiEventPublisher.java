package iped.rcp.core.events;

import java.util.Map;

import org.eclipse.e4.core.services.events.IEventBroker;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link IUiEventPublisher} over the OSGi {@code EventAdmin} (task T012).
 * The payload travels under the {@link IEventBroker#DATA} property — the
 * same wire format produced by the e4 {@code EventBroker} — keeping part
 * subscribers ({@code @UIEventTopic}) agnostic of who published.
 *
 * <p>
 * {@code EventAdmin} is an optional dynamic reference: without it (plain-JVM
 * parity harness) publishing degrades to a debug log instead of failing.
 */
@Component(service = IUiEventPublisher.class)
public class UiEventPublisher implements IUiEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(UiEventPublisher.class);

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    private volatile EventAdmin eventAdmin;

    @Override
    public void post(String topic, Object data) {
        EventAdmin admin = eventAdmin;
        if (admin == null) {
            LOGGER.debug("No EventAdmin available, dropping event {}", topic);
            return;
        }
        Map<String, Object> properties = data == null ? Map.of() : Map.of(IEventBroker.DATA, data);
        admin.postEvent(new Event(topic, properties));
    }
}
