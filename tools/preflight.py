#!/usr/bin/env python3
"""
Static pre-flight checks for the Android source.

No Android toolchain is available in the environment this was written in, so
these checks catch the classes of error that do not need a compiler: resource
references that point at nothing, manifest entries naming classes that do not
exist, packages that disagree with their directory. They are cheap and they
catch the mistakes that would otherwise cost a CI round-trip each.

    python3 tools/preflight.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "android"
MODULES = ["core", "tv", "phone"]

problems = []
warnings = []
checked = 0


def fail(where, message):
    """A real build or runtime error."""
    problems.append(f"{where}: {message}")


def warn(where, message):
    """Worth a look, but the build will succeed."""
    warnings.append(f"{where}: {message}")


def kotlin_files(module):
    return sorted((ROOT / module / "src").rglob("*.kt"))


def layout_files(module):
    d = ROOT / module / "src/main/res/layout"
    return sorted(d.glob("*.xml")) if d.exists() else []


def value_files(module):
    d = ROOT / module / "src/main/res/values"
    return sorted(d.glob("*.xml")) if d.exists() else []


def declared_resources(module):
    """Everything the module's res/ directory actually defines."""
    res = {"id": set(), "layout": set(), "string": set(), "color": set(),
           "drawable": set(), "xml": set(), "style": set()}

    for f in layout_files(module):
        res["layout"].add(f.stem)
        for m in re.finditer(r'android:id="@\+id/([A-Za-z0-9_]+)"', f.read_text(encoding="utf-8")):
            res["id"].add(m.group(1))

    for f in value_files(module):
        text = f.read_text(encoding="utf-8")
        for kind, key in (("string", "string"), ("color", "color"), ("style", "style")):
            for m in re.finditer(rf'<{key}\s+name="([A-Za-z0-9_.]+)"', text):
                res[kind].add(m.group(1).replace(".", "_"))

    for sub in ("drawable", "drawable-xhdpi", "mipmap-anydpi-v26"):
        d = ROOT / module / "src/main/res" / sub
        if d.exists():
            for f in d.iterdir():
                res["drawable"].add(f.stem)

    d = ROOT / module / "src/main/res/xml"
    if d.exists():
        for f in d.glob("*.xml"):
            res["xml"].add(f.stem)

    return res


def check_resource_references(module):
    """Every R.something.name used in Kotlin must exist in res/."""
    global checked
    declared = declared_resources(module)
    # The TV and phone apps depend on :core, which declares no resources, so a
    # missing reference is genuinely missing rather than inherited.
    for f in kotlin_files(module):
        text = f.read_text(encoding="utf-8")
        # The lookbehind excludes android.R.*, which is the framework's own
        # resource table and is never declared in our res/ directory.
        for m in re.finditer(
            r'(?<!android\.)\bR\.(id|layout|string|color|drawable|xml|style)\.([A-Za-z0-9_]+)', text
        ):
            kind, name = m.group(1), m.group(2)
            checked += 1
            if name not in declared[kind]:
                line = text[:m.start()].count("\n") + 1
                fail(f"{f.relative_to(ROOT)}:{line}",
                     f"R.{kind}.{name} is not declared in {module}/src/main/res")


def check_layout_ids_are_used_consistently(module):
    """findViewById(R.id.x) where x is declared in a layout of another module."""
    # Covered by the check above; this one looks the other way for dead ids.
    declared = declared_resources(module)
    used = set()
    for f in kotlin_files(module):
        for m in re.finditer(r'R\.id\.([A-Za-z0-9_]+)', f.read_text(encoding="utf-8")):
            used.add(m.group(1))
    unused = declared["id"] - used
    for name in sorted(unused):
        warn(f"{module}/res/layout", f"id '{name}' is declared but never referenced")


def check_manifest_classes(module):
    """Every android:name=".Foo" in the manifest must be a real class."""
    global checked
    manifest = ROOT / module / "src/main/AndroidManifest.xml"
    if not manifest.exists():
        return
    text = manifest.read_text(encoding="utf-8")

    namespace = None
    gradle = ROOT / module / "build.gradle.kts"
    if gradle.exists():
        m = re.search(r'namespace\s*=\s*"([^"]+)"', gradle.read_text(encoding="utf-8"))
        if m:
            namespace = m.group(1)
    if not namespace:
        fail(f"{module}/build.gradle.kts", "no namespace declared")
        return

    existing = set()
    for f in kotlin_files(module):
        for m in re.finditer(r'^(?:class|object)\s+([A-Za-z0-9_]+)', f.read_text(encoding="utf-8"), re.M):
            existing.add(m.group(1))

    for m in re.finditer(r'android:name="\.([A-Za-z0-9_]+)"', text):
        checked += 1
        cls = m.group(1)
        if cls not in existing:
            line = text[:m.start()].count("\n") + 1
            fail(f"{module}/src/main/AndroidManifest.xml:{line}",
                 f'android:name=".{cls}" but no such class exists in {module}')


