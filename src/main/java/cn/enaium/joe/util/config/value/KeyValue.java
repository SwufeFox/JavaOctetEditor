package cn.enaium.joe.util.config.value;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

public final class KeyValue extends Value<KeyStroke> {
    public KeyValue(String name, KeyStroke value, String description) {
        super(KeyStroke.class, name, value, description);
    }

    @Override
    public void decode(JsonElement jsonElement) {
        if (jsonElement == null || jsonElement.isJsonNull()) {
            throw new IllegalArgumentException("Expected a key binding");
        }
        KeyStroke decoded;
        if (jsonElement.isJsonObject()) {
            JsonObject object = jsonElement.getAsJsonObject();
            int keyCode = integer(object.get("keyCode"));
            int modifiers = integer(object.get("modifiers"));
            requirePrimitive(object.get("onKeyRelease"), "boolean");
            boolean release = object.get("onKeyRelease").getAsBoolean();
            if (keyCode < 0 || modifiers < 0 || (modifiers & ~0x23ff) != 0) {
                throw new IllegalArgumentException("Invalid key code or keyboard modifiers");
            }
            if (keyCode == KeyEvent.VK_UNDEFINED) {
                requirePrimitive(object.get("keyChar"), "string");
                String character = object.get("keyChar").getAsString();
                if (character.length() != 1 || character.charAt(0) == KeyEvent.CHAR_UNDEFINED || release) {
                    throw new IllegalArgumentException("Expected a typed character or a pressed/released key code");
                }
                decoded = KeyStroke.getKeyStroke(Character.valueOf(character.charAt(0)), modifiers);
            } else {
                decoded = KeyStroke.getKeyStroke(keyCode, modifiers, release);
            }
        } else {
            requirePrimitive(jsonElement, "string");
            decoded = KeyStroke.getKeyStroke(jsonElement.getAsString());
        }
        if (decoded == null || (decoded.getKeyCode() == KeyEvent.VK_UNDEFINED
                && decoded.getKeyChar() == KeyEvent.CHAR_UNDEFINED)) {
            throw new IllegalArgumentException("Unrecognized key binding: " + jsonElement);
        }
        this.setValue(decoded);
    }

    public static Component createGui(final KeyValue keyValue){
        return new JButton(keyValue.getValue().toString()) {{
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyChar() != 65535) {
                        KeyStroke newKey = KeyStroke.getKeyStroke(e.getKeyCode(), e.getModifiersEx());
                        keyValue.setValue(newKey);
                        setText(newKey.toString());
                    }
                }
            });
        }};
    }
}
