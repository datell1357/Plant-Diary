#!/usr/bin/env python3
"""Enforce the project's logical source-line limit on changed/new files."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path
from typing import Final

REPO_ROOT: Final = Path(__file__).resolve().parents[2]
LIMIT: Final = 250
GOVERNED_SUFFIXES: Final = frozenset(
    {".swift", ".py", ".ts", ".tsx", ".mts", ".cts", ".rs", ".go"}
)
C_STYLE_SUFFIXES: Final = GOVERNED_SUFFIXES - {".py"}
NESTED_BLOCK_SUFFIXES: Final = frozenset({".swift", ".rs"})


def git_paths(*args: str) -> set[str]:
    output = subprocess.check_output(
        ["git", *args], cwd=REPO_ROOT, text=True
    )
    return set(output.splitlines())


def is_governed_source(path: Path) -> bool:
    return path.suffix.lower() in GOVERNED_SUFFIXES


def logical_line_count(source: str, suffix: str) -> int:
    count = 0
    block_depth = 0
    normalized_suffix = suffix.lower()
    supports_block_comments = normalized_suffix in C_STYLE_SUFFIXES
    supports_nested_blocks = normalized_suffix in NESTED_BLOCK_SUFFIXES
    supports_hash_comments = normalized_suffix == ".py"

    for line in source.splitlines():
        cursor = 0
        while cursor < len(line):
            if block_depth > 0:
                block_end = line.find("*/", cursor)
                block_start = line.find("/*", cursor)
                nested_block_starts_first = block_start != -1 and (
                    block_end == -1 or block_start < block_end
                )
                if supports_nested_blocks and nested_block_starts_first:
                    block_depth += 1
                    cursor = block_start + 2
                    continue
                if block_end == -1:
                    break
                block_depth -= 1
                cursor = block_end + 2
                continue

            while cursor < len(line) and line[cursor].isspace():
                cursor += 1
            if cursor == len(line):
                break
            if supports_hash_comments and line.startswith("#", cursor):
                break
            if supports_block_comments and line.startswith("//", cursor):
                break
            if supports_block_comments and line.startswith("/*", cursor):
                block_depth = 1
                cursor += 2
                continue
            count += 1
            break

    return count


def self_test() -> int:
    fixtures = {
        ".swift": (
            "// comment\n/* outer\n/* nested\n*/\nstill outer */\n"
            "let value = \"// code\"\n"
        ),
        ".py": "# comment\nvalue = '# code'\n\n",
        ".ts": "// comment\nconst value = '/* code */';\n/* comment */\n",
        ".tsx": "// comment\nconst view = <View />;\n",
        ".mts": "/* comment */\nexport const value = 1;\n",
        ".cts": "// comment\nmodule.exports = { value: 1 };\n",
        ".rs": "// comment\nlet value = \"// code\";\n",
        ".go": "/* outer\n/* marker\n*/\nvalue := `// code`\n",
    }
    for suffix, fixture in fixtures.items():
        assert is_governed_source(Path(f"fixture{suffix}"))
        assert logical_line_count(fixture, suffix) == 1
    assert not is_governed_source(Path("fixture.sh"))
    return 0


def main() -> int:
    paths = git_paths("diff", "--name-only", "--diff-filter=ACMR", "HEAD", "--")
    paths |= git_paths("ls-files", "--others", "--exclude-standard", "--")
    failures: list[str] = []
    for relative in sorted(paths):
        path = REPO_ROOT / relative
        if not is_governed_source(path) or not path.is_file():
            continue
        count = logical_line_count(
            path.read_text(encoding="utf-8"), path.suffix
        )
        if count > LIMIT:
            failures.append(
                f"{relative}: {count} logical lines (limit {LIMIT})"
            )
    if failures:
        print("CHANGED_SWIFT_SIZE_AUDIT_FAILED")
        print("\n".join(failures))
        return 1
    print("CHANGED_SWIFT_SIZE_AUDIT_OK")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    sys.exit(main())
