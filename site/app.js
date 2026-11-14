(() => {
  "use strict";

  const files = {
    calculator: {
      name: "Calculator.class",
      path: "demo/Calculator.class",
      kind: "class",
      source: [
        "package demo;",
        "",
        "public class Calculator {",
        "    public static int add(int a, int b) {",
        "        return a + b;",
        "    }",
        "}"
      ],
      bytecode: [
        "// add(int a, int b)",
        "public static add(II)I",
        "    ILOAD 0",
        "    ILOAD 1",
        "    IADD",
        "    IRETURN",
        "    MAXSTACK = 2",
        "    MAXLOCALS = 2"
      ],
      highlight: { source: 4, bytecode: 4 }
    },
    main: {
      name: "Main.class",
      path: "demo/Main.class",
      kind: "class",
      source: [
        "package demo;",
        "",
        "public class Main {",
        "    public static void main(String[] args) {",
        "        System.out.println(Calculator.add(1, 2));",
        "    }",
        "}"
      ],
      bytecode: [
        "// main(String[] args)",
        "GETSTATIC java/lang/System.out",
        "ICONST_1",
        "ICONST_2",
        "INVOKESTATIC demo/Calculator.add (II)I",
        "INVOKEVIRTUAL java/io/PrintStream.println (I)V",
        "RETURN",
        "MAXSTACK = 3",
        "MAXLOCALS = 1"
      ],
      highlight: { source: 4, bytecode: 4 }
    },
    config: {
      name: "config.json",
      path: "config.json",
      kind: "resource",
      source: [
        "{",
        '  "application": "example",',
        '  "mode": "development",',
        '  "logging": true',
        "}"
      ],
      highlight: { source: 2 }
    },
    manifest: {
      name: "MANIFEST.MF",
      path: "META-INF/MANIFEST.MF",
      kind: "resource",
      source: [
        "Manifest-Version: 1.0",
        "Main-Class: demo.Main",
        "Created-By: JavaOctetEditor",
        ""
      ],
      highlight: {}
    }
  };

  const modes = {
    desktop: {
      title: "打开窗口，开始检查。",
      description: "用文件树浏览归档，在源码和字节码视图之间切换。适合需要边看边改的工作。",
      command: "java -jar JavaOctetEditor.jar",
      note: "请将 JavaOctetEditor.jar 替换为你下载的 JAR 文件名。使用完整 JDK 21 启动。"
    },
    cli: {
      title: "把归档检查写进脚本。",
      description: "直接获得 JSON 结果。列出工具、查找类与资源，或传入完整源码进行编译，再明确选择导出路径。",
      command: "java -jar JavaOctetEditor.jar --cli tools\njava -jar JavaOctetEditor.jar --cli find_entries --archive application.jar",
      note: "将 JAR 与 application.jar 替换为实际文件名。结果写入标准输出，诊断信息使用标准错误；修改需明确导出。"
    },
    mcp: {
      title: "接进你自己的 AI 客户端。",
      description: "通过本地 MCP stdio 服务发现与调用工具。AI 可以检查归档、读取源码，并在指定修订版本上提交修改。",
      command: "java -jar JavaOctetEditor.jar --mcp",
      note: "在 MCP 客户端中配置 java 命令、JAR 的绝对路径和 --mcp 参数。服务本身无需模型 API Key，客户端按自己的模型配置运行。"
    }
  };

  const fileButtons = [...document.querySelectorAll("[data-file]")];
  const viewButtons = [...document.querySelectorAll("[data-view]")];
  const modeButtons = [...document.querySelectorAll("[data-mode]")];
  const filter = document.querySelector("#file-filter");
  const code = document.querySelector("#preview-code");
  const codePanel = document.querySelector("#preview-panel");
  let activeFile = "calculator";
  let activeView = "source";

  const keywords = new Set([
    "package", "public", "class", "static", "int", "void", "return", "true", "false",
    "GETSTATIC", "ILOAD", "IADD", "IRETURN", "RETURN", "ICONST_1", "ICONST_2",
    "INVOKESTATIC", "INVOKEVIRTUAL", "MAXSTACK", "MAXLOCALS"
  ]);
  const types = new Set(["Main", "Calculator", "String", "System"]);
  const tokens = /\/\/.*|"(?:[^"\\]|\\.)*"|\b(?:package|public|class|static|int|void|return|true|false|GETSTATIC|ILOAD|IADD|IRETURN|RETURN|ICONST_1|ICONST_2|INVOKESTATIC|INVOKEVIRTUAL|MAXSTACK|MAXLOCALS|Main|Calculator|String|System|add|main|println)\b|\b\d+(?:\.\d+)?\b/g;

  function addHighlightedText(container, line) {
    let offset = 0;
    for (const match of line.matchAll(tokens)) {
      container.append(document.createTextNode(line.slice(offset, match.index)));
      const token = document.createElement("span");
      const value = match[0];
      token.className = value.startsWith("//") ? "tok-comment"
        : value.startsWith('"') ? "tok-string"
        : /^\d/.test(value) ? "tok-number"
        : keywords.has(value) ? "tok-keyword"
        : types.has(value) ? "tok-type" : "tok-method";
      token.textContent = value;
      container.append(token);
      offset = match.index + value.length;
    }
    container.append(document.createTextNode(line.slice(offset)));
  }

  function renderFile() {
    const file = files[activeFile];
    if (file.kind !== "class") activeView = "source";
    fileButtons.forEach(button => {
      const selected = button.dataset.file === activeFile;
      button.classList.toggle("is-selected", selected);
      button.setAttribute("aria-pressed", String(selected));
    });
    viewButtons.forEach(button => {
      const selected = button.dataset.view === activeView;
      button.disabled = button.dataset.view === "bytecode" && file.kind !== "class";
      button.setAttribute("aria-selected", String(selected));
      button.tabIndex = selected ? 0 : -1;
    });
    document.querySelector("#source-tab").textContent = file.kind === "class" ? "源码" : "内容";
    document.querySelector("#active-file-name").textContent = file.name;
    document.querySelector("#active-file-icon").textContent = file.kind === "class" ? "C" : "R";
    document.querySelector("#active-file-path").textContent = `example.jar / ${file.path}`;
    document.querySelector("#preview-status").textContent = file.kind === "class" ? "Java 8 · 只读示例" : "UTF-8 · 只读示例";
    codePanel.setAttribute("aria-labelledby", activeView === "source" ? "source-tab" : "bytecode-tab");
    const fragment = document.createDocumentFragment();
    file[activeView].forEach((line, index) => {
      const row = document.createElement("span");
      row.className = "code-line";
      if (file.highlight[activeView] === index) row.classList.add("is-highlighted");
      const number = document.createElement("span");
      number.className = "line-number";
      number.setAttribute("aria-hidden", "true");
      number.textContent = String(index + 1);
      const content = document.createElement("span");
      addHighlightedText(content, line);
      row.append(number, content);
      fragment.append(row);
    });
    code.replaceChildren(fragment);
    codePanel.scrollLeft = 0;
    codePanel.scrollTop = 0;
  }

  function filterFiles() {
    const query = filter.value.trim().toLowerCase();
    let visible = 0;
    fileButtons.forEach(button => {
      const file = files[button.dataset.file];
      const dotted = file.path.replace(/\.class$/, "").replaceAll("/", ".");
      const matches = `${file.path} ${dotted}`.toLowerCase().includes(query);
      button.closest("li").hidden = !matches;
      if (matches) visible++;
    });
    document.querySelector("#file-empty").hidden = visible !== 0;
    document.querySelector("#tree-summary").textContent = query ? `显示 ${visible} / 4 个文件` : "2 个类 · 2 个资源";
  }

  function wireArrowNavigation(buttons, activate, orientation) {
    buttons.forEach(button => {
      button.addEventListener("keydown", event => {
        const enabled = buttons.filter(item => !item.disabled && !item.closest("[hidden]"));
        const current = enabled.indexOf(button);
        const backward = orientation === "vertical" ? "ArrowUp" : "ArrowLeft";
        const forward = orientation === "vertical" ? "ArrowDown" : "ArrowRight";
        let next;
        if (event.key === backward) next = (current - 1 + enabled.length) % enabled.length;
        else if (event.key === forward) next = (current + 1) % enabled.length;
        else if (event.key === "Home") next = 0;
        else if (event.key === "End") next = enabled.length - 1;
        else return;
        if (!enabled.length) return;
        event.preventDefault();
        enabled[next].focus();
        if (activate) activate(enabled[next]);
      });
    });
  }

  fileButtons.forEach(button => button.addEventListener("click", () => {
    activeFile = button.dataset.file;
    renderFile();
  }));
  viewButtons.forEach(button => button.addEventListener("click", () => {
    activeView = button.dataset.view;
    renderFile();
  }));
  wireArrowNavigation(fileButtons, null, "vertical");
  wireArrowNavigation(viewButtons, button => button.click(), "horizontal");
  filter.addEventListener("input", filterFiles);
  filter.addEventListener("keydown", event => {
    if (event.key === "Escape") {
      filter.value = "";
      filterFiles();
    }
    if (event.key === "ArrowDown") {
      const first = fileButtons.find(button => !button.closest("li").hidden);
      if (first) {
        event.preventDefault();
        first.focus();
      }
    }
  });

  function showMode(button) {
    const mode = modes[button.dataset.mode];
    modeButtons.forEach(item => {
      const selected = item === button;
      item.setAttribute("aria-selected", String(selected));
      item.tabIndex = selected ? 0 : -1;
    });
    document.querySelector("#mode-title").textContent = mode.title;
    document.querySelector("#mode-description").textContent = mode.description;
    document.querySelector("#launch-command").textContent = mode.command;
    document.querySelector("#command-note").textContent = mode.note;
    document.querySelector("#command-output").setAttribute("aria-labelledby", button.id);
  }
  modeButtons.forEach(button => button.addEventListener("click", () => showMode(button)));
  wireArrowNavigation(modeButtons, showMode, "horizontal");

  const copyResetTimers = new WeakMap();
  document.querySelectorAll("[data-copy]").forEach(button => {
    button.addEventListener("click", async () => {
      const source = document.getElementById(button.dataset.copy);
      const text = source.textContent;
      const label = button.querySelector("span");
      const icon = button.querySelector("use");
      let copied = false;
      try {
        if (navigator.clipboard && window.isSecureContext) {
          await navigator.clipboard.writeText(text);
          copied = true;
        }
      } catch {
        copied = false;
      }
      if (!copied) {
        const selection = window.getSelection();
        const range = document.createRange();
        range.selectNodeContents(source);
        selection.removeAllRanges();
        selection.addRange(range);
        try { copied = document.execCommand("copy"); } catch { copied = false; }
        if (copied) selection.removeAllRanges();
      }
      clearTimeout(copyResetTimers.get(button));
      label.textContent = copied ? "已复制" : "请手动复制";
      icon.setAttribute("href", copied ? "#i-check" : "#i-copy");
      copyResetTimers.set(button, setTimeout(() => {
        label.textContent = "复制命令";
        icon.setAttribute("href", "#i-copy");
      }, 2400));
    });
  });

  const themeButton = document.querySelector("[data-theme-toggle]");
  function setTheme(theme) {
    document.documentElement.dataset.theme = theme;
    const label = theme === "dark" ? "切换网站到浅色" : "切换网站到深色";
    themeButton.setAttribute("aria-label", label);
    themeButton.title = label;
    document.querySelector('meta[name="theme-color"]').content = theme === "dark" ? "#191c25" : "#f5f6fb";
  }
  try {
    const savedTheme = localStorage.getItem("joe-site-theme");
    if (savedTheme === "dark" || savedTheme === "light") setTheme(savedTheme);
  } catch { /* Local files and restricted browser profiles may disable storage. */ }
  themeButton.addEventListener("click", () => {
    const theme = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
    setTheme(theme);
    try { localStorage.setItem("joe-site-theme", theme); } catch { /* Theme still works for this page. */ }
  });

  const screenshotDialog = document.querySelector("#screenshot-dialog");
  document.querySelectorAll("[data-screenshot-open]").forEach(link => {
    link.addEventListener("click", event => {
      if (typeof screenshotDialog.showModal !== "function") return;
      event.preventDefault();
      screenshotDialog.showModal();
    });
  });
  document.querySelector("[data-screenshot-close]").addEventListener("click", () => screenshotDialog.close());
  screenshotDialog.addEventListener("click", event => {
    if (event.target === screenshotDialog) screenshotDialog.close();
  });

  renderFile();
})();
