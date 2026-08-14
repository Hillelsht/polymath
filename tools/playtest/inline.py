#!/usr/bin/env python3
"""Fold one of the portal's pages into a single self-contained HTML file.

`gradle -p webplay site` produces the portal: several pages sharing one JavaScript bundle and one
stylesheet. That is the right shape for the web — the second page a visitor opens costs them
nothing — and the wrong shape for anything else. A single file can be attached, mailed, opened
from a phone's downloads folder, or hosted anywhere at all without a directory, and it is the only
form an Artifact can take, since a strict CSP there blocks every external request.

So this reads a page and substitutes each local `<script src>` and `<link rel=stylesheet>` with the
file's contents. Nothing else changes — it is the same page committed under `webplay/web/`, which
is what keeps the standalone copy and the published site from drifting into two different things.

The default page is The Vaults, because it is the one worth handing to someone as a file. The
daily is not: it would arrive frozen on whatever day it was built.

Offline, stdlib only, and self-testing like every other tool here:

    python3 tools/playtest/inline.py --self-test
    python3 tools/playtest/inline.py --page vaults.html --out build/vaults.html
"""

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "webplay" / "build" / "web"

SCRIPT_SRC = re.compile(r'<script src="\./([^"]+)"></script>')
STYLE_HREF = re.compile(r'<link rel="stylesheet" href="\./([^"]+)">')


def inline(page_html: str, read_asset) -> str:
    """Replace every local script and stylesheet reference in *page_html* with its contents."""
    missing = []

    def swap(match, tag, closing):
        name = match.group(1)
        try:
            body = read_asset(name)
        except FileNotFoundError:
            missing.append(name)
            return match.group(0)
        # A bundle containing "</script>" would end the tag early and break the page. The Kotlin
        # compiler never emits one, but a silent corruption here would be very hard to spot.
        if closing in body.lower():
            raise ValueError(f"{name} contains a closing {tag} tag and cannot be inlined")
        # Source maps are not published alongside, so a reference to one is a 404 per page load.
        body = "\n".join(l for l in body.splitlines() if "sourceMappingURL" not in l)
        return f"<{tag}>\n{body}\n</{tag}>"

    out = SCRIPT_SRC.sub(lambda m: swap(m, "script", "</script"), page_html)
    out = STYLE_HREF.sub(lambda m: swap(m, "style", "</style"), out)
    if missing:
        raise FileNotFoundError(
            "missing bundle files: " + ", ".join(missing) + "\nRun: gradle -p webplay bundle"
        )
    return out


def self_test() -> int:
    page = (
        '<title>T</title>\n<link rel="stylesheet" href="./a.css">\n'
        '<script src="./a.js"></script>\n<div>x</div>\n<script src="./b.js"></script>'
    )
    assets = {"a.js": "var a=1;", "b.js": "var b=2;\n//# sourceMappingURL=b.js.map", "a.css": "b{color:red}"}
    out = inline(page, lambda n: assets[n])
    assert "<script src=" not in out, "a src attribute survived"
    assert "<link rel" not in out, "a stylesheet link survived"
    assert "var a=1;" in out and "var b=2;" in out, "script bodies not inlined"
    assert "<style>\nb{color:red}\n</style>" in out, "stylesheet not inlined"
    assert "sourceMappingURL" not in out, "source map reference not stripped"
    assert "<div>x</div>" in out, "page body was disturbed"

    # A page that reaches outside the site is left exactly as it was: this tool inlines what the
    # build produced, and rewriting a real URL would silently change what the page loads.
    remote = '<script src="https://example.com/x.js"></script>'
    assert inline(remote, lambda n: "") == remote, "a remote script was touched"

    for broken, why in (
        ('<script src="./gone.js"></script>', "a missing bundle file should be an error"),
        ('<link rel="stylesheet" href="./gone.css">', "a missing stylesheet should be an error"),
    ):
        try:
            inline(broken, lambda n: (_ for _ in ()).throw(FileNotFoundError()))
        except FileNotFoundError:
            pass
        else:
            raise AssertionError(why + ", not a silent gap")

    for broken, asset in (
        ('<script src="./bad.js"></script>', "x = '</script>'"),
        ('<link rel="stylesheet" href="./bad.css">', "/* </style> */"),
    ):
        try:
            inline(broken, lambda n: asset)
        except ValueError:
            pass
        else:
            raise AssertionError("a closing tag inside an asset should be refused")

    print("inline.py self-test passed")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--page", default="vaults.html", help="which page of the portal to fold up")
    ap.add_argument("--out", default=None)
    ap.add_argument("--build-dir", default=str(BUILD))
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    build = Path(args.build_dir)
    page = build / args.page
    if not page.exists():
        print(f"no page at {page}\nRun: gradle -p webplay bundle", file=sys.stderr)
        return 2

    out = inline(page.read_text(encoding="utf-8"), lambda n: (build / n).read_text(encoding="utf-8"))
    dest = Path(args.out or ROOT / "webplay" / "build" / "standalone" / args.page)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(out, encoding="utf-8")
    print(f"{dest}  ({len(out) / 1024:.0f} KB, self-contained)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
