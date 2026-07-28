package itda.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import itda.user.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Nested
    @DisplayName("Describe: User를 ID로 비관적 잠금 조회한다")
    class DescribeFindByIdForUpdate {

        @Test
        @DisplayName("It: PESSIMISTIC_WRITE Lock Mode를 적용한다")
        void itAppliesPessimisticWriteLock() {
            User saved = userRepository.saveAndFlush(user());
            entityManager.clear();

            User locked = userRepository.findByIdForUpdate(saved.getId())
                    .orElseThrow();

            assertThat(locked.getId()).isEqualTo(saved.getId());
            assertThat(entityManager.getLockMode(locked))
                    .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        }
    }

    private User user() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        return User.register(
                unique + "@example.com",
                "encoded",
                "사용자",
                "사용자#" + unique.substring(0, 8),
                "4113111500"
        );
    }
}
