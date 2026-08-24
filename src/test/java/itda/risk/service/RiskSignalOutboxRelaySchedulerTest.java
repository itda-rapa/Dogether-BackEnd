package itda.risk.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import itda.common.config.SchedulingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class RiskSignalOutboxRelaySchedulerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class, RiskSignalOutboxRelayScheduler.class)
            .withBean(RiskSignalOutboxRelayWorker.class, () -> {
                RiskSignalOutboxRelayWorker worker = mock(RiskSignalOutboxRelayWorker.class);
                org.mockito.Mockito.when(worker.runOnce())
                        .thenReturn(new RiskSignalOutboxRelayWorker.Result(0, 0, 0, 0));
                return worker;
            });

    @Test
    void relayIsDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(RiskSignalOutboxRelayScheduler.class);
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks())
                    .isEmpty();
        });
    }

    @Test
    void enabledRelayRegistersScheduledTaskThroughSharedSchedulingConfig() {
        contextRunner
                .withPropertyValues(
                        "app.risk.outbox-relay.enabled=true",
                        "app.risk.outbox-relay.delay-ms=60000")
                .run(context -> {
                    assertThat(context).hasSingleBean(RiskSignalOutboxRelayScheduler.class);
                    assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
                    assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks())
                            .hasSize(1);
                });
    }
}
