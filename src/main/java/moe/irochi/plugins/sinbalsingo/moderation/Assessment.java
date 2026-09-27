package moe.irochi.plugins.sinbalsingo.moderation;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import moe.irochi.plugins.sinbalsingo.ChatHistory;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

public record Assessment(String summary, List<Finding> findings) {

    public enum Status { ESTABLISHED, SUSPECTED, NOT_VIOLATION }

    public record ExceptionCheck(String exception, boolean applies, String explanation) {}

    public record Finding(String ruleId, String category, Status status, int severity, int confidence,
                          List<String> evidenceIds, List<String> alternatives, List<String> missingContext,
                          List<String> questions, String explanation, String checkExplanation,
                          boolean checkSatisfied, List<ExceptionCheck> exceptions) {}

    public static Assessment parse(String text, List<ChatHistory.Entry> evidence, UUID target) {
        if (text == null || text.length() > 100_000) throw new IllegalArgumentException("response size");
        JsonElement tree = strictJson(text);
        validate(tree, Policy.schema());
        Assessment a = new Gson().fromJson(tree, Assessment.class);
        if (a.summary.isBlank() || a.findings.size() > 30) throw new IllegalArgumentException("summary/findings");

        Map<String, ChatHistory.Entry> index = new HashMap<>();
        evidence.forEach(e -> index.put(e.id(), e));
        for (Finding f : a.findings) {
            Policy.Rule rule = Policy.rule(f.ruleId);
            if (!rule.category().equals(f.category) || f.explanation.isBlank() || f.checkExplanation.isBlank()) {
                throw new IllegalArgumentException("rule or explanation");
            }
            if (f.evidenceIds.isEmpty() || new HashSet<>(f.evidenceIds).size() != f.evidenceIds.size()) {
                throw new IllegalArgumentException("evidence required");
            }
            for (String id : f.evidenceIds) {
                ChatHistory.Entry e = index.get(id);
                if (e == null || !target.equals(e.senderId())) throw new IllegalArgumentException("unsupported attribution");
            }
            if (f.exceptions.isEmpty()) throw new IllegalArgumentException("exceptions not considered");
            if (f.exceptions.stream().anyMatch(e -> e.exception().isBlank() || e.explanation().isBlank())) {
                throw new IllegalArgumentException("empty exception check");
            }
            // Every evidence ID was just matched to a message, so only the free-text claims can be blank.
            if (Stream.of(f.alternatives, f.missingContext, f.questions).flatMap(List::stream).anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("Blank contextual claim");
            }
            if (f.status == Status.NOT_VIOLATION && (!f.questions.isEmpty() || !f.missingContext.isEmpty())) {
                throw new IllegalArgumentException("Unresolved context is not acquittal");
            }
            if (f.status == Status.SUSPECTED && f.questions.isEmpty()) {
                throw new IllegalArgumentException("concrete uncertainty required");
            }
            if (f.status == Status.ESTABLISHED && (!f.questions.isEmpty() || !f.alternatives.isEmpty()
                    || !f.missingContext.isEmpty() || f.exceptions.stream().anyMatch(ExceptionCheck::applies)
                    || !f.checkSatisfied)) {
                throw new IllegalArgumentException("contradictory established finding");
            }
        }
        return a;
    }

    private static JsonElement strictJson(String text) {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement value = read(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing JSON");
            return value;
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON", e);
        }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 12) throw new IllegalArgumentException("JSON nesting");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                JsonObject object = new JsonObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IllegalArgumentException("Duplicate key");
                    object.add(key, read(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                JsonArray array = new JsonArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1));
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> JsonParser.parseString(reader.nextString());
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new IllegalArgumentException("JSON token");
        };
    }

    // Deliberately strict subset of JSON Schema used by both providers; rejects coercions and unknown keys.
    private static void validate(JsonElement value, JsonObject schema) {
        switch (schema.get("type").getAsString()) {
            case "object" -> {
                if (!value.isJsonObject()) throw new IllegalArgumentException("object required");
                JsonObject object = value.getAsJsonObject();
                JsonObject properties = schema.getAsJsonObject("properties");
                if (!object.keySet().equals(properties.keySet())) {
                    throw new IllegalArgumentException("unexpected/missing fields");
                }
                properties.entrySet().forEach(e -> validate(object.get(e.getKey()), e.getValue().getAsJsonObject()));
            }
            case "array" -> {
                if (!value.isJsonArray() || value.getAsJsonArray().size() > 100) throw new IllegalArgumentException("array");
                value.getAsJsonArray().forEach(e -> validate(e, schema.getAsJsonObject("items")));
            }
            case "string" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                        || value.getAsString().length() > 4000) {
                    throw new IllegalArgumentException("string");
                }
                if (schema.has("enum") && !schema.getAsJsonArray("enum").contains(value)) {
                    throw new IllegalArgumentException("enum");
                }
            }
            case "boolean" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException("boolean");
                }
            }
            case "integer" -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                        || !value.toString().matches("[0-9]+")
                        || value.getAsBigDecimal().compareTo(schema.get("maximum").getAsBigDecimal()) > 0) {
                    throw new IllegalArgumentException("score");
                }
            }
            default -> throw new IllegalArgumentException("schema");
        }
    }
}
