"""Build V2.5: deterministically invoke LocalAttackMCP for ATT&CK-oriented fallback requests."""

from __future__ import annotations

import copy
import json
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "dify-generated" / "省网智能体-平台意图分流-V2.4.yml"
OUTPUT = ROOT / "dify-generated" / "省网智能体-平台意图分流-V2.5.yml"

KNOWLEDGE_ID = "1780576087736"
FALLBACK_AGENT_ID = "1781159570414"
MAIN_ATTACK_TOOL_ID = "1783259406677"
MULTIMODAL_ID = "1779195036236"

INTENT_CODE_ID = "fallback_attack_intent_v25"
INTENT_GATE_ID = "fallback_attack_gate_v25"
TOOL_ID = "fallback_attack_tool_v25"
FAILURE_ID = "fallback_attack_failure_v25"
RESULT_ID = "fallback_attack_result_v25"
ANSWER_AGENT_ID = "fallback_attack_answer_v25"
ANSWER_ID = "fallback_attack_reply_v25"


INTENT_CODE = r'''import re


def main(query="", attachment_context="", knowledge_context=""):
    query = str(query or "").strip()
    attachment = str(attachment_context or "").strip()
    combined = (query + "\n" + attachment).lower()

    explicit = bool(
        re.search(r"(?i)\bT\d{4}(?:\.\d{3})?\b", combined)
        or re.search(r"mitre|att\s*&?\s*ck|attck|技战术|攻击链.{0,8}(?:映射|分析)|映射.{0,8}(?:攻击行为|技术)", combined)
    )

    behavior_terms = (
        "powershell", "cmd.exe", "rundll32", "regsvr32", "mshta", "wmic", "psexec",
        "webshell", "钓鱼附件", "恶意附件", "凭据抓取", "口令转储", "横向移动",
        "权限提升", "计划任务", "注册表自启动", "远程服务", "可疑进程", "进程树",
        "数据外传", "命令执行", "脚本执行", "下载并执行", "后门", "勒索",
    )
    observation_terms = (
        "攻击者", "检测到", "发现", "告警", "日志", "随后", "执行", "下载", "创建",
        "连接", "收集", "窃取", "持久化", "恶意", "可疑", "入侵", "远程",
    )
    ai_research_terms = (
        "对抗样本", "模型投毒", "模型后门", "模型窃取", "成员推理", "梯度泄露",
        "联邦学习", "大模型越狱", "提示注入研究", "rag安全", "多模态模型攻击",
    )
    observed_behavior = any(term in combined for term in behavior_terms) and any(
        term in combined for term in observation_terms
    )
    excluded_research = any(term in combined for term in ai_research_terms) and not explicit
    needs_attack = bool(explicit or (observed_behavior and not excluded_research))

    sections = ["【用户原始问题】\n" + query]
    if attachment:
        sections.append("【附件或多模态中可观察行为】\n" + attachment[:9000])
    knowledge = str(knowledge_context or "").strip()
    if knowledge:
        sections.append("【知识库背景（不能替代原文行为证据）】\n" + knowledge[:3500])
    sections.append(
        "【映射约束】只映射输入中明确发生或被观察到的行为；每项必须引用原文。"
        "裸 IOC、CVE 严重度、信誉和知识库相似文本不能单独形成正式 ATT&CK 映射。"
    )
    return {"needs_attack": needs_attack, "analysis_context": "\n\n".join(sections)}
'''


FAILURE_CODE = r'''def main():
    return {
        "text": "LocalAttackMCP 调用失败，当前无法给出经工具验证的 ATT&CK 技术编号。请基于原始行为继续分析，并明确说明工具结果不可用。",
        "status": "tool_failed",
        "mapping_text": "",
        "warnings_text": "LocalAttackMCP 调用失败，不得凭模型记忆补充技术编号。",
    }
'''