def check_package_matches_directory(module):
    global checked
    for f in kotlin_files(module):
        text = f.read_text(encoding="utf-8")
        m = re.match(r'package\s+([A-Za-z0-9_.]+)', text)
        if not m:
            fail(str(f.relative_to(ROOT)), "no package declaration")
            continue
        checked += 1
        declared = m.group(1)
        # src/<main|test>/java/... - read the source set from the path parts
        # rather than matching "/test/", which never matches on Windows.
        source_set = f.relative_to(ROOT / module / "src").parts[0]
        expected = ".".join(f.parent.relative_to(ROOT / module / "src" / source_set / "java").parts)
        if declared != expected:
            fail(str(f.relative_to(ROOT)),
                 f"package is '{declared}' but the directory says '{expected}'")


def check_custom_views_in_layouts(module):
    """A fully-qualified view in XML must be a class the build can see."""
    global checked
    known_prefixes = ("org.webrtc.", "androidx.", "com.google.android.material.")
    for f in layout_files(module):
        for m in re.finditer(r'<([a-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)', f.read_text(encoding="utf-8")):
            name = m.group(1)
            checked += 1
            if not name.startswith(known_prefixes) and not defined_in_project(name):
                fail(str(f.relative_to(ROOT)), f"custom view '{name}' may not resolve")


def defined_in_project(class_name):
    """A view class of our own, in this module or one it can see (core)."""
    package, _, simple = class_name.rpartition(".")
    relative = Path(*package.split(".")) / f"{simple}.kt"
    return any((ROOT / m / "src" / "main" / "java" / relative).is_file() for m in MODULES)


def check_string_format_args(module):
    """getString(R.string.x, a) needs x to contain a format specifier."""
    global checked
    strings = {}
    for f in value_files(module):
        for m in re.finditer(r'<string\s+name="([A-Za-z0-9_]+)"\s*>(.*?)</string>',
                             f.read_text(encoding="utf-8"), re.S):
            strings[m.group(1)] = m.group(2)

    for f in kotlin_files(module):
        text = f.read_text(encoding="utf-8")
        for m in re.finditer(r'getString\(\s*R\.string\.([A-Za-z0-9_]+)\s*(,)?', text):
            name, has_args = m.group(1), bool(m.group(2))
            if name not in strings:
                continue
            checked += 1
            has_spec = "%1$s" in strings[name] or "%s" in strings[name] or "%1$d" in strings[name]
            line = text[:m.start()].count("\n") + 1
            if has_args and not has_spec:
                fail(f"{f.relative_to(ROOT)}:{line}",
                     f"getString(R.string.{name}, …) passes an argument but the string has no placeholder")
            if has_spec and not has_args:
                fail(f"{f.relative_to(ROOT)}:{line}",
                     f"R.string.{name} expects an argument but none is passed")


def check_service_and_activity_registration(module):
    """A Service or Activity subclass that is not in the manifest will not start."""
    manifest_path = ROOT / module / "src/main/AndroidManifest.xml"
    if not manifest_path.exists():
        return
    manifest = manifest_path.read_text(encoding="utf-8")

    for f in kotlin_files(module):
        text = f.read_text(encoding="utf-8")
        for m in re.finditer(
            r'^class\s+([A-Za-z0-9_]+)\s*(?:\([^)]*\))?\s*:\s*([A-Za-z0-9_.]+)', text, re.M
        ):
            cls, parent = m.group(1), m.group(2)
            needs_manifest = parent.endswith(("Activity", "Service", "BroadcastReceiver")) or \
                parent in ("LifecycleService", "AppCompatActivity", "AccessibilityService",
                           "FirebaseMessagingService")
            if needs_manifest and f'.{cls}"' not in manifest:
                fail(str(f.relative_to(ROOT)),
                     f"{cls} extends {parent} but is not declared in the manifest")


# Styles that come from Material or AppCompat rather than from our own res/.
LIBRARY_STYLE_PREFIXES = (
    "Widget.Material3.", "Widget.MaterialComponents.", "Widget.AppCompat.",
    "Theme.Material3.", "Theme.MaterialComponents.", "Theme.AppCompat.",
    "TextAppearance.Material3.", "TextAppearance.MaterialComponents.",
    "TextAppearance.AppCompat.", "ThemeOverlay.Material3.", "Base.",
)


def xml_resource_files(module):
    """Every XML the resource compiler reads, including the manifest."""
    base = ROOT / module / "src/main"
    files = []
    if (base / "AndroidManifest.xml").exists():
        files.append(base / "AndroidManifest.xml")
    res = base / "res"
    if res.exists():
        for d in sorted(res.iterdir()):
            if d.is_dir():
                files.extend(sorted(d.glob("*.xml")))
    return files


