package itda.chat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        name = "app.chat-room.lifecycle.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ChatRoomLifecycleScheduler {

    private final ChatRoomLifecycleMaintenanceService maintenanceService;

    @Scheduled(fixedDelayString = "${app.chat-room.lifecycle.maintenance-delay-ms:60000}")
    public void runMaintenance() {
        try {
            log.debug("Starting chat room lifecycle maintenance");
            ChatRoomLifecycleMaintenanceService.MaintenanceResult result =
                    maintenanceService.runOnce();
            if (result.expiredGreetings() > 0
                    || result.deletedRooms() > 0
                    || result.archivedRooms() > 0) {
                log.info("Chat room lifecycle maintenance: expired={}, deleted={}, archived={}",
                        result.expiredGreetings(),
                        result.deletedRooms(),
                        result.archivedRooms());
            }
        } catch (RuntimeException exception) {
            log.error("Chat room lifecycle maintenance failed", exception);
        }
    }
}
