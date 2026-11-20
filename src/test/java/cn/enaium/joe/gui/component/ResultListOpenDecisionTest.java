package cn.enaium.joe.gui.component;

import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.util.classes.ClassNode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResultListOpenDecisionTest {
    @Test
    void opensAResultWhoseClassIdentityIsStillIndexed() {
        Jar archive = new Jar();
        ClassNode result = node("example/Target");
        archive.putClassNode(result);

        assertEquals(ResultList.OpenDecision.OPEN, ResultList.openDecision(archive, result));
    }

    @Test
    void opensARenamedResultThatIsStillTheSameClass() {
        Jar archive = new Jar();
        ClassNode result = node("example/Old");
        archive.putClassNode(result);
        result.getClassNode().name = "example/New";
        archive.refresh();

        assertEquals(ResultList.OpenDecision.OPEN, ResultList.openDecision(archive, result));
    }

    @Test
    void rejectsASameNamedClassFromAReplacedArchive() {
        Jar previous = new Jar();
        ClassNode previousResult = node("example/Target");
        previous.putClassNode(previousResult);

        Jar current = new Jar();
        ClassNode currentClass = node("example/Target");
        current.putClassNode(currentClass);

        assertEquals(ResultList.OpenDecision.ARCHIVE_CHANGED,
                ResultList.openDecision(current, previousResult));
    }

    @Test
    void rejectsAResultFromAnArchiveCopy() {
        Jar previous = new Jar();
        ClassNode previousResult = node("example/Target");
        previous.putClassNode(previousResult);

        assertEquals(ResultList.OpenDecision.ARCHIVE_CHANGED,
                ResultList.openDecision(previous.copy(), previousResult));
    }

    @Test
    void reportsAMissingClassAfterItDisappears() {
        Jar previous = new Jar();
        ClassNode previousResult = node("example/Target");
        previous.putClassNode(previousResult);

        Jar current = new Jar();
        current.putClassNode(node("example/Other"));

        assertEquals(ResultList.OpenDecision.MISSING, ResultList.openDecision(current, previousResult));
    }

    @Test
    void reportsAMissingClassRemovedFromTheSameArchive() {
        Jar archive = new Jar();
        ClassNode result = node("example/Target");
        archive.putClassNode(result);
        archive.removeClass("example/Target");

        assertEquals(ResultList.OpenDecision.MISSING, ResultList.openDecision(archive, result));
    }

    @Test
    void treatsAbsentArchiveOrResultAsMissing() {
        assertEquals(ResultList.OpenDecision.MISSING,
                ResultList.openDecision(null, node("example/Target")));
        assertEquals(ResultList.OpenDecision.MISSING,
                ResultList.openDecision(new Jar(), null));
    }

    private static ClassNode node(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return ClassNode.of(writer.toByteArray());
    }
}
