#!/usr/bin/env python3
"""Catch missing imports in Android sources that cannot be compiled here.

The parse check in `tools/parsecheck/` resolves nothing under androidx/android, so a forgotten
import reads as just another unresolved reference among thousands — `tools/compile_scan.py`
correctly buckets it away as noise, which means that one class of real mistake is invisible to
it. This walks the other way: every capitalised identifier a file *uses* must be accounted for
by an import, a declaration in the same file, a declaration elsewhere in the same package, or
the Kotlin stdlib.

    python3 tools/import_audit.py                       # every file
    python3 tools/import_audit.py app/src/.../Foo.kt    # just these

Heuristic, not a compiler: it reads Kotlin with regexes, so treat a report as "look at this"
rather than "this is broken". Exits 1 if anything is unaccounted for.

A standing six false positives as of the Hebrew release, all verified against a green CI build —
`IconButton`, `CompositionLocalProvider`, `PaddingValues` and friends are imported normally but
land in files where the regex loses them, and `Q` in `Fen.kt` is a FEN board character, not a
type. Chase a *new* name in this list, not these.
"""

import re
import sys
from pathlib import Path

# Relative to this file, so the audit travels with the repository rather than with one machine.
ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app" / "src" / "main" / "java"

STDLIB = {
    "String", "Int", "Long", "Double", "Float", "Boolean", "Char", "Byte", "Short", "Unit",
    "Any", "Nothing", "List", "Set", "Map", "MutableList", "MutableSet", "MutableMap",
    "ArrayDeque", "Array", "Pair", "Triple", "Exception", "Throwable", "Error", "Comparable",
    "Regex", "RegexOption", "MatchResult", "Sequence", "Iterable", "Collection", "Result",
    "IllegalStateException", "IllegalArgumentException", "RuntimeException", "Math",
    "StringBuilder", "Thread", "System", "Runnable", "Comparator", "Number", "CharSequence",
    "Deprecated", "JvmStatic", "JvmField", "Suppress", "OptIn", "Volatile", "Synchronized",
    "T", "R", "K", "V", "E",
    "HashMap", "HashSet", "LinkedHashMap", "ArrayList", "IntRange", "LongRange", "CharRange",
    "IntArray", "LongArray", "FloatArray", "DoubleArray", "BooleanArray", "ByteArray",
    "UInt", "ULong", "Function", "Lazy", "Sequence", "Random",
}


# Declared in the file's own package (built from the tree) or imported explicitly.
def package_symbols():
    by_package = {}
    for path in SRC.rglob("*.kt"):
        text = path.read_text()
        pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
        if not pkg:
            continue
        names = set(re.findall(
            r"^(?:@\w+\s+)*(?:public |internal |private |abstract |open |sealed |data |enum |value |annotation )*"
            r"(?:class|interface|object|typealias)\s+(\w+)", text, re.M))
        names |= set(re.findall(r"^(?:public |internal |private )?(?:suspend )?fun\s+(\w+)\s*\(", text, re.M))
        names |= set(re.findall(r"^(?:public |internal |private )?(?:const )?va[lr]\s+(\w+)", text, re.M))
        by_package.setdefault(pkg.group(1), set()).update(names)
    return by_package


def audit(path, by_package):
    text = path.read_text()
    pkg = re.search(r"^package\s+([\w.]+)", text, re.M).group(1)

    imported = set()
    for line in re.findall(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?", text, re.M):
        imported.add(line[1] or line[0].rsplit(".", 1)[-1])

    body = re.sub(r"^(?:package|import)\s+.*$", "", text, flags=re.M)
    body = re.sub(r'""".*?"""', '""', body, flags=re.S)
    body = re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', body)
    body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
    body = re.sub(r"//.*$", "", body, flags=re.M)

    # Declared locally: types, functions, properties, parameters, enum-ish receivers.
    local = set(re.findall(r"\b(?:class|interface|object|typealias|enum class)\s+(\w+)", body))
    # An extension declaration names its receiver first: `fun ColumnScope.Fight(...)` declares
    # Fight, not ColumnScope — so the receiver prefix is skipped before the name is taken.
    local |= set(re.findall(r"\bfun\s+(?:<[^>]+>\s*)?(?:[\w.]+\.)?(\w+)", body))
    local |= set(re.findall(r"\bva[lr]\s+(\w+)", body))
    # Enum entries are declarations too, whether the body is spread over lines or not:
    # `enum class F { A, B, C }` and `enum class F {\n  A,\n  B,\n}` must both work.
    for block in re.findall(r"enum class\s+\w+[^{]*\{(.*?)\}", body, re.S):
        local |= set(re.findall(r"\b([A-Z][A-Z0-9_]{1,})\b", block))
    # `data object X : Y` inside sealed hierarchies.
    local |= set(re.findall(r"\b(?:data\s+)?object\s+(\w+)", body))

    # Fully-qualified uses need no import.
    body = re.sub(r"\b[a-z][\w]*(?:\.[a-z][\w]*)+\.([A-Z]\w*)", r"\1", body)

    used = set()
    for match in re.finditer(r"(?<![\w.@])([A-Z]\w*)", body):
        used.add(match.group(1))

    known = imported | local | STDLIB | by_package.get(pkg, set())
    return sorted(used - known)


def main():
    by_package = package_symbols()
    targets = sys.argv[1:] or [str(p) for p in SRC.rglob("*.kt")]
    bad = 0
    for target in targets:
        missing = audit(Path(target), by_package)
        if missing:
            bad += 1
            print(f"{Path(target).name}: {', '.join(missing)}")
    print(f"\n{len(targets)} files checked, {bad} with unaccounted symbols")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
