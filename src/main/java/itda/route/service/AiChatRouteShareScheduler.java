package itda.route.service;

import itda.route.dto.RouteShareRequest;
import itda.route.repository.AiChatRouteJobRepository;
import itda.route.repository.RouteRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.route.ai-chat-auto-share-enabled", havingValue = "true")
public class AiChatRouteShareScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiChatRouteShareScheduler.class);
    private final AiChatRouteJobRepository jobRepository;
    private final RouteRequestRepository routeRepository;
    private final RouteShareService routeShareService;

    public AiChatRouteShareScheduler(AiChatRouteJobRepository jobRepository,
                                     RouteRequestRepository routeRepository,
                                     RouteShareService routeShareService) {
        this.jobRepository = jobRepository;
        this.routeRepository = routeRepository;
        this.routeShareService = routeShareService;
    }

    @Scheduled(fixedDelayString = "${app.route.ai-chat-share-delay-ms:1000}")
    public void shareReadyRoutes() {
        for (var job : jobRepository.findReady(20)) {
            if ("FAILED".equals(job.routeStatus())) {
                jobRepository.markFailed(job.routeRequestId(), job.errorCode());
                continue;
            }
            try {
                routeRepository.saveOwnedCompleted(job.routeRequestId(), job.requesterUserId());
                routeShareService.share(job.requesterUserId(), job.roomId(),
                        new RouteShareRequest("ai-route:" + job.routeRequestId(),
                                job.routeRequestId()));
                jobRepository.markShared(job.routeRequestId());
            } catch (RuntimeException exception) {
                log.warn("AI chat route share is waiting for retry: routeId={}, roomId={}",
                        job.routeRequestId(), job.roomId());
            }
        }
    }
}
