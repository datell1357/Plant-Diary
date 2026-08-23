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
SHA256_DIGEST = re.compile(r"^[0-9a-f]{64}$")
REFERENCE_REVIEW = "reference-review.json"


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_source_id(value: object, label: str) -> str:
    if value is None:
        raise ValueError(f"missing expected {label}")
    if not isinstance(value, str) or not GIT_OBJECT_ID.fullmatch(value):
        raise ValueError(f"expected {label} must be exactly 40 lowercase hex characters")
    return value


def read_json(path: Path, label: str) -> dict:
    if not path.is_file():
        raise SystemExit(f"missing {label} in {path}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SystemExit(f"malformed {label} in {path}") from error
    if not isinstance(value, dict):
        raise SystemExit(f"malformed {label} in {path}")
    return value


def required_source_id(value: object, label: str) -> str:
    try:
        return validate_source_id(value, label)
    except ValueError as error:
        raise SystemExit(str(error)) from error


def source_id_argument(label: str) -> Callable[[str], str]:
    def parse(value: str) -> str:
        try:
            return validate_source_id(value, label)
        except ValueError as error:
            raise argparse.ArgumentTypeError(str(error)) from error

    return parse


def require_evidence(
    run: Path,
    expected_head: str | None,
    expected_tree: str | None,
    *,
    require_status: bool,
) -> dict:
    if require_status:
        status = run / "instrumentation.status"
        if not status.is_file() or status.read_text(encoding="utf-8").strip() != "PASS":
            raise SystemExit(f"refusing failed or incomplete run: {run}")
    manifest = read_json(run / MANIFEST, MANIFEST)
    manifest_head = required_source_id(manifest.get("sourceHead"), "manifest source HEAD")
    manifest_tree = required_source_id(manifest.get("sourceTree"), "manifest source tree")
    if expected_head is not None and manifest_head != expected_head:
        raise SystemExit(f"wrong source HEAD in {run}")
    if expected_tree is not None and manifest_tree != expected_tree:
        raise SystemExit(f"wrong source tree in {run}")
    files = manifest.get("files")
    if not isinstance(files, dict):
        raise SystemExit(f"malformed manifest files in {run}")
    for name in FILES:
        image = run / name
        if not image.is_file():
            raise SystemExit(f"missing {name} in {run}")
        recorded = files.get(name)
        if not isinstance(recorded, str) or not SHA256_DIGEST.fullmatch(recorded):
            raise SystemExit(f"malformed manifest digest: {image}")
        if recorded != digest(image):
            raise SystemExit(f"manifest digest mismatch: {image}")
    return manifest


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
    (canonical / REFERENCE_REVIEW).write_text(
        json.dumps(
            {
                "contractVersion": 1,
                "referenceHead": expected_head,
                "referenceTree": expected_tree,
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


def require_reference(canonical: Path) -> tuple[str, str, dict[str, str]]:
    manifest = require_evidence(canonical, None, None, require_status=False)
    review = read_json(canonical / REFERENCE_REVIEW, REFERENCE_REVIEW)
    reference_head = required_source_id(review.get("referenceHead"), "reference HEAD")
    reference_tree = required_source_id(review.get("referenceTree"), "reference tree")
    if manifest.get("sourceHead") != reference_head:
        raise SystemExit("canonical manifest/reference-review source HEAD mismatch")
    if manifest.get("sourceTree") != reference_tree:
        raise SystemExit("canonical manifest/reference-review source tree mismatch")
    reviewed = review.get("reviewedFiles")
    if not isinstance(reviewed, dict):
        raise SystemExit("malformed reviewed files in reference-review.json")
    manifest_files = manifest["files"]
    for name in FILES:
        reviewed_digest = reviewed.get(name)
        if not isinstance(reviewed_digest, str) or not SHA256_DIGEST.fullmatch(reviewed_digest):
            raise SystemExit(f"malformed reviewed digest: {name}")
        if reviewed_digest != manifest_files[name]:
            raise SystemExit(f"reference-review digest mismatch: {name}")
    return reference_head, reference_tree, {
        name: manifest_files[name] for name in FILES
    }


def verify(
    canonical: Path,
    runs: list[Path],
    output: Path,
    expected_head: str,
    expected_tree: str,
) -> None:
    if len(runs) != 2:
        raise SystemExit("exactly two independently wiped comparison runs are required")
    reference_head, reference_tree, expected = require_reference(canonical)
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
        "referenceHead": reference_head,
        "referenceTree": reference_tree,
        "currentHead": expected_head,
        "currentTree": expected_tree,
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
    reference_head = "1" * 40
    reference_tree = "a" * 40
    current_head = "3" * 40
    current_tree = "b" * 40
    expect_failure(lambda: validate_source_id(None, "HEAD"), "missing expected HEAD")
    expect_failure(
        lambda: validate_source_id("A" * 40, "HEAD"),
        "40 lowercase hex",
    )

    def write_run(run: Path, source_head: str, source_tree: str) -> None:
        run.mkdir(parents=True, exist_ok=True)
        (run / "instrumentation.status").write_text("PASS\n", encoding="utf-8")
        for name in FILES:
            (run / name).write_bytes(name.encode("ascii"))
        manifest = {
            "sourceHead": source_head,
            "sourceTree": source_tree,
            "files": {name: digest(run / name) for name in FILES},
        }
        (run / MANIFEST).write_text(json.dumps(manifest), encoding="utf-8")

    with tempfile.TemporaryDirectory(prefix="todo15-visual-source-binding-") as directory:
        root = Path(directory)
        reference = root / "reference"
        canonical = root / "canonical"
        same_run1 = root / "same-run1"
        same_run2 = root / "same-run2"
        current_run1 = root / "current-run1"
        current_run2 = root / "current-run2"
        output = root / "result.json"
        write_run(reference, reference_head, reference_tree)
        promote(reference, canonical, reference_head, reference_tree)

        # Same-source verification remains valid.
        write_run(same_run1, reference_head, reference_tree)
        write_run(same_run2, reference_head, reference_tree)
        verify(canonical, [same_run1, same_run2], output, reference_head, reference_tree)
        same_result = read_json(output, "self-test output")
        if same_result["referenceHead"] != reference_head or same_result["currentHead"] != reference_head:
            raise AssertionError("same-source output lost source roles")

        # A fresh run may have a distinct source, while the reference remains immutable.
        write_run(current_run1, current_head, current_tree)
        write_run(current_run2, current_head, current_tree)
        verify(canonical, [current_run1, current_run2], output, current_head, current_tree)
        distinct_result = read_json(output, "self-test output")
        if (
            distinct_result["referenceHead"] != reference_head
            or distinct_result["referenceTree"] != reference_tree
            or distinct_result["currentHead"] != current_head
            or distinct_result["currentTree"] != current_tree
        ):
            raise AssertionError("distinct-source output did not preserve both source roles")

        review_path = canonical / REFERENCE_REVIEW
        review_text = review_path.read_text(encoding="utf-8")
        review = json.loads(review_text)
        review["referenceHead"] = "not-a-git-id"
        review_path.write_text(json.dumps(review), encoding="utf-8")
        expect_failure(
            lambda: verify(canonical, [current_run1, current_run2], output, current_head, current_tree),
            "40 lowercase hex",
        )
        review_path.write_text(review_text, encoding="utf-8")

        canonical_manifest_path = canonical / MANIFEST
        canonical_manifest = json.loads(canonical_manifest_path.read_text(encoding="utf-8"))
        canonical_manifest["sourceHead"] = current_head
        canonical_manifest_path.write_text(json.dumps(canonical_manifest), encoding="utf-8")
        expect_failure(
            lambda: verify(canonical, [current_run1, current_run2], output, current_head, current_tree),
            "canonical manifest/reference-review source HEAD mismatch",
        )
        canonical_manifest_path.write_text(
            json.dumps({**canonical_manifest, "sourceHead": reference_head}),
            encoding="utf-8",
        )

        current_manifest_path = current_run1 / MANIFEST
        current_manifest = json.loads(current_manifest_path.read_text(encoding="utf-8"))
        current_manifest["sourceHead"] = "4" * 40
        current_manifest_path.write_text(json.dumps(current_manifest), encoding="utf-8")
        expect_failure(
            lambda: verify(canonical, [current_run1, current_run2], output, current_head, current_tree),
            "wrong source HEAD",
        )
        current_manifest["sourceHead"] = current_head
        current_manifest_path.write_text(json.dumps(current_manifest), encoding="utf-8")

        changed_name = FILES[0]
        with (current_run1 / changed_name).open("ab") as image:
            image.write(b"changed")
        current_manifest["files"][changed_name] = digest(current_run1 / changed_name)
        current_manifest_path.write_text(json.dumps(current_manifest), encoding="utf-8")
        expect_failure(
            lambda: verify(canonical, [current_run1, current_run2], output, current_head, current_tree),
            "visual mismatch",
        )
    print("TODO15_VISUAL_SOURCE_BINDING_SELF_TEST tests=9 failures=0")


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
