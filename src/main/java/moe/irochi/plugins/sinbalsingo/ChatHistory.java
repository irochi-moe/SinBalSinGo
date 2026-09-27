package moe.irochi.plugins.sinbalsingo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public class ChatHistory {

    public enum Type { CHAT, WHISPER, KILL }

    public record Entry(String id, long time, Type type, UUID senderId, String sender, String recipient, String message,
                        boolean filtered, boolean blocked) {

        public boolean involves(String name) {
            return sender.equalsIgnoreCase(name)
                    || (recipient != null && recipient.equalsIgnoreCase(name));
        }

    }

    private final Deque<Entry> entries = new ArrayDeque<>();
    private volatile int maxEntries;
    private volatile long maxAgeMillis;

    /** Must be called before the history is used. */
    public void configure(int maxEntries, int maxAgeMinutes) {
        this.maxEntries = Math.max(1, maxEntries);
        this.maxAgeMillis = Math.max(1, maxAgeMinutes) * 60_000L;
    }

    public synchronized void addChat(UUID senderId, String sender, String message, boolean filtered, boolean blocked) {
        add(Type.CHAT, senderId, sender, null, message, filtered, blocked);
    }

    public synchronized void addWhisper(UUID senderId, String sender, String recipient, String message, boolean filtered, boolean blocked) {
        add(Type.WHISPER, senderId, sender, recipient, message, filtered, blocked);
    }

    public synchronized void addKill(UUID senderId, String killer, String victim, String weaponName) {
        add(Type.KILL, senderId, killer, victim, weaponName, false, false);
    }

    private void add(Type type, UUID senderId, String sender, String recipient, String message, boolean filtered, boolean blocked) {
        entries.addLast(new Entry(UUID.randomUUID().toString(), System.currentTimeMillis(), type, senderId, sender, recipient, message, filtered, blocked));
        prune();
    }

    private void prune() {
        long cutoff = System.currentTimeMillis() - maxAgeMillis;
        while (!entries.isEmpty()
                && (entries.size() > maxEntries || entries.peekFirst().time() < cutoff)) {
            entries.pollFirst();
        }
    }

    /** A report must not expose a private conversation the reporter was not part of. */
    public synchronized List<Entry> snapshotFor(String target, String reporter, int limit) {
        prune();
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries) {
            boolean relevant = switch (entry.type()) {
                case CHAT -> true;
                case WHISPER -> entry.involves(target) && entry.involves(reporter);
                case KILL -> entry.involves(target);
            };
            if (relevant) result.add(entry);
        }

        if (result.size() > limit) {
            Iterator<Entry> it = result.iterator();
            int excess = result.size() - limit;
            while (excess > 0 && it.hasNext()) {
                if (!it.next().sender().equalsIgnoreCase(target)) {
                    it.remove();
                    excess--;
                }
            }
        }
        return List.copyOf(result.subList(Math.max(0, result.size() - limit), result.size()));
    }

    public synchronized int size() {
        prune();
        return entries.size();
    }
}
