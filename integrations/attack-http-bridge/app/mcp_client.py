import asyncio
import json
import os
from contextlib import AsyncExitStack
from typing import Any, Dict, List, Optional

from dotenv import load_dotenv
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

load_dotenv()


class AttackMCPClient:
    """
    HTTP Bridge 内部的 MCP Client。
    对外由 FastAPI 提供 HTTP；对内通过 stdio 启动并调用 mitre-attack-mcp。
    """

    def __init__(self):
        self.session: Optional[ClientSession] = None
        self.exit_stack: Optional[AsyncExitStack] = None
        self.lock = asyncio.Lock()

    async def start(self):
        command = os.getenv("ATTACK_MCP_COMMAND", "mitre-attack-mcp")
        args_raw = os.getenv("ATTACK_MCP_ARGS", "")
        args = [x.strip() for x in args_raw.split(",") if x.strip()]

        server_params = StdioServerParameters(
            command=command,
            args=args,
            env=os.environ.copy(),
        )

        self.exit_stack = AsyncExitStack()

        read_stream, write_stream = await self.exit_stack.enter_async_context(
            stdio_client(server_params)
        )

        self.session = await self.exit_stack.enter_async_context(
            ClientSession(read_stream, write_stream)
        )

        await self.session.initialize()

    async def stop(self):
        if self.exit_stack:
            await self.exit_stack.aclose()

    async def list_tools(self) -> List[str]:
        if not self.session:
            raise RuntimeError("MCP session not initialized")

        result = await self.session.list_tools()
        return [tool.name for tool in result.tools]

    async def call_tool(self, tool_name: str, arguments: Dict[str, Any]) -> Any:
        if not self.session:
            raise RuntimeError("MCP session not initialized")

        timeout = int(os.getenv("REQUEST_TIMEOUT", "60"))

        # stdio MCP 第一版先串行调用，避免并发读写混乱。
        async with self.lock:
            result = await asyncio.wait_for(
                self.session.call_tool(tool_name, arguments),
                timeout=timeout,
            )

        return self._parse_result(result)

    def _parse_result(self, result: Any) -> Any:
        """
        MCP 返回一般是 content 列表。
        如果是文本，就提取 text。
        如果文本是 JSON，就解析成 JSON。
        否则原样返回字符串。
        """
        if not hasattr(result, "content"):
            return result

        items = []

        for item in result.content:
            if hasattr(item, "text"):
                text = item.text
                try:
                    items.append(json.loads(text))
                except Exception:
                    items.append(text)
            else:
                items.append(str(item))

        if len(items) == 1:
            return items[0]

        return items
