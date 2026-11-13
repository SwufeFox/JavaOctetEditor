package cn.enaium.joe.util.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoadRequestStateTest {
    @Test
    void newerIntentInvalidatesOlderEvenWhenNewerNeverPublishes() {
        LoadRequestState state = new LoadRequestState();
        long older = state.begin();
        long newer = state.begin();
        assertFalse(state.isCurrent(older));
        assertTrue(state.isCurrent(newer));
        // Failed or cancelled requests do not restore an older request's authority.
        assertFalse(state.isCurrent(older));
    }

    @Test
    void replacementAndShutdownInvalidatePendingPublication() {
        LoadRequestState state = new LoadRequestState();
        long first = state.begin();
        state.invalidate();
        assertFalse(state.isCurrent(first));
        long second = state.begin();
        state.close();
        assertFalse(state.isCurrent(second));
        assertThrows(IllegalStateException.class, state::begin);
    }
}
