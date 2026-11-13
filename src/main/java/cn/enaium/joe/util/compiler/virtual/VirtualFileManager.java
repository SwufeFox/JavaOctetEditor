package cn.enaium.joe.util.compiler.virtual;

import javax.tools.*;
import java.io.IOException;
import java.util.*;

/** Presents dependency bytecode to javac without loading classes or consulting editor state. */
public class VirtualFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
    private final Map<String, VirtualJavaClassObject> dependencies = new LinkedHashMap<>();
    private final Map<String, VirtualJavaClassObject> outputs = new LinkedHashMap<>();

    public VirtualFileManager(StandardJavaFileManager javaFileManager, Map<String, byte[]> classPath) {
        super(javaFileManager);
        classPath.forEach((name, bytes) -> dependencies.put(name,
                new VirtualJavaClassObject(name, Objects.requireNonNull(bytes, "Bytecode for " + name))));
    }

    public Map<String, byte[]> getClasses() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        outputs.forEach((name, file) -> classes.put(name, file.getBytecode()));
        return Collections.unmodifiableMap(classes);
    }

    @Override
    public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                                               FileObject sibling) throws IOException {
        if (kind == JavaFileObject.Kind.CLASS) {
            VirtualJavaClassObject output = new VirtualJavaClassObject(className, null);
            outputs.put(className, output);
            return output;
        }
        return super.getJavaFileForOutput(location, className, kind, sibling);
    }

    @Override
    public JavaFileObject getJavaFileForInput(Location location, String className, JavaFileObject.Kind kind) throws IOException {
        if (kind == JavaFileObject.Kind.CLASS) {
            JavaFileObject file = null;
            if (location == StandardLocation.CLASS_PATH) file = dependencies.get(className);
            if (location == StandardLocation.CLASS_OUTPUT) file = outputs.get(className);
            if (file != null) return file;
        }
        return super.getJavaFileForInput(location, className, kind);
    }

    @Override
    public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds,
                                         boolean recurse) throws IOException {
        if (location != StandardLocation.CLASS_PATH || !kinds.contains(JavaFileObject.Kind.CLASS)) {
            return super.list(location, packageName, kinds, recurse);
        }
        Map<String, JavaFileObject> files = new LinkedHashMap<>();
        for (Map.Entry<String, VirtualJavaClassObject> entry : dependencies.entrySet()) {
            String name = entry.getKey();
            int separator = name.lastIndexOf('.');
            String ownerPackage = separator < 0 ? "" : name.substring(0, separator);
            if (ownerPackage.equals(packageName) || (recurse && (packageName.isEmpty() || ownerPackage.startsWith(packageName + ".")))) {
                files.put(name, entry.getValue());
            }
        }
        for (JavaFileObject file : super.list(location, packageName, kinds, recurse)) {
            files.putIfAbsent(super.inferBinaryName(location, file), file);
        }
        return files.values();
    }

    @Override
    public String inferBinaryName(Location location, JavaFileObject file) {
        if (file instanceof VirtualJavaClassObject virtual) return virtual.getBinaryName();
        return super.inferBinaryName(location, file);
    }

    @Override
    public boolean contains(Location location, FileObject file) throws IOException {
        if (file instanceof VirtualJavaClassObject virtual) {
            return (location == StandardLocation.CLASS_PATH && dependencies.get(virtual.getBinaryName()) == file)
                    || (location == StandardLocation.CLASS_OUTPUT && outputs.get(virtual.getBinaryName()) == file);
        }
        return super.contains(location, file);
    }
}
