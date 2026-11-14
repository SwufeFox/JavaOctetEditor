package cn.enaium.joe.jar;

/** Validation shared by resource imports and headless resource writes. */
public final class ResourceEntryPath {
    private ResourceEntryPath() {
    }

    public enum Reason { INVALID_PATH, BASE_CLASS, COLLISION }

    public static final class ValidationException extends IllegalArgumentException {
        private final Reason reason;
        private final String entry;

        private ValidationException(Reason reason, String entry, String message) {
            super(message);
            this.reason = reason;
            this.entry = entry;
        }

        public Reason getReason() {
            return reason;
        }

        public String getEntry() {
            return entry;
        }
    }

    /** Returns a relative file path without normalization or any archive mutation. */
    public static String validate(Jar archive, String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\")
                || path.indexOf(':') >= 0 || path.indexOf('\0') >= 0) {
            throw invalid(path);
        }
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw invalid(path);
        }
        if (path.endsWith(".class") && !path.startsWith("META-INF/versions/")) {
            throw new ValidationException(Reason.BASE_CLASS, path,
                    "Base .class entries are managed as classes; use source compilation to change them. "
                            + "Versioned .class entries under META-INF/versions/ are allowed as resources.");
        }
        for (String existing : archive.getResources().keySet()) checkCollision(path, existing);
        for (var node : archive.getClasses()) checkCollision(path, node.getInternalName() + ".class");
        return path;
    }

    private static ValidationException invalid(String path) {
        return new ValidationException(Reason.INVALID_PATH, path,
                "Use a non-empty relative archive file path with forward slashes; "
                        + "empty segments, '.', '..', backslashes, colons and NUL are not allowed.");
    }

    private static void checkCollision(String path, String existing) {
        if (path.equals(existing)) return;
        String plain = existing.endsWith("/") ? existing.substring(0, existing.length() - 1) : existing;
        if (plain.equals(path) || plain.startsWith(path + "/")
                || (!existing.endsWith("/") && path.startsWith(existing + "/"))) {
            throw new ValidationException(Reason.COLLISION, existing,
                    "Archive resource path conflicts with an existing file or directory: " + existing);
        }
    }
}
