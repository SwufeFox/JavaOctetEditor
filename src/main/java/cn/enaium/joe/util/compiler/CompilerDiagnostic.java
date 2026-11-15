package cn.enaium.joe.util.compiler;

import javax.tools.Diagnostic;

/** Owned javac diagnostic; positions address the submitted source, not a later editor draft. */
public record CompilerDiagnostic(Diagnostic.Kind kind, String source, long line, long column,
                                 long position, String message) {
}
