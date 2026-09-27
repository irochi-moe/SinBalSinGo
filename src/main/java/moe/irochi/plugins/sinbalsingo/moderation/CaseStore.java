package moe.irochi.plugins.sinbalsingo.moderation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.AssessmentState;
import moe.irochi.plugins.sinbalsingo.moderation.ModerationCase.Enforcement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * One local owner, atomic durable replacement, fail closed on corrupt storage. Only opening the store throws a checked
 * exception; later disk failures are unchecked so callers need not wrap them.
 */
public final class CaseStore implements AutoCloseable {

    public static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String INTERRUPTED = "Restart during dispatch; verify externally. Never replay automatically.";

    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private boolean closed;
    private final Map<String, ModerationCase> cases = new LinkedHashMap<>();

    public CaseStore(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(directory.resolve("store.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        lock = lockChannel.tryLock();
        if (lock == null) throw new IOException("Case store already in use");
        try {
            load();
        } catch (Exception e) {
            close();
            throw new IOException("Cannot load case store", e);
        }
    }

    private void load() throws IOException {
        try (var paths = Files.list(directory)) {
            for (Path p : paths.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                ModerationCase c = JSON.fromJson(Files.readString(p), ModerationCase.class);
                if (c == null || c.target == null || !p.getFileName().toString().equals(c.id + ".json")) {
                    throw new IOException("Invalid case store");
                }
                cases.put(c.id, c);
            }
        }
        for (ModerationCase c : cases.values()) {
            String saved = JSON.toJson(c);
            recover(c);
            if (!JSON.toJson(c).equals(saved)) persist(c);
        }
    }

    /** Settles work a restart cut short. An action that may have been dispatched is never replayed. */
    private static void recover(ModerationCase c) {
        if (c.assessmentState == AssessmentState.RUNNING) c.assessmentState = AssessmentState.PENDING;
        if (c.enforcement == Enforcement.DISPATCHING) {
            c.enforcement = Enforcement.UNKNOWN;
            c.executionOutcome = INTERRUPTED;
            c.discordDirty = true;
        }
        if (c.automaticEnforcement == Enforcement.DISPATCHING) {
            c.automaticEnforcement = Enforcement.UNKNOWN;
            c.automaticOutcome = INTERRUPTED;
            c.discordDirty = true;
        }
    }

    private ModerationCase copy(ModerationCase c) {
        return JSON.fromJson(JSON.toJson(c), ModerationCase.class);
    }

    public synchronized List<ModerationCase> all() {
        return cases.values().stream().map(this::copy).toList();
    }

    public synchronized ModerationCase get(String id) {
        ModerationCase c = cases.get(id);
        return c == null ? null : copy(c);
    }

    public synchronized void insert(ModerationCase c) {
        ensureOpen();
        if (cases.containsKey(c.id)) throw new IllegalStateException("Duplicate case");
        persist(c);
        cases.put(c.id, copy(c));
    }

    public synchronized ModerationCase update(String id, Consumer<ModerationCase> change) {
        ensureOpen();
        ModerationCase c = get(id);
        if (c == null) throw new IllegalStateException("Unknown case");
        change.accept(c);
        persist(c);
        cases.put(id, copy(c));
        return copy(c);
    }

    private void persist(ModerationCase c) {
        Path temp = directory.resolve(c.id + ".tmp");
        Path dest = directory.resolve(c.id + ".json");
        ByteBuffer bytes = ByteBuffer.wrap(JSON.toJson(c).getBytes(StandardCharsets.UTF_8));
        try {
            try (FileChannel out = FileChannel.open(temp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                while (bytes.hasRemaining()) out.write(bytes);
                out.force(true);
            }
            Files.move(temp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) {
                dir.force(true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void export(Path path) {
        // No chat text or reporter names: the export must be safe to share for evaluation.
        List<String> lines = new ArrayList<>();
        for (ModerationCase c : cases.values()) {
            JsonObject row = new JsonObject();
            row.addProperty("caseId", c.id);
            row.addProperty("policyVersion", c.policyVersion);
            row.addProperty("automaticCandidate", c.automaticCandidate);
            row.addProperty("decision", c.decision.name());
            row.addProperty("assessment", c.assessmentState.name());
            row.addProperty("review", c.reviewState.name());
            row.addProperty("enforcement", c.enforcement.name());
            row.add("automaticFindings", JSON.toJsonTree(c.automaticFindings));
            row.addProperty("automaticEnforcement", c.automaticEnforcement.name());
            row.add("findings", JSON.toJsonTree(c.assessment == null ? List.of() : c.assessment.findings()));
            row.add("audit", JSON.toJsonTree(c.audit));
            lines.add(row.toString());
        }
        try {
            Files.write(path, lines);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Deletes old cases whole, except those with an action still to run or that {@code busy} still expects. */
    public synchronized int deleteOlderThan(long cutoff, Predicate<String> busy) {
        ensureOpen();
        int deleted = 0;
        for (ModerationCase c : List.copyOf(cases.values())) {
            if (c.created >= cutoff || unfinished(c.enforcement) || unfinished(c.automaticEnforcement) || busy.test(c.id)) {
                continue;
            }
            try {
                Files.deleteIfExists(directory.resolve(c.id + ".json"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            cases.remove(c.id);
            deleted++;
        }
        return deleted;
    }

    private static boolean unfinished(Enforcement e) {
        return e == Enforcement.READY || e == Enforcement.DISPATCHING;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Case store closed");
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        lock.release();
        lockChannel.close();
    }
}
