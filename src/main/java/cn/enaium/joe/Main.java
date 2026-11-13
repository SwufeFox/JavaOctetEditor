/*
 * Copyright 2022 Enaium
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.enaium.joe;

import cn.enaium.joe.jar.Jar;
import cn.enaium.joe.tool.ToolApplication;
import cn.enaium.joe.util.*;
import cn.enaium.joe.util.classes.ClassNode;
import cn.enaium.joe.util.task.tasks.InputJarTask;
import org.tinylog.Logger;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;

import static cn.enaium.joe.util.TinyLogPrintStream.Type.STDERR;
import static cn.enaium.joe.util.TinyLogPrintStream.Type.STDOUT;

/**
 * @author Enaium
 */
public final class Main {
    public static final ClassLoader classLoader = Main.class.getClassLoader();

    public static void main(String[] args) {
        if (args.length > 0 && args[0].startsWith("--")) {
            PrintStream output = System.out;
            System.setOut(System.err);
            if (args[0].equals("--mcp") || args[0].equals("--cli")) {
                System.setProperty("java.awt.headless", "true");
            }
            System.exit(ToolApplication.run(args, System.in, output, System.err));
            return;
        }
        if (args.length > 1) {
            System.err.println("Usage: java -jar JavaOctetEditor.jar [archive] | --help");
            System.exit(2);
            return;
        }
        ImagineBreakerHelper.boot();
        launch(args.length == 1 ? new File(args[0]) : null, null, false);
    }

    public static void agent(Instrumentation inst) {
        ImagineBreakerHelper.boot();
        Jar jar = new Jar();
        for (Class<?> loadedClass : inst.getAllLoadedClasses()) {
            String name = loadedClass.getName().replace('.', '/');
            if (loadedClass.isArray() || loadedClass.isPrimitive() || loadedClass.isHidden()
                    || loadedClass.getClassLoader() == null
                    || loadedClass.getClassLoader() == Main.classLoader
                    || name.startsWith("cn/enaium/joe/")
                    || name.startsWith("java/") || name.startsWith("javax/")
                    || name.startsWith("sun/") || name.startsWith("com/sun/")
                    || name.startsWith("jdk/")) {
                continue;
            }
            try (InputStream input = loadedClass.getResourceAsStream("/" + name + ".class")) {
                if (input != null) {
                    jar.putClassNode(ClassNode.of(input.readAllBytes()));
                }
            } catch (IOException | IllegalArgumentException e) {
                Logger.warn("Could not read loaded class {}: {}", name, e.getMessage());
            }
        }
        launch(null, jar, true);
    }

    private static void launch(File input, Jar initial, boolean attached) {
        if (!attached) {
            System.setOut(new TinyLogPrintStream(System.out, STDOUT));
            System.setErr(new TinyLogPrintStream(System.err, STDERR));
        }
        Logger.info("DIR:{}", System.getProperty("user.dir"));
        SwingUtilities.invokeLater(() -> {
            JavaOctetEditor editor = new JavaOctetEditor();
            editor.run();
            if (attached) {
                editor.window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            }
            if (initial != null) {
                editor.setJar(initial);
            }
            if (input != null) {
                editor.TASKS.submit(new InputJarTask(input));
            }
        });
    }
}
