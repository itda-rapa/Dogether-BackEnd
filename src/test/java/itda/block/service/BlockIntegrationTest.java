package itda.block.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.block.dto.BlockCreateRequest;
import itda.block.dto.response.BlockListResponse;
import itda.block.dto.response.BlockResponse;
import itda.block.repository.UserBlockRepository;
import itda.block.service.BlockService.BlockResult;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class BlockIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer(
                DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                        .asCompatibleSubstituteFor("postgres")
        );

    @Autowired
    private BlockService blockService;

    @Autowired
    private BlockRelationshipQueryService blockRelationshipQueryService;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userIdA;
    private Long userIdB;
    private Long petIdA1;
    private Long petIdB1;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
                TRUNCATE user_blocks, pets, users
                RESTART IDENTITY CASCADE
                """);

        userIdA = createUser("blockerA");
        userIdB = createUser("blockedB");
        petIdA1 = createPet(userIdA, "A의강아지");
        petIdB1 = createPet(userIdB, "B의강아지");

        // Set active pet
        jdbcTemplate.update("UPDATE users SET active_pet_id = ? WHERE id = ?", petIdA1, userIdA);
    }

    @Nested
    @DisplayName("block")
    class CreateBlockTests {

        @Test
        @DisplayName("creates new block → isNew=true")
        void createsNewBlock() {
            BlockResult result = blockService.block(userIdA, new BlockCreateRequest(petIdB1));

            assertThat(result.created()).isTrue();
            assertThat(result.block().blockedUserId()).isEqualTo(userIdB);
            assertThat(result.block().blockedUserPublicTag()).isNotBlank();
            assertThat(result.block().createdAt()).isNotNull();
        }

        @Test
        @DisplayName("returns existing block → isNew=false when blocker already blocked same user")
        void returnsExistingBlock() {
            BlockResult first = blockService.block(userIdA, new BlockCreateRequest(petIdB1));
            assertThat(first.created()).isTrue();

            petIdA1 = createPet(userIdA, "A의두번째강아지");
            jdbcTemplate.update("UPDATE users SET active_pet_id = ? WHERE id = ?", petIdA1, userIdA);

            BlockResult second = blockService.block(userIdA, new BlockCreateRequest(petIdB1));

            assertThat(second.created()).isFalse();
            assertThat(second.block().blockedUserId()).isEqualTo(userIdB);
            assertThat(second.block().blockId()).isEqualTo(first.block().blockId());
        }

        @Test
        @DisplayName("concurrent duplicate requests create one row")
        void concurrentDuplicateRequestsCreateOneRow() throws Exception {
            List<BlockResult> results = runConcurrently(
                    () -> blockService.block(userIdA, new BlockCreateRequest(petIdB1)));

            assertThat(results).hasSize(8);
            assertThat(results.stream().filter(BlockResult::created).count()).isEqualTo(1);
            assertThat(results)
                    .extracting(result -> result.block().blockId())
                    .containsOnly(results.get(0).block().blockId());
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from user_blocks", Integer.class)).isEqualTo(1);
        }

        @Test
        @DisplayName("rejects self-block")
        void rejectsSelfBlock() {
            assertThatThrownBy(() -> blockService.block(userIdA, new BlockCreateRequest(petIdA1)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.SAME_OWNER_INTERACTION_FORBIDDEN);
        }

        @Test
        @DisplayName("rejects same-owner block")
        void rejectsSameOwnerBlock() {
            Long petA2 = createPet(userIdA, "A의둘째강아지");

            assertThatThrownBy(() -> blockService.block(userIdA, new BlockCreateRequest(petA2)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.SAME_OWNER_INTERACTION_FORBIDDEN);
        }

        @Test
        @DisplayName("rejects when caller has no active pet")
        void rejectsNoActivePet() {
            Long userIdNoPet = createUser("noPetUser");
            jdbcTemplate.update("UPDATE users SET active_pet_id = NULL WHERE id = ?", userIdNoPet);

            assertThatThrownBy(() -> blockService.block(userIdNoPet, new BlockCreateRequest(petIdB1)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.ACTIVE_PET_REQUIRED);
        }

        @Test
        @DisplayName("rejects when target pet not found")
        void rejectsTargetNotFound() {
            assertThatThrownBy(() -> blockService.block(userIdA, new BlockCreateRequest(99999L)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.PET_NOT_FOUND);
        }

        @Test
        @DisplayName("allows blocking a suspended target user")
        void allowsSuspendedTargetUser() {
            jdbcTemplate.update(
                    "UPDATE users SET account_status = 'SUSPENDED' WHERE id = ?",
                    userIdB
            );

            BlockResult result =
                    blockService.block(userIdA, new BlockCreateRequest(petIdB1));

            assertThat(result.created()).isTrue();
            assertThat(result.block().blockedUserId()).isEqualTo(userIdB);
        }

        @Test
        @DisplayName("allows blocking a suspended target pet")
        void allowsSuspendedTargetPet() {
            jdbcTemplate.update(
                    "UPDATE pets SET status = 'SUSPENDED' WHERE id = ?",
                    petIdB1
            );

            BlockResult result =
                    blockService.block(userIdA, new BlockCreateRequest(petIdB1));

            assertThat(result.created()).isTrue();
            assertThat(result.block().blockedUserId()).isEqualTo(userIdB);
        }

        @Test
        @DisplayName("allows blocking a soft-deleted target pet")
        void allowsSoftDeletedTargetPet() {
            jdbcTemplate.update(
                    """
                    UPDATE pets
                    SET status = 'DELETED', deleted_at = now()
                    WHERE id = ?
                    """,
                    petIdB1
            );

            BlockResult result =
                    blockService.block(userIdA, new BlockCreateRequest(petIdB1));

            assertThat(result.created()).isTrue();
            assertThat(result.block().blockedUserId()).isEqualTo(userIdB);
        }

        @Test
        @DisplayName("rolls back block insert when friend cleanup fails")
        void rollsBackBlockWhenCleanupFails() {
            jdbcTemplate.update(
                    """
                    INSERT INTO friendships (pet_low_id, pet_high_id)
                    VALUES (?, ?)
                    """,
                    Math.min(petIdA1, petIdB1),
                    Math.max(petIdA1, petIdB1)
            );
            jdbcTemplate.execute("""
                    CREATE FUNCTION fail_friendship_delete()
                    RETURNS trigger
                    LANGUAGE plpgsql
                    AS $$
                    BEGIN
                        RAISE EXCEPTION 'forced cleanup failure';
                    END;
                    $$
                    """);
            jdbcTemplate.execute("""
                    CREATE TRIGGER trg_fail_friendship_delete
                    BEFORE DELETE ON friendships
                    FOR EACH ROW
                    EXECUTE FUNCTION fail_friendship_delete()
                    """);

            try {
                assertThatThrownBy(() ->
                        blockService.block(userIdA, new BlockCreateRequest(petIdB1))
                ).isInstanceOf(DataAccessException.class);

                assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM user_blocks",
                        Integer.class
                )).isZero();
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM friendships",
                        Integer.class
                )).isEqualTo(1);
            } finally {
                jdbcTemplate.execute(
                        "DROP TRIGGER IF EXISTS trg_fail_friendship_delete ON friendships"
                );
                jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_friendship_delete()");
            }
        }
    }

    @Nested
    @DisplayName("listBlocks")
    class ListBlocksTests {

        @Test
        @DisplayName("returns blocked list with cursor pagination, newest first")
        void returnsBlockedList() {
            blockService.block(userIdA, new BlockCreateRequest(petIdB1));

            BlockListResponse response = blockService.listBlocks(userIdA, null, 20);

            assertThat(response.items()).hasSize(1);
            BlockResponse br = response.items().get(0);
            assertThat(br.blockedUserId()).isEqualTo(userIdB);
            assertThat(response.page().nextCursor()).isNull();
            assertThat(response.page().hasNext()).isFalse();
        }

        @Test
        @DisplayName("returns empty list when no blocks")
        void emptyList() {
            BlockListResponse response = blockService.listBlocks(userIdA, null, 20);

            assertThat(response.items()).isEmpty();
            assertThat(response.page().nextCursor()).isNull();
            assertThat(response.page().hasNext()).isFalse();
        }

        @Test
        @DisplayName("paginates with cursor, newest first across multiple blocks")
        void paginatesByCursor() {
            Long userIdC = createUser("userC");
            Long userIdD = createUser("userD");
            Long petC1 = createPet(userIdC, "C강아지");
            Long petD1 = createPet(userIdD, "D강아지");

            blockService.block(userIdA, new BlockCreateRequest(petIdB1));
            blockService.block(userIdA, new BlockCreateRequest(petC1));
            blockService.block(userIdA, new BlockCreateRequest(petD1));

            BlockListResponse page1 = blockService.listBlocks(userIdA, null, 2);
            assertThat(page1.items()).hasSize(2);
            assertThat(page1.page().hasNext()).isTrue();

            List<Long> page1BlockedIds = page1.items().stream()
                    .map(BlockResponse::blockedUserId)
                    .toList();
            // Newest first: D, C
            assertThat(page1BlockedIds).containsExactly(userIdD, userIdC);

            String cursor = page1.page().nextCursor();
            assertThat(cursor).isNotNull();

            BlockListResponse page2 = blockService.listBlocks(userIdA, cursor, 2);
            assertThat(page2.items()).hasSize(1);
            assertThat(page2.page().hasNext()).isFalse();
            assertThat(page2.items().get(0).blockedUserId()).isEqualTo(userIdB);
        }

        @Test
        @DisplayName("other user blocks do not leak")
        void isolation() {
            Long userIdOther = createUser("otherUser");
            Long petOther = createPet(userIdOther, "otherPet");
            jdbcTemplate.update("UPDATE users SET active_pet_id = ? WHERE id = ?", petOther, userIdOther);

            blockService.block(userIdA, new BlockCreateRequest(petIdB1));
            blockService.block(userIdOther, new BlockCreateRequest(petIdB1));

            BlockListResponse responseA = blockService.listBlocks(userIdA, null, 20);
            BlockListResponse responseOther = blockService.listBlocks(userIdOther, null, 20);

            assertThat(responseA.items()).hasSize(1);
            assertThat(responseA.items().get(0).blockedUserId()).isEqualTo(userIdB);
            assertThat(responseOther.items()).hasSize(1);
            assertThat(responseOther.items().get(0).blockedUserId()).isEqualTo(userIdB);
        }
    }

    @Nested
    @DisplayName("BlockRelationshipQueryService")
    class BlockRelationshipQueryServiceTests {

        @Test
        @DisplayName("reports blocks in either direction")
        void reportsBidirectionalBlock() {
            blockService.block(userIdA, new BlockCreateRequest(petIdB1));

            assertThat(blockRelationshipQueryService.existsBlockBetween(userIdA, userIdB)).isTrue();
            assertThat(blockRelationshipQueryService.existsBlockBetween(userIdB, userIdA)).isTrue();
        }

        @Test
        @DisplayName("reports no block for unrelated users")
        void noBlockForUnrelated() {
            Long userIdC = createUser("userC");
            createPet(userIdC, "C강아지");

            assertThat(blockRelationshipQueryService.existsBlockBetween(userIdA, userIdB)).isFalse();
            assertThat(blockRelationshipQueryService.existsBlockBetween(userIdA, userIdC)).isFalse();
        }
    }

    @Nested
    @DisplayName("UserBlockRepository")
    class RepositoryTests {

        @Test
        @DisplayName("findByBlockerUserIdAndBlockedUserId returns block")
        void findByBlockerAndBlocked() {
            blockService.block(userIdA, new BlockCreateRequest(petIdB1));

            var found = userBlockRepository.findByBlockerUserIdAndBlockedUserId(userIdA, userIdB);
            assertThat(found).isPresent();
            assertThat(found.get().getBlockerUserId()).isEqualTo(userIdA);
            assertThat(found.get().getBlockedUserId()).isEqualTo(userIdB);
        }

        @Test
        @DisplayName("findByBlockerUserIdAndBlockedUserId returns empty for non-existent")
        void findByBlockerAndBlockedNotFound() {
            var found = userBlockRepository.findByBlockerUserIdAndBlockedUserId(userIdA, userIdB);
            assertThat(found).isEmpty();
        }
    }

    private Long createUser(String tag) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (
                    email,
                    password_hash,
                    nickname,
                    public_tag,
                    role,
                    account_status,
                    neighborhood_code
                ) VALUES (?, 'encoded', '보호자', ?, 'USER', 'ACTIVE', '4113165000')
                RETURNING id
                """,
                Long.class,
                unique + "@example.com",
                "보호자#" + unique.substring(0, 8)
        );
    }

    private Long createPet(Long ownerId, String nickname) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        return jdbcTemplate.queryForObject("""
                INSERT INTO pets (
                    owner_user_id,
                    public_tag,
                    nickname,
                    status
                ) VALUES (?, ?, ?, 'ACTIVE')
                RETURNING id
                """,
                Long.class,
                ownerId,
                nickname + "#" + unique.substring(0, 4).toUpperCase(),
                nickname
        );
    }

    private <T> List<T> runConcurrently(Callable<T> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return action.call();
                }));
            }
            start.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
