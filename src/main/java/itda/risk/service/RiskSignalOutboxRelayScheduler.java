package itda.risk.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.risk.outbox-relay.enabled", havingValue = "true")
public class RiskSignalOutboxRelayScheduler {
    private final RiskSignalOutboxRelayWorker worker;

    @Scheduled(fixedDelayString = "${app.risk.outbox-relay.delay-ms:1000}")
    public void run() {
        try {
            RiskSignalOutboxRelayWorker.Result result = worker.runOnce();
            if (result.sent() > 0 || result.retried() > 0
                    || result.failed() > 0 || result.fenced() > 0) {
                log.info("Risk relay: sent={}, retried={}, failed={}, fenced={}",
                        result.sent(), result.retried(), result.failed(), result.fenced());
            }
        } catch (RuntimeException exception) {
            log.error("Risk relay cycle failed: exceptionType={}", exception.getClass().getSimpleName());
        }
    }
}
