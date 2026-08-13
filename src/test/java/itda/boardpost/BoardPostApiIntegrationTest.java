package itda.boardpost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import itda.board.domain.Board;
import itda.board.repository.BoardRepository;
import itda.common.security.CurrentUser;
import itda.user.domain.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class BoardPostApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BoardRepository boards;

    private long authorId;
    private long authorPetId;
    private long boardId;

    @BeforeEach
    void setUp() {
        jdbc.execute("delete from user_blocks");
        jdbc.execute("delete from board_posts");
        jdbc.execute("delete from pets");
        jdbc.execute("delete from users");
        jdbc.execute("delete from boards");
        authorId = createUser("author", "4113111500");
        authorPetId = createPet(authorId, "작성견");
        jdbc.update("update users set active_pet_id = ? where id = ?", authorPetId, authorId);
        boardId = boards.saveAndFlush(Board.create("자유")).getId();
    }

    @Test
    void createRejectsServerManagedAndUnknownFieldsAndPreservesOriginalText() throws Exception {
        for (String body : new String[] {
                "{\"title\":\"t\",\"content\":\"c\",\"authorUserId\":1}",
                "{\"title\":\"t\",\"content\":\"c\",\"extra\":true}",
                "{\"title\":\"t\"}",
                "[]"
        }) {
            create(authorId, boardId, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }

        create(authorId, boardId, "{\"title\":\"  제목  \",\"content\":\"  내용  \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.title").value("  제목  "))
                .andExpect(jsonPath("$.data.content").value("  내용  "))
                .andExpect(jsonPath("$.data.authorPet.nickname").value("작성견"));
    }

    @Test
    void createRejectsNullNonObjectAndUnicodeBoundaryViolations() throws Exception {
        for (String body : new String[] { "null", "[]", "{\"title\":null,\"content\":\"x\"}",
                "{\"title\":\"x\",\"content\":null}", "{\"title\":\" \",\"content\":\"x\"}",
                "{\"title\":\"x\",\"content\":\"\\t\"}",
                "{\"title\":\"" + "😀".repeat(121) + "\",\"content\":\"x\"}",
                "{\"title\":\"x\",\"content\":\"" + "😀".repeat(5001) + "\"}" }) {
            create(authorId, boardId, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        // H2 VARCHAR counts UTF-16 units, unlike PostgreSQL VARCHAR character semantics;
        // the 120-emoji acceptance boundary is asserted by the PostgreSQL suite.
        create(authorId, boardId, "{\"title\":\"" + "가".repeat(120) + "\",\"content\":\"" + "가".repeat(5000) + "\"}")
                .andExpect(status().isCreated());
    }

    @Test
    void createRejectsAbsentBodyMissingFieldsAndWrongFieldTypes() throws Exception {
        mockMvc.perform(post("/boards/{boardId}/posts", boardId).with(user(principal(authorId))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        for (String body : new String[] { "{}", "{\"title\":\"only\"}", "{\"content\":\"only\"}",
                "{\"title\":3,\"content\":\"text\"}", "{\"title\":\"title\",\"content\":false}" }) {
            create(authorId, boardId, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void createRequiresUsableActivePetAndRejectsAbsentSuspendedAndDeletedActor() throws Exception {
        jdbc.update("update users set active_pet_id = null where id = ?", authorId);
        create(authorId, boardId, "{\"title\":\"t\",\"content\":\"c\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACTIVE_PET_REQUIRED"));
        jdbc.update("update users set active_pet_id = ? where id = ?", authorPetId, authorId);
        jdbc.update("update pets set status = 'SUSPENDED' where id = ?", authorPetId);
        create(authorId, boardId, "{\"title\":\"t\",\"content\":\"c\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACTIVE_PET_REQUIRED"));
        jdbc.update("update pets set status = 'DELETED', deleted_at = current_timestamp where id = ?", authorPetId);
        create(authorId, boardId, "{\"title\":\"t\",\"content\":\"c\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACTIVE_PET_REQUIRED"));
    }

    @Test
    void createReturnsBoardNotFoundForMissingBoard() throws Exception {
        create(authorId, 999999999L, "{\"title\":\"t\",\"content\":\"c\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("BOARD_NOT_FOUND"));
    }

    @Test
    void suspendedAndDeletedAuthorsActivePetCannotPatchOrDeleteAndLeavePostsPublished() throws Exception {
        long suspendedPostId = createPost(authorId, "suspended", "content");
        long deletedPostId = createPost(authorId, "deleted", "content");
        jdbc.update("update pets set status = 'SUSPENDED' where id = ?", authorPetId);
        mockMvc.perform(patch("/posts/{postId}", suspendedPostId).with(user(principal(authorId))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"new\",\"version\":0}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACTIVE_PET_REQUIRED"));
        mockMvc.perform(delete("/posts/{postId}", suspendedPostId).with(user(principal(authorId))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACTIVE_PET_REQUIRED"));
        Map<String, Object> suspendedState = jdbc.queryForMap("""
                select title, content, status, deleted_at, version from board_posts where id = ?
                """, suspendedPostId);
        assertThat(suspendedState).containsEntry("title", "suspended").containsEntry("content", "content")
                .containsEntry("status", "PUBLISHED").containsEntry("version", 0L)
                .containsEntry("deleted_at", null);
        jdbc.update("update pets set status = 'DELETED', deleted_at = current_timestamp where id = ?", authorPetId);
        mockMvc.perform(patch("/posts/{postId}", deletedPostId).with(user(principal(authorId))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"new\",\"version\":0}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACTIVE_PET_REQUIRED"));
        mockMvc.perform(delete("/posts/{postId}", deletedPostId).with(user(principal(authorId))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACTIVE_PET_REQUIRED"));
        Map<String, Object> deletedState = jdbc.queryForMap("""
                select title, content, status, deleted_at, version from board_posts where id = ?
                """, deletedPostId);
        assertThat(deletedState).containsEntry("title", "deleted").containsEntry("content", "content")
                .containsEntry("status", "PUBLISHED").containsEntry("version", 0L)
                .containsEntry("deleted_at", null);
    }

    @Test
    void otherUserCannotMutateAuthorsPost() throws Exception {
        long postId = createPost(authorId, "title", "content");
        long other = createUser("other", "4113111500");
        long otherPet = createPet(other, "otherPet");
        jdbc.update("update users set active_pet_id = ? where id = ?", otherPet, other);
        mockMvc.perform(patch("/posts/{postId}", postId).with(user(principal(other))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"hijack\",\"version\":0}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("BOARD_POST_FORBIDDEN"));
        mockMvc.perform(delete("/posts/{postId}", postId).with(user(principal(other))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("BOARD_POST_FORBIDDEN"));
    }

    @Test
    void createUsesAuthenticatedUsersActivePetAndNeighborhoodSnapshot() throws Exception {
        long secondPet = createPet(authorId, "둘째견");
        long other = createUser("other", "4113111500");
        long otherPet = createPet(other, "다른견");
        jdbc.update("update users set active_pet_id = ? where id = ?", otherPet, other);

        long postId = createPost(authorId, "제목", "내용");
        jdbc.update("update users set neighborhood_code = ? where id = ?", "4113111600", authorId);
        jdbc.update("update users set active_pet_id = ? where id = ?", secondPet, authorId);

        mockMvc.perform(get("/posts/{postId}", postId).with(user(principal(authorId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorPet.nickname").value("작성견"));
        mockMvc.perform(patch("/posts/{postId}", postId).with(user(principal(authorId)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"변경\",\"version\":0}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("BOARD_POST_FORBIDDEN"));
        mockMvc.perform(delete("/posts/{postId}", postId).with(user(principal(authorId))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("BOARD_POST_FORBIDDEN"));
        jdbc.update("update users set active_pet_id = ? where id = ?", authorPetId, authorId);
        mockMvc.perform(patch("/posts/{postId}", postId).with(user(principal(authorId)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"reselected\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("reselected"));
        // Feed uses PostgreSQL-specific TIMESTAMPTZ casts and is covered in the
        // PostgreSQL integration suite rather than this H2 API-contract suite.
    }

    @Test
    void patchValidatesShapeRejectsStaleAndNoOpKeepsVersion() throws Exception {
        long postId = createPost(authorId, "제목", "내용");
        for (String body : new String[] { null, "null", "[]", "{}", "{\"title\":\"x\"}",
                "{\"title\":\"x\",\"version\":null}", "{\"title\":\"x\",\"version\":1.5}",
                "{\"title\":\"x\",\"version\":-1}", "{\"title\":\"x\",\"version\":0,\"x\":true}" }) {
            ResultActions action = mockMvc.perform(patch("/posts/{postId}", postId).with(user(principal(authorId))));
            if (body != null) action = mockMvc.perform(patch("/posts/{postId}", postId).with(user(principal(authorId)))
                    .contentType(MediaType.APPLICATION_JSON).content(body));
            action.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        Instant before = jdbc.queryForObject("select updated_at from board_posts where id = ?", Instant.class, postId);
        mockMvc.perform(patch("/posts/{postId}", postId).with(user(principal(authorId)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"제목\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(0));
        assertThat(jdbc.queryForObject("select updated_at from board_posts where id = ?", Instant.class, postId)).isEqualTo(before);
        mockMvc.perform(patch("/posts/{postId}", postId).with(user(principal(authorId)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"변경\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(patch("/posts/{postId}", postId).with(user(principal(authorId)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"다시\",\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONCURRENT_UPDATE_CONFLICT"));
        mockMvc.perform(patch("/posts/{postId}", postId).with(user(principal(authorId)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"변경\",\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONCURRENT_UPDATE_CONFLICT"));
        Map<String, Object> staleSameValueState = jdbc.queryForMap("""
                select content, version from board_posts where id = ?
                """, postId);
        assertThat(staleSameValueState).containsEntry("content", "변경").containsEntry("version", 1L);
    }

    @Test
    void patchRejectsSemanticInvalidityAndSupportsTitleContentAndBoth() throws Exception {
        long postId = createPost(authorId, "original", "content");
        for (String body : new String[] { "{\"title\":\" \",\"version\":0}",
                "{\"content\":\"\\n\",\"version\":0}",
                "{\"title\":\"" + "가".repeat(121) + "\",\"version\":0}",
                "{\"content\":\"" + "가".repeat(5001) + "\",\"version\":0}" }) {
            mockMvc.perform(patch("/posts/{postId}", postId).with(user(principal(authorId)))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        mockMvc.perform(patch("/posts/{postId}", postId).with(user(principal(authorId)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"title only\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("title only"))
                .andExpect(jsonPath("$.data.content").value("content")).andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(patch("/posts/{postId}", postId).with(user(principal(authorId)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"content only\",\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").value("content only"))
                .andExpect(jsonPath("$.data.version").value(2));
        mockMvc.perform(patch("/posts/{postId}", postId).with(user(principal(authorId)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"both title\",\"content\":\"both content\",\"version\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("both title"))
                .andExpect(jsonPath("$.data.content").value("both content")).andExpect(jsonPath("$.data.version").value(3));
    }

    @Test
    void concurrentSameVersionPatchHasOneSuccessOneStaleConflictAndOnePersistedUpdate() throws Exception {
        long postId = createPost(authorId, "original", "content");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String title : List.of("first", "second")) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                    return mockMvc.perform(patch("/posts/{postId}", postId).with(user(principal(authorId)))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"title\":\"%s\",\"version\":0}".formatted(title)))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) statuses.add(result.get(30, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.queryForObject("select title from board_posts where id = ?", String.class, postId))
                    .isIn("first", "second");
            assertThat(jdbc.queryForObject("select version from board_posts where id = ?", Long.class, postId)).isEqualTo(1L);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void detailHidesPublishedPostFromViewerInOtherNeighborhood() throws Exception {
        long postId = createPost(authorId, "title", "content");
        long other = createUser("otherRegion", "4113111600");
        mockMvc.perform(get("/posts/{postId}", postId).with(user(principal(other))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("BOARD_POST_NOT_FOUND"));
    }

    @Test
    void feedFiltersNeighborhoodAndBlockBeforePagingAndDeletedPostsAreHidden() throws Exception {
        long sameArea = createUser("same", "4113111500");
        long samePet = createPet(sameArea, "같은견");
        jdbc.update("update users set active_pet_id = ? where id = ?", samePet, sameArea);
        long otherArea = createUser("far", "4113111600");
        long farPet = createPet(otherArea, "먼견");
        jdbc.update("update users set active_pet_id = ? where id = ?", farPet, otherArea);
        long visible = createPost(authorId, "보임", "내용");
        long hidden = createPost(sameArea, "차단됨", "내용");
        createPost(otherArea, "지역다름", "내용");
        jdbc.update("insert into user_blocks (blocker_user_id, blocked_user_id, created_at) values (?, ?, current_timestamp)", authorId, sameArea);
        mockMvc.perform(get("/posts/{postId}", hidden).with(user(principal(authorId))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("BOARD_POST_NOT_FOUND"));
        mockMvc.perform(delete("/posts/{postId}", visible).with(user(principal(authorId))))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/posts/{postId}", visible).with(user(principal(authorId))))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/posts/{postId}", visible).with(user(principal(authorId))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("BOARD_POST_NOT_FOUND"));
    }

    @Test
    void boardDeletionRequiresAdminAndIsBlockedOnlyByPublishedPost() throws Exception {
        long postId = createPost(authorId, "제목", "내용");
        mockMvc.perform(delete("/admin/boards/{boardId}", boardId).with(user(principal(authorId))))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/boards/{boardId}", boardId).with(user(admin())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("BOARD_NOT_EMPTY"));
        mockMvc.perform(delete("/posts/{postId}", postId).with(user(principal(authorId))))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/admin/boards/{boardId}", boardId).with(user(admin())))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from boards where id = ?", Integer.class, boardId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select deleted_at from boards where id = ?", Instant.class, boardId))
                .isNotNull();
        assertThat(jdbc.queryForObject("select status from board_posts where id = ?", String.class, postId))
                .isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("select deleted_at from board_posts where id = ?", Instant.class, postId))
                .isNotNull();
    }

    @Test
    void boardDeletionAllowsEmptyBoardAndReturnsNotFoundForMissingBoard() throws Exception {
        long emptyBoardId = boards.saveAndFlush(Board.create("빈 게시판")).getId();
        mockMvc.perform(delete("/admin/boards/{boardId}", emptyBoardId).with(user(principal(authorId))))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/boards/{boardId}", emptyBoardId).with(user(admin())))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from boards where id = ?", Integer.class, emptyBoardId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select deleted_at from boards where id = ?", Instant.class, emptyBoardId))
                .isNotNull();
        mockMvc.perform(delete("/admin/boards/{boardId}", emptyBoardId).with(user(admin())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("BOARD_NOT_FOUND"));
    }

    @Test
    void rejectsPostCreateAndFeedOnSoftDeletedBoard() throws Exception {
        mockMvc.perform(delete("/admin/boards/{boardId}", boardId).with(user(admin())))
                .andExpect(status().isNoContent());

        create(authorId, boardId, "{\"title\":\"제목\",\"content\":\"내용\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BOARD_NOT_FOUND"));
        mockMvc.perform(get("/boards/{boardId}/posts", boardId).with(user(principal(authorId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BOARD_NOT_FOUND"));
    }

    private ResultActions create(long userId, long targetBoardId, String body) throws Exception {
        return mockMvc.perform(post("/boards/{boardId}/posts", targetBoardId).with(user(principal(userId)))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private long createPost(long userId, String title, String content) throws Exception {
        String response = create(userId, boardId, "{\"title\":\"%s\",\"content\":\"%s\"}".formatted(title, content))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.postId")).longValue();
    }
    private long createUser(String name, String neighborhood) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("insert into users (email, password_hash, nickname, public_tag, role, account_status, neighborhood_code, version, created_at, updated_at) values (?, 'encoded', ?, ?, 'USER', 'ACTIVE', ?, 0, current_timestamp, current_timestamp)", unique + "@test.com", name, name + unique.substring(0, 8), neighborhood);
        return jdbc.queryForObject("select max(id) from users", Long.class);
    }
    private long createPet(long ownerId, String name) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("insert into pets (owner_user_id, public_tag, nickname, status, version, created_at, updated_at) values (?, ?, ?, 'ACTIVE', 0, current_timestamp, current_timestamp)", ownerId, name + unique.substring(0, 6), name);
        return jdbc.queryForObject("select max(id) from pets", Long.class);
    }
    private CurrentUser principal(long userId) { return new CurrentUser(userId, "user" + userId + "@test.com", Role.USER); }
    private CurrentUser admin() { return new CurrentUser(999L, "admin@test.com", Role.ADMIN); }
}
