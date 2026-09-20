package com.videoai.infra.cost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.analysis.AiUsage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class AiCostCalculatorTest {
    @Test void springLoadsDefaultPricesWithoutWorkerConfiguration() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(AiPricingProperties.class)
                .run(context->{assertNull(context.getStartupFailure());assertEquals(5,context.getBean(AiPricingProperties.class).getModels().size());});
    }
    static AiPricingProperties prices() throws Exception {
        var env=new StandardEnvironment();
        env.getPropertySources().addLast(new PropertiesPropertySource("test-prices",
                PropertiesLoaderUtils.loadProperties(new ClassPathResource("ai-pricing.properties"))));
        return Binder.get(env).bind("ai.pricing",AiPricingProperties.class).get();
    }
    @Test void propertiesBindAndTierBoundaryIsPerRequest() throws Exception {
        var config=prices();assertEquals(5,config.getModels().size());
        var calculator=new AiCostCalculator(config,new ObjectMapper());
        var snapshot=calculator.snapshot("qwen3.7-plus");
        assertEquals(new BigDecimal("0.5322880000"),calculator.calculate(snapshot,new AiUsage(262144L,1000L,null,null)).amount());
        assertEquals(new BigDecimal("1.5968700000"),calculator.calculate(snapshot,new AiUsage(262145L,1000L,null,null)).amount());
        assertEquals("INVALID_USAGE",calculator.calculate(snapshot,new AiUsage(1000001L,0L,null,null)).unknownReason());
    }
    @Test void frozenPriceUnaffectedByChangesAndMatchesActualExperiment() throws Exception {
        var config=prices();var calculator=new AiCostCalculator(config,new ObjectMapper());
        var snapshot=calculator.snapshot("qwen3.8-flash");
        config.getModels().get("qwen3.8-flash").getTiers().get(0).setInputRate(new BigDecimal("100"));
        assertEquals(new BigDecimal("0.0046157000"),calculator.calculate(snapshot,new AiUsage(4747L,303L,null,null)).amount());
        assertNotEquals(snapshot,calculator.snapshot("qwen3.8-flash"));
    }
    @Test void audioAndInputOnlyAreNotForcedIntoTextTokenFormula() throws Exception {
        var calculator=new AiCostCalculator(prices(),new ObjectMapper());
        assertEquals(new BigDecimal("0.0864875000"),calculator.calculate(calculator.snapshot("qwen-audio-3.0-asr-flash-filetrans"),
                new AiUsage(null,null,new BigDecimal("393.125"),null)).amount());
        assertEquals(new BigDecimal("0.0038530000"),calculator.calculate(calculator.snapshot("qwen3-rerank"),
                new AiUsage(7706L,null,null,null)).amount());
    }
    @Test void unknownAndInvalidNeverBecomeFree() throws Exception {
        var c=new AiCostCalculator(prices(),new ObjectMapper());var s=c.snapshot("qwen3.8-flash");
        assertEquals("MISSING_PRICE",c.calculate(c.snapshot("not-configured"),AiUsage.unknown()).unknownReason());
        assertEquals("MISSING_USAGE",c.calculate(s,new AiUsage(100L,null,null,null)).unknownReason());
        assertEquals("INVALID_USAGE",c.calculate(s,new AiUsage(-1L,0L,null,null)).unknownReason());
        assertEquals(new BigDecimal("0.0000000000"),c.calculate(s,new AiUsage(0L,0L,null,null)).amount());
    }
    @Test void visualSubcountsAreNotAddedAgain() throws Exception {
        var c=new AiCostCalculator(prices(),new ObjectMapper());
        var usage=new AiUsage(67995L,1023L,null,"{\"prompt_tokens_details\":{\"video_tokens\":67718}}");
        assertEquals(new BigDecimal("0.1441740000"),c.calculate(c.snapshot("qwen3.7-plus"),usage).amount());
    }
}
