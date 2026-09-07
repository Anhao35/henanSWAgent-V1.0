"""Structural regression checks for the generated LocalAttackMCP V2 DSL."""

from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "dify-generated" / "LocalAttackMCP-V2.yml"
NORMALIZE_ID = "1779279825816"
EXTRACTOR_ID = "local_attack_behavior_extractor_v2"
PAYLOAD_ID = "1779280569364"
CLEANER_ID = "1779267509398"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


document = yaml.safe_load(PATH.read_text(encoding="utf-8"))
graph = document["workflow"]["graph"]
nodes = graph["nodes"]
edges = graph["edges"]
by_id = {str(node["id"]): node for node in nodes}

require(len(by_id) == len(nodes), "Node IDs are not unique")
for item in edges:
    require(str(item["source"]) in by_id, f"Missing edge source: {item['source']}")
    require(str(item["target"]) in by_id, f"Missing edge target: {item['target']}")

edge_pairs = {(str(item["source"]), str(item["target"])) for item in edges}
require((NORMALIZE_ID, EXTRACTOR_ID) in edge_pairs, "Extractor is not after normalization")
require((EXTRACTOR_ID, PAYLOAD_ID) in edge_pairs, "Payload is not after extractor")
require((NORMALIZE_ID, PAYLOAD_ID) not in edge_pairs, "Old bypass edge still exists")

prompt = "\n".join(
    item.get("text", "") for item in by_id[EXTRACTOR_ID]["data"].get("prompt_template", [])
)
require("exact verbatim substring" in prompt, "Prompt does not require verbatim evidence")
require("CVSS" in prompt and "not observed ATT&CK behavior" in prompt, "Prompt lacks IOC/CVE guardrail")

payload_code = by_id[PAYLOAD_ID]["data"]["code"]
require('"candidate_mappings"' in payload_code, "Candidate mappings are not sent")
require('"allow_content_fallback": False' in payload_code, "Content fallback is not disabled")
require("quote not in context" in payload_code, "Payload does not enforce exact-quote validation")

cleaner_code = by_id[CLEANER_ID]["data"]["code"]
for field in ["mapping_status", "potential_techniques", "content_candidates", "rejected_candidates"]:
    require(field in cleaner_code, f"Cleaner does not preserve {field}")

end_outputs = {item["variable"] for item in next(
    node for node in nodes if node["data"].get("type") == "end"
)["data"]["outputs"]}
for field in ["mapping_status", "potential_mapping_count", "potential_technique_ids", "rejected_candidates_json"]:
    require(field in end_outputs, f"End node does not expose {field}")

print(f"PASS: {PATH}")
print(f"nodes={len(nodes)}, edges={len(edges)}, outputs={len(end_outputs)}")
