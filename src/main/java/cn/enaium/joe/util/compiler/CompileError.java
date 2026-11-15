package cn.enaium.joe.util.compiler;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public class CompileError extends Exception {
    private final List<CompilerDiagnostic> diagnostics;

    public CompileError(String message) {
        this(message, null, List.of());
    }

    public CompileError(String message, Throwable cause) {
        this(message, cause, List.of());
    }

    public CompileError(Throwable cause) {
        this(cause == null ? null : cause.toString(), cause, List.of());
    }

    public CompileError(String message, Throwable cause, List<CompilerDiagnostic> diagnostics) {
        super(message, cause);
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<CompilerDiagnostic> getDiagnostics() {
        return diagnostics;
    }

    /** Unwrap task/future transport exceptions without treating other failures as compiler errors. */
    public static CompileError from(Throwable failure) {
        while (failure instanceof CompletionException || failure instanceof ExecutionException) {
            if (failure.getCause() == null) return null;
            failure = failure.getCause();
        }
        return failure instanceof CompileError error ? error : null;
    }
}
