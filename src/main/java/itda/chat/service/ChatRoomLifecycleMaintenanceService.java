package itda.chat.service;

import itda.chat.repository.ChatRoomRepository;
import itda.greeting.repository.GreetingRepository;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatRoomLifecycleMaintenanceService {

    private final GreetingRepository greetingRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomLifecycleTransactionService transactionService;

    @Value("${app.chat-room.lifecycle.batch-size:100}")
    private int batchSize;

    @Value("${app.chat-room.lifecycle.archive-after-days:30}")
    private long archiveAfterDays;

    public MaintenanceResult runOnce() {
        return runOnce(Instant.now());
    }

    public MaintenanceResult runOnce(Instant now) {
        int expiredGreetings = 0;
        int deletedRooms = 0;
        for (GreetingRepository.ExpiredGreetingCandidate candidate :
                greetingRepository.findExpiredSentCandidates(now, batchSize)) {
            try {
                ChatRoomLifecycleTransactionService.ExpiredGreetingOutcome outcome =
                        transactionService.expireGreetingAndCleanup(
                                candidate.getGreetingId(),
                                candidate.getRoomId(),
                                candidate.getPetLowId(),
                                candidate.getPetHighId(),
                                now
                        );
                if (outcome.expired()) {
                    expiredGreetings++;
                }
                if (outcome.roomDeleted()) {
                    deletedRooms++;
                }
            } catch (RuntimeException exception) {
                log.warn("Failed to expire greeting {} for room {}",
                        candidate.getGreetingId(), candidate.getRoomId(), exception);
            }
        }

        Instant cutoff = now.minus(Duration.ofDays(archiveAfterDays));
        int archivedRooms = 0;
        for (ChatRoomRepository.InactiveAnsweredDirectRoom candidate :
                chatRoomRepository.findInactiveAnsweredDirectRooms(
                        cutoff,
                        batchSize
                )) {
            try {
                if (transactionService.archiveIfEligible(
                        candidate.getRoomId(),
                        candidate.getPetLowId(),
                        candidate.getPetHighId(),
                        now,
                        cutoff
                )) {
                    archivedRooms++;
                }
            } catch (RuntimeException exception) {
                log.warn("Failed to archive chat room {}",
                        candidate.getRoomId(), exception);
            }
        }

        return new MaintenanceResult(
                expiredGreetings,
                deletedRooms,
                archivedRooms
        );
    }

    public record MaintenanceResult(
            int expiredGreetings,
            int deletedRooms,
            int archivedRooms
    ) {
    }
}
