#!/usr/bin/env python3
"""Reject direct visual literals in shipped SwiftUI call sites.

Reference-metric and design-token declarations are the only places where exact
Figma values may be declared. A narrow `visual-audit: allow(<reason>)` comment is
available only for nonvisual domain geometry or provider-owned system primitives.
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path

IOS_ROOT = Path(__file__).resolve().parents[1]
APP_ROOT = IOS_ROOT / "App"
ALLOW_MARKER = "visual-audit: allow("


@dataclass(frozen=True)
class Rule:
    name: str
    pattern: re.Pattern[str]


RULES = (
    Rule(
        "direct-color",
        re.compile(
            r"(?:Color\.|\.(?:foregroundStyle|foregroundColor|background|fill)\()"
            r"\.(?:black|white|red)\b"
        ),
    ),
    Rule("numeric-system-font", re.compile(r"\.font\(\s*\.system\(\s*size:\s*-?\d")),
    Rule("numeric-container-spacing", re.compile(r"\b(?:VStack|HStack|LazyVGrid|LazyHGrid)\([^\n)]*\bspacing:\s*-?\d")),
    Rule("numeric-padding", re.compile(r"\.padding\([^\n)]*(?<![A-Za-z_])-?\d+(?:\.\d+)?")),
    Rule("numeric-frame", re.compile(r"\.frame\([^\n)]*\b(?:width|height|minWidth|minHeight|maxWidth|maxHeight):\s*-?\d")),
    Rule("numeric-radius", re.compile(r"cornerRadius:\s*-?\d")),
    Rule("numeric-shadow", re.compile(r"\.shadow\([^\n)]*(?:\.(?:black|white|red)|\bradius:\s*-?\d|\bx:\s*-?\d|\by:\s*-?\d)")),
    Rule("numeric-offset", re.compile(r"\.offset\([^\n)]*\b(?:x|y):\s*-?\d")),
    Rule("numeric-opacity", re.compile(r"\.opacity\(\s*-?\d")),
    Rule("numeric-line-spacing", re.compile(r"\.lineSpacing\(\s*-?\d")),
    Rule("numeric-scale-factor", re.compile(r"\.minimumScaleFactor\(\s*-?\d")),
)

TYPE_DECLARATION = re.compile(r"\b(?:enum|struct)\s+\w*(?:Reference|Layout)?Metrics\b")


def metric_declaration_lines(lines: list[str]) -> set[int]:
    skipped: set[int] = set()
    depth = 0
    metric_depth: int | None = None
    pending_metric = False

    for index, line in enumerate(lines, start=1):
        if metric_depth is None and TYPE_DECLARATION.search(line):
            pending_metric = True
        opens = line.count("{")
        closes = line.count("}")
        if pending_metric and opens:
            metric_depth = depth + opens - closes
            pending_metric = False
        if metric_depth is not None:
            skipped.add(index)
        depth += opens - closes
        if metric_depth is not None and depth < metric_depth:
            metric_depth = None
    return skipped


def violations() -> list[str]:
    findings: list[str] = []
    for path in sorted(APP_ROOT.glob("*.swift")):
        text = path.read_text(encoding="utf-8")
        if "import SwiftUI" not in text:
            continue
        lines = text.splitlines()
        skipped = metric_declaration_lines(lines)
        for line_number, line in enumerate(lines, start=1):
            if line_number in skipped or ALLOW_MARKER in line:
                continue
            for rule in RULES:
                if rule.pattern.search(line):
                    findings.append(
                        f"{path.relative_to(IOS_ROOT)}:{line_number}: "
                        f"{rule.name}: {line.strip()}"
                    )
    return findings


def main() -> int:
    findings = violations()
    if findings:
        print("SHIPPED_VISUAL_LITERAL_AUDIT_FAILED")
        print("\n".join(findings))
        return 1
    print("SHIPPED_VISUAL_LITERAL_AUDIT_OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
