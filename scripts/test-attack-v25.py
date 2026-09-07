from __future__ import annotations

import importlib.util
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / "dify-generated" / "省网智能体-平台意图分流-V2.5.yml"
BUILDER = ROOT / "scripts" / "build-attack-v25.py"


def load_intent_main():
    spec = importlib.util.spec_from_file_location("build_attack_v25", BUILDER)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader
    spec.loader.exec_module(module)
    scope: dict = {}
    exec(module.INTENT_CODE, scope)
    return scope["main"]


def main():
    doc = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
    graph = doc["workflow"]["graph"]
    ids = {str(node["id"]) for node in graph["nodes"]}
    required = {
        "fallback_attack_intent_v25", "fallback_attack_gate_v25", "fallback_attack_tool_v25",
        "fallback_attack_failure_v25", "fallback_attack_result_v25", "fallback_attack_answer_v25",
        "fallback_attack_reply_v25",
    }
    assert required <= ids
    edges = {(str(e["source"]), str(e.get("sourceHandle")), str(e["target"])) for e in graph["edges"]}
    assert ("1780576087736", "source", "fallback_attack_intent_v25") in edges
    assert ("fallback_attack_gate_v25", "true", "fallback_attack_tool_v25") in edges
    assert ("fallback_attack_gate_v25", "false", "1781159570414") in edges
    assert ("1780576087736", "source", "1781159570414") not in edges

    classify = load_intent_main()
    positives = [
        "请把攻击者通过恶意 Excel 诱导用户启用宏，随后 PowerShell 下载载荷的行为映射到 MITRE ATT&CK",
        "EDR 检测到 powershell 下载并执行脚本，请分析攻击链",
        "T1059.001 是什么，给出检测建议",
    ]
    negatives = [
        "Spring Boot 登录模块应该怎样设计？",
        "推荐几个 RAG 安全研究项目",
        "这篇论文研究了对抗样本攻击，请概括创新点",
    ]
    for text in positives:
        assert classify(text, "", "")["needs_attack"] is True, text
    for text in negatives:
        assert classify(text, "", "")["needs_attack"] is False, text
    print("V2.5 structure and deterministic intent regression: PASS")


if __name__ == "__main__":
    main()
