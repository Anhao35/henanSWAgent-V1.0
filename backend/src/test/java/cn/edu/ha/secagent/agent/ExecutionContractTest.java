package cn.edu.ha.secagent.agent;

import cn.edu.ha.secagent.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionContractTest {
    final ObjectMapper mapper=new ObjectMapper();
    @Test void intentDoesNotRemoveIndicators() {
        assertEquals("READ",TaskIntent.resolve("AUTO","阅读这篇文章 https://doi.org/10.1/test"));
        assertEquals("SECURITY",TaskIntent.resolve("SECURITY","这篇论文中的恶意URL"));
        assertEquals("EXPLAIN",TaskIntent.resolve("AUTO","仅解释日志，不查询外部情报，IP 8.8.8.8"));
        assertEquals("AUTO",TaskIntent.resolve("AUTO","网络安全法对日志留存有什么规定？"));
        assertEquals("AUTO",TaskIntent.resolve("KNOWLEDGE","根据省网知识库回答"));
        assertEquals("AUTO",TaskIntent.resolve("AUTO","研判8.8.8.8"));
        assertThrows(ApiException.class,()->TaskIntent.resolve("AUTO","看看这个https://example.com"));
        assertThrows(ApiException.class,()->TaskIntent.resolve("INVALID","test"));
    }
    @Test void sseSupportsMultilineAndRejectsTruncation() throws Exception {
        var received=new ArrayList<String>();
        DifyStreamTransport.consume(new BufferedReader(new StringReader(":keepalive\n\ndata: {\"event\":\"message\",\n"+"data: \"answer\":\"中文\"}\n\n")),received::add);
        assertEquals(1,received.size());assertEquals("中文",mapper.readTree(received.get(0)).path("answer").asText());
        assertThrows(IOException.class,()->DifyStreamTransport.consume(new BufferedReader(new StringReader("data: {}")),received::add));
    }
    @Test void sourceAliasesAndNestedHashRemainSupported() throws Exception {
        assertEquals("CVE result",DifyStreamTransport.answer(mapper.readTree("{\"text1\":\"CVE result\"}")));
        assertEquals("URL result",DifyStreamTransport.answer(mapper.readTree("{\"result2\":\"URL result\"}")));
        assertEquals("hash result",DifyStreamTransport.answer(mapper.readTree("{\"result\":{\"output\":\"hash result\"}}")));
        assertEquals("",DifyStreamTransport.answer(mapper.readTree("{\"error\":\"not found\"}")));
    }
    @Test void traceDoesNotExposeInputsOutputsOrReasoning() throws Exception {
        var transport=new DifyStreamTransport(mapper,mock(RunTraceService.class));
        try {
            var state=new DifyStreamTransport.State();var received=new ArrayList<DifyClient.DifyEvent>();
            transport.accept(mapper.readTree("{\"event\":\"node_finished\",\"data\":{\"id\":\"exec-1\",\"node_id\":\"n\",\"title\":\"查询\",\"status\":\"succeeded\",\"inputs\":{\"api_key\":\"DO_NOT_EXPOSE\"},\"outputs\":{\"reasoning\":\"DO_NOT_EXPOSE\"}}}"),state,"main",received::add);
            assertFalse(mapper.writeValueAsString(received).contains("DO_NOT_EXPOSE"));
            assertEquals("exec-1",received.get(1).details().get("nodeExecutionId").toString().split(":")[2]);
        } finally { transport.close(); }
    }
    @Test void failureOutletIsNotSuccessfulQuery() throws Exception {
        var transport=new DifyStreamTransport(mapper,mock(RunTraceService.class));
        try {
            var event=mapper.readTree("{\"event\":\"workflow_finished\",\"data\":{\"status\":\"succeeded\",\"outputs\":{\"error_http\":\"provider failed\"}}}");
            assertThrows(ApiException.class,()->transport.accept(event,new DifyStreamTransport.State(),"quick",e->{}));
        } finally { transport.close(); }
    }
    @Test void reportDoesNotInventMissingEvidenceOrAssets() throws Exception {
        String text=ReportService.render("测试",mapper.readTree("[\"scope\",\"evidence\",\"remediation\",\"limitations\"]"),mapper.readTree("{\"runId\":\"test\",\"status\":\"COMPLETED\",\"evidence\":null}"));
        assertTrue(text.contains("未捕获结构化证据"));assertTrue(text.contains("待资产负责人核验"));assertFalse(text.contains("总体风险（上游原值）"));
        assertEquals(64,ReportService.sha256(text).length());
    }
}
