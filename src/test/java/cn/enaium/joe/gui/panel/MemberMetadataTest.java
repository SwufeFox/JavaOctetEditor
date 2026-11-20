package cn.enaium.joe.gui.panel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MemberMetadataTest {
    @Test
    void fieldDescriptorsAreTrimmedAndValidated() {
        assertEquals("Ljava/lang/String;", MemberMetadata.fieldDescriptor(" Ljava/lang/String; "));
        assertEquals("[I", MemberMetadata.fieldDescriptor("[I"));
        assertThrows(IllegalArgumentException.class, () -> MemberMetadata.fieldDescriptor("()V"));
        assertThrows(IllegalArgumentException.class, () -> MemberMetadata.fieldDescriptor("V"));
        assertThrows(IllegalArgumentException.class, () -> MemberMetadata.fieldDescriptor("I;"));
        assertThrows(IllegalArgumentException.class, () -> MemberMetadata.fieldDescriptor("L;"));
        assertThrows(IllegalArgumentException.class, () -> MemberMetadata.fieldDescriptor("Ljava/lang/String;X"));
        assertThrows(IllegalArgumentException.class, () -> MemberMetadata.fieldDescriptor(""));
    }

    @Test
    void methodDescriptorsAreTrimmedAndValidated() {
        assertEquals("(ILjava/lang/String;)V", MemberMetadata.methodDescriptor(" (ILjava/lang/String;)V "));
        assertThrows(IllegalArgumentException.class, () -> MemberMetadata.methodDescriptor("I"));
        assertThrows(IllegalArgumentException.class, () -> MemberMetadata.methodDescriptor("(I)"));
        assertThrows(IllegalArgumentException.class, () -> MemberMetadata.methodDescriptor("(L;)V"));
        assertThrows(IllegalArgumentException.class, () -> MemberMetadata.methodDescriptor("(I)VX"));
        assertThrows(IllegalArgumentException.class, () -> MemberMetadata.methodDescriptor(""));
    }

    @Test
    void accessAndExceptionsAreParsedAndEmptyEntriesAreIgnored() {
        assertEquals(0x0001, MemberMetadata.access(" 1 "));
        assertThrows(IllegalArgumentException.class, () -> MemberMetadata.access("public"));
        assertEquals(List.of("java/lang/Error", "java/lang/RuntimeException"),
                MemberMetadata.exceptions("java/lang/Error;; java/lang/RuntimeException;"));
        assertNull(MemberMetadata.nullableText("  "));
        assertEquals("java/lang/Error;java/lang/RuntimeException",
                MemberMetadata.displayList(List.of("java/lang/Error", "java/lang/RuntimeException")));
    }
}
