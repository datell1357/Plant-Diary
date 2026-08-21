#!/usr/bin/env python3
import argparse
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8", errors="replace")


def node_tap_result(path: str) -> dict:
    text = read(path)
    values = {}
    for field in ("tests", "pass", "fail", "skipped"):
        match = re.search(rf"^(?:#|ℹ) {field} (\d+)$", text, re.MULTILINE)
        if not match:
            raise ValueError(f"Missing TAP {field} count in {path}")
        values[field] = int(match.group(1))
    runtimes = sorted(set(re.findall(
        r"^NODE_RUNTIME required=22 actual=([^ ]+) status=ok$",
        text,
        re.MULTILINE,
    )))
    return {**values, "nodeRuntimes": runtimes}


def rules_result(path: str) -> dict:
    prefix = "RULES_RESULT_JSON "
    lines = [line[len(prefix):] for line in read(path).splitlines() if line.startswith(prefix)]
    if len(lines) != 1:
        raise ValueError(f"Expected one RULES_RESULT_JSON line in {path}, found {len(lines)}")
    result = json.loads(lines[0])
    if result["tests"] != len(result["testNames"]):
        raise ValueError("Rules test count does not match emitted test names")
    if len(set(result["testNames"])) != len(result["testNames"]):
        raise ValueError("Rules test names are not unique")
    return result


def xml_counts(path: Path) -> dict:
    root = ET.parse(path).getroot()
    return {
        field: int(root.attrib.get(field, 0))
        for field in ("tests", "failures", "errors", "skipped")
    }


def aggregate_jvm(repository: Path) -> dict:
    files = sorted(repository.glob("**/build/test-results/testDebugUnitTest/TEST-*.xml"))
    totals = {field: 0 for field in ("tests", "failures", "errors", "skipped")}
    for path in files:
        counts = xml_counts(path)
        for field, value in counts.items():
            totals[field] += value
    return {"xmlFiles": len(files), **totals}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def replace_line(text: str, key: str, value: str) -> str:
    pattern = re.compile(rf"^{re.escape(key)} .*$", re.MULTILINE)
    replacement = f"{key} {value}"
    if pattern.search(text):
        return pattern.sub(replacement, text, count=1)
    return text.rstrip() + "\n" + replacement + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", default=".")
    parser.add_argument("--rules-log", required=True)
    parser.add_argument("--functions-unit-log", required=True)
    parser.add_argument("--functions-emulator-log", required=True)
    parser.add_argument("--functions-smoke-log", required=True)
    parser.add_argument("--api-report", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--fingerprint", required=True)
    args = parser.parse_args()

    repository = Path(args.repository).resolve()
    rules = rules_result(args.rules_log)
    unit = node_tap_result(args.functions_unit_log)
    emulator = node_tap_result(args.functions_emulator_log)
    smoke = node_tap_result(args.functions_smoke_log)
    api_report = Path(args.api_report)
    api = xml_counts(api_report)
    api_report_sha256 = sha256(api_report)
    jvm = aggregate_jvm(repository)
    evidence = {
        "schemaVersion": 1,
        "source": "machine-parsed-canonical-test-output",
        "node": {
            "requiredMajor": 22,
            "unitRuntimes": unit.pop("nodeRuntimes"),
            "emulatorRuntimes": emulator.pop("nodeRuntimes"),
            "smokeRuntimes": smoke.pop("nodeRuntimes"),
        },
        "rules": rules,
        "functionsUnit": unit,
        "functionsEmulator": emulator,
        "functionsSmoke": smoke,
        "androidJvm": jvm,
        "api37": {**api, "reportSha256": api_report_sha256},
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    fingerprint_path = Path(args.fingerprint)
    fingerprint = fingerprint_path.read_text(encoding="utf-8")
    fingerprint = replace_line(
        fingerprint,
        "android-jvm-suite",
        f"{jvm['tests'] - jvm['failures'] - jvm['errors']}/{jvm['tests']}",
    )
    fingerprint = replace_line(fingerprint, "android-jvm-xml-files", str(jvm["xmlFiles"]))
    fingerprint = replace_line(
        fingerprint,
        "api37-tests",
        f"{api['tests'] - api['failures'] - api['errors']}/{api['tests']}",
    )
    fingerprint = replace_line(
        fingerprint,
        "api37-test-report-sha256",
        api_report_sha256,
    )
    fingerprint = replace_line(
        fingerprint,
        "firebase-rules-tests",
        f"{rules['passes']}/{rules['tests']}",
    )
    fingerprint = replace_line(
        fingerprint,
        "functions-unit-tests",
        f"{unit['pass']}/{unit['tests']}",
    )
    fingerprint = replace_line(
        fingerprint,
        "functions-emulator-tests",
        f"{emulator['pass']}/{emulator['tests']}",
    )
    fingerprint = replace_line(
        fingerprint,
        "functions-smoke-tests",
        f"{smoke['pass']}/{smoke['tests']}",
    )
    fingerprint = replace_line(
        fingerprint,
        "node-runtime-evidence",
        "required-22-machine-parsed-from-canonical-test-output",
    )
    fingerprint = replace_line(
        fingerprint,
        "qa-counts-evidence-sha256",
        sha256(output),
    )
    fingerprint_path.write_text(fingerprint, encoding="utf-8")

    print(
        "QA_EVIDENCE "
        f"rules={rules['passes']}/{rules['tests']} "
        f"functionsUnit={unit['pass']}/{unit['tests']} "
        f"functionsEmulator={emulator['pass']}/{emulator['tests']} "
        f"functionsSmoke={smoke['pass']}/{smoke['tests']} "
        f"jvm={jvm['tests'] - jvm['failures'] - jvm['errors']}/{jvm['tests']} "
        f"api37={api['tests'] - api['failures'] - api['errors']}/{api['tests']} "
        f"sha256={sha256(output)}"
    )


if __name__ == "__main__":
    main()
