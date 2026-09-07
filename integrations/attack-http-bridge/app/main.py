import os
from contextlib import asynccontextmanager
from typing import Any, Dict, List

from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException

from app.mcp_client import AttackMCPClient
from app.normalizer import (
    build_evidence_for_fusion,
    dedupe_techniques,
    extract_attack_ids,
    infer_search_terms,
    normalize_domain,
    parse_formatted_objects,
    rule_based_attack_candidates,
)
from app.schemas import AttackLookupRequest, AttackMapRequest, RawMCPRequest

load_dotenv()

mcp_client = AttackMCPClient()


def check_api_key(x_api_key: str | None):
    expected = os.getenv("HTTP_API_KEY", "")
    if expected and x_api_key != expected:
        raise HTTPException(status_code=401, detail="Invalid API key")


@asynccontextmanager
async def lifespan(app: FastAPI):
    await mcp_client.start()
    yield
    await mcp_client.stop()


app = FastAPI(
    title="Local ATT&CK HTTP Bridge",
    description="把本地 MITRE ATT&CK MCP stdio 服务封装为 Dify 可调用的 HTTP 服务",
    version="0.2.0",
    lifespan=lifespan,
)


@app.get("/health")
async def health():
    try:
        tools = await mcp_client.list_tools()
        return {
            "status": "ok",
            "service": "local-attack-http-bridge",
            "mcp_status": "connected",
            "tool_count": len(tools),
            "tools_sample": tools[:20],
        }
    except Exception as exc:
        return {
            "status": "error",
            "service": "local-attack-http-bridge",
            "mcp_status": "disconnected",
            "error": str(exc),
        }


@app.post("/api/attack/lookup")
async def attack_lookup(
    req: AttackLookupRequest,
    x_api_key: str | None = Header(default=None),
):
    """
    明确知道 Txxxx 编号时使用。
    例如：T1059 是什么？
    """
    check_api_key(x_api_key)

    domain = normalize_domain(req.domain)
    attack_id = req.attack_id.upper()

    try:
        raw = await mcp_client.call_tool(
            "get_object_by_attack_id",
            {
                "attack_id": attack_id,
                "stix_type": req.stix_type,
                "domain": domain,
                "include_description": req.include_description,
            },
        )

        techniques = parse_formatted_objects(raw)

        return {
            "status": "success",
            "query_mode": "lookup_by_attack_id",
            "domain": domain,
            "attack_id": attack_id,
            "matched_techniques": techniques,
            "raw_result": raw,
            "warnings": [],
        }

    except Exception as exc:
        return {
            "status": "failed",
            "query_mode": "lookup_by_attack_id",
            "domain": domain,
            "attack_id": attack_id,
            "matched_techniques": [],
            "raw_result": None,
            "warnings": [str(exc)],
        }


