package com.examprep.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LLM 双模选路测试（本地 Ollama vs 云端 API / auto）")
class LlmDualModeRoutingTest {

    private List<LlmClient> createClients(AiProperties props) {
        PromptBuilder pb = new PromptBuilder(props);
        ObjectMapper om = new ObjectMapper();
        List<LlmClient> clients = new ArrayList<>(List.of(
                new OllamaLlmClient(props, pb, om),
                new OpenAiCompatLlmClient(props, pb, om),
                new DashScopeLlmClient(props, pb, om),
                new ZhipuLlmClient(props, pb, om)
        ));
        AnnotationAwareOrderComparator.sort(clients);
        return clients;
    }

    private String pickFirstAvailable(List<LlmClient> clients) {
        return clients.stream()
                .filter(LlmClient::available)
                .map(LlmClient::name)
                .findFirst()
                .orElse("extractive");
    }

    @Test
    void ollamaModeSelectsLocalOnly() {
        AiProperties props = new AiProperties();
        props.getLlm().setProvider("ollama");
        props.getLlm().setApiKey("sk-even-if-set");
        List<LlmClient> clients = createClients(props);

        assertEquals("ollama", pickFirstAvailable(clients));
        assertEquals(1, clients.stream().filter(LlmClient::available).count());
    }

    @Test
    void openaiModeRoutesToOpenAiAndDoesNotGetHijackedByOllama() {
        AiProperties props = new AiProperties();
        props.getLlm().setProvider("openai");
        props.getLlm().setOpenaiBaseUrl("https://api.deepseek.com/v1");
        props.getLlm().setOpenaiModel("deepseek-chat");
        props.getLlm().setApiKey("sk-test-key");
        List<LlmClient> clients = createClients(props);

        assertEquals("openai", pickFirstAvailable(clients));
        assertFalse(clients.stream().filter(c -> "ollama".equals(c.name())).findFirst().orElseThrow().available());
    }

    @Test
    void dashscopeAndZhipuModesRouteDirectly() {
        AiProperties dsProps = new AiProperties();
        dsProps.getLlm().setProvider("dashscope");
        dsProps.getLlm().setApiKey("sk-ds-key");
        assertEquals("dashscope", pickFirstAvailable(createClients(dsProps)));

        AiProperties zpProps = new AiProperties();
        zpProps.getLlm().setProvider("zhipu");
        zpProps.getLlm().setApiKey("zp-key");
        assertEquals("zhipu", pickFirstAvailable(createClients(zpProps)));
    }

    @Test
    void autoModePrefersApiWhenKeyPresentAndFallsBackToOllamaWithoutKey() {
        AiProperties withKey = new AiProperties();
        withKey.getLlm().setProvider("auto");
        withKey.getLlm().setApiKey("sk-auto-key");
        List<LlmClient> clientsWithKey = createClients(withKey);
        assertEquals("openai", pickFirstAvailable(clientsWithKey));
        assertTrue(clientsWithKey.stream().filter(c -> "ollama".equals(c.name())).findFirst().orElseThrow().available());

        AiProperties noKey = new AiProperties();
        noKey.getLlm().setProvider("auto");
        noKey.getLlm().setApiKey("");
        assertEquals("ollama", pickFirstAvailable(createClients(noKey)));
    }

    @Test
    void extractiveModeDisablesAllLlmClients() {
        AiProperties props = new AiProperties();
        props.getLlm().setProvider("extractive");
        props.getLlm().setApiKey("sk-ignored");
        assertEquals("extractive", pickFirstAvailable(createClients(props)));
    }
}
