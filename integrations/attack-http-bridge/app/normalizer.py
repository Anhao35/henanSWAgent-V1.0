import re
from typing import Any, Dict, List


ATTACK_ID_PATTERN = re.compile(r"\bT\d{4}(?:\.\d{3})?\b", re.IGNORECASE)


def normalize_domain(domain: str | None) -> str:
    if not domain:
        return "enterprise"

    domain = domain.lower().strip()

    if domain in ["enterprise", "ics", "mobile"]:
        return domain

    return "enterprise"


def extract_attack_ids(text: str) -> List[str]:
    if not text:
        return []

    ids = ATTACK_ID_PATTERN.findall(text)
    return list(dict.fromkeys([x.upper() for x in ids]))


def infer_search_terms(context: str) -> List[str]:
    """
    第一版做一个规则兜底。
    后面 Dify 的 LocalAttackMCP 工作流里，可以用 LLM 抽取 search_terms_en 再传进来。
    """
    if not context:
        return []

    text = context.lower()
    terms = []

    keyword_map = [
        (["钓鱼", "phishing", "邮件附件", "恶意附件"], "phishing"),
        (["powershell", "ps1", "命令执行", "脚本执行"], "PowerShell"),
        (["cmd.exe", "command execution", "命令行"], "command execution"),
        (["webshell", "web shell", "一句话木马"], "web shell"),
        (["横向移动", "lateral movement", "远程登录", "rdp", "smb", "wmic"], "lateral movement"),
        (["凭证", "密码", "credential", "mimikatz", "lsass"], "credential dumping"),
        (["c2", "command and control", "外联", "回连", "beacon"], "command and control"),
        (["计划任务", "schtasks", "scheduled task"], "scheduled task"),
        (["注册表", "registry", "自启动", "run key"], "registry run keys"),
        (["提权", "权限提升", "privilege escalation"], "privilege escalation"),
        (["利用公网应用", "exploit public-facing", "public-facing application"], "exploit public-facing application"),
        (["漏洞利用", "exploit", "cve"], "exploitation"),
        (["数据泄露", "数据窃取", "exfiltration"], "exfiltration"),
        (["防御规避", "免杀", "绕过检测", "defense evasion"], "defense evasion"),
    ]

    for keys, term in keyword_map:
        if any(k.lower() in text for k in keys):
            terms.append(term)

    return list(dict.fromkeys(terms))


def _evidence_snippet(context: str, keywords: List[str], radius: int = 90) -> str:
    """Return a short, verbatim snippet around the first matched behavior keyword."""
    original = str(context or "")
    lowered = original.lower()
    for keyword in keywords:
        index = lowered.find(keyword.lower())
        if index < 0:
            continue
        start = max(0, index - radius)
        end = min(len(original), index + len(keyword) + radius)
        return original[start:end].strip()
    return ""


