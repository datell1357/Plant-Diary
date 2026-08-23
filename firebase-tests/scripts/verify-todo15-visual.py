#!/usr/bin/env python3
import argparse
import hashlib
import json
import re
import shutil
import tempfile
from pathlib import Path
from typing import Callable

FILES = [
    "todo15-api37-rendering.png",
    "todo15-api37-ready.png",
    "todo15-api37-render-failure.png",
    "todo15-api37-chooser-cancelled.png",
    "todo15-api37-active-link.png",
    "todo15-api37-offline-failure.png",
    "todo15-api37-revoked.png",
    "todo15-api37-active-link-font-200.png",
]
MANIFEST = "todo15-api37-determinism.json"
GIT_OBJECT_ID = re.compile(r"^[0-9a-f]{40}$")


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_source_id(value: str | None, label: str) -> str:
    if value is None:
        raise ValueError(f"missing expected {label}")
    if not GIT_OBJECT_ID.fullmatch(value):
        raise ValueError(f"expected {label} must be exactly 40 lowercase hex characters")
    return value


def source_id_argument(label: str) -> Callable[[str], str]:
    def parse(value: str) -> str:
        try:
            return validate_source_id(value, label)
        except ValueError as error:
            raise argparse.ArgumentTypeError(str(error)) from error

    return parse


def require_evidence(
    run: Path,
    expected_head: str,
    expected_tree: str,
    *,
    require_status: bool,
) -> None:
    if require_status:
        status = run / "instrumentation.status"
        if not status.is_file() or status.read_text(encoding="utf-8").strip() != "PASS":
            raise SystemExit(f"refusing failed or incomplete run: {run}")
    manifest_path = run / MANIFEST
    if not manifest_path.is_file():
        raise SystemExit(f"missing {MANIFEST} in {run}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("sourceHead") != expected_head:
        raise SystemExit(f"wrong source HEAD in {run}")
    if manifest.get("sourceTree") != expected_tree:
        raise SystemExit(f"wrong source tree in {run}")
    for name in FILES:
        image = run / name
        if not image.is_file():
            raise SystemExit(f"missing {name} in {run}")
        if manifest.get("files", {}).get(name) != digest(image):
            raise SystemExit(f"manifest digest mismatch: {image}")


def require_green(run: Path, expected_head: str, expected_tree: str) -> None:
    require_evidence(
        run,
        expected_head,
        expected_tree,
        require_status=True,
    )


def promote(
    reference: Path,
    canonical: Path,
    expected_head: str,
    expected_tree: str,
) -> None:
    require_green(reference, expected_head, expected_tree)
    canonical.mkdir(parents=True, exist_ok=True)
    for name in [*FILES, MANIFEST]:
        shutil.copyfile(reference / name, canonical / name)
    (canonical / "reference-review.json").write_text(
        json.dumps(
            {
                "contractVersion": 1,
                "sourceHead": expected_head,
                "sourceTree": expected_tree,
                "referenceRun": reference.name,
                "promotionPolicy": "first-reviewed-green-run-only",
                "failedRunPromotion": False,
                "reviewedFiles": {name: digest(reference / name) for name in FILES},
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"TODO15_VISUAL_REFERENCE promoted={reference} files={len(FILES)}")


def verify(
    canonical: Path,
    runs: list[Path],
    output: Path,
    expected_head: str,
    expected_tree: str,
) -> None:
    if len(runs) != 2:
        raise SystemExit("exactly two independently wiped comparison runs are required")
    require_evidence(
        canonical,
        expected_head,
        expected_tree,
        require_status=False,
    )
    expected = {name: digest(canonical / name) for name in FILES}
    comparisons = []
    for run in runs:
        require_green(run, expected_head, expected_tree)
        actual = {name: digest(run / name) for name in FILES}
        mismatches = [name for name in FILES if actual[name] != expected[name]]
        comparisons.append({"run": run.name, "files": actual, "mismatches": mismatches})
        if mismatches:
            raise SystemExit(f"visual mismatch in {run}: {', '.join(mismatches)}")
    result = {
        "contractVersion": 1,
        "sourceHead": expected_head,
        "sourceTree": expected_tree,
        "device": "VerifyAdFix_pixel_7_api37_1080x2400_420dpi",
        "renderer": "swiftshader_indirect",
        "defaultAnimations": True,
        "independentlyWipedRuns": 3,
        "referencePolicy": "first-reviewed-green-run-only",
        "failedRunPromotion": False,
        "reference": expected,
        "comparisons": comparisons,
        "byteIdentical": True,
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f"TODO15_VISUAL_VERIFY comparisons=2 files={len(FILES)} byteIdentical=true")


def expect_failure(action: Callable[[], None], expected_message: str) -> None:
    try:
        action()
    except (SystemExit, ValueError) as error:
        if expected_message not in str(error):
            raise AssertionError(f"unexpected failure: {error}") from error
    else:
        raise AssertionError(f"expected failure containing: {expected_message}")


def self_test() -> None:
    head = "1" * 40
    tree = "a" * 40
    expect_failure(lambda: validate_source_id(None, "HEAD"), "missing expected HEAD")
    expect_failure(
        lambda: validate_source_id("A" * 40, "HEAD"),
        "40 lowercase hex",
    )
    with tempfile.TemporaryDirectory(prefix="todo15-visual-source-binding-") as directory:
        run = Path(directory)
        (run / "instrumentation.status").write_text("PASS\n", encoding="utf-8")
        for name in FILES:
            (run / name).write_bytes(name.encode("ascii"))
        manifest = {
            "sourceHead": "2" * 40,
            "sourceTree": tree,
            "files": {name: digest(run / name) for name in FILES},
        }
        (run / MANIFEST).write_text(json.dumps(manifest), encoding="utf-8")
        expect_failure(
            lambda: require_green(run, head, tree),
            "wrong source HEAD",
        )
        manifest["sourceHead"] = head
        (run / MANIFEST).write_text(json.dumps(manifest), encoding="utf-8")
        require_green(run, head, tree)
    print("TODO15_VISUAL_SOURCE_BINDING_SELF_TEST tests=4 failures=0")


def add_source_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--expected-head",
        required=True,
        type=source_id_argument("HEAD"),
        help="expected committed HEAD, exactly 40 lowercase hex characters",
    )
    parser.add_argument(
        "--expected-tree",
        required=True,
        type=source_id_argument("tree"),
        help="expected committed tree, exactly 40 lowercase hex characters",
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Promote or verify Todo 15 API 37 evidence bound to an explicit source revision."
    )
    sub = parser.add_subparsers(dest="command", required=True)
    promote_parser = sub.add_parser("promote")
    add_source_arguments(promote_parser)
    promote_parser.add_argument("--reference", required=True, type=Path)
    promote_parser.add_argument("--canonical", required=True, type=Path)
    verify_parser = sub.add_parser("verify")
    add_source_arguments(verify_parser)
    verify_parser.add_argument("--canonical", required=True, type=Path)
    verify_parser.add_argument("--runs", required=True, nargs=2, type=Path)
    verify_parser.add_argument("--output", required=True, type=Path)
    sub.add_parser("self-test", help="run deterministic source-binding checks")
    args = parser.parse_args()
    if args.command == "promote":
        promote(
            args.reference,
            args.canonical,
            args.expected_head,
            args.expected_tree,
        )
    elif args.command == "verify":
        verify(
            args.canonical,
            args.runs,
            args.output,
            args.expected_head,
            args.expected_tree,
        )
    else:
        self_test()


if __name__ == "__main__":
    main()
