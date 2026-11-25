#!/usr/bin/env python3
"""Sanitize a raw /api/orchestration/shell response so it can be committed as a test fixture.

Keeps the full structure (every key, every type, enum-like values, timestamps, numbers, booleans)
so the parser is tested against the real shape, but replaces anything that could identify the
user: ids, titles, paths, branches, message text, labels.

Usage: sanitize_shell.py <raw.json> <out.json> [--max-threads N]
"""
import json
import re
import sys

# Values of these keys are enum-like and safe to keep verbatim.
KEEP_KEYS = {
    "status", "activityRunStatus", "kind", "type", "role", "interactionMode", "runtimeMode",
    "creationSource", "historyOrigin", "defaultThreadEnvMode", "lastErrorClass", "lastErrorCode",
    "provider", "state", "decision", "source", "reason", "mode",
}
ISO = re.compile(r"^\d{4}-\d{2}-\d{2}T[\d:.]+Z?$")
ID_KEY = re.compile(r"(^id$|Id$|Ids$|^requestId$)")

ids: dict[str, str] = {}
counter = [0]


def fake_id(value: str) -> str:
    if value not in ids:
        ids[value] = f"id-{len(ids) + 1:04d}"
    return ids[value]


def scrub(value, key=""):
    if isinstance(value, dict):
        return {k: scrub(v, k) for k, v in value.items()}
    if isinstance(value, list):
        return [scrub(v, key) for v in value]
    if isinstance(value, str):
        if ISO.match(value) or key in KEEP_KEYS:
            return value
        if ID_KEY.search(key):
            return fake_id(value)
        counter[0] += 1
        return f"redacted-{counter[0]}"
    return value


def main() -> None:
    args = sys.argv[1:]
    max_threads = None
    if "--max-threads" in args:
        i = args.index("--max-threads")
        max_threads = int(args[i + 1])
        del args[i : i + 2]
    raw, out = args
    data = json.load(open(raw))
    if max_threads is not None:
        threads = data.get("threads", [])
        # Keep a variety of statuses rather than just the first N.
        by_status: dict[str, list] = {}
        for t in threads:
            by_status.setdefault(t.get("status"), []).append(t)
        picked = []
        while len(picked) < max_threads and any(by_status.values()):
            for bucket in by_status.values():
                if bucket and len(picked) < max_threads:
                    picked.append(bucket.pop(0))
        data["threads"] = picked
        used = {t.get("projectId") for t in picked}
        data["projects"] = [p for p in data.get("projects", []) if p.get("id") in used]
    json.dump(scrub(data), open(out, "w"), indent=2)
    open(out, "a").write("\n")


if __name__ == "__main__":
    main()
