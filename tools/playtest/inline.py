#!/usr/bin/env python3
"""Fold the Vaults' browser build into one self-contained HTML file.

`gradle -p webplay bundle` produces a page plus three JavaScript modules. That is the right shape
for local work, and the wrong shape for publishing: GitHub Pages will serve it happily, but a
single file can also be attached, mailed, opened from a phone's downloads folder, or hosted
anywhere at all without a directory. It is also the only form an Artifact can take, since a strict
CSP blocks every external request.

So this reads the page and substitutes each `<script src="...">` with the file's contents. Nothing
else changes — the page is the same one committed at webplay/web/index.html, which is what keeps
the published game and the development harness from drifting into two different things.

Offline, stdlib only, and self-testing like every other tool here:

    python3 tools/playtest/inline.py --self-test
    python3 tools/playtest/inline.py --out webplay/build/site/index.html
"""

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "webplay" / "build" / "web"

SCRIPT_SRC = re.compile(r'<script src="\./([^"]+)"></script>')


def inline(page_html: str, read_asset) -> str:
    """Replace every local <script src> in *page_html* with the script's contents."""
    missing = []

    def swap(match):
        name = match.group(1)
        try:
            body = read_asset(name)
        except FileNotFoundError:
            missing.append(name)
            return match.group(0)
        # A bundle containing "</script>" would end the tag early and break the page. The Kotlin
        # compiler never emits one, but a silent corruption here would be very hard to spot.
        if "</script" in body.lower():
            raise ValueError(f"{name} contains a closing script tag and cannot be inlined")
        # Source maps are not published alongside, so a reference to one is a 404 per page load.
        body = "\n".join(l for l in body.splitlines() if "sourceMappingURL" not in l)
        return f"<script>\n{body}\n</script>"

    out = SCRIPT_SRC.sub(swap, page_html)
    if missing:
        raise FileNotFoundError(
            "missing bundle files: " + ", ".join(missing) + "\nRun: gradle -p webplay bundle"
        )
    return out


def self_test() -> int:
    page = '<title>T</title>\n<script src="./a.js"></script>\n<div>x</div>\n<script src="./b.js"></script>'
    assets = {"a.js": "var a=1;", "b.js": "var b=2;\n//# sourceMappingURL=b.js.map"}
    out = inline(page, lambda n: assets[n])
    assert "<script src=" not in out, "a src attribute survived"
    assert "var a=1;" in out and "var b=2;" in out, "script bodies not inlined"
    assert "sourceMappingURL" not in out, "source map reference not stripped"
    assert "<div>x</div>" in out, "page body was disturbed"

    try:
        inline('<script src="./gone.js"></script>', lambda n: (_ for _ in ()).throw(FileNotFoundError()))
    except FileNotFoundError:
        pass
    else:
        raise AssertionError("a missing bundle file should be an error, not a silent gap")

    try:
        inline('<script src="./bad.js"></script>', lambda n: "x = '</script>'")
    except ValueError:
        pass
    else:
        raise AssertionError("a closing script tag inside a bundle should be refused")

    print("inline.py self-test passed")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", default=str(ROOT / "webplay" / "build" / "site" / "index.html"))
    ap.add_argument("--build-dir", default=str(BUILD))
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    build = Path(args.build_dir)
    page = build / "index.html"
    if not page.exists():
        print(f"no page at {page}\nRun: gradle -p webplay bundle", file=sys.stderr)
        return 2

    out = inline(page.read_text(encoding="utf-8"), lambda n: (build / n).read_text(encoding="utf-8"))
    dest = Path(args.out)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(out, encoding="utf-8")
    print(f"{dest}  ({len(out) / 1024:.0f} KB, self-contained)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
