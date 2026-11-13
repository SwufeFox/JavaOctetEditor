package cn.enaium.joe.tool;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;

final class ToolJson {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private ToolJson() {
    }

    static JsonElement parse(String input) {
        if (input.isBlank()) {
            throw new JsonParseException("Expected a JSON value");
        }
        try (JsonReader reader = new JsonReader(new StringReader(input))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new JsonParseException("Unexpected content after JSON value");
            }
            return value;
        } catch (IOException e) {
            throw new JsonParseException(e);
        }
    }

    static JsonObject object(String input) {
        JsonElement value = parse(input);
        if (!value.isJsonObject()) {
            throw new JsonParseException("Expected a JSON object");
        }
        return value.getAsJsonObject();
    }
}
