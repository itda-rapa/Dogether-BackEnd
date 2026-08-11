package itda.user.repository;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.user.domain.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByPublicTag(String publicTag);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") Long userId);

    @Query(value = """
            SELECT
                user_account.id AS userId,
                user_account.account_status AS accountStatus,
                user_account.active_pet_id AS activePetId,
                user_account.public_tag AS publicTag
            FROM users user_account
            WHERE user_account.id = :userId
            FOR UPDATE
            """, nativeQuery = true)
    Optional<LockedUserRow> findLockedUserRow(@Param("userId") Long userId);

    default User findByIdOrThrow(Long id) {
        return findById(id).orElseThrow(
                ()-> new BusinessException(ErrorCode.USER_NOT_FOUND)
        );
    }

    interface LockedUserRow {

        Long getUserId();

        String getAccountStatus();

        Long getActivePetId();

        String getPublicTag();
    }
}