def rule_based_attack_candidates(context: str) -> List[Dict[str, Any]]:
    """
    Map only observable, high-signal behaviors. Generic risk words, CVE numbers,
    CVSS and KEV status deliberately do not produce formal ATT&CK mappings.
    """
    text = str(context or "")
    lowered = text.lower()
    candidates: List[Dict[str, Any]] = []

    rules = [
        (["恶意邮件附件", "恶意 excel 附件", "恶意excel附件", "钓鱼附件", "邮件附件", "spearphishing attachment"], "T1566.001", 0.96),
        (["钓鱼链接", "邮件链接", "spearphishing link"], "T1566.002", 0.96),
        (["钓鱼", "phishing", "恶意邮件", "spearphishing"], "T1566", 0.88),
        (["powershell", ".ps1"], "T1059.001", 0.97),
        (["cmd.exe", "windows command shell", "命令提示符"], "T1059.003", 0.97),
        (["从远程服务器下载", "下载后门", "下载载荷", "download payload", "ingress tool transfer"], "T1105", 0.91),
        (["webshell", "web shell", "一句话木马"], "T1505.003", 0.97),
        (["远程桌面", "rdp"], "T1021.001", 0.94),
        (["smb", "windows admin shares", "管理共享"], "T1021.002", 0.94),
        (["wmic", "wmi", "windows management instrumentation"], "T1047", 0.95),
        (["lsass", "sekurlsa", "credential dumping", "凭证转储"], "T1003.001", 0.96),
        (["浏览器凭据", "浏览器密码", "credentials from web browsers"], "T1555.003", 0.95),
        (["计划任务", "schtasks", "scheduled task"], "T1053.005", 0.96),
        (["注册表运行键", "run key", "runonce", "注册表自启动"], "T1547.001", 0.96),
        (["创建系统服务", "恶意服务", "windows service"], "T1543.003", 0.93),
        (["进程注入", "process injection"], "T1055", 0.92),
        (["混淆脚本", "编码命令", "-encodedcommand", "obfuscated files"], "T1027", 0.92),
        (["压缩窃取数据", "archive collected data", "rar.exe", "7z.exe"], "T1560.001", 0.9),
        (["dns 隧道", "dns tunnel"], "T1071.004", 0.94),
        (["http c2", "https c2", "web protocol c2"], "T1071.001", 0.92),
        (["利用公网应用", "攻击公网应用", "exploit public-facing application"], "T1190", 0.92),
    ]

    seen = set()
    for keywords, attack_id, confidence in rules:
        if not any(keyword.lower() in lowered for keyword in keywords):
            continue
        if attack_id in seen:
            continue
        quote = _evidence_snippet(text, keywords)
        if not quote:
            continue
        quote_lower = quote.lower()
        potential_markers = [
            "可能", "可用于", "可被用于", "理论上", "潜在", "尚未发现",
            "may ", "might ", "could ", "potential", "capable of",
        ]
        is_vulnerability_capability = "漏洞" in quote and any(
            marker in quote for marker in ["允许攻击者", "可导致", "可以使攻击者"]
        )
        mapping_type = "potential" if (
            any(marker in quote_lower for marker in potential_markers)
            or is_vulnerability_capability
        ) else "observed"
        seen.add(attack_id)
        candidates.append({
            "attack_id": attack_id,
            "evidence_quote": quote,
            "confidence": min(confidence, 0.82) if mapping_type == "potential" else confidence,
            "mapping_type": mapping_type,
            "mapping_method": "rule",
            "reason": "命中潜在行为规则，尚不能证明行为已发生。" if mapping_type == "potential" else "命中高置信攻击行为规则。",
        })

    # Prefer a precise sub-technique over its parent when both matched.
    precise_parents = {item["attack_id"].split(".")[0] for item in candidates if "." in item["attack_id"]}
    return [item for item in candidates if item["attack_id"] not in precise_parents]


def rule_based_attack_mapping(context: str, search_terms: List[str] | None = None) -> List[str]:
    """Backward-compatible ID-only view used by older callers."""
    return [item["attack_id"] for item in rule_based_attack_candidates(context)]


def parse_formatted_objects(raw: Any) -> List[Dict[str, str]]:
    """
    解析 server.py 里的 format_objects 输出格式：
    Name: xxx
    ID: T1059
    STIX ID: attack-pattern--...
    Description: ...
    ---
    Name: yyy
    """
    if raw is None:
        return []

    if isinstance(raw, list):
        text = "\n---\n".join([str(x) for x in raw])
    else:
        text = str(raw)

    if not text.strip():
        return []

    blocks = [b.strip() for b in text.split("---") if b.strip()]
    results = []

    for block in blocks:
        item = {
            "technique_id": "",
            "technique_name": "",
            "stix_id": "",
            "description": "",
            "raw": block,
        }

        name_match = re.search(r"^Name:\s*(.+)$", block, re.MULTILINE)
        id_match = re.search(r"^ID:\s*(T\d{4}(?:\.\d{3})?)$", block, re.MULTILINE | re.IGNORECASE)
        stix_match = re.search(r"^STIX ID:\s*(.+)$", block, re.MULTILINE)
        desc_match = re.search(r"^Description:\s*([\s\S]+)$", block, re.MULTILINE)

        if name_match:
            item["technique_name"] = name_match.group(1).strip()

        if id_match:
            item["technique_id"] = id_match.group(1).strip().upper()

        if stix_match:
            item["stix_id"] = stix_match.group(1).strip()

        if desc_match:
            item["description"] = desc_match.group(1).strip()

        if item["technique_id"]:
            results.append(item)

    return results


def dedupe_techniques(items: List[Dict[str, str]], top_k: int = 5) -> List[Dict[str, str]]:
    seen = set()
    output = []

    for item in items:
        tid = item.get("technique_id", "")
        if not tid or tid in seen:
            continue

        seen.add(tid)
        output.append(item)

        if len(output) >= top_k:
            break

    return output


def build_evidence_for_fusion(techniques: List[Dict[str, str]]) -> str:
    if not techniques:
        return "本地 ATT&CK MCP 未返回明确技术映射结果。最终报告不得编造 ATT&CK 技术编号，应标注该部分证据不足。"

    lines = ["本地 ATT&CK MCP 返回以下技战术映射结果："]

    for technique in techniques:
        tid = technique.get("technique_id", "")
        name = technique.get("technique_name", "")
        lines.append(f"- {tid} {name}")

    return "\n".join(lines)
