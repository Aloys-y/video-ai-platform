package com.videoai.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.mock.env.MockEnvironment;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisModePolicyTest {
    @Test void requiresBothSwitchAndWhitelistAndBindsEmptyDefault() {
        var policy = new AnalysisModePolicy(); policy.setPrefilterUserIds(Set.of(7L));
        assertEquals("DIRECT_VIDEO", policy.forUser(7L));
        policy.setPrefilterEnabled(true);
        assertEquals("AUDIO_PREFILTER", policy.forUser(7L)); assertEquals("DIRECT_VIDEO", policy.forUser(8L));
        var environment = new MockEnvironment().withProperty("videoai.analysis.prefilter-enabled", "true")
                .withProperty("videoai.analysis.prefilter-user-ids", "");
        var bound = Binder.get(environment).bind("videoai.analysis", Bindable.of(AnalysisModePolicy.class)).get();
        assertEquals("DIRECT_VIDEO", bound.forUser(7L));
    }
}
