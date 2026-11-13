package cn.enaium.joe.tool;

import com.google.gson.JsonObject;

/** A failure that a tool caller can handle without interpreting a Java stack trace. */
public final class ToolException extends Exception {
    private final String code;
    private final JsonObject details;

    public ToolException(String code, String message) {
        this(code, message, new JsonObject());
    }

    public ToolException(String code, String message, JsonObject details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public JsonObject toJson() {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", getMessage());
        if (!details.isEmpty()) {
            error.add("details", details.deepCopy());
        }
        JsonObject result = new JsonObject();
        result.add("error", error);
        return result;
    }
}