def style_declarations(module):
    """(name, parent, file, line) for every style, with the dots left intact."""
    out = []
    res = ROOT / module / "src/main/res"
    if not res.exists():
        return out
    for d in sorted(res.glob("values*")):
        for f in sorted(d.glob("*.xml")):
            text = f.read_text(encoding="utf-8")
            for m in re.finditer(r'<style\s+([^>]*?)/?>', text):
                attrs = m.group(1)
                name = re.search(r'name="([A-Za-z0-9_.]+)"', attrs)
                if not name:
                    continue
                parent = re.search(r'parent="([^"]*)"', attrs)
                line = text[:m.start()].count("\n") + 1
                out.append((name.group(1),
                            parent.group(1) if parent else None,
                            f, line))
    return out


def is_library_style(name):
    return name.startswith(LIBRARY_STYLE_PREFIXES)


def check_style_references(module):
    """@style/x must exist, and a dotted style with no parent implies one.

    aapt reads `Intercom.Tv.Field` with no parent attribute as inheriting from
    `Intercom.Tv`. If nothing declares that name the build fails, and the error
    names a style nobody ever wrote, which is a confusing half hour.
    """
    global checked
    declared = {name for name, _, _, _ in style_declarations(module)}

    for f in xml_resource_files(module):
        text = f.read_text(encoding="utf-8")
        # @android:style/... is the framework's own table, and does not match.
        for m in re.finditer(r'@style/([A-Za-z0-9_.]+)', text):
            name = m.group(1)
            checked += 1
            if name not in declared and not is_library_style(name):
                line = text[:m.start()].count("\n") + 1
                fail(f"{f.relative_to(ROOT)}:{line}",
                     f"@style/{name} is not declared in {module}/src/main/res")

    for name, parent, f, line in style_declarations(module):
        if parent is not None or "." not in name:
            continue
        checked += 1
        implied = name.rsplit(".", 1)[0]
        if implied not in declared and not is_library_style(implied):
            fail(f"{f.relative_to(ROOT)}:{line}",
                 f"style '{name}' has no parent attribute, so aapt inherits "
                 f"from '{implied}', which nothing declares - add parent=\"\"")


def declared_xml_resources(module):
    """What res/ defines, including night and other qualified variants.

    Distinct from declared_resources(): that one answers "can Kotlin say
    R.color.x", so it flattens dots. This one answers "can aapt resolve
    @color/x from another XML file", which is a different question once
    values-night/ and colour-state-list selectors are in play.
    """
    res = ROOT / module / "src/main/res"
    out = {"color": set(), "drawable": set(), "string": set(), "dimen": set()}
    if not res.exists():
        return out

    for d in res.glob("values*"):
        text_of = (f.read_text(encoding="utf-8") for f in sorted(d.glob("*.xml")))
        for text in text_of:
            for kind in ("color", "string", "dimen"):
                for m in re.finditer(rf'<{kind}\s+name="([A-Za-z0-9_.]+)"', text):
                    out[kind].add(m.group(1))

    # A file in drawable/, mipmap/ or color/ declares a resource of that name.
    for prefix, kind in (("drawable", "drawable"), ("mipmap", "drawable"),
                         ("color", "color")):
        for d in res.glob(prefix + "*"):
            if d.is_dir():
                for f in d.iterdir():
                    out[kind].add(f.stem)

    return out


def check_xml_resource_references(module):
    """@color/x written inside another XML file must resolve.

    Kotlin references are covered by check_resource_references; these are the
    ones only aapt sees - a drawable naming a colour, a manifest naming an
    icon - and they are easy to strand when a palette is rewritten.
    """
    global checked
    declared = declared_xml_resources(module)

    for f in xml_resource_files(module):
        text = f.read_text(encoding="utf-8")
        # @android:color/... is the framework's table and does not match.
        for m in re.finditer(r'"@(color|drawable|string|dimen|mipmap)/([A-Za-z0-9_.]+)"', text):
            kind, name = m.group(1), m.group(2)
            checked += 1
            if name not in declared["drawable" if kind == "mipmap" else kind]:
                line = text[:m.start()].count("\n") + 1
                fail(f"{f.relative_to(ROOT)}:{line}",
                     f"@{kind}/{name} is not declared in {module}/src/main/res")


def main():
    for module in MODULES:
        check_resource_references(module)
        check_manifest_classes(module)
        check_package_matches_directory(module)
        check_custom_views_in_layouts(module)
        check_string_format_args(module)
        check_service_and_activity_registration(module)
        check_style_references(module)
        check_xml_resource_references(module)
        check_layout_ids_are_used_consistently(module)

    print(f"\n{checked} references checked across {len(MODULES)} modules\n")

    for w in warnings:
        print(f"  warning  {w}")
    if warnings:
        print()

    if problems:
        for p in problems:
            print(f"  ERROR    {p}")
        print(f"\n{len(problems)} error(s), {len(warnings)} warning(s)\n")
        return 1

    print(f"  No errors. {len(warnings)} warning(s).\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
