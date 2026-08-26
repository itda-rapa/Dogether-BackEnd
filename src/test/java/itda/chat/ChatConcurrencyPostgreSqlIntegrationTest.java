package itda.chat;

import static org.assertj.core.api.Assertions.assertThat;

import itda.chat.domain.RoomOrigin;
import itda.chat.dto.ChatMessageCreateRequest;
import itda.chat.dto.ChatMessageResult;
import itda.chat.dto.EnsureDirectRoomResult;
import itda.chat.service.ChatMessageService;
import itda.chat.service.ChatRoomService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Exercises the two races the chat core is designed around, with real threads and real
 * transactions. Both guarantees come from database constraints rather than application checks,
 * so neither can be demonstrated by the mocked unit tests.
 *
 * <p>Each worker waits on a start latch so the calls overlap instead of running in sequence.
 */
@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class ChatConcurrencyPostgreSqlIntegrationTest {

    private static final int WORKERS = 8;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer(
                DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                        .asCompatibleSubstituteFor("postgres")
        );

    @Autowired
    private ChatRoomService chatRoomService;

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetChatTables() {
        jdbcTemplate.execute(
                "truncate chat_messages, chat_room_participants, chat_rooms restart identity cascade");
    }

    @Test
    void concurrentEnsureDirectRoomCreatesExactlyOneRoom() throws Exception {
        List<EnsureDirectRoomResult> results = runConcurrently(
                () -> chatRoomService.ensureDirectRoom(11L, 22L, RoomOrigin.GREETING));

        // Every caller must get the same room back, and only one may report having created it.
        assertThat(results).hasSize(WORKERS);
        assertThat(results).extracting(EnsureDirectRoomResult::roomId).containsOnly(results.get(0).roomId());
        assertThat(results.stream().filter(EnsureDirectRoomResult::isNew).count()).isEqualTo(1);

        assertThat(countOf("chat_rooms")).isEqualTo(1);
        // The losing writers must not add duplicate participant rows either.
        assertThat(countOf("chat_room_participants")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                "select pet_id from chat_room_participants where room_id = ?",
                Long.class, results.get(0).roomId()))
                .containsExactlyInAnyOrder(11L, 22L);
    }

    @Test
    void concurrentSendsWithOneKeyStoreOneMessageAndOnlyOneReportsCreated() throws Exception {
        long roomId = chatRoomService.ensureDirectRoom(11L, 22L, RoomOrigin.GREETING).roomId();
        ChatMessageCreateRequest request = new ChatMessageCreateRequest("idem-race", "안녕하세요");

        List<ChatMessageResult> results =
                runConcurrently(() -> chatMessageService.sendText(roomId, 11L, request));

        assertThat(results).hasSize(WORKERS);
        assertThat(results).extracting(r -> r.message().getId())
                .containsOnly(results.get(0).message().getId());
        assertThat(countOf("chat_messages")).isEqualTo(1);

        // Exactly one caller wrote the row. The rest lost the race and must say so, otherwise a
        // duplicate send would answer 201 and would bump the room activity timestamp.
        assertThat(results.stream().filter(ChatMessageResult::created).count()).isEqualTo(1);
    }

    @Test
    void concurrentSendsWithDistinctKeysStoreEveryMessage() throws Exception {
        long roomId = chatRoomService.ensureDirectRoom(11L, 22L, RoomOrigin.GREETING).roomId();

        List<ChatMessageResult> results = runConcurrentlyIndexed(i -> chatMessageService.sendText(
                roomId, 11L, new ChatMessageCreateRequest("idem-" + i, "message " + i)));

        assertThat(results).extracting(r -> r.message().getId()).doesNotHaveDuplicates();
        assertThat(results).allMatch(ChatMessageResult::created);
        assertThat(countOf("chat_messages")).isEqualTo(WORKERS);
    }

    // ---------- helpers ----------

    private <T> List<T> runConcurrently(Callable<T> action) throws Exception {
        return runConcurrentlyIndexed(i -> action.call());
    }

    /**
     * Runs {@link #WORKERS} copies of the action, released simultaneously. Any worker exception is
     * rethrown by {@code Future.get()} so a failed race cannot be mistaken for a passing test.
     */
    private <T> List<T> runConcurrentlyIndexed(IndexedAction<T> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < WORKERS; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    return action.apply(index);
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

    @FunctionalInterface
    private interface IndexedAction<T> {
        T apply(int index) throws Exception;
    }

    private int countOf(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }
}
