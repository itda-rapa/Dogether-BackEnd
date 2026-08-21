package itda.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.boardpost.domain.BoardPost;
import itda.boardpost.domain.PostStatus;
import itda.boardpost.repository.BoardPostRepository;
import itda.boardpost.service.BoardPostService;
import itda.comment.domain.BoardPostComment;
import itda.comment.dto.CommentCreateRequest;
import itda.comment.repository.BoardPostCommentRepository;
import itda.comment.service.BoardPostCommentService;
import itda.common.exception.BusinessException;
import itda.common.security.CurrentUser;
import itda.user.domain.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class BoardPostCommentPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private BoardPostCommentRepository comments;
    @Autowired private BoardPostRepository posts;
    @Autowired private BoardPostCommentService commentService;
    @Autowired private BoardPostService postService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MockMvc mockMvc;

    @Test
    void flywayCreatesForeignKeysChecksAndHierarchyIndexesWithoutCascade() {
        long boardId = createBoard();
        Author author = createAuthor("author", "4113111500");
        long postId = insertPost(boardId, author, "PUBLISHED");

        assertThatThrownBy(() -> insertComment(999999999L, author.userId(), author.petId(), "content", Instant.now(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertComment(postId, 999999999L, author.petId(), "content", Instant.now(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertComment(postId, author.userId(), 999999999L, "content", Instant.now(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertComment(postId, author.userId(), author.petId(), " \t\n", Instant.now(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertComment(postId, author.userId(), author.petId(), "😀".repeat(5001), Instant.now(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertComment(postId, author.userId(), author.petId(), "😀".repeat(5000), Instant.now(), null);
        long rootId = insertComment(postId, author.userId(), author.petId(), "root", Instant.now().plusSeconds(1), null);
        long replyId = insertHierarchyComment(postId, author.userId(), author.petId(), "reply", Instant.now().plusSeconds(2),
                rootId, rootId, (short) 1);
        assertThatThrownBy(() -> insertHierarchyComment(postId, author.userId(), author.petId(), "bad-root", Instant.now(),
                rootId, null, (short) 1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertHierarchyComment(postId, author.userId(), author.petId(), "bad-depth", Instant.now(),
                rootId, rootId, (short) 4)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertHierarchyComment(postId, author.userId(), author.petId(), "missing-parent", Instant.now(),
                999999999L, rootId, (short) 1)).isInstanceOf(DataIntegrityViolationException.class);

        String index = jdbc.queryForObject("""
                select indexdef from pg_indexes
                 where schemaname = current_schema()
                   and indexname = 'ix_board_post_comments_visible_post_created_id'
                """, String.class);
        assertThat(index).contains("post_id, created_at, id").contains("WHERE (deleted_at IS NULL)");
        assertThat(jdbc.queryForObject("select indexdef from pg_indexes where schemaname = current_schema() and indexname = 'ix_board_post_comments_root_cursor'", String.class))
                .contains("post_id, created_at, id").contains("WHERE (parent_comment_id IS NULL)");
        assertThat(jdbc.queryForObject("select indexdef from pg_indexes where schemaname = current_schema() and indexname = 'ix_board_post_comments_reply_root_created_id'", String.class))
                .contains("root_comment_id, created_at, id").contains("WHERE (parent_comment_id IS NOT NULL)");
        List<String> foreignKeyRules = jdbc.queryForList("""
                select delete_rule from information_schema.referential_constraints rc
                join information_schema.table_constraints tc
                  on tc.constraint_catalog = rc.constraint_catalog
                 and tc.constraint_schema = rc.constraint_schema
                 and tc.constraint_name = rc.constraint_name
                where tc.table_schema = current_schema()
                  and tc.table_name = 'board_post_comments'
                  and tc.constraint_name in (
                      'fk_board_post_comments_post',
                      'fk_board_post_comments_author_user',
                      'fk_board_post_comments_author_pet',
                      'fk_board_post_comments_parent',
                      'fk_board_post_comments_root'
                  )
                """, String.class);
        assertThat(foreignKeyRules).containsExactlyInAnyOrder("NO ACTION", "NO ACTION", "NO ACTION", "NO ACTION", "NO ACTION");
        assertThat(jdbc.queryForObject("select parent_comment_id is null and root_comment_id is null and depth = 0 from board_post_comments where id = ?", Boolean.class, rootId)).isTrue();
        assertThat(jdbc.queryForObject("select parent_comment_id from board_post_comments where id = ?", Long.class, replyId)).isEqualTo(rootId);
        assertThat(jdbc.queryForObject("select root_comment_id from board_post_comments where id = ?", Long.class, replyId)).isEqualTo(rootId);
        assertThat(jdbc.queryForObject("select depth from board_post_comments where id = ?", Short.class, replyId)).isEqualTo((short) 1);
    }

    @Test
    void v32UpgradePreservesRowsInsertedBeforeHierarchyColumnsAsRoots() {
        String schema = "comment_upgrade_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Flyway beforeV32 = configuredFlyway(schema, "31");
        Flyway afterV32 = configuredFlyway(schema, null);
        try {
            beforeV32.migrate();
            JdbcTemplate upgradeJdbc = schemaJdbc(schema);
            upgradeJdbc.update("""
                    insert into neighborhoods (code, sido_name, sigungu_name, eupmyeondong_name)
                    values ('4113111500', '경기도', '성남시 수정구', '시흥동')
                    """);
            long userId = upgradeJdbc.queryForObject("""
                    insert into users (email, password_hash, nickname, public_tag, role, account_status, neighborhood_code)
                    values (?, 'encoded', 'legacy-user', 'legacy#UPGRADE', 'USER', 'ACTIVE', '4113111500') returning id
                    """, Long.class, unique("legacy") + "@example.com");
            long petId = upgradeJdbc.queryForObject("""
                    insert into pets (owner_user_id, public_tag, nickname, status)
                    values (?, 'legacypet#ABCD', 'legacy-pet', 'ACTIVE') returning id
                    """, Long.class, userId);
            long boardId = upgradeJdbc.queryForObject(
                    "insert into boards (name) values (?) returning id", Long.class, unique("legacy-board")
            );
            long postId = upgradeJdbc.queryForObject("""
                    insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                    values (?, ?, ?, '4113111500', 'title', 'content', 'PUBLISHED') returning id
                    """, Long.class, boardId, userId, petId);
            long legacyCommentId = upgradeJdbc.queryForObject("""
                    insert into board_post_comments (post_id, author_user_id, author_pet_id, content)
                    values (?, ?, ?, 'legacy root comment') returning id
                    """, Long.class, postId, userId, petId);

            afterV32.migrate();

            assertThat(upgradeJdbc.queryForObject(
                    "select parent_comment_id is null from board_post_comments where id = ?", Boolean.class, legacyCommentId
            )).isTrue();
            assertThat(upgradeJdbc.queryForObject(
                    "select root_comment_id is null from board_post_comments where id = ?", Boolean.class, legacyCommentId
            )).isTrue();
            assertThat(upgradeJdbc.queryForObject(
                    "select depth from board_post_comments where id = ?", Short.class, legacyCommentId
            )).isEqualTo((short) 0);
        } finally {
            afterV32.clean();
        }
    }

    @Test
    void softDeletedCommentsAreHiddenButRowsRemainAndPostSoftDeleteRetainsHistory() {
        long boardId = createBoard();
        Author author = createAuthor("author", "4113111500");
        long postId = insertPost(boardId, author, "PUBLISHED");
        long visibleId = insertComment(postId, author.userId(), author.petId(), "visible", Instant.now(), null);
        long deletedId = insertComment(postId, author.userId(), author.petId(), "deleted", Instant.now().plusSeconds(1), Instant.now());

        assertThat(comments.findVisibleByPostId(postId, author.userId(), null, null, 10))
                .extracting(BoardPostComment::getId).containsExactly(visibleId);
        jdbc.update("update board_posts set status = 'DELETED', deleted_at = now() where id = ?", postId);
        assertThat(jdbc.queryForObject("select count(*) from board_post_comments where post_id = ?", Long.class, postId))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("select deleted_at is not null from board_post_comments where id = ?", Boolean.class, deletedId))
                .isTrue();
    }

    @Test
    void nativeListFiltersBlockedCommentsBeforeLimitAndUsesCreatedAtIdKeyset() {
        long boardId = createBoard();
        long viewer = createUser("viewer", "4113111500");
        Author postAuthor = createAuthor("postAuthor", "4113111500");
        Author blocked = createAuthor("blocked", "4113111500");
        Author visible = createAuthor("visible", "4113111500");
        long postId = insertPost(boardId, postAuthor, "PUBLISHED");
        Instant tie = Instant.parse("2026-08-10T00:00:00Z");
        long hiddenId = insertComment(postId, blocked.userId(), blocked.petId(), "hidden", tie, null);
        long firstVisibleId = insertComment(postId, visible.userId(), visible.petId(), "first", tie, null);
        long secondVisibleId = insertComment(postId, postAuthor.userId(), postAuthor.petId(), "second", tie.plusSeconds(1), null);
        jdbc.update("insert into user_blocks (blocker_user_id, blocked_user_id) values (?, ?)", viewer, blocked.userId());

        List<BoardPostComment> first = comments.findVisibleByPostId(postId, viewer, null, null, 2);
        assertThat(first).extracting(BoardPostComment::getId).containsExactly(firstVisibleId, secondVisibleId)
                .doesNotContain(hiddenId);
        assertThat(comments.findVisibleByPostId(postId, viewer, tie, firstVisibleId, 10))
                .extracting(BoardPostComment::getId).containsExactly(secondVisibleId);
    }

    @Test
    void listApiReturnsOldestFirstCursorPageFiltersReverseBlocksAndExcludesSoftDeletedRows() throws Exception {
        long boardId = createBoard();
        Author postAuthor = createAuthor("postAuthor", "4113111500");
        Author viewer = createAuthor("viewer", "4113111500");
        Author reverseBlocker = createAuthor("reverseBlocker", "4113111500");
        long postId = insertPost(boardId, postAuthor, "PUBLISHED");
        Instant created = Instant.parse("2026-08-10T00:00:00Z");
        long firstId = insertComment(postId, postAuthor.userId(), postAuthor.petId(), "first", created, null);
        insertComment(postId, reverseBlocker.userId(), reverseBlocker.petId(), "blocked", created.plusSeconds(1), null);
        long deletedId = insertComment(postId, postAuthor.userId(), postAuthor.petId(), "deleted", created.plusSeconds(2), created.plusSeconds(3));
        long secondId = insertComment(postId, postAuthor.userId(), postAuthor.petId(), "second", created.plusSeconds(4), null);
        jdbc.update("insert into user_blocks (blocker_user_id, blocked_user_id) values (?, ?)", reverseBlocker.userId(), viewer.userId());

        String firstPage = mockMvc.perform(get("/posts/{postId}/comments", postId)
                        .param("size", "1").with(user(principal(viewer.userId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].commentId").value(firstId))
                .andExpect(jsonPath("$.data.items[0].content").value("first"))
                .andExpect(jsonPath("$.data.page.hasNext").value(true))
                .andExpect(jsonPath("$.data.page.nextCursor").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String cursor = com.jayway.jsonpath.JsonPath.read(firstPage, "$.data.page.nextCursor");
        mockMvc.perform(get("/posts/{postId}/comments", postId)
                        .param("size", "1").param("cursor", cursor).with(user(principal(viewer.userId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].commentId").value(secondId))
                .andExpect(jsonPath("$.data.page.hasNext").value(false));
        assertThat(jdbc.queryForObject("select deleted_at is not null from board_post_comments where id = ?", Boolean.class, deletedId))
                .isTrue();
    }

    @Test
    void rootCandidatesKeepOnlyFinalVisibleThreadsBeforeLimitAndCursorPagination() throws Exception {
        long boardId = createBoard();
        long viewerId = createUser("viewer", "4113111500");
        Author postAuthor = createAuthor("postAuthor", "4113111500");
        Author blockedAuthor = createAuthor("blockedAuthor", "4113111500");
        long postId = insertPost(boardId, postAuthor, "PUBLISHED");
        Instant start = Instant.parse("2026-08-10T00:00:00Z");

        long tombstoneWithVisibleReply = insertComment(
                postId, postAuthor.userId(), postAuthor.petId(), "deleted root kept", start, start.plusSeconds(1)
        );
        insertHierarchyComment(postId, postAuthor.userId(), postAuthor.petId(), "visible reply", start.plusSeconds(2),
                tombstoneWithVisibleReply, tombstoneWithVisibleReply, (short) 1);
        long deletedLeaf = insertComment(
                postId, postAuthor.userId(), postAuthor.petId(), "deleted leaf excluded", start.plusSeconds(3), start.plusSeconds(4)
        );
        long deletedRootWithOnlyBlockedReply = insertComment(
                postId, postAuthor.userId(), postAuthor.petId(), "deleted blocked root excluded", start.plusSeconds(5), start.plusSeconds(6)
        );
        insertHierarchyComment(postId, blockedAuthor.userId(), blockedAuthor.petId(), "blocked reply", start.plusSeconds(7),
                deletedRootWithOnlyBlockedReply, deletedRootWithOnlyBlockedReply, (short) 1);
        long deletedRootWithBlockedMiddleAncestor = insertComment(
                postId, postAuthor.userId(), postAuthor.petId(), "deleted blocked middle excluded", start.plusSeconds(7).plusMillis(500), start.plusSeconds(7).plusMillis(600)
        );
        long blockedMiddle = insertHierarchyComment(
                postId, blockedAuthor.userId(), blockedAuthor.petId(), "blocked middle", start.plusSeconds(7).plusMillis(700),
                deletedRootWithBlockedMiddleAncestor, deletedRootWithBlockedMiddleAncestor, (short) 1
        );
        insertHierarchyComment(postId, postAuthor.userId(), postAuthor.petId(), "active depth two hidden by blocked middle", start.plusSeconds(7).plusMillis(800),
                blockedMiddle, deletedRootWithBlockedMiddleAncestor, (short) 2);
        long firstActiveRoot = insertComment(postId, postAuthor.userId(), postAuthor.petId(), "first active", start.plusSeconds(8), null);
        long secondActiveRoot = insertComment(postId, postAuthor.userId(), postAuthor.petId(), "second active", start.plusSeconds(9), null);
        long thirdActiveRoot = insertComment(postId, postAuthor.userId(), postAuthor.petId(), "third active", start.plusSeconds(10), null);
        jdbc.update("insert into user_blocks (blocker_user_id, blocked_user_id) values (?, ?)", viewerId, blockedAuthor.userId());

        assertThat(comments.findVisibleByPostId(postId, viewerId, null, null, 10))
                .extracting(BoardPostComment::getId)
                .containsExactly(tombstoneWithVisibleReply, firstActiveRoot, secondActiveRoot, thirdActiveRoot)
                .doesNotContain(deletedLeaf, deletedRootWithOnlyBlockedReply, deletedRootWithBlockedMiddleAncestor);

        String firstPage = mockMvc.perform(get("/posts/{postId}/comments", postId)
                        .param("size", "3").with(user(principal(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].commentId").value(tombstoneWithVisibleReply))
                .andExpect(jsonPath("$.data.items[0].deleted").value(true))
                .andExpect(jsonPath("$.data.items[0].replies[0].content").value("visible reply"))
                .andExpect(jsonPath("$.data.page.hasNext").value(true))
                .andExpect(jsonPath("$.data.page.nextCursor").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        List<Number> firstIds = com.jayway.jsonpath.JsonPath.read(firstPage, "$.data.items[*].commentId");
        assertThat(firstIds).extracting(Number::longValue)
                .containsExactly(tombstoneWithVisibleReply, firstActiveRoot, secondActiveRoot);

        String cursor = com.jayway.jsonpath.JsonPath.read(firstPage, "$.data.page.nextCursor");
        mockMvc.perform(get("/posts/{postId}/comments", postId)
                        .param("size", "3").param("cursor", cursor).with(user(principal(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].commentId").value(thirdActiveRoot))
                .andExpect(jsonPath("$.data.page.hasNext").value(false))
                .andExpect(jsonPath("$.data.page.nextCursor").doesNotExist());
    }

    @Test
    void listRendersNestedDepthThreeTreeWithStableCreatedAtThenIdSiblingOrdering() throws Exception {
        long boardId = createBoard();
        long viewerId = createUser("viewer", "4113111500");
        Author author = createAuthor("author", "4113111500");
        long postId = insertPost(boardId, author, "PUBLISHED");
        Instant tie = Instant.parse("2026-08-10T00:00:00Z");
        long rootId = insertComment(postId, author.userId(), author.petId(), "root", tie, null);
        long firstReplyId = insertHierarchyComment(postId, author.userId(), author.petId(), "first sibling", tie.plusSeconds(1),
                rootId, rootId, (short) 1);
        long secondReplyId = insertHierarchyComment(postId, author.userId(), author.petId(), "second sibling", tie.plusSeconds(1),
                rootId, rootId, (short) 1);
        long depthTwoId = insertHierarchyComment(postId, author.userId(), author.petId(), "depth two", tie.plusSeconds(2),
                firstReplyId, rootId, (short) 2);
        long depthThreeId = insertHierarchyComment(postId, author.userId(), author.petId(), "depth three", tie.plusSeconds(3),
                depthTwoId, rootId, (short) 3);

        mockMvc.perform(get("/posts/{postId}/comments", postId).with(user(principal(viewerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].commentId").value(rootId))
                .andExpect(jsonPath("$.data.items[0].replies[0].commentId").value(firstReplyId))
                .andExpect(jsonPath("$.data.items[0].replies[1].commentId").value(secondReplyId))
                .andExpect(jsonPath("$.data.items[0].replies[0].replies[0].commentId").value(depthTwoId))
                .andExpect(jsonPath("$.data.items[0].replies[0].replies[0].replies[0].commentId").value(depthThreeId))
                .andExpect(jsonPath("$.data.items[0].replies[0].replies[0].replies[0].depth").value(3));
    }

    @Test
    void listApiRejectsInvalidCursorAndSize() throws Exception {
        long boardId = createBoard();
        Author postAuthor = createAuthor("postAuthor", "4113111500");
        Author viewer = createAuthor("viewer", "4113111500");
        long postId = insertPost(boardId, postAuthor, "PUBLISHED");
        for (String size : List.of("0", "101", "-1")) {
            mockMvc.perform(get("/posts/{postId}/comments", postId)
                            .param("size", size).with(user(principal(viewer.userId()))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        mockMvc.perform(get("/posts/{postId}/comments", postId)
                        .param("cursor", "not-a-comment-cursor").with(user(principal(viewer.userId()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void concurrentManagedCommentUpdatesProduceOneActualOptimisticLockFailure() throws Exception {
        long boardId = createBoard();
        Author author = createAuthor("author", "4113111500");
        long postId = insertPost(boardId, author, "PUBLISHED");
        BoardPostComment comment = comments.saveAndFlush(BoardPostComment.create(postId, author.userId(), author.petId(), "original"));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch bothRead = new CountDownLatch(2);
        CountDownLatch flush = new CountDownLatch(1);
        List<Boolean> outcomes = runConcurrently(index -> {
            try {
                tx.executeWithoutResult(status -> {
                    BoardPostComment managed = comments.findById(comment.getId()).orElseThrow();
                    bothRead.countDown();
                    await(flush);
                    managed.changeContent(index == 0 ? "first" : "second");
                    comments.flush();
                });
                return true;
            } catch (ObjectOptimisticLockingFailureException exception) {
                return false;
            }
        }, bothRead, flush);
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(comments.findById(comment.getId()).orElseThrow().getVersion()).isEqualTo(1L);
    }

    @Test
    void serviceSoftDeleteUsesPessimisticWriteAndConflictsWithAStalePatch() throws Exception {
        long boardId = createBoard();
        Author author = createAuthor("author", "4113111500");
        activate(author);
        long postId = insertPost(boardId, author, "PUBLISHED");
        BoardPostComment comment = comments.saveAndFlush(BoardPostComment.create(postId, author.userId(), author.petId(), "original"));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch patchRead = new CountDownLatch(1);
        CountDownLatch deleteFlushed = new CountDownLatch(1);
        CountDownLatch allowPatchFlush = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> patch = executor.submit(() -> inTransaction(tx, () -> {
                BoardPostComment stale = comments.findById(comment.getId()).orElseThrow();
                patchRead.countDown();
                await(allowPatchFlush);
                stale.changeContent("patched");
                comments.flush();
            }));
            assertThat(patchRead.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> delete = executor.submit(() -> inTransaction(tx, () -> {
                commentService.delete(author.userId(), comment.getId());
                comments.flush();
                deleteFlushed.countDown();
            }));
            assertThat(deleteFlushed.await(10, TimeUnit.SECONDS)).isTrue();
            allowPatchFlush.countDown();
            assertThat(delete.get(30, TimeUnit.SECONDS)).isNull();
            assertThat(patch.get(30, TimeUnit.SECONDS)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        } finally {
            allowPatchFlush.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        BoardPostComment persisted = comments.findById(comment.getId()).orElseThrow();
        assertThat(persisted.getDeletedAt()).isNotNull();
        assertThat(persisted.getVersion()).isEqualTo(1L);
        assertThat(persisted.getContent()).isEqualTo("original");
    }

    @Test
    void createFirstThenPostDeleteWaitsForShareLockAndRetainsCommentHistory() throws Exception {
        long boardId = createBoard();
        Author postAuthor = createAuthor("postAuthor", "4113111500");
        Author commenter = createAuthor("commenter", "4113111500");
        activate(postAuthor);
        activate(commenter);
        long postId = insertPost(boardId, postAuthor, "PUBLISHED");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch commentSavedWithReadLock = new CountDownLatch(1);
        CountDownLatch allowCommentCommit = new CountDownLatch(1);
        CountDownLatch deleteStarted = new CountDownLatch(1);
        AtomicLong deleteBackendPid = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> create = executor.submit(() -> inTransaction(tx, () -> {
                commentService.create(commenter.userId(), postId, new CommentCreateRequest("content"));
                commentSavedWithReadLock.countDown();
                await(allowCommentCommit);
            }));
            assertThat(commentSavedWithReadLock.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> delete = executor.submit(() -> inTransaction(tx, () -> {
                deleteBackendPid.set(jdbc.queryForObject("select pg_backend_pid()", Long.class));
                postService.delete(postAuthor.userId(), postId);
                deleteStarted.countDown();
                posts.flush();
            }));
            assertThat(deleteStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitDatabaseLockWait(deleteBackendPid.get());
            allowCommentCommit.countDown();
            assertThat(create.get(30, TimeUnit.SECONDS)).isNull();
            assertThat(delete.get(30, TimeUnit.SECONDS)).isNull();
        } finally {
            allowCommentCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("select status from board_posts where id = ?", String.class, postId)).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("select count(*) from board_post_comments where post_id = ?", Long.class, postId)).isEqualTo(1L);
    }

    @Test
    void deleteFirstWithFlushedWriteLockMakesWaitingCommentCreateReevaluatePublishedPredicate() throws Exception {
        long boardId = createBoard();
        Author postAuthor = createAuthor("postAuthor", "4113111500");
        Author commenter = createAuthor("commenter", "4113111500");
        activate(postAuthor);
        activate(commenter);
        long postId = insertPost(boardId, postAuthor, "PUBLISHED");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch deleteWriteLocked = new CountDownLatch(1);
        CountDownLatch allowDeleteCommit = new CountDownLatch(1);
        CountDownLatch createStarted = new CountDownLatch(1);
        AtomicLong createBackendPid = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> delete = executor.submit(() -> inTransaction(tx, () -> {
                postService.delete(postAuthor.userId(), postId);
                posts.flush();
                deleteWriteLocked.countDown();
                await(allowDeleteCommit);
            }));
            assertThat(deleteWriteLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> create = executor.submit(() -> inTransaction(tx, () -> {
                createBackendPid.set(jdbc.queryForObject("select pg_backend_pid()", Long.class));
                createStarted.countDown();
                    commentService.create(commenter.userId(), postId, new CommentCreateRequest("content"));
            }));
            assertThat(createStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitDatabaseLockWait(createBackendPid.get());
            allowDeleteCommit.countDown();
            assertThat(delete.get(30, TimeUnit.SECONDS)).isNull();
            assertBusiness(create.get(30, TimeUnit.SECONDS), "BOARD_POST_NOT_FOUND");
        } finally {
            allowDeleteCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("select count(*) from board_post_comments where post_id = ?", Long.class, postId)).isZero();
    }

    @Test
    void replyCreateFirstThenParentDeleteWaitsForActualParentShareLock() throws Exception {
        long boardId = createBoard();
        Author parentAuthor = createAuthor("parentAuthor", "4113111500");
        Author replier = createAuthor("replier", "4113111500");
        activate(parentAuthor);
        activate(replier);
        long postId = insertPost(boardId, parentAuthor, "PUBLISHED");
        long parentId = insertComment(postId, parentAuthor.userId(), parentAuthor.petId(), "root", Instant.now(), null);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch replySaved = new CountDownLatch(1);
        CountDownLatch allowReplyCommit = new CountDownLatch(1);
        CountDownLatch deleteStarted = new CountDownLatch(1);
        AtomicLong deleteBackendPid = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> reply = executor.submit(() -> inTransaction(tx, () -> {
                commentService.createReply(replier.userId(), parentId, new CommentCreateRequest("reply"));
                replySaved.countDown();
                await(allowReplyCommit);
            }));
            assertThat(replySaved.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> delete = executor.submit(() -> inTransaction(tx, () -> {
                deleteBackendPid.set(jdbc.queryForObject("select pg_backend_pid()", Long.class));
                deleteStarted.countDown();
                commentService.delete(parentAuthor.userId(), parentId);
                comments.flush();
            }));
            assertThat(deleteStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitDatabaseLockWait(deleteBackendPid.get());
            allowReplyCommit.countDown();
            assertThat(reply.get(30, TimeUnit.SECONDS)).isNull();
            assertThat(delete.get(30, TimeUnit.SECONDS)).isNull();
        } finally {
            allowReplyCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("select deleted_at is not null from board_post_comments where id = ?", Boolean.class, parentId)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from board_post_comments where parent_comment_id = ?", Long.class, parentId)).isEqualTo(1L);
    }

    @Test
    void parentDeleteFirstMakesWaitingReplyCreateReevaluateActiveParentPredicate() throws Exception {
        long boardId = createBoard();
        Author parentAuthor = createAuthor("parentAuthor", "4113111500");
        Author replier = createAuthor("replier", "4113111500");
        activate(parentAuthor);
        activate(replier);
        long postId = insertPost(boardId, parentAuthor, "PUBLISHED");
        long parentId = insertComment(postId, parentAuthor.userId(), parentAuthor.petId(), "root", Instant.now(), null);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch deleteFlushed = new CountDownLatch(1);
        CountDownLatch allowDeleteCommit = new CountDownLatch(1);
        CountDownLatch replyStarted = new CountDownLatch(1);
        AtomicLong replyBackendPid = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> delete = executor.submit(() -> inTransaction(tx, () -> {
                commentService.delete(parentAuthor.userId(), parentId);
                comments.flush();
                deleteFlushed.countDown();
                await(allowDeleteCommit);
            }));
            assertThat(deleteFlushed.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> reply = executor.submit(() -> inTransaction(tx, () -> {
                replyBackendPid.set(jdbc.queryForObject("select pg_backend_pid()", Long.class));
                replyStarted.countDown();
                commentService.createReply(replier.userId(), parentId, new CommentCreateRequest("reply"));
            }));
            assertThat(replyStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitDatabaseLockWait(replyBackendPid.get());
            allowDeleteCommit.countDown();
            assertThat(delete.get(30, TimeUnit.SECONDS)).isNull();
            assertBusiness(reply.get(30, TimeUnit.SECONDS), "BOARD_POST_COMMENT_NOT_FOUND");
        } finally {
            allowDeleteCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("select count(*) from board_post_comments where parent_comment_id = ?", Long.class, parentId)).isZero();
    }

    @Test
    void replyCreateFirstThenPostDeleteWaitsForPostShareLockAndRetainsReplyHistory() throws Exception {
        long boardId = createBoard();
        Author postAuthor = createAuthor("postAuthor", "4113111500");
        Author replier = createAuthor("replier", "4113111500");
        activate(postAuthor);
        activate(replier);
        long postId = insertPost(boardId, postAuthor, "PUBLISHED");
        long parentId = insertComment(postId, postAuthor.userId(), postAuthor.petId(), "root", Instant.now(), null);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch replySaved = new CountDownLatch(1);
        CountDownLatch allowReplyCommit = new CountDownLatch(1);
        CountDownLatch deleteStarted = new CountDownLatch(1);
        AtomicLong deleteBackendPid = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> reply = executor.submit(() -> inTransaction(tx, () -> {
                commentService.createReply(replier.userId(), parentId, new CommentCreateRequest("reply"));
                replySaved.countDown();
                await(allowReplyCommit);
            }));
            assertThat(replySaved.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> delete = executor.submit(() -> inTransaction(tx, () -> {
                deleteBackendPid.set(jdbc.queryForObject("select pg_backend_pid()", Long.class));
                postService.delete(postAuthor.userId(), postId);
                deleteStarted.countDown();
                posts.flush();
            }));
            assertThat(deleteStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitDatabaseLockWait(deleteBackendPid.get());
            allowReplyCommit.countDown();
            assertThat(reply.get(30, TimeUnit.SECONDS)).isNull();
            assertThat(delete.get(30, TimeUnit.SECONDS)).isNull();
        } finally {
            allowReplyCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("select status from board_posts where id = ?", String.class, postId)).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("select count(*) from board_post_comments where parent_comment_id = ?", Long.class, parentId)).isEqualTo(1L);
    }

    @Test
    void postDeleteFirstMakesWaitingReplyCreateReevaluatePublishedPredicate() throws Exception {
        long boardId = createBoard();
        Author postAuthor = createAuthor("postAuthor", "4113111500");
        Author replier = createAuthor("replier", "4113111500");
        activate(postAuthor);
        activate(replier);
        long postId = insertPost(boardId, postAuthor, "PUBLISHED");
        long parentId = insertComment(postId, postAuthor.userId(), postAuthor.petId(), "root", Instant.now(), null);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch deleteFlushed = new CountDownLatch(1);
        CountDownLatch allowDeleteCommit = new CountDownLatch(1);
        CountDownLatch replyStarted = new CountDownLatch(1);
        AtomicLong replyBackendPid = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> delete = executor.submit(() -> inTransaction(tx, () -> {
                postService.delete(postAuthor.userId(), postId);
                posts.flush();
                deleteFlushed.countDown();
                await(allowDeleteCommit);
            }));
            assertThat(deleteFlushed.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> reply = executor.submit(() -> inTransaction(tx, () -> {
                replyBackendPid.set(jdbc.queryForObject("select pg_backend_pid()", Long.class));
                replyStarted.countDown();
                commentService.createReply(replier.userId(), parentId, new CommentCreateRequest("reply"));
            }));
            assertThat(replyStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitDatabaseLockWait(replyBackendPid.get());
            allowDeleteCommit.countDown();
            assertThat(delete.get(30, TimeUnit.SECONDS)).isNull();
            assertBusiness(reply.get(30, TimeUnit.SECONDS), "BOARD_POST_NOT_FOUND");
        } finally {
            allowDeleteCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("select count(*) from board_post_comments where parent_comment_id = ?", Long.class, parentId)).isZero();
    }

    private long createBoard() {
        return jdbc.queryForObject("insert into boards (name) values (?) returning id", Long.class, unique("board"));
    }

    private Author createAuthor(String nickname, String neighborhood) {
        long userId = createUser(nickname, neighborhood);
        long petId = jdbc.queryForObject("""
                insert into pets (owner_user_id, public_tag, nickname, status)
                values (?, ?, ?, 'ACTIVE') returning id
                """, Long.class, userId, tag(nickname, 4), nickname);
        return new Author(userId, petId);
    }

    private long createUser(String nickname, String neighborhood) {
        return jdbc.queryForObject("""
                insert into users (email, password_hash, nickname, public_tag, role, account_status, neighborhood_code)
                values (?, 'encoded', ?, ?, 'USER', 'ACTIVE', ?) returning id
                """, Long.class, unique("user") + "@example.com", nickname, tag(nickname, 8), neighborhood);
    }

    private long insertPost(long boardId, Author author, String status) {
        return jdbc.queryForObject("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status)
                values (?, ?, ?, '4113111500', 'title', 'content', ?) returning id
                """, Long.class, boardId, author.userId(), author.petId(), status);
    }

    private long insertComment(long postId, long userId, long petId, String content, Instant createdAt, Instant deletedAt) {
        return jdbc.queryForObject("""
                insert into board_post_comments (post_id, author_user_id, author_pet_id, content, created_at, updated_at, deleted_at)
                values (?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, postId, userId, petId, content,
                java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(createdAt),
                deletedAt == null ? null : java.sql.Timestamp.from(deletedAt));
    }

    private long insertHierarchyComment(long postId, long userId, long petId, String content, Instant createdAt,
            Long parentCommentId, Long rootCommentId, short depth) {
        return jdbc.queryForObject("""
                insert into board_post_comments
                    (post_id, author_user_id, author_pet_id, content, parent_comment_id, root_comment_id, depth,
                     created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, postId, userId, petId, content, parentCommentId, rootCommentId, depth,
                java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(createdAt));
    }

    private void activate(Author author) {
        jdbc.update("update users set active_pet_id = ? where id = ?", author.petId(), author.userId());
    }

    private List<Boolean> runConcurrently(IndexedAction action, CountDownLatch bothRead, CountDownLatch flush) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = List.of(executor.submit(() -> action.apply(0)), executor.submit(() -> action.apply(1)));
            assertThat(bothRead.await(10, TimeUnit.SECONDS)).isTrue();
            flush.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        } finally {
            flush.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Throwable inTransaction(TransactionTemplate tx, ThrowingAction action) {
        try {
            tx.executeWithoutResult(status -> {
                try {
                    action.run();
                } catch (Exception exception) {
                    throw new TransactionActionFailure(exception);
                }
            });
            return null;
        } catch (Throwable error) {
            if (error instanceof TransactionActionFailure failure) {
                return failure.getCause();
            }
            return error;
        }
    }

    private void awaitDatabaseLockWait(long contenderBackendPid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc.queryForObject("""
                    select wait_event_type = 'Lock' and cardinality(pg_blocking_pids(pid)) > 0
                      from pg_stat_activity where pid = ?
                    """, Boolean.class, contenderBackendPid);
            if (Boolean.TRUE.equals(waiting)) return;
        }
        throw new AssertionError("contender did not block on a PostgreSQL lock");
    }

    private void assertBusiness(Throwable error, String code) {
        assertThat(error).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) error).getErrorCode().name()).isEqualTo(code);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("concurrent test timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private Flyway configuredFlyway(String schema, String targetVersion) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .cleanDisabled(false);
        if (targetVersion != null) {
            configuration.target(targetVersion);
        }
        return configuration.load();
    }

    private JdbcTemplate schemaJdbc(String schema) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()
        );
        Properties properties = new Properties();
        properties.setProperty("currentSchema", schema);
        dataSource.setConnectionProperties(properties);
        return new JdbcTemplate(dataSource);
    }

    private String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
    }

    private String tag(String nickname, int length) {
        return nickname + "#" + UUID.randomUUID().toString().replace("-", "").substring(0, length).toUpperCase();
    }

    private CurrentUser principal(long userId) {
        return new CurrentUser(userId, "user" + userId + "@test.com", Role.USER);
    }

    private record Author(long userId, long petId) {}

    @FunctionalInterface
    private interface IndexedAction { boolean apply(int index) throws Exception; }

    @FunctionalInterface
    private interface ThrowingAction { void run() throws Exception; }

    private static final class TransactionActionFailure extends RuntimeException {
        private TransactionActionFailure(Exception cause) {
            super(cause);
        }
    }
}
