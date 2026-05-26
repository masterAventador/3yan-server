package com.sanyan.proactive.internal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ProactivePropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(EnableProps.class);

    @EnableConfigurationProperties(ProactiveProperties.class)
    static class EnableProps {}

    @Test
    void should_bind_defaults_when_no_properties_given() {
        runner.run(ctx -> {
            ProactiveProperties props = ctx.getBean(ProactiveProperties.class);
            assertThat(props.getQuietHours().getStart()).isEqualTo(23);
            assertThat(props.getQuietHours().getEnd()).isEqualTo(8);
            assertThat(props.getScatterWindowMinutes()).isEqualTo(30);
            assertThat(props.getDailyCapByStage()).containsExactly(2, 3, 4, 5, 6);
            assertThat(props.getRecall().getThresholdsHours()).containsExactly(24, 72, 168);
        });
    }

    @Test
    void should_bind_custom_greeting_cron_and_scenes_by_stage() {
        runner.withPropertyValues(
                "sanyan.proactive.greeting.morning-cron=0 0 8 * * *",
                "sanyan.proactive.greeting.night-cron=0 30 22 * * *",
                "sanyan.proactive.scenes-by-stage.0.morning=false",
                "sanyan.proactive.scenes-by-stage.0.recall=true",
                "sanyan.proactive.scenes-by-stage.2.morning=true",
                "sanyan.proactive.scenes-by-stage.2.emotion-care=true"
        ).run(ctx -> {
            ProactiveProperties props = ctx.getBean(ProactiveProperties.class);
            assertThat(props.getGreeting().getMorningCron()).isEqualTo("0 0 8 * * *");
            assertThat(props.getGreeting().getNightCron()).isEqualTo("0 30 22 * * *");
            assertThat(props.getScenesByStage().get(0).isMorning()).isFalse();
            assertThat(props.getScenesByStage().get(0).isRecall()).isTrue();
            assertThat(props.getScenesByStage().get(2).isMorning()).isTrue();
            assertThat(props.getScenesByStage().get(2).isEmotionCare()).isTrue();
        });
    }
}
