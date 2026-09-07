"""Structural regression checks for the generated V2.4 main Dify DSL."""

from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
V23 = ROOT / "dify-generated" / "省网智能体-平台意图分流-V2.3.yml"
V24 = ROOT / "dify-generated" / "省网智能体-平台意图分流-V2.4.yml"
BASE_CONTEXT_ID = "1779276774206"
EVIDENCE_BUS_ID = "1782156878921"
MERGE_ID = "platform_attack_context_merge_v2"
ATTACK_TOOL_ID = "1783259406677"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


old = yaml.safe_load(V23.read_text(encoding="utf-8-sig"))
new = yaml.safe_load(V24.read_text(encoding="utf-8"))
old_graph = old["workflow"]["graph"]
graph = new["workflow"]["graph"]
nodes = graph["nodes"]
edges = graph["edges"]
by_id = {str(node["id"]): node for node in nodes}

require(len(nodes) == len(old_graph["nodes"]) + 1, "V2.4 must add exactly one node")
require(len(edges) == len(old_graph["edges"]) + 1, "V2.4 must add exactly one net edge")
require(len(by_id) == len(nodes), "Node IDs are not unique")

for item in edges:
    require(str(item["source"]) in by_id, f"Missing edge source: {item['source']}")
    require(str(item["target"]) in by_id, f"Missing edge target: {item['target']}")

pairs = {(str(item["source"]), str(item["target"])) for item in edges}
require((EVIDENCE_BUS_ID, MERGE_ID) in pairs, "Evidence Bus does not feed the merge node")
require((MERGE_ID, ATTACK_TOOL_ID) in pairs, "Merge node does not feed LocalAttackMCP")
require((EVIDENCE_BUS_ID, ATTACK_TOOL_ID) not in pairs, "Old Evidence Bus bypass edge remains")

merge = by_id[MERGE_ID]["data"]
selectors = {item["variable"]: item["value_selector"] for item in merge["variables"]}
require(selectors["base_context"] == [BASE_CONTEXT_ID, "analysis_context"], "Original behavior context is missing")
require(
    selectors["evidence_context"] == [EVIDENCE_BUS_ID, "f_llm_evidence_context"],
    "Evidence Bus context is missing",
)
require("CVSS" in merge["code"] and "potential" in merge["code"], "Mapping boundary is incomplete")

tool_value = by_id[ATTACK_TOOL_ID]["data"]["tool_parameters"]["analysis_context"]["value"]
require(tool_value == "{{#platform_attack_context_merge_v2.attack_context#}}", "Tool still receives the wrong context")

final = next(node for node in nodes if node["data"].get("title") == "最终总结LLM")
prompt = "\n".join(
    item.get("text", "") for item in final["data"].get("prompt_template", []) if item.get("role") == "system"
)
for phrase in ["mapping_status = formal_mapping", "potential_only", "content_candidates_only", "evidence_quote"]:
    require(phrase in prompt, f"Final prompt lacks V2 semantic guardrail: {phrase}")

require("V2.4" in new["app"]["name"], "App name was not advanced to V2.4")
print(f"PASS: {V24}")
print(f"nodes={len(nodes)}, edges={len(edges)}, source_nodes={len(old_graph['nodes'])}")
