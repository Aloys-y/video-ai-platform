package com.videoai.worker.service.provider;

import com.alibaba.dashscope.aigc.multimodalconversation.*;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.protocol.ConnectionOptions;
import com.alibaba.dashscope.utils.Constants;
import com.videoai.worker.config.DashScopeConfig;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DashScopeVideoProviderTest {
    @Test void concurrentRequestsUseIndependentParametersAndOptionsWithoutStaticMutation() throws Exception {
        var config = new DashScopeConfig(); config.setApiKey("test");
        var previous = Constants.connectionConfigurations;
        Set<ConnectionOptions> options = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<MultiModalConversationParam> params = Collections.newSetFromMap(new IdentityHashMap<>());
        var barrier = new CyclicBarrier(2);
        var provider = new DashScopeVideoProvider(config) {
            @Override protected MultiModalConversationResult invoke(MultiModalConversationParam param, ConnectionOptions option) throws Exception {
                synchronized (options) { options.add(option); params.add(param); }
                assertFalse(option.isUseDefaultClient()); assertEquals(Duration.ofSeconds(300), option.getReadTimeout());
                assertEquals(4096, param.getMaxTokens()); assertEquals(false, param.getEnableThinking());
                barrier.await(2, TimeUnit.SECONDS);
                var choice = new MultiModalConversationOutput.Choice(); choice.setFinishReason("stop");
                choice.setMessage(MultiModalMessage.builder().role("assistant").content(List.of(Map.of("text", "ok"))).build());
                var output = new MultiModalConversationOutput(); output.setChoices(List.of(choice));
                var result = new com.google.gson.Gson().fromJson("{}", MultiModalConversationResult.class);
                result.setOutput(output); result.setRequestId("id");
                return result;
            }
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> provider.callDetailed("https://a.example/video", "A"));
            var b = pool.submit(() -> provider.callDetailed("https://b.example/video", "B"));
            assertEquals("ok", a.get().text()); assertEquals("stop", b.get().finishReason());
        } finally { pool.shutdownNow(); }
        assertEquals(2, params.size()); assertEquals(2, options.size()); assertSame(previous, Constants.connectionConfigurations);
        assertFalse(provider.segmentSettings().toString().contains("apiKey"));
    }
}
