package itda.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.board.domain.Board;
import itda.board.dto.BoardCreateRequest;
import itda.board.dto.BoardUpdateRequest;
import itda.board.repository.BoardRepository;
import itda.board.service.BoardService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.security.CurrentUser;
import itda.user.domain.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class BoardPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BoardRepository boardRepository;

    @Autowired
    private BoardService boardService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetBoards() {
        jdbcTemplate.execute("truncate boards restart identity cascade");
    }

    @Test
    void flywayCreatesNamedUniqueConstraintAndBlankCheckIsEnforced()
            throws Exception {
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                  from pg_constraint c
                  join pg_class table_name on table_name.oid = c.conrelid
                 where table_name.relname = 'boards'
                   and c.conname = 'uk_boards_name'
                   and c.contype = 'u'
                """, Integer.class)).isEqualTo(1);

        jdbcTemplate.update("insert into boards (name) values (?)", "unique");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into boards (name) values (?)", "unique"))
                .isInstanceOf(DataIntegrityViolationException.class);

        for (String blank : List.of(" ", "\t", "\n")) {
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "insert into boards (name) values (?)", blank))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void postDuplicateCreationMapsNamedConstraintToBoardErrorCode()
            throws Exception {
        createThroughAdminApi("중복").andExpect(status().isCreated());

        createThroughAdminApi("중복")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value(ErrorCode.BOARD_NAME_DUPLICATED.name()));
    }

    @Test
    void postValidatesNamesUsingUnicodeCodePointsAfterStrip() throws Exception {
        String thirtyCodePoints = "😀".repeat(30);
        String thirtyOneCodePoints = "😀".repeat(31);
        assertThat(thirtyCodePoints.length()).isEqualTo(60);
        assertThat(thirtyCodePoints.codePointCount(0, thirtyCodePoints.length()))
                .isEqualTo(30);

        createThroughAdminApi("  " + thirtyCodePoints + "  ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value(thirtyCodePoints));
        createThroughAdminApi(thirtyOneCodePoints)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value(ErrorCode.VALIDATION_FAILED.name()));
    }

    @Test
    void renameToAnotherBoardsNameMapsNamedConstraintToBoardErrorCode() {
        Board source = boardRepository.saveAndFlush(Board.create("원본"));
        boardRepository.saveAndFlush(Board.create("대상"));

        assertThatThrownBy(() -> boardService.update(
                source.getId(),
                new BoardUpdateRequest("대상", source.getVersion())
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.BOARD_NAME_DUPLICATED);
        assertThat(boardRepository.findById(source.getId()).orElseThrow().getName())
                .isEqualTo("원본");
    }

    @Test
    void concurrentIdenticalCreatesHaveOneSuccessAndOneBoardNameDuplicate()
            throws Exception {
        List<CreateOutcome> outcomes = runConcurrently(
                () -> create("동시 생성")
        );

        assertThat(outcomes.stream().filter(CreateOutcome::created).count())
                .isEqualTo(1);
        assertThat(outcomes).extracting(CreateOutcome::errorCode)
                .containsExactlyInAnyOrder(null, ErrorCode.BOARD_NAME_DUPLICATED);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from boards where name = '동시 생성'", Integer.class
        )).isEqualTo(1);
    }

    @Test
    void concurrentDifferentRenamesOfOneBoardUseActualOptimisticVersionConflict()
            throws Exception {
        Board board = boardRepository.saveAndFlush(Board.create("원래 이름"));
        CountDownLatch bothRead = new CountDownLatch(2);
        CountDownLatch allowFlush = new CountDownLatch(1);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        List<RenameOutcome> outcomes = runConcurrentlyIndexed(index -> {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    Board managed = boardRepository.findById(board.getId()).orElseThrow();
                    bothRead.countDown();
                    await(allowFlush);
                    managed.changeName(index == 0 ? "첫 변경" : "둘째 변경");
                    boardRepository.flush();
                });
                return RenameOutcome.successResult();
            } catch (ObjectOptimisticLockingFailureException exception) {
                return RenameOutcome.conflictResult();
            }
        }, bothRead, allowFlush);

        assertThat(outcomes.stream().filter(RenameOutcome::succeeded).count())
                .isEqualTo(1);
        assertThat(outcomes).extracting(RenameOutcome::optimisticConflict)
                .containsExactlyInAnyOrder(true, false);
        assertThat(boardRepository.findById(board.getId()).orElseThrow().getVersion())
                .isEqualTo(1L);
    }

    private CreateOutcome create(String name) {
        try {
            boardService.create(new BoardCreateRequest(name));
            return CreateOutcome.success();
        } catch (BusinessException exception) {
            return CreateOutcome.failed(exception.getErrorCode());
        }
    }

    private org.springframework.test.web.servlet.ResultActions createThroughAdminApi(
            String name
    ) throws Exception {
        return mockMvc.perform(post("/admin/boards")
                .with(user(new CurrentUser(1L, "admin@example.com", Role.ADMIN)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}"));
    }

    private <T> List<T> runConcurrently(ConcurrentAction<T> action)
            throws Exception {
        return runConcurrentlyIndexed(index -> action.apply(), null, null);
    }

    private <T> List<T> runConcurrentlyIndexed(
            IndexedConcurrentAction<T> action,
            CountDownLatch bothRead,
            CountDownLatch allowFlush
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 2; index++) {
                int worker = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    return action.apply(worker);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            if (bothRead != null) {
                assertThat(bothRead.await(10, TimeUnit.SECONDS)).isTrue();
                allowFlush.countDown();
            }

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            if (allowFlush != null) {
                allowFlush.countDown();
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for concurrent test barrier");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    @FunctionalInterface
    private interface ConcurrentAction<T> {
        T apply() throws Exception;
    }

    @FunctionalInterface
    private interface IndexedConcurrentAction<T> {
        T apply(int index) throws Exception;
    }

    private record CreateOutcome(boolean created, ErrorCode errorCode) {

        static CreateOutcome success() {
            return new CreateOutcome(true, null);
        }

        static CreateOutcome failed(ErrorCode errorCode) {
            return new CreateOutcome(false, errorCode);
        }
    }

    private record RenameOutcome(boolean succeeded, boolean optimisticConflict) {

        static RenameOutcome successResult() {
            return new RenameOutcome(true, false);
        }

        static RenameOutcome conflictResult() {
            return new RenameOutcome(false, true);
        }
    }
}
