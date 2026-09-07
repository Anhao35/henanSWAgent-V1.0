import os
import unittest
from unittest.mock import AsyncMock, patch

from app.main import attack_map, mcp_client
from app.schemas import AttackCandidate, AttackMapRequest


def formatted(attack_id: str) -> str:
    return f"Name: Technique {attack_id}\nID: {attack_id}\nSTIX ID: attack-pattern--{attack_id.lower()}"


class MappingRegressionTests(unittest.IsolatedAsyncioTestCase):
    async def call(self, request: AttackMapRequest):
        async def fake_call(tool_name, arguments):
            if tool_name == "get_object_by_attack_id":
                return formatted(arguments["attack_id"])
            if tool_name == "get_objects_by_content":
                return formatted("T1021.005")
            raise AssertionError(tool_name)

        with patch.dict(os.environ, {"HTTP_API_KEY": ""}), patch.object(
            mcp_client, "call_tool", new=AsyncMock(side_effect=fake_call)
        ):
            return await attack_map(request, None)

    async def test_observable_phishing_and_powershell_use_rules(self):
        result = await self.call(AttackMapRequest(
            analysis_context="攻击者向财务人员发送恶意 Excel 附件，随后使用 PowerShell 下载载荷并执行。"
        ))
        self.assertEqual(result["mapping_status"], "formal_mapping")
        self.assertFalse(result["content_fallback_used"])
        self.assertEqual(
            [item["technique_id"] for item in result["matched_techniques"]],
            ["T1566.001", "T1059.001", "T1105"],
        )
        self.assertTrue(all(item["evidence_quote"] for item in result["matched_techniques"]))

    async def test_generic_cve_does_not_become_attack_mapping(self):
        result = await self.call(AttackMapRequest(
            analysis_context="CVE-2024-3400，CVSS 10.0，已列入 CISA KEV，需要安装补丁。"
        ))
        self.assertEqual(result["mapping_status"], "no_mapping")
        self.assertEqual(result["matched_techniques"], [])
        self.assertEqual(result["content_candidates"], [])
        self.assertFalse(result["content_fallback_used"])

    async def test_validated_llm_candidate_requires_verbatim_evidence(self):
        quote = "通过伪造登录页面收集用户凭据"
        result = await self.call(AttackMapRequest(
            analysis_context=f"告警显示攻击者{quote}，随后尝试登录。",
            candidate_mappings=[AttackCandidate(
                attack_id="T1056.002",
                evidence_quote=quote,
                confidence=0.86,
                mapping_type="observed",
                reason="输入描述了凭据钓取行为。",
            )],
        ))
        self.assertEqual(result["mapping_status"], "formal_mapping")
        self.assertEqual(result["validated_candidate_attack_ids"], ["T1056.002"])
        self.assertEqual(result["matched_techniques"][0]["mapping_method"], "llm_candidate_validated")

    async def test_hallucinated_evidence_quote_is_rejected(self):
        result = await self.call(AttackMapRequest(
            analysis_context="这里只提供一个恶意 IP 的信誉查询结果。",
            candidate_mappings=[AttackCandidate(
                attack_id="T1059.001",
                evidence_quote="攻击者执行了 PowerShell",
                confidence=0.99,
                mapping_type="observed",
            )],
        ))
        self.assertEqual(result["mapping_status"], "no_mapping")
        self.assertEqual(result["matched_techniques"], [])
        self.assertEqual(result["rejected_candidates"][0]["reason"], "evidence_quote_not_found")

    async def test_potential_mapping_is_not_formal_evidence(self):
        quote = "该漏洞可能允许攻击者利用公网应用获得初始访问"
        result = await self.call(AttackMapRequest(
            analysis_context=quote,
            candidate_mappings=[AttackCandidate(
                attack_id="T1190",
                evidence_quote=quote,
                confidence=0.8,
                mapping_type="potential",
            )],
        ))
        self.assertEqual(result["mapping_status"], "potential_only")
        self.assertFalse(result["need_attack_mapping"])
        self.assertEqual(result["matched_techniques"], [])
        self.assertEqual(result["potential_techniques"][0]["technique_id"], "T1190")

    async def test_content_fallback_never_becomes_formal_mapping(self):
        result = await self.call(AttackMapRequest(
            analysis_context="CVE-2024-3400 漏洞信息",
            search_terms_en=["exploitation"],
            allow_content_fallback=True,
        ))
        self.assertEqual(result["mapping_status"], "content_candidates_only")
        self.assertTrue(result["content_fallback_used"])
        self.assertEqual(result["matched_techniques"], [])
        self.assertEqual(result["content_candidates"][0]["mapping_method"], "content_candidate")


if __name__ == "__main__":
    unittest.main()
