from typing import List, Literal, Optional

from pydantic import BaseModel, Field


class AttackCandidate(BaseModel):
    attack_id: str = Field(..., description="候选 ATT&CK 编号，例如 T1059.001")
    evidence_quote: str = Field(
        default="",
        description="必须来自 analysis_context 的原文证据片段",
    )
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)
    mapping_type: Literal["observed", "potential"] = Field(default="observed")
    reason: str = Field(default="", description="候选映射理由，不作为事实证据")


class AttackMapRequest(BaseModel):
    analysis_context: str = Field(
        ...,
        description="安全上下文，可以包含用户原始输入、文件解析结果、图片识别结果、IOC查询摘要、告警日志等",
    )
    search_terms_en: Optional[List[str]] = Field(
        default_factory=list,
        description="可选，Dify上游LLM抽取出的英文检索词，例如 phishing, PowerShell, credential dumping",
    )
    direct_attack_ids: Optional[List[str]] = Field(
        default_factory=list,
        description="可选，输入中已经明确出现的ATT&CK编号，例如 T1059、T1566",
    )
    candidate_mappings: Optional[List[AttackCandidate]] = Field(
        default_factory=list,
        description="上游行为提取模型给出的候选；Bridge 会校验编号、置信度和原文证据",
    )
    allow_content_fallback: bool = Field(
        default=False,
        description="是否执行 ATT&CK 描述字符串检索。结果只作为人工候选，不进入正式映射",
    )
    domain: Optional[str] = Field(
        default="enterprise",
        description="ATT&CK领域，可选 enterprise、ics、mobile",
    )
    top_k: Optional[int] = Field(
        default=5,
        description="最多返回多少个匹配技术",
    )
    include_description: Optional[bool] = Field(
        default=False,
        description="是否返回较长描述。给Dify建议默认False，避免上下文太长",
    )


class AttackLookupRequest(BaseModel):
    attack_id: str = Field(..., description="ATT&CK编号，例如 T1059、T1566、T1003")
    stix_type: str = Field(default="attack-pattern", description="默认查询攻击技术 attack-pattern")
    domain: Optional[str] = Field(default="enterprise", description="enterprise、ics、mobile")
    include_description: Optional[bool] = Field(default=True, description="是否包含描述")


class RawMCPRequest(BaseModel):
    tool_name: str = Field(..., description="MCP工具名称")
    arguments: dict = Field(default_factory=dict, description="MCP工具参数")
