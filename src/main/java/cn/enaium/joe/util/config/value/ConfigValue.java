package cn.enaium.joe.util.config.value;

import cn.enaium.joe.util.config.Config;
import cn.enaium.joe.util.config.ConfigManager;
import cn.enaium.joe.dialog.ConfigDialog;
import com.google.gson.JsonElement;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public final class ConfigValue<T extends Config> extends Value<T>{
    public ConfigValue(T config, String description){
        super(config.getClass(), config.getName(), config, description);
    }

    @Override
    public void decode(JsonElement jsonElement) {
        if (jsonElement == null || !jsonElement.isJsonObject()) {
            throw new IllegalArgumentException("Expected nested settings object for " + getName());
        }
        ConfigManager.decodeConfig(getValue(), jsonElement.getAsJsonObject());
    }

    public static Component createGui(ConfigValue<?> configValue){
        return new JButton(configValue.getName()) {{
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    new ConfigDialog(configValue.getValue()).setVisible(true);
                }
            });
        }};
    }
}
