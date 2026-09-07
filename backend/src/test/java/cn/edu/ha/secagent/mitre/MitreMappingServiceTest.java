package cn.edu.ha.secagent.mitre;

import cn.edu.ha.secagent.config.AppProperties;
import cn.edu.ha.secagent.domain.User;
import cn.edu.ha.secagent.repository.MitreMappingRunRepository;
import cn.edu.ha.secagent.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MitreMappingServiceTest {
    @Test
    void normalizesAttackMappingAndChecksEvidence() {
        var runs = mock(MitreMappingRunRepository.class);
        var users = mock(UserRepository.class);
        var client = mock(DeepSeekMitreClient.class);
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        var properties = new AppProperties(null, null, null, null, null, null, null, null,
                new AppProperties.DeepSeek("https://api.deepseek.com", "test-key", "deepseek-v4-flash", 180, 5, 24));
        var objectMapper = new ObjectMapper();
        var service = new MitreMappingService(runs, users, client, redis, objectMapper,
                new MitreResultParser(objectMapper), new MitreKnowledgeCatalog(), properties);
        var userId = UUID.randomUUID();
        var source = "攻击者向员工发送恶意附件，员工打开附件后触发宏代码执行。";
        var json = """
                {"summary":"钓鱼附件触发执行","attackBehaviors":["发送恶意附件","员工打开附件"],
                 "attackMappings":[{"behaviorOrder":1,"tacticId":"TA0001","tacticName":"Initial Access",
                 "techniqueId":"T1566.001","techniqueName":"Spearphishing Attachment","confidence":0.93,
                 "evidence":"发送恶意附件","reasoning":"通过附件完成初始访问"}],
                 "cweMappings":[],"unmappedObservations":[]}
                """;

        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(any(String.class))).thenReturn(1L);
        when(runs.findFirstByUserIdAndMappingTypeAndInputHashAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(users.getReferenceById(userId)).thenReturn(new User());
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(client.map(userId, "ATTACK", source)).thenReturn(new DeepSeekMitreClient.ClientResult(json, 120, 80));

        var result = service.map(userId, new MitreDtos.MappingRequest(source, "ATTACK"));

        assertThat(result.attackMappings()).hasSize(1);
        assertThat(result.attackMappings().get(0).techniqueId()).isEqualTo("T1566.001");
        assertThat(result.attackMappings().get(0).identifierFormatValid()).isTrue();
        assertThat(result.attackMappings().get(0).evidenceMatched()).isTrue();
        assertThat(result.promptTokens()).isEqualTo(120);
        assertThat(result.completionTokens()).isEqualTo(80);
    }

    @Test
    void repairsMalformedFirstResponseAndCombinesUsage() {
        var fixture = fixture();
        var source = "攻击者向员工发送恶意附件，员工打开附件后执行其中宏代码。";
        var repairedJson = """
                {"summary":"钓鱼附件触发执行","attackBehaviors":["发送恶意附件"],
                 "attackMappings":[{"behaviorOrder":"1","tactic_id":"TA0001","tacticName":"Initial Access",
                 "technique_id":"T1566.001","techniqueName":"Spearphishing Attachment","confidence":"95%",
                 "evidence":"发送恶意附件","reasoning":"恶意附件用于初始访问"}],
                 "cweMappings":[],"unmappedObservations":[]}
                """;
        when(fixture.client.map(fixture.userId, "ATTACK", source))
                .thenReturn(new DeepSeekMitreClient.ClientResult("{not-json", 100, 20));
        when(fixture.client.repair(fixture.userId, "ATTACK", source, "{not-json"))
                .thenReturn(new DeepSeekMitreClient.ClientResult(repairedJson, 140, 40));

        var result = fixture.service.map(fixture.userId, new MitreDtos.MappingRequest(source, "ATTACK"));

        assertThat(result.attackMappings()).hasSize(1);
        assertThat(result.attackMappings().get(0).confidence()).isEqualTo(0.95);
        assertThat(result.promptTokens()).isEqualTo(240);
        assertThat(result.completionTokens()).isEqualTo(60);
        verify(fixture.client).repair(fixture.userId, "ATTACK", source, "{not-json");
    }

    @Test
    void skipsMalformedMappingItemInsteadOfFailingWholeResult() throws Exception {
        var parser = new MitreResultParser(new ObjectMapper());
        var result = parser.parse("""
                {"summary":"test","attackBehaviors":"执行命令","attackMappings":[
                  "bad item",
                  {"techniqueId":"T1059.001","confidence":95,"evidence":"执行命令"}
                ],"unmappedObservations":[]}
                """);

        assertThat(result.attackMappings()).hasSize(1);
        assertThat(result.attackMappings().get(0).confidence()).isEqualTo(0.95);
        assertThat(result.attackBehaviors()).containsExactly("执行命令");
    }

    private Fixture fixture() {
        var runs = mock(MitreMappingRunRepository.class);
        var users = mock(UserRepository.class);
        var client = mock(DeepSeekMitreClient.class);
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        var properties = new AppProperties(null, null, null, null, null, null, null, null,
                new AppProperties.DeepSeek("https://api.deepseek.com", "test-key", "deepseek-v4-flash", 180, 5, 24));
        var objectMapper = new ObjectMapper();
        var service = new MitreMappingService(runs, users, client, redis, objectMapper,
                new MitreResultParser(objectMapper), new MitreKnowledgeCatalog(), properties);
        var userId = UUID.randomUUID();
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(any(String.class))).thenReturn(1L);
        when(runs.findFirstByUserIdAndMappingTypeAndInputHashAndStatusAndCreatedAtAfterOrderByCreatedAtDesc(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(users.getReferenceById(userId)).thenReturn(new User());
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new Fixture(service, client, userId);
    }

    private record Fixture(MitreMappingService service, DeepSeekMitreClient client, UUID userId) {}
}