ANSWER_RULES = """

【V2.5 强制工具结果使用规则】
本请求已由确定性意图门显式调用 LocalAttackMCP，下面提供的是已执行工具结果，不要再次调用同名工具：

工具状态：{{#fallback_attack_result_v25.attack_status#}}
映射正文：{{#fallback_attack_result_v25.attack_mapping_text#}}
完整文本：{{#fallback_attack_result_v25.attack_text#}}
告警：{{#fallback_attack_result_v25.attack_warnings#}}

只有 mapping_status/formal_mapping 且有 matched_count 的项目可作为正式映射。
potential_only 只能称为潜在能力；content_candidates_only 只能称为人工检索候选；no_mapping 必须明确没有可靠映射。
工具失败时不得自行编造 T 编号。回答应直接解释行为、证据片段、技术映射与核验建议，不暴露内部节点和路由。
"""


def by_id(nodes: list[dict], node_id: str) -> dict:
    matches = [node for node in nodes if str(node.get("id")) == node_id]
    if len(matches) != 1:
        raise RuntimeError(f"Expected exactly one node {node_id}, found {len(matches)}")
    return matches[0]


def edge(source: str, target: str, source_type: str, target_type: str, handle: str = "source") -> dict:
    return {
        "id": f"{source}-{handle}-{target}-target",
        "source": source,
        "sourceHandle": handle,
        "target": target,
        "targetHandle": "target",
        "type": "custom",
        "zIndex": 0,
        "data": {"sourceType": source_type, "targetType": target_type, "isInIteration": False, "isInLoop": False},
    }


def shell(node_id: str, x: float, y: float, data: dict, height: int = 80) -> dict:
    return {
        "id": node_id, "type": "custom", "width": 242, "height": height,
        "position": {"x": x, "y": y}, "positionAbsolute": {"x": x, "y": y},
        "selected": False, "sourcePosition": "right", "targetPosition": "left", "data": data,
    }


