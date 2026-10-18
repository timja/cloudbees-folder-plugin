package com.cloudbees.hudson.plugins.folder;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.persistence.ConflictException;
import jenkins.persistence.PersistenceStore;

/**
 * A {@link PersistenceStore} held in memory, for tests. {@link #as} gives a view whose changes carry another origin,
 * as if made by another controller sharing the store. Changes are told to subscribers on the thread making them,
 * after the change.
 */
final class InMemoryStore implements PersistenceStore {

    private static final Logger LOGGER = Logger.getLogger(InMemoryStore.class.getName());

    private final InMemoryStore root;
    private final String origin;
    private final NavigableMap<String, Entry> entries;
    private final List<Consumer<ChangeEvent>> listeners;
    private long clock;

    InMemoryStore() {
        this.root = this;
        this.origin = UUID.randomUUID().toString();
        this.entries = new TreeMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
    }

    private InMemoryStore(InMemoryStore root, String origin) {
        this.root = root;
        this.origin = origin;
        this.entries = root.entries;
        this.listeners = root.listeners;
    }

    /** A view of the same entries whose changes carry a different origin. */
    InMemoryStore as(@NonNull String origin) {
        return new InMemoryStore(root, origin);
    }

    @NonNull
    @Override
    public String getOrigin() {
        return origin;
    }

    @NonNull
    @Override
    public AutoCloseable subscribe(@NonNull Consumer<ChangeEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void tell(List<ChangeEvent> events) {
        for (ChangeEvent e : events) {
            for (Consumer<ChangeEvent> l : listeners) {
                try {
                    l.accept(e);
                } catch (RuntimeException x) {
                    // the change is made: the other subscribers still get it
                    LOGGER.log(Level.WARNING, "A subscriber failed to handle " + e, x);
                }
            }
        }
    }

    @CheckForNull
    @Override
    public Entry read(@NonNull String key) {
        synchronized (root) {
            Entry e = entries.get(key);
            return e == null ? null : new Entry(e.content().clone(), e.version());
        }
    }

    @Override
    public long write(@NonNull String key, @NonNull byte[] content, long expectedVersion) throws IOException {
        long version;
        synchronized (root) {
            Entry current = entries.get(key);
            long actual = current == null ? ABSENT : current.version();
            if (expectedVersion != ANY && expectedVersion != actual) {
                throw new ConflictException(key, expectedVersion, actual);
            }
            if (current != null && Arrays.equals(current.content(), content)) {
                return actual; // unchanged: no new version, no event
            }
            version = ++root.clock;
            entries.put(key, new Entry(content.clone(), version));
        }
        tell(List.of(new ChangeEvent(ChangeEvent.Kind.WRITTEN, key, null, version, origin)));
        return version;
    }

    @Override
    public void delete(@NonNull String key, long expectedVersion) throws IOException {
        long version;
        synchronized (root) {
            Entry current = entries.get(key);
            if (current == null) {
                return;
            }
            if (expectedVersion != ANY && expectedVersion != current.version()) {
                throw new ConflictException(key, expectedVersion, current.version());
            }
            entries.remove(key);
            version = ++root.clock;
        }
        tell(List.of(new ChangeEvent(ChangeEvent.Kind.DELETED, key, null, version, origin)));
    }

    @NonNull
    @Override
    public Set<String> children(@NonNull String parentKey) {
        String prefix = parentKey + "/";
        Set<String> names = new TreeSet<>();
        synchronized (root) {
            for (String key : entries.tailMap(prefix).keySet()) {
                if (!key.startsWith(prefix)) {
                    break;
                }
                String rest = key.substring(prefix.length());
                int slash = rest.indexOf('/');
                names.add(slash == -1 ? rest : rest.substring(0, slash));
            }
        }
        return names;
    }

    @NonNull
    @Override
    public List<Moved> move(
            @NonNull String fromPrefix, @NonNull String toPrefix, @NonNull List<Precondition> preconditions)
            throws IOException {
        List<Moved> moved = new ArrayList<>();
        List<ChangeEvent> events = new ArrayList<>();
        synchronized (root) {
            for (Precondition p : preconditions) {
                Entry e = entries.get(p.key());
                long actual = e == null ? ABSENT : e.version();
                long expected = p.expectedVersion();
                if (expected != ANY && (expected == PRESENT ? actual == ABSENT : actual != expected)) {
                    throw new ConflictException(p.key(), expected, actual);
                }
            }
            List<String> keys = new ArrayList<>();
            for (String key : entries.keySet()) {
                if (key.equals(toPrefix) || key.startsWith(toPrefix + "/")) {
                    throw new ConflictException(key, ABSENT, entries.get(key).version());
                }
                if (key.equals(fromPrefix) || key.startsWith(fromPrefix + "/")) {
                    keys.add(key);
                }
            }
            // shallower keys first, so that an item's configuration moves before what is below it
            keys.sort((a, b) -> {
                int da = a.split("/").length;
                int db = b.split("/").length;
                return da != db ? Integer.compare(da, db) : a.compareTo(b);
            });
            for (String key : keys) {
                String newKey = toPrefix + key.substring(fromPrefix.length());
                long version = ++root.clock;
                Entry old = entries.remove(key);
                entries.put(newKey, new Entry(old.content(), version));
                moved.add(new Moved(key, old.version(), newKey, version));
                events.add(new ChangeEvent(ChangeEvent.Kind.MOVED, newKey, key, version, origin));
            }
        }
        tell(events);
        return moved;
    }
}
