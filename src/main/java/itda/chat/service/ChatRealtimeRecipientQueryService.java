package itda.chat.service;

import itda.chat.repository.ChatRealtimeRecipientRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ChatRealtimeRecipientQueryService {

    private final ChatRealtimeRecipientRepository recipientRepository;

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Long> findActiveRecipientUserIds(long roomId, Long senderPetId) {
        return recipientRepository.findActiveRecipientUserIds(roomId, senderPetId);
    }
}
