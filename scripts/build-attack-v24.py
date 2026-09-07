"""Build the V2.4 main Dify workflow from V2.3 without overwriting V2.3."""

from __future__ import annotations

import json
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "dify-generated" / "省网智能体-平台意图分流-V2.3.yml"
OUTPUT = ROOT / "dify-generated" / "省网智能体-平台意图分流-V2.4.yml"

BASE_CONTEXT_ID = "1779276774206"
EVIDENCE_BUS_ID = "1782156878921"
MERGE_ID = "platform_attack_context_merge_v2"
ATTACK_TOOL_ID = "1783259406677"


MERGE_CODE = r'''def clip(value, limit):
    text = str(value or "").strip()
    if len(text) <= limit:
        return text
    return text[:limit] + "\n[内容因长度限制已截断]"


def main(base_context="", evidence_context="", ioc_list_json=""):
    sections = []

    base = clip(base_context, 9000)
    if base:
        sections.append(
            "【原始行为与附件上下文——ATT&CK 映射的主要依据】\n" + base
        )

    evidence = clip(evidence_context, 5000)
    if evidence:
        sections.append(
            "【Evidence Bus 工具证据——仅用于补充行为线索】\n" + evidence
        )

    iocs = clip(ioc_list_json, 2500)
    if iocs:
        sections.append(
            "【标准化 IOC 列表——不得仅凭 IOC 风险形成 ATT&CK 映射】\n" + iocs
        )

    sections.append(
        "【ATT&CK 映射边界】\n"
        "只把输入中明确发生或被观测到的攻击行为标为正式映射。"
        "裸 IOC、CVE 编号、CVSS、KEV、信誉与严重度不属于攻击行为。"
        "漏洞描述中的可利用能力只能标为 potential，不能证明该行为已经发生。"
        "每个候选必须引用输入中的原文片段，不得编造技术编号。"
    )

    return {"attack_context": "\n\n".join(sections)}
'''


STRICT_RULES = """LocalAttackMCP V2 字段语义必须严格遵守：
mapping_status = formal_mapping 且 matched_count > 0 时，matched_techniques 才能形成正式 ATT&CK 映射；
mapping_status = potential_only 时，只能表述为漏洞或能力层面的“潜在映射”，不得写成已发生的攻击行为；
mapping_status = content_candidates_only 时，content_candidates 仅供人工检索，绝不是正式映射；
mapping_status = no_mapping 时，必须明确本次没有可靠的 ATT&CK 行为映射；
potential_techniques 和 content_candidates 即使含有合法 T 编号，也不得列入正式 ATT&CK 技战术结果；
正式映射应优先展示工具返回的 evidence_quote，并保持为输入中的原文证据。"""


def node_by_id(nodes: list[dict], node_id: str) -> dict:
    matches = [node for node in nodes if str(node.get("id")) == node_id]
    if len(matches) != 1:
        raise RuntimeError(f"Expected exactly one node {node_id}, found {len(matches)}")
    return matches[0]


def make_edge(source: str, target: str, source_type: str, target_type: str) -> dict:
    return {
        "data": {
            "isInIteration": False,
            "isInLoop": False,
            "sourceType": source_type,
            "targetType": target_type,
        },
        "id": f"{source}-source-{target}-target",
        "source": source,
        "sourceHandle": "source",
        "target": target,
        "targetHandle": "target",
        "type": "custom",
        "zIndex": 0,
    }


def build() -> Path:
    if not SOURCE.exists():
        raise FileNotFoundError(SOURCE)
    if OUTPUT.exists():
        raise FileExistsError(f"Refusing to overwrite generated V2.4: {OUTPUT}")

    document = yaml.safe_load(SOURCE.read_text(encoding="utf-8-sig"))
    graph = document["workflow"]["graph"]
    nodes = graph["nodes"]
    edges = graph["edges"]

    evidence_node = node_by_id(nodes, EVIDENCE_BUS_ID)
    attack_tool = node_by_id(nodes, ATTACK_TOOL_ID)

    merge_node = {
        "data": {
            "code": MERGE_CODE,
            "code_language": "python3",
            "outputs": {"attack_context": {"children": None, "type": "string"}},
            "selected": False,
            "title": "合并 ATT&CK 行为与证据上下文",
            "type": "code",
            "variables": [
                {
                    "value_selector": [BASE_CONTEXT_ID, "analysis_context"],
                    "value_type": "string",
                    "variable": "base_context",
                },
                {
                    "value_selector": [EVIDENCE_BUS_ID, "f_llm_evidence_context"],
                    "value_type": "string",
                    "variable": "evidence_context",
                },
                {
                    "value_selector": [EVIDENCE_BUS_ID, "f_unified_ioc_list_json"],
                    "value_type": "string",
                    "variable": "ioc_list_json",
                },
            ],
        },
        "height": 54,
        "id": MERGE_ID,
        "position": {"x": 7660, "y": 770},
        "positionAbsolute": {"x": 7660, "y": 770},
        "selected": False,
        "sourcePosition": "right",
        "targetPosition": "left",
        "type": "custom",
        "width": 242,
    }
    nodes.append(merge_node)

    attack_tool["data"]["tool_parameters"]["analysis_context"] = {
        "type": "mixed",
        "value": "{{#platform_attack_context_merge_v2.attack_context#}}",
    }

    old_edge_id = f"{EVIDENCE_BUS_ID}-source-{ATTACK_TOOL_ID}-target"
    graph["edges"] = [item for item in edges if item.get("id") != old_edge_id]
    if len(graph["edges"]) != len(edges) - 1:
        raise RuntimeError("Could not locate Evidence Bus to LocalAttackMCP edge")
    graph["edges"].extend(
        [
            make_edge(EVIDENCE_BUS_ID, MERGE_ID, "code", "code"),
            make_edge(MERGE_ID, ATTACK_TOOL_ID, "code", "tool"),
        ]
    )

    final_nodes = [node for node in nodes if node.get("data", {}).get("title") == "最终总结LLM"]
    if len(final_nodes) != 1:
        raise RuntimeError(f"Expected one final LLM node, found {len(final_nodes)}")
    system_prompts = [
        item
        for item in final_nodes[0]["data"].get("prompt_template", [])
        if item.get("role") == "system"
    ]
    if len(system_prompts) != 1:
        raise RuntimeError("Final LLM system prompt not found")
    anchor = "七、ATT&CK 使用规则\n"
    text = system_prompts[0]["text"]
    if anchor not in text:
        raise RuntimeError("ATT&CK rules anchor not found in final prompt")
    system_prompts[0]["text"] = text.replace(anchor, anchor + STRICT_RULES + "\n", 1)

    document["app"]["name"] = document["app"]["name"].replace("V2.3", "V2.4")
    document["app"]["description"] = (
        str(document["app"].get("description") or "")
        + "；V2.4 修复 ATT&CK 上下文断链，并分离正式、潜在与内容候选映射"
    )

    OUTPUT.write_text(
        yaml.safe_dump(document, allow_unicode=True, sort_keys=False, width=1000),
        encoding="utf-8",
    )
    return OUTPUT


if __name__ == "__main__":
    generated = build()
    print(json.dumps({"generated": str(generated)}, ensure_ascii=False))
