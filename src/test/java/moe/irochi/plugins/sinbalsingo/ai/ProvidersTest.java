package moe.irochi.plugins.sinbalsingo.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import moe.irochi.plugins.sinbalsingo.moderation.Policy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProvidersTest {

    @Test
    void providersSendHighReasoningWithStructuredAssessments() {
        var openai = new OpenAiProvider(null, "unused", "gpt-6-sol", 8192, 60, "high");
        var o = JsonParser.parseString(openai.buildBody("policy", "evidence")).getAsJsonObject();
        assertEquals("gpt-6-sol", o.get("model").getAsString());
        assertEquals("high", o.get("reasoning_effort").getAsString());
        assertTrue(o.getAsJsonObject("response_format").getAsJsonObject("json_schema").get("strict").getAsBoolean());

        var g = generationConfig(new GeminiProvider(null, "unused", "gemini-3.8-flash", 8192, 60, "high", true));
        assertEquals("high", g.getAsJsonObject("thinkingConfig").get("thinkingLevel").getAsString());
        assertEquals(Policy.schema(), g.getAsJsonObject("responseJsonSchema"));
        assertFalse(generationConfig(new GeminiProvider(null, "unused", "gemini-3.8-flash", 8192, 60, "", true))
                .has("thinkingConfig"), "a blank thinking-level leaves thinking to the model");
    }

    private static JsonObject generationConfig(GeminiProvider gemini) {
        return JsonParser.parseString(gemini.buildBody("policy", "evidence")).getAsJsonObject()
                .getAsJsonObject("generationConfig");
    }
}
