package itda.chat.service;

import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ChatAuthorizationCacheService {

    private static final String ACTIVE_PET_KEY_PATTERN = "chat:auth:user:%d:active-pet";
    private static final String PARTICIPANTS_KEY_PATTERN = "chat:auth:room:%d:participants";

    private final StringRedisTemplate redisTemplate;

    public ChatAuthorizationCacheService(
            @Qualifier("chatAuthorizationStringRedisTemplate") StringRedisTemplate redisTemplate
    ) {
        this.redisTemplate = redisTemplate;
    }

    public void cacheActivePet(long userId, long petId) {
        redisTemplate.opsForValue().set(ACTIVE_PET_KEY_PATTERN.formatted(userId), Long.toString(petId));
    }

    public void addParticipant(long roomId, long petId) {
        redisTemplate.opsForSet().add(PARTICIPANTS_KEY_PATTERN.formatted(roomId), Long.toString(petId));
    }

    public void addParticipants(long roomId, Collection<Long> petIds) {
        if (petIds == null || petIds.isEmpty()) {
            return;
        }
        String[] values = petIds.stream()
                .map(String::valueOf)
                .toArray(String[]::new);
        redisTemplate.opsForSet().add(PARTICIPANTS_KEY_PATTERN.formatted(roomId), values);
    }

    public void removeParticipant(long roomId, long petId) {
        redisTemplate.opsForSet().remove(PARTICIPANTS_KEY_PATTERN.formatted(roomId), Long.toString(petId));
    }

    public void clearParticipants(long roomId) {
        redisTemplate.delete(PARTICIPANTS_KEY_PATTERN.formatted(roomId));
    }
}
