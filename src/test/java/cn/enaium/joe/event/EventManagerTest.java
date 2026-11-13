package cn.enaium.joe.event;

import cn.enaium.joe.util.event.EventManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class EventManagerTest {
    record Changed(int value) implements EventManager.Event {}
    record Other() implements EventManager.Event {}

    @Test
    void deliversToMatchingTypesSynchronouslyAndInRegistrationOrder() {
        EventManager manager = new EventManager();
        List<Integer> observed = new ArrayList<>();
        AtomicInteger allEvents = new AtomicInteger();
        manager.register(EventManager.Event.class, event -> allEvents.incrementAndGet());
        manager.register(Changed.class, event -> observed.add(event.value()));
        manager.register(Changed.class, event -> observed.add(event.value() * 10));
        manager.register(Other.class, event -> fail("Unrelated listener must not run"));
        manager.call(new Changed(3));
        assertEquals(List.of(3, 30), observed);
        assertEquals(1, allEvents.get());
    }

    @Test
    void registrationsDuringDispatchStartWithTheNextEvent() {
        EventManager manager = new EventManager();
        List<String> observed = new ArrayList<>();
        Consumer<Changed> later = event -> observed.add("later:" + event.value());
        manager.register(Changed.class, event -> {
            observed.add("first:" + event.value());
            manager.register(Changed.class, later);
        });
        manager.call(new Changed(1));
        assertEquals(List.of("first:1"), observed);
        manager.call(new Changed(2));
        assertEquals(List.of("first:1", "first:2", "later:2"), observed);
    }

    @Test
    void listenerFailuresRemainVisibleAfterEveryMatchingListenerRuns() {
        EventManager manager = new EventManager();
        AtomicInteger completed = new AtomicInteger();
        RuntimeException first = new IllegalStateException("first failure");
        RuntimeException second = new IllegalArgumentException("second failure");
        manager.register(Changed.class, event -> { throw first; });
        manager.register(Changed.class, event -> completed.incrementAndGet());
        manager.register(Changed.class, event -> { throw second; });
        manager.register(EventManager.Event.class, event -> completed.incrementAndGet());
        assertSame(first, assertThrows(IllegalStateException.class, () -> manager.call(new Changed(1))));
        assertEquals(2, completed.get());
        assertArrayEquals(new Throwable[]{second}, first.getSuppressed());
    }

    @Test
    void aFailedEventDoesNotPoisonTheNextDispatch() {
        EventManager manager = new EventManager();
        List<Integer> values = new ArrayList<>();
        manager.register(Changed.class, event -> {
            if (event.value() == 1) throw new IllegalStateException("one bad event");
        });
        manager.register(Changed.class, event -> values.add(event.value()));
        assertThrows(IllegalStateException.class, () -> manager.call(new Changed(1)));
        manager.call(new Changed(2));
        assertEquals(List.of(1, 2), values);
    }

    @Test
    void rejectsNullListenersAtRegistration() {
        EventManager manager = new EventManager();
        assertThrows(NullPointerException.class, () -> manager.register(Changed.class, null));
        assertThrows(NullPointerException.class, () -> manager.call(null));
    }
}