def build() -> Path:
    if OUTPUT.exists():
        raise FileExistsError(f"Refusing to overwrite {OUTPUT}")
    document = yaml.safe_load(SOURCE.read_text(encoding="utf-8-sig"))
    graph = document["workflow"]["graph"]
    nodes = graph["nodes"]
    edges = graph["edges"]

    fallback_agent = by_id(nodes, FALLBACK_AGENT_ID)
    attack_tool = by_id(nodes, MAIN_ATTACK_TOOL_ID)

    intent = shell(INTENT_CODE_ID, 3580, 850, {
        "type": "code", "title": "兜底 ATT&CK 确定性意图识别", "code_language": "python3", "code": INTENT_CODE,
        "variables": [
            {"variable": "query", "value_selector": ["sys", "query"], "value_type": "string"},
            {"variable": "attachment_context", "value_selector": [MULTIMODAL_ID, "multimodal_context_text"], "value_type": "string"},
            {"variable": "knowledge_context", "value_selector": [KNOWLEDGE_ID, "result"], "value_type": "array[object]"},
        ],
        "outputs": {"needs_attack": {"type": "boolean", "children": None}, "analysis_context": {"type": "string", "children": None}},
    })
    gate = shell(INTENT_GATE_ID, 3890, 850, {
        "type": "if-else", "title": "是否显式执行兜底 ATT&CK 映射", "cases": [{
            "id": "true", "case_id": "true", "logical_operator": "and", "conditions": [{
                "id": "fallback-attack-required", "variable_selector": [INTENT_CODE_ID, "needs_attack"],
                "varType": "boolean", "comparison_operator": "is", "value": True,
            }],
        }],
    })

    tool = copy.deepcopy(attack_tool)
    tool["id"] = TOOL_ID
    tool["position"] = tool["positionAbsolute"] = {"x": 4210, "y": 750}
    tool["data"]["title"] = "兜底分支-显式 LocalAttackMCP"
    tool["data"]["tool_parameters"]["analysis_context"] = {"type": "mixed", "value": "{{#fallback_attack_intent_v25.analysis_context#}}"}

    failure = shell(FAILURE_ID, 4510, 960, {
        "type": "code", "title": "兜底 ATT&CK 工具失败处理", "code_language": "python3", "code": FAILURE_CODE,
        "variables": [], "outputs": {
            "text": {"type": "string", "children": None}, "status": {"type": "string", "children": None},
            "mapping_text": {"type": "string", "children": None}, "warnings_text": {"type": "string", "children": None},
        },
    })
    result = shell(RESULT_ID, 4820, 790, {
        "type": "variable-aggregator", "title": "兜底 ATT&CK 结果聚合", "output_type": "any", "variables": [],
        "advanced_settings": {"group_enabled": True, "groups": [
            {"groupId": "fallback-text", "group_name": "attack_text", "output_type": "string", "variables": [[TOOL_ID, "text"], [FAILURE_ID, "text"]]},
            {"groupId": "fallback-status", "group_name": "attack_status", "output_type": "string", "variables": [[TOOL_ID, "status"], [FAILURE_ID, "status"]]},
            {"groupId": "fallback-map", "group_name": "attack_mapping_text", "output_type": "string", "variables": [[TOOL_ID, "attack_mapping_text"], [FAILURE_ID, "mapping_text"]]},
            {"groupId": "fallback-warnings", "group_name": "attack_warnings", "output_type": "string", "variables": [[TOOL_ID, "warnings_text"], [FAILURE_ID, "warnings_text"]]},
        ]},
    }, height=330)

    mapped_agent = copy.deepcopy(fallback_agent)
    mapped_agent["id"] = ANSWER_AGENT_ID
    mapped_agent["position"] = mapped_agent["positionAbsolute"] = {"x": 5140, "y": 790}
    mapped_agent["data"]["title"] = "兜底 ATT&CK 证据回答 Agent"
    tools = mapped_agent["data"]["agent_parameters"]["tools"]["value"]
    mapped_agent["data"]["agent_parameters"]["tools"]["value"] = [
        item for item in tools if item.get("tool_name") != "LocalAttackMCP"
    ]
    mapped_agent["data"]["agent_parameters"]["query"]["value"] += ANSWER_RULES

    answer = shell(ANSWER_ID, 5460, 830, {
        "type": "answer", "title": "兜底 ATT&CK 显式映射回复", "answer": "{{#fallback_attack_answer_v25.text#}}", "variables": [],
    }, height=103)

    nodes.extend([intent, gate, tool, failure, result, mapped_agent, answer])
    old = f"{KNOWLEDGE_ID}-source-{FALLBACK_AGENT_ID}-target"
    graph["edges"] = [item for item in edges if item.get("id") != old]
    if len(graph["edges"]) != len(edges) - 1:
        raise RuntimeError("Fallback knowledge-to-agent edge not found")
    graph["edges"].extend([
        edge(KNOWLEDGE_ID, INTENT_CODE_ID, "knowledge-retrieval", "code"),
        edge(INTENT_CODE_ID, INTENT_GATE_ID, "code", "if-else"),
        edge(INTENT_GATE_ID, FALLBACK_AGENT_ID, "if-else", "agent", "false"),
        edge(INTENT_GATE_ID, TOOL_ID, "if-else", "tool", "true"),
        edge(TOOL_ID, RESULT_ID, "tool", "variable-aggregator"),
        edge(TOOL_ID, FAILURE_ID, "tool", "code", "fail-branch"),
        edge(FAILURE_ID, RESULT_ID, "code", "variable-aggregator"),
        edge(RESULT_ID, ANSWER_AGENT_ID, "variable-aggregator", "agent"),
        edge(ANSWER_AGENT_ID, ANSWER_ID, "agent", "answer"),
    ])

    document["app"]["name"] = str(document["app"]["name"]).replace("V2.4", "V2.5")
    document["app"]["description"] = str(document["app"].get("description") or "") + "；V2.5 为兜底安全行为增加确定性 ATT&CK 工具调用"
    OUTPUT.write_text(yaml.safe_dump(document, allow_unicode=True, sort_keys=False, width=1000), encoding="utf-8")
    return OUTPUT


if __name__ == "__main__":
    print(json.dumps({"generated": str(build())}, ensure_ascii=False))
