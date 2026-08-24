package itda.safety.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.safety.evaluator.enabled", havingValue = "true")
public class SafetyCaseEvaluationScheduler {
    private final SafetyCaseEvaluationWorker worker;

    @Scheduled(fixedDelayString = "${app.safety.evaluator.delay-ms:5000}")
    public void run() {
        try {
            SafetyCaseEvaluationWorker.Result result = worker.runOnce();
            if (result.completed() + result.retried() + result.failed() + result.fenced() > 0) {
                log.info("Safety evaluator: completed={}, cases={}, retried={}, failed={}, fenced={}",
                        result.completed(), result.cases(), result.retried(),
                        result.failed(), result.fenced());
            }
        } catch (RuntimeException exception) {
            log.error("Safety evaluator cycle failed: exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
