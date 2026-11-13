package cn.enaium.joe.util.task;

/** EDT-owned authority for archive loads, including intents that later fail or cancel. */
public final class LoadRequestState {
    private long generation;
    private boolean closed;

    public long begin() {
        if (closed) throw new IllegalStateException("Editor is closed");
        return ++generation;
    }

    public boolean isCurrent(long request) {
        return !closed && generation == request;
    }

    public void invalidate() {
        generation++;
    }

    public void close() {
        closed = true;
        invalidate();
    }
}