@app.post("/api/attack/map")
async def attack_map(
    req: AttackMapRequest,
    x_api_key: str | None = Header(default=None),
):
    """
    核心接口：没有 Txxxx 也可以调用。
    输入安全上下文，返回可能相关的 ATT&CK 技术。
    """
    check_api_key(x_api_key)

    domain = normalize_domain(req.domain)
    top_k = max(1, min(req.top_k or 5, 10))

    direct_ids = []
    if req.direct_attack_ids:
        direct_ids.extend([x.upper() for x in req.direct_attack_ids])

    direct_ids.extend(extract_attack_ids(req.analysis_context))
    direct_ids = list(dict.fromkeys(direct_ids))

    matched = []
    potential_matched = []
    warnings = []

    async def resolve_candidate(candidate: Dict[str, Any]) -> List[Dict[str, Any]]:
        attack_id = str(candidate.get("attack_id") or "").upper().strip()
        try:
            raw = await mcp_client.call_tool(
                "get_object_by_attack_id",
                {
                    "attack_id": attack_id,
                    "stix_type": "attack-pattern",
                    "domain": domain,
                    "include_description": req.include_description,
                },
            )
            resolved = parse_formatted_objects(raw)
            for item in resolved:
                item.update({
                    "mapping_method": candidate.get("mapping_method", "unknown"),
                    "mapping_type": candidate.get("mapping_type", "observed"),
                    "confidence": float(candidate.get("confidence") or 0.0),
                    "evidence_quote": str(candidate.get("evidence_quote") or "")[:800],
                    "mapping_reason": str(candidate.get("reason") or "")[:500],
                })
            return resolved
        except Exception as exc:
            warnings.append(f"lookup {attack_id} failed: {str(exc)}")
            return []

    for attack_id in direct_ids:
        matched.extend(await resolve_candidate({
            "attack_id": attack_id,
            "mapping_method": "direct_id",
            "mapping_type": "observed",
            "confidence": 1.0,
            "evidence_quote": attack_id,
            "reason": "输入上下文中显式包含该 ATT&CK 编号。",
        }))

    search_terms: List[str] = []

    if req.search_terms_en:
        search_terms.extend(req.search_terms_en)

    search_terms.extend(infer_search_terms(req.analysis_context))
    search_terms = list(dict.fromkeys([x.strip() for x in search_terms if x.strip()]))

    rule_candidates = rule_based_attack_candidates(req.analysis_context)
    rule_ids = [item["attack_id"] for item in rule_candidates if item.get("mapping_type") == "observed"]
    potential_rule_ids = [item["attack_id"] for item in rule_candidates if item.get("mapping_type") == "potential"]
    rule_ids = [attack_id for attack_id in rule_ids if attack_id not in direct_ids]
    potential_rule_ids = [attack_id for attack_id in potential_rule_ids if attack_id not in direct_ids]

    for candidate in rule_candidates:
        if candidate["attack_id"] not in direct_ids:
            resolved = await resolve_candidate(candidate)
            if candidate.get("mapping_type") == "potential":
                potential_matched.extend(resolved)
            else:
                matched.extend(resolved)

    normalized_context = " ".join(str(req.analysis_context or "").split()).lower()
    validated_candidate_ids = []
    rejected_candidates = []
    existing_ids = set(direct_ids) | set(rule_ids) | set(potential_rule_ids)

    for model_candidate in req.candidate_mappings or []:
        attack_id = str(model_candidate.attack_id or "").upper().strip()
        quote = " ".join(str(model_candidate.evidence_quote or "").split())
        confidence = float(model_candidate.confidence or 0.0)
        reason = str(model_candidate.reason or "")

        if not extract_attack_ids(attack_id) or extract_attack_ids(attack_id)[0] != attack_id:
            rejected_candidates.append({"attack_id": attack_id, "reason": "invalid_attack_id"})
            continue
        if confidence < 0.65:
            rejected_candidates.append({"attack_id": attack_id, "reason": "low_confidence"})
            continue
        if len(quote) < 4 or quote.lower() not in normalized_context:
            rejected_candidates.append({"attack_id": attack_id, "reason": "evidence_quote_not_found"})
            continue
        if attack_id in existing_ids:
            continue

        candidate = {
            "attack_id": attack_id,
            "mapping_method": "llm_candidate_validated",
            "mapping_type": model_candidate.mapping_type,
            "confidence": confidence,
            "evidence_quote": quote,
            "reason": reason,
        }
        resolved = await resolve_candidate(candidate)
        if not resolved:
            rejected_candidates.append({"attack_id": attack_id, "reason": "attack_id_not_resolved"})
            continue
        validated_candidate_ids.append(attack_id)
        existing_ids.add(attack_id)
        if model_candidate.mapping_type == "potential":
            potential_matched.extend(resolved)
        else:
            matched.extend(resolved)

    content_fallback_used = False
    content_candidates = []

    if not matched and not potential_matched and req.allow_content_fallback:
        content_fallback_used = True
        for term in search_terms:
            try:
                raw = await mcp_client.call_tool(
                    "get_objects_by_content",
                    {
                        "content": term,
                        "object_type": "attack-pattern",
                        "domain": domain,
                        "include_description": req.include_description,
                    },
                )
                for item in parse_formatted_objects(raw):
                    item.update({
                        "mapping_method": "content_candidate",
                        "mapping_type": "unverified",
                        "confidence": 0.0,
                        "evidence_quote": "",
                        "mapping_reason": f"ATT&CK 描述包含字符串：{term}",
                    })
                    content_candidates.append(item)
            except Exception as exc:
                warnings.append(f"content search '{term}' failed: {str(exc)}")

    techniques = dedupe_techniques(matched, top_k=top_k)
    potential_techniques = dedupe_techniques(potential_matched, top_k=top_k)
    content_candidates = dedupe_techniques(content_candidates, top_k=top_k)

    if techniques:
        need_attack_mapping = True
        mapping_status = "formal_mapping"
        attack_chain_summary = "已根据可追溯的攻击行为证据形成 ATT&CK 映射，并由本地 ATT&CK MCP 完成编号校验。"
    elif potential_techniques:
        need_attack_mapping = False
        mapping_status = "potential_only"
        attack_chain_summary = "仅获得潜在相关 ATT&CK 技法，当前上下文不足以证明这些行为已经发生。"
    elif content_candidates:
        need_attack_mapping = False
        mapping_status = "content_candidates_only"
        attack_chain_summary = "仅获得字符串内容检索候选，不能作为正式 ATT&CK 映射。"
    else:
        need_attack_mapping = False
        mapping_status = "no_mapping"
        attack_chain_summary = "未检索到明确的 ATT&CK 技术映射结果。"

    evidence_for_fusion = build_evidence_for_fusion(techniques)
    if potential_techniques:
        evidence_for_fusion += "\n潜在相关技法（不能证明行为已发生）：\n" + "\n".join(
            f"- {item.get('technique_id', '')} {item.get('technique_name', '')}"
            for item in potential_techniques
        )

    return {
        "bridge_version": "0.2.0",
        "status": "success",
        "query_mode": "auto_map",
        "mapping_status": mapping_status,
        "domain": domain,
        "need_attack_mapping": need_attack_mapping,
        "direct_attack_ids": direct_ids,
        "rule_based_attack_ids": rule_ids,
        "potential_rule_based_attack_ids": potential_rule_ids,
        "validated_candidate_attack_ids": validated_candidate_ids,
        "rejected_candidates": rejected_candidates,
        "search_terms": search_terms,
        "content_fallback_used": content_fallback_used,
        "matched_techniques": techniques,
        "potential_techniques": potential_techniques,
        "content_candidates": content_candidates,
        "attack_chain_summary": attack_chain_summary,
        "evidence_for_fusion": evidence_for_fusion,
        "warnings": warnings,
    }


@app.post("/api/attack/raw-call")
async def raw_call(
    req: RawMCPRequest,
    x_api_key: str | None = Header(default=None),
):
    """
    调试用：直接调用任意 MCP 工具。
    正式给 Dify 用时，一般不开放这个接口。
    """
    check_api_key(x_api_key)

    try:
        raw = await mcp_client.call_tool(req.tool_name, req.arguments)
        return {
            "status": "success",
            "tool_name": req.tool_name,
            "raw_result": raw,
            "warnings": [],
        }
    except Exception as exc:
        return {
            "status": "failed",
            "tool_name": req.tool_name,
            "raw_result": None,
            "warnings": [str(exc)],
        }
