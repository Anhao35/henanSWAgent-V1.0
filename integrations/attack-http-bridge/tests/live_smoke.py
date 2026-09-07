"""Live v0.2 smoke test against the service listening on localhost:8011."""

from __future__ import annotations

import json
import os
import urllib.request
from pathlib import Path


def load_api_key() -> str:
    for line in (Path(__file__).resolve().parents[1] / ".env").read_text(
        encoding="utf-8-sig"
    ).splitlines():
        if line.strip().startswith("HTTP_API_KEY="):
            return line.split("=", 1)[1].strip().strip('"').strip("'")
    return ""


def call(context: str) -> dict:
    payload = {
        "analysis_context": context,
        "domain": "enterprise",
        "top_k": 8,
        "allow_content_fallback": False,
    }
    request = urllib.request.Request(
        "http://127.0.0.1:8011/api/attack/map",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json", "X-API-Key": load_api_key()},
    )
    with urllib.request.urlopen(request, timeout=45) as response:
        return json.load(response)


behavior = call(
    "攻击者向财务人员发送恶意 Excel 附件。用户启用宏后，"
    "PowerShell 从远程服务器下载载荷并执行。"
)
plain_cve = call(
    "请研判 CVE-2024-3400，CVSS 10.0，已列入 CISA KEV，请给出修复建议。"
)
plain_ioc = call("请查询 8.8.8.8 的情报和风险。")

behavior_ids = [item.get("technique_id") for item in behavior["matched_techniques"]]
assert behavior["bridge_version"] == "0.2.0", behavior
assert behavior["mapping_status"] == "formal_mapping", behavior
assert behavior_ids == ["T1566.001", "T1059.001", "T1105"], behavior_ids
assert behavior["content_fallback_used"] is False, behavior

for label, result in [("plain_cve", plain_cve), ("plain_ioc", plain_ioc)]:
    assert result["bridge_version"] == "0.2.0", result
    assert result["mapping_status"] == "no_mapping", (label, result)
    assert result["matched_techniques"] == [], (label, result)
    assert result["content_candidates"] == [], (label, result)
    assert result["content_fallback_used"] is False, (label, result)

print("PASS: live bridge v0.2 smoke")
print("behavior=" + ",".join(behavior_ids))
print("plain_cve=no_mapping")
print("plain_ioc=no_mapping")
