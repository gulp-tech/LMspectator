#!/usr/bin/env python3
"""Локальная проверка проекта ModChecker без доступа к Maven-репозиториям.

В песочнице нет Maven Central / repo.purpurmc.org, поэтому полноценный
`mvn clean package` выполнить нельзя. Этот скрипт закрывает максимум того,
что можно проверить офлайн:

  1. Синтаксис Java-исходников (tree-sitter-java): узлы ERROR/MISSING.
  2. Структура pom.xml и обязательные по ТЗ свойства (Java 21, Purpur 1.21.x).
  3. plugin.yml: валидность YAML + соответствие ТЗ.
  4. Баланс тегов MiniMessage во всех строковых литералах (плейсхолдеры
     <value>/<name> считаются корректными — их закрывает Placeholder.unparsed).
  5. Простая статика: неиспользуемые импорты, вызовы несуществующих методов,
     наличие методов, обязательных для Bukkit-плагина.

Запуск:  python3 tools/verify_project.py
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

try:
    from tree_sitter import Language, Parser
    import tree_sitter_java
except ImportError as exc:  # pragma: no cover
    print(f"[FAIL] Не установлены зависимости проверки: {exc}")
    sys.exit(2)

ROOT = Path(__file__).resolve().parent.parent
JAVA_DIR = ROOT / "src" / "main" / "java"
RES_DIR = ROOT / "src" / "main" / "resources"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}

# Теги-плейсхолдеры, значения которых подставляются через Placeholder.unparsed().
PLACEHOLDERS = {"value", "name"}
# Самозакрывающиеся служебные теги MiniMessage.
VOID_TAGS = {"br", "newline", "p"}

problems: list[str] = []
notes: list[str] = []
ok_count = 0


def header(title: str) -> None:
    print(f"\n=== {title} ===")


def ok(message: str) -> None:
    global ok_count
    ok_count += 1
    print(f"  [ OK ] {message}")


def fail(message: str) -> None:
    problems.append(message)


def walk(node):
    """Рекурсивный обход всех узлов дерева разбора."""
    yield node
    for child in node.children:
        yield from walk(child)


# =============================================================== 1. Java
header("1. Синтаксис Java (tree-sitter-java)")
parser = Parser(Language(tree_sitter_java.language()))
java_files = sorted(JAVA_DIR.rglob("*.java"))
if not java_files:
    fail("Не найдено ни одного .java файла в src/main/java")

sources: dict[Path, str] = {}
trees: dict[Path, object] = {}
for path in java_files:
    raw = path.read_bytes()
    sources[path] = raw.decode("utf-8")
    tree = parser.parse(raw)
    trees[path] = tree

    errors: list[str] = []
    lines = sources[path].splitlines()
    for node in walk(tree.root_node):
        if node.type == "ERROR" or node.is_missing:
            line = node.start_point[0] + 1
            snippet = lines[line - 1].strip()[:90] if line <= len(lines) else ""
            errors.append(f"строка {line}: {node.type} -> {snippet!r}")

    rel = path.relative_to(ROOT)
    if errors:
        fail(f"{rel}: синтаксические ошибки:\n      " + "\n      ".join(errors[:12]))
    else:
        ok(f"{rel} — синтаксис корректен, узлов: {tree.root_node.descendant_count}")

# обязательные точки входа Bukkit-плагина
header("1.1 Обязательные методы плагина")
declared: dict[str, set[str]] = {}
for path, tree in trees.items():
    methods = set()
    for node in walk(tree.root_node):
        if node.type == "method_declaration":
            name_node = node.child_by_field_name("name")
            if name_node:
                methods.add(name_node.text.decode())
    declared[path.stem] = methods

required_methods = {
    "ModChecker": {"onEnable", "onCommand"},
    "ModListListener": {"onPluginMessageReceived"},
}
for cls, needed in required_methods.items():
    missing = sorted(needed - declared.get(cls, set()))
    if cls not in declared:
        fail(f"Класс {cls} не найден в src/main/java")
    elif missing:
        fail(f"{cls}: отсутствуют обязательные методы {missing}")
    else:
        ok(f"{cls}: {', '.join(sorted(needed))} на месте (всего методов: {len(declared[cls])})")

# =============================================================== 2. pom.xml
header("2. pom.xml")
pom_path = ROOT / "pom.xml"
root = None
try:
    root = ET.fromstring(pom_path.read_text(encoding="utf-8"))
    ok("XML корректен")
except Exception as exc:
    fail(f"pom.xml: невалидный XML: {exc}")

if root is not None:
    props = {child.tag.split('}')[-1]: (child.text or "").strip()
             for child in root.findall("./m:properties/*", NS)}

    def resolve(value: str) -> str:
        match = re.fullmatch(r"\$\{([\w.\-]+)\}", value or "")
        return props.get(match.group(1), value) if match else value

    if props.get("java.version") != "21":
        fail(f"pom.xml: java.version должен быть 21, найдено {props.get('java.version')!r}")
    else:
        ok("java.version = 21")

    release = resolve(props.get("maven.compiler.release", ""))
    if release != "21":
        fail(f"pom.xml: maven.compiler.release должен разрешаться в 21, найдено {props.get('maven.compiler.release')!r}")
    else:
        ok("maven.compiler.release = 21")

    cfg_release = root.find(".//m:plugin[m:artifactId='maven-compiler-plugin']//m:release", NS)
    if cfg_release is None:
        fail("pom.xml: у maven-compiler-plugin не задан <release>")
    elif resolve(cfg_release.text or "") != "21":
        fail(f"pom.xml: <release> компилятора = {cfg_release.text!r}, ожидалось 21")
    else:
        ok(f"maven-compiler-plugin <release> = {cfg_release.text} -> 21")

    deps = [(d.findtext("m:groupId", "", NS), d.findtext("m:artifactId", "", NS),
             d.findtext("m:version", "", NS), d.findtext("m:scope", "", NS))
            for d in root.findall("./m:dependencies/m:dependency", NS)]
    purpur = [d for d in deps if d[1] == "purpur-api"]
    if not purpur:
        fail("pom.xml: нет зависимости purpur-api")
    else:
        group, artifact, version, scope = purpur[0]
        dep_problems = []
        if group != "org.purpurmc.purpur":
            dep_problems.append(f"groupId = {group!r}, ожидалось 'org.purpurmc.purpur'")
        if not version.startswith("1.21."):
            dep_problems.append(f"версия = {version!r}, ожидалась линейка 1.21.x")
        if scope != "provided":
            dep_problems.append(f"scope = {scope!r}, ожидалось 'provided'")

        if dep_problems:
            for message in dep_problems:
                fail(f"pom.xml: purpur-api {message}")
        else:
            ok(f"{group}:{artifact}:{version} ({scope})")

    repos = [r.findtext("m:url", "", NS) for r in root.findall("./m:repositories/m:repository", NS)]
    if not any("purpurmc.org" in url for url in repos):
        fail("pom.xml: не подключён репозиторий repo.purpurmc.org")
    else:
        ok(f"репозитории: {', '.join(repos)}")

    if props.get("project.build.sourceEncoding") != "UTF-8":
        fail(f"pom.xml: sourceEncoding = {props.get('project.build.sourceEncoding')!r}, ожидалось 'UTF-8'")
    else:
        ok("project.build.sourceEncoding = UTF-8")

    if root.find(".//m:plugin[m:artifactId='maven-shade-plugin']", NS) is not None:
        ok("maven-shade-plugin подключён (сборка jar)")

# =============================================================== 3. plugin.yml
header("3. src/main/resources/plugin.yml")
yml_path = RES_DIR / "plugin.yml"
if not yml_path.exists():
    fail("plugin.yml не найден в src/main/resources")
else:
    yml_text = yml_path.read_text(encoding="utf-8")
    data = None
    try:
        import yaml  # type: ignore

        data = yaml.safe_load(yml_text)
        ok("YAML корректен")
    except ImportError:
        notes.append("PyYAML не установлен — plugin.yml проверен регулярными выражениями")
    except Exception as exc:
        fail(f"plugin.yml: невалидный YAML: {exc}")

    patterns = {
        "name: ModChecker": r"^name:\s*ModChecker\s*$",
        "main: ru.musd348.modchecker.ModChecker": r"^main:\s*ru\.musd348\.modchecker\.ModChecker\s*$",
        "author: musd348": r"^author:\s*musd348\s*$",
        "api-version: 1.21": r"^api-version:\s*['\"]?1\.21",
        "команда checkmods": r"^ {2}checkmods:\s*$",
        "permission: modchecker.use": r"permission:\s*modchecker\.use",
        "default: op": r"default:\s*op",
    }
    for label, pattern in patterns.items():
        if re.search(pattern, yml_text, re.MULTILINE):
            ok(label)
        else:
            fail(f"plugin.yml: не найдено «{label}»")

    if data:
        if data.get("name") != "ModChecker":
            fail(f"plugin.yml: name = {data.get('name')!r}")
        if data.get("main") != "ru.musd348.modchecker.ModChecker":
            fail(f"plugin.yml: main = {data.get('main')!r}")
        if data.get("author") != "musd348":
            fail(f"plugin.yml: author = {data.get('author')!r}")
        cmd = (data.get("commands") or {}).get("checkmods") or {}
        if not cmd:
            fail("plugin.yml: команда checkmods не описана")
        elif cmd.get("permission") != "modchecker.use":
            fail(f"plugin.yml: permission команды = {cmd.get('permission')!r}")
        perm = (data.get("permissions") or {}).get("modchecker.use") or {}
        if perm.get("default") != "op":
            fail(f"plugin.yml: default права = {perm.get('default')!r}, ожидалось 'op'")
        if data.get("api-version") not in ("1.21", 1.21):
            fail(f"plugin.yml: api-version = {data.get('api-version')!r}, ожидалось '1.21'")

    # main-класс из plugin.yml должен существовать
    main_class = re.search(r"^main:\s*([\w.]+)\s*$", yml_text, re.MULTILINE)
    if main_class:
        expected = (JAVA_DIR / Path(*main_class.group(1).split("."))).with_suffix(".java")
        if expected.exists():
            ok(f"main-класс {main_class.group(1)} существует: {expected.relative_to(ROOT)}")
        else:
            fail(f"plugin.yml указывает на несуществующий класс {main_class.group(1)}")

# =============================================================== 4. MiniMessage
header("4. Баланс тегов MiniMessage в строковых литералах")
literals_checked = 0
for path, tree in trees.items():
    for node in walk(tree.root_node):
        if node.type != "string_literal":
            continue
        text = node.text.decode("utf-8")
        inner = text[1:-1]
        if "<" not in inner or not re.search(r"<[a-zA-Z/]", inner):
            continue

        literals_checked += 1
        line = node.start_point[0] + 1
        stack: list[str] = []
        balanced = True
        for tag in re.finditer(r"<(/?)([a-zA-Z_][\w-]*)((?:[^<>]|\"[^\"]*\")*?)(/?)>", inner):
            closing, name, _attrs, self_close = tag.group(1), tag.group(2).lower(), tag.group(3), tag.group(4)
            if name in PLACEHOLDERS:
                continue
            if closing:
                if not stack or stack.pop() != name:
                    balanced = False
                    break
            elif not self_close and name not in VOID_TAGS:
                stack.append(name)
        if stack:
            balanced = False

        if balanced:
            ok(f"{path.relative_to(ROOT)}:{line} — {inner[:70]}")
        else:
            fail(f"{path.relative_to(ROOT)}:{line}: несбалансированные теги MiniMessage в {inner[:120]!r}")

if literals_checked == 0:
    fail("Не найдено ни одного строкового литерала с MiniMessage-разметкой")

# =============================================================== 5. Статика
header("5. Простая статика")
all_source = "\n".join(sources.values())
calls = set(re.findall(r"\bplugin\.(\w+)\s*\(", all_source))
missing = sorted(call for call in calls if call not in declared.get("ModChecker", set()))
if missing:
    fail(f"Вызовы несуществующих методов ModChecker: {missing}")
else:
    ok(f"все вызовы plugin.*() существуют: {sorted(calls)}")

for path, text in sources.items():
    unused = []
    for pkg, symbol in re.findall(r"^import\s+(?:static\s+)?([\w.]+)\.(\w+);", text, re.MULTILINE):
        body = re.sub(r"^import .*$", "", text, flags=re.MULTILINE)
        body = re.sub(r"/\*.*?\*/", "", body, flags=re.DOTALL)  # javadoc {@link ...} не считается использованием
        if not re.search(rf"\b{re.escape(symbol)}\b", body):
            unused.append(f"{pkg}.{symbol}")
    if unused:
        fail(f"{path.relative_to(ROOT)}: неиспользуемые импорты {unused}")
    else:
        ok(f"{path.relative_to(ROOT)}: лишних импортов нет")

# пакеты файлов должны совпадать с путями
for path, text in sources.items():
    pkg = re.search(r"^package\s+([\w.]+);", text, re.MULTILINE)
    if not pkg:
        fail(f"{path.relative_to(ROOT)}: не объявлен package")
        continue
    expected_dir = pkg.group(1).replace(".", "/")
    actual_dir = str(path.parent.relative_to(JAVA_DIR))
    if expected_dir != actual_dir:
        fail(f"{path.relative_to(ROOT)}: package {pkg.group(1)} не соответствует каталогу {actual_dir}")
    else:
        ok(f"{path.relative_to(ROOT)}: package {pkg.group(1)} совпадает с путём")

# =============================================================== итог
header("ИТОГ")
for note in notes:
    print(f"  [INFO] {note}")
print(f"  Проверок пройдено: {ok_count}")
if problems:
    print(f"  Проблем найдено: {len(problems)}")
    for problem in problems:
        print(f"  [FAIL] {problem}")
    sys.exit(1)
print("\nВсе проверки пройдены — проект готов к сборке `mvn clean package`.")
sys.exit(0)
