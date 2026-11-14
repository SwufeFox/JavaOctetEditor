package cn.enaium.joe.gui.panel.file.tabbed.tab.resources;

import org.junit.jupiter.api.Test;

import java.nio.charset.CharacterCodingException;

import static org.junit.jupiter.api.Assertions.*;

class TextTablePanelEncodingTest {
    @Test
    void rejectsMalformedUtf8AndUnpairedSurrogatesWithoutReplacement() throws Exception {
        assertThrows(CharacterCodingException.class, () -> TextTablePanel.decodeUtf8(new byte[]{(byte) 0xc3, 0x28}));
        assertThrows(CharacterCodingException.class, () -> TextTablePanel.decodeUtf8(new byte[]{(byte) 0xed, (byte) 0xa0, (byte) 0x80}));
        assertThrows(CharacterCodingException.class, () -> TextTablePanel.encodeUtf8("before\ud800after"));
        assertThrows(CharacterCodingException.class, () -> TextTablePanel.encodeUtf8("before\udc00after"));
        String text = "配置\nemoji: \ud83d\ude80\n\ufeff";
        assertEquals(text, TextTablePanel.decodeUtf8(TextTablePanel.encodeUtf8(text)));
    }
}
