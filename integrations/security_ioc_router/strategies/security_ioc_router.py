import json
import re
from collections.abc import Generator
from typing import Any

from pydantic import BaseModel

from dify_plugin.entities.agent import AgentInvokeMessage
from dify_plugin.interfaces.agent import AgentModelConfig, AgentStrategy, ToolEntity


class SecurityParams(BaseModel):
    model: AgentModelConfig
    tools: list[ToolEntity] | None = None
    query: str = ""
    maximum_iterations: int = 3


class SecurityIocRouterAgentStrategy(AgentStrategy):
    """
    Security IOC Router + Evidence Fusion Agent Strategy

    v0.2-route-planner-for-workflow-tools:
    1. 确定性识别 IOC：IP / Domain / URL / Hash / CVE
    2. 从 Agent 节点已启用工具中识别工作流工具类型
    3. 按 IOC 类型生成 route_plan
    4. 当前阶段只做路由规划，不真实执行工具
    """

    def _invoke(self, parameters: dict[str, Any]) -> Generator[AgentInvokeMessage, None, None]:
        params = SecurityParams(**parameters)

        if not params.query:
            yield self.create_text_message(
                text=json.dumps(
                    {
                        "strategy": "Security IOC Router + Evidence Fusion",
                        "version": "v0.2-domain-fix-20260518",
                        "status": "missing_query",
                        "message": "没有收到 query。请在 Agent 节点中把 Query / 用户输入绑定到开始节点的用户输入变量。",
                        "received_parameter_keys": list(parameters.keys()),
                    },
                    ensure_ascii=False,
                    indent=2,
                )
            )
            return

        tools = params.tools or []
        iocs = self.extract_iocs(params.query)
        flags = self.build_flags(iocs)
        tool_inventory = self.build_tool_inventory(tools)
        route_plan = self.build_route_plan(tools, iocs)
        unmatched_iocs = self.find_unmatched_iocs(iocs, route_plan)

        result = {
            "strategy": "Security IOC Router + Evidence Fusion",
            "version": "v0.2-route-planner-for-workflow-tools",
            "mode": "route_plan_only",
            "query": params.query,
            "iocs": iocs,
            "flags": flags,
            "tool_count": len(tools),
            "tool_inventory": tool_inventory,
            "route_plan": route_plan,
            "unmatched_iocs": unmatched_iocs,
            "next_step_suggestion": {
                "description": "下游代码节点可解析 route_plan，然后按 ioc_type 分流到对应工作流工具。",
                "branch_examples": {
                    "has_ip": "调用 IOC查询-IP查询（VT）v0.1 / IOC查询-微步信誉IP查询v0.2",
                    "has_domain": "调用 IOC查询-域名查询v0.2",
                    "has_url": "调用 IOC查询-URL查询v0.2",
                    "has_cve": "调用 CVE-漏洞查询v0.2",
                    "has_hash": "调用文件 Hash 查询工具，如果当前没有工具则进入 unmatched_iocs",
                },
            },
            "message": "v0.2 已生成 IOC 类型到工作流工具的确定性路由计划。当前阶段不真实执行工具。",
        }

        yield self.create_text_message(
            text=json.dumps(result, ensure_ascii=False, indent=2)
        )

    # ------------------------------------------------------------------
    # 1. IOC 提取
    # ------------------------------------------------------------------

    def extract_iocs(self, text: str) -> dict[str, list[str]]:
        urls = self.extract_urls(text)
        ips = self.extract_ips(text)
        cves = self.extract_cves(text)
        hashes = self.extract_hashes(text)
        domains = self.extract_domains(text, urls)

        return {
            "ips": ips,
            "domains": domains,
            "urls": urls,
            "hashes": hashes,
            "cves": cves,
        }



    def extract_ips(self, text: str) -> list[str]:
        pattern = r"(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])"
        candidates = re.findall(pattern, text)

        valid = []
        for ip in candidates:
            parts = ip.split(".")
            if all(part.isdigit() and 0 <= int(part) <= 255 for part in parts):
                valid.append(ip)

        return list(dict.fromkeys(valid))

    def extract_urls(self, text: str) -> list[str]:
        pattern = r"https?://[^\s，。；;、]+"
        urls = re.findall(pattern, text, flags=re.IGNORECASE)
        return list(dict.fromkeys([url.rstrip("。；;，,、") for url in urls]))

    def extract_cves(self, text: str) -> list[str]:
        pattern = r"(?<![A-Za-z0-9-])CVE-\d{4}-\d{4,7}(?![A-Za-z0-9-])"
        cves = re.findall(pattern, text, flags=re.IGNORECASE)
        return list(dict.fromkeys([cve.upper() for cve in cves]))

    def extract_hashes(self, text: str) -> list[str]:
        # MD5 / SHA1 / SHA256
        pattern = r"\b[a-fA-F0-9]{32}\b|\b[a-fA-F0-9]{40}\b|\b[a-fA-F0-9]{64}\b"
        return list(dict.fromkeys(re.findall(pattern, text)))

    def extract_domains(self, text: str, urls: list[str]) -> list[str]:
        """
        提取域名，兼容中文上下文：
        例如：查询域名baidu.com、baidu.com是否有风险，均可识别。
        """
        cleaned = text

        # 避免 URL 中的域名重复进入 domains
        for url in urls:
            cleaned = cleaned.replace(url, " ")

        # 不使用 \b，改用“前后不能是域名组成字符”的方式，兼容中文紧挨域名的情况
        pattern = r"(?<![A-Za-z0-9.-])(?:[A-Za-z0-9-]+\.)+[A-Za-z]{2,}(?![A-Za-z0-9.-])"
        candidates = re.findall(pattern, cleaned)

        blacklist_suffix = {
         ".png", ".jpg", ".jpeg", ".gif", ".zip",
            ".exe", ".dll", ".doc", ".docx", ".pdf",
            ".xlsx", ".csv"
        }

        result = []
        for domain in candidates:
            d = domain.lower().strip().rstrip("。；;，,、")
            if any(d.endswith(suffix) for suffix in blacklist_suffix):
             continue
            result.append(d)

        return list(dict.fromkeys(result))

    def build_flags(self, iocs: dict[str, list[str]]) -> dict[str, bool]:
        return {
            "has_ip": bool(iocs.get("ips")),
            "has_domain": bool(iocs.get("domains")),
            "has_url": bool(iocs.get("urls")),
            "has_hash": bool(iocs.get("hashes")),
            "has_cve": bool(iocs.get("cves")),
        }

    # ------------------------------------------------------------------
    # 2. 工具信息读取
    # ------------------------------------------------------------------

    def build_tool_inventory(self, tools: list[ToolEntity]) -> list[dict[str, Any]]:
        inventory = []

        for tool in tools:
            inventory.append(
                {
                    "tool_name": self.safe_get_tool_name(tool),
                    "tool_provider": self.safe_get_tool_provider(tool),
                    "matched_categories": self.detect_tool_categories(tool),
                    "parameters": self.safe_get_tool_parameters(tool),
                    "tool_search_text": self.get_tool_search_text(tool),
                }
            )

        return inventory

    def safe_get_tool_name(self, tool: ToolEntity) -> str:
        try:
            return str(tool.identity.name)
        except Exception:
            return ""

    def safe_get_tool_provider(self, tool: ToolEntity) -> str:
        try:
            return str(tool.identity.provider)
        except Exception:
            return ""

    def safe_get_tool_parameters(self, tool: ToolEntity) -> list[str]:
        names = []

        try:
            parameters = tool.parameters or []
        except Exception:
            parameters = []

        for parameter in parameters:
            name = getattr(parameter, "name", None)
            if name:
                names.append(str(name))

        return names

    def get_tool_search_text(self, tool: ToolEntity) -> str:
        """
        尽量把工具名称、供应商、描述、参数名、标签全部转成字符串，
        便于兼容 Dify 工作流工具的中文名称。
        """
        parts = []

        try:
            parts.append(str(tool.identity.name))
        except Exception:
            pass

        try:
            parts.append(str(tool.identity.provider))
        except Exception:
            pass

        try:
            label = getattr(tool.identity, "label", "")
            parts.append(str(label))
        except Exception:
            pass

        try:
            description = getattr(tool.identity, "description", "")
            parts.append(str(description))
        except Exception:
            pass

        try:
            parameters = tool.parameters or []
            for parameter in parameters:
                parts.append(str(getattr(parameter, "name", "")))
                parts.append(str(getattr(parameter, "label", "")))
                parts.append(str(getattr(parameter, "human_description", "")))
                parts.append(str(getattr(parameter, "llm_description", "")))
        except Exception:
            pass

        return " ".join([p for p in parts if p]).lower()

    # ------------------------------------------------------------------
    # 3. 工具分类：把工作流工具识别成 IP / Domain / URL / CVE / Hash
    # ------------------------------------------------------------------

    def detect_tool_categories(self, tool: ToolEntity) -> list[str]:
        tool_name = self.safe_get_tool_name(tool).lower()
        tool_text = self.get_tool_search_text(tool)

        categories = []

        # 状态检查类工具不参与 IOC 路由
        if "server_status" in tool_name or "server_status" in tool_text:
            return ["status"]

        # IP 工具：微步 IP、VT IP、安恒 IP、任意 IP 查询工作流
        if any(keyword in tool_text for keyword in [
            "query_threatbook_ip",
            "query_vt_ip",
            "query_ip_threat_intel",
            "ioc查询-ip查询",
            "ioc查询-ip查询（vt）",
            "ip查询",
            "ip 查询",
            "微步信誉ip",
            "微步",
            "threatbook",
            "virusTotal".lower(),
            "vt",
            "ip reputation",
            "ip threat",
            "ip威胁",
            "ip 威胁",
            "ip信誉",
            "ip 信誉",
        ]):
            categories.append("ips")

        # 域名工具
        if any(keyword in tool_text for keyword in [
            "query_domain_intel",
            "query_domain_threat_intel",
            "ioc查询-域名查询",
            "域名查询",
            "域名",
            "domain",
            "domain intel",
            "domain threat",
        ]):
            categories.append("domains")

        # URL 工具
        if any(keyword in tool_text for keyword in [
            "query_url_intel",
            "query_url_threat_intel",
            "ioc查询-url查询",
            "url查询",
            "url 查询",
            "url",
            "网址",
            "链接",
        ]):
            categories.append("urls")

        # 文件 Hash 工具
        if any(keyword in tool_text for keyword in [
            "query_file_threat_intel",
            "query_file_intel",
            "file threat",
            "file intel",
            "hash",
            "sha256",
            "sha1",
            "md5",
            "文件",
            "哈希",
            "样本",
        ]):
            categories.append("hashes")

        # CVE / 漏洞工具
        if any(keyword in tool_text for keyword in [
            "query_cve_intel",
            "cve-漏洞查询",
            "cve查询",
            "cve 查询",
            "cve",
            "漏洞查询",
            "漏洞",
            "vulnerability",
        ]):
            categories.append("cves")

        # Google/搜索类工具：只作为 CVE 兜底工具，不默认处理 IP/域名/URL
        if any(keyword in tool_text for keyword in [
            "google_search",
            "google",
            "search",
            "搜索",
        ]):
            if "cves" not in categories:
                categories.append("cves")

        return list(dict.fromkeys(categories))

    def tool_matches_ioc_type(self, tool: ToolEntity, ioc_type: str) -> bool:
        categories = self.detect_tool_categories(tool)

        if "status" in categories:
            return False

        return ioc_type in categories

    # ------------------------------------------------------------------
    # 4. 路由计划生成
    # ------------------------------------------------------------------

    def build_route_plan(
        self,
        tools: list[ToolEntity],
        iocs: dict[str, list[str]],
    ) -> list[dict[str, Any]]:
        route_plan = []

        for ioc_type, values in iocs.items():
            for value in values:
                matched_tools = []

                for tool in tools:
                    if not self.tool_matches_ioc_type(tool, ioc_type):
                        continue

                    matched_tools.append(
                        {
                            "tool_name": self.safe_get_tool_name(tool),
                            "tool_provider": self.safe_get_tool_provider(tool),
                            "tool_categories": self.detect_tool_categories(tool),
                            "args": self.guess_tool_args(tool, ioc_type, value),
                        }
                    )

                if matched_tools:
                    route_plan.append(
                        {
                            "ioc_type": self.normalize_ioc_type(ioc_type),
                            "ioc_type_raw": ioc_type,
                            "ioc": value,
                            "target_tool_type": self.get_target_tool_type(ioc_type),
                            "recommended_tools": [item["tool_name"] for item in matched_tools],
                            "matched_tools": matched_tools,
                            "primary_args": matched_tools[0]["args"] if matched_tools else self.default_args(ioc_type, value),
                        }
                    )

        return route_plan

    def normalize_ioc_type(self, ioc_type: str) -> str:
        mapping = {
            "ips": "ip",
            "domains": "domain",
            "urls": "url",
            "hashes": "hash",
            "cves": "cve",
        }
        return mapping.get(ioc_type, ioc_type)

    def get_target_tool_type(self, ioc_type: str) -> str:
        mapping = {
            "ips": "ip_reputation",
            "domains": "domain_intel",
            "urls": "url_intel",
            "hashes": "file_hash_intel",
            "cves": "cve_intel",
        }
        return mapping.get(ioc_type, "unknown")

    def find_unmatched_iocs(
        self,
        iocs: dict[str, list[str]],
        route_plan: list[dict[str, Any]],
    ) -> list[dict[str, str]]:
        matched = set()

        for item in route_plan:
            matched.add((item.get("ioc_type_raw"), item.get("ioc")))

        unmatched = []
        for ioc_type, values in iocs.items():
            for value in values:
                if not value:
                    continue
                if (ioc_type, value) not in matched:
                    unmatched.append(
                        {
                            "ioc_type": self.normalize_ioc_type(ioc_type),
                            "ioc_type_raw": ioc_type,
                            "ioc": value,
                            "target_tool_type": self.get_target_tool_type(ioc_type),
                            "reason": "当前 Agent 节点工具列表中没有匹配该 IOC 类型的工作流工具。",
                        }
                    )

        return unmatched

    # ------------------------------------------------------------------
    # 5. 参数映射
    # ------------------------------------------------------------------

    def guess_tool_args(
        self,
        tool: ToolEntity,
        ioc_type: str,
        ioc: str,
    ) -> dict[str, Any]:
        param_names = self.safe_get_tool_parameters(tool)

        candidates_by_type = {
            "ips": ["ip", "resource", "ioc", "query", "indicator", "input"],
            "domains": ["domain", "resource", "ioc", "query", "indicator", "input"],
            "urls": ["url", "resource", "ioc", "query", "indicator", "input"],
            "hashes": ["hash", "file_hash", "sha256", "sha1", "md5", "resource", "ioc", "query", "indicator", "input"],
            "cves": ["cve", "cve_id", "query", "resource", "vulnerability", "keyword", "input"],
        }

        candidates = candidates_by_type.get(ioc_type, ["query", "resource", "ioc", "input"])

        for name in candidates:
            if name in param_names:
                return {name: ioc}

        if param_names:
            return {param_names[0]: ioc}

        return self.default_args(ioc_type, ioc)

    def default_args(self, ioc_type: str, ioc: str) -> dict[str, Any]:
        if ioc_type == "cves":
            return {"query": ioc}
        return {"resource": ioc}
