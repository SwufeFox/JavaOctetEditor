package cn.enaium.joe.asm;

import cn.enaium.joe.util.compiler.Compiler;
import cn.enaium.joe.util.compiler.virtual.MemoryClassLoader;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.ASMifier;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VisitorTest {
    @Test
    void asmifiedSourceRecreatesExecutableClass() throws Exception {
        byte[] original = Compiler.compileSources(Map.of("fixture.Sample", """
                package fixture;
                public class Sample { public static int answer() { return 42; } }
                """), Map.of()).get("fixture.Sample");
        StringWriter source = new StringWriter();
        new ClassReader(original).accept(new TraceClassVisitor(null, new ASMifier(), new PrintWriter(source)), 0);
        StringWriter errors = new StringWriter();
        byte[] dumpClass = Compiler.compileSingle("asm.fixture.SampleDump", source.toString(), errors);
        assertNotNull(dumpClass, errors.toString());
        Class<?> visitor = new MemoryClassLoader(getClass().getClassLoader(), Map.of("asm.fixture.SampleDump", dumpClass))
                .loadClass("asm.fixture.SampleDump");
        byte[] recreated = (byte[]) visitor.getMethod("dump").invoke(null);
        assertEquals("fixture/Sample", new ClassReader(recreated).getClassName());
        Class<?> sample = new MemoryClassLoader(getClass().getClassLoader(), Map.of("fixture.Sample", recreated)).loadClass("fixture.Sample");
        assertEquals(42, sample.getMethod("answer").invoke(null));
    }
}
