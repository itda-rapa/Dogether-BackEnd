package itda.comment;

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
import java.util.UUID;
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
class BoardPostCommentApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BoardRepository boards;

    private long authorId;
    private long authorPetId;
    private long boardId;
    private long postId;

    @BeforeEach
    void setUp() {
        jdbc.execute("delete from user_blocks");
        jdbc.execute("delete from board_post_comments");
        jdbc.execute("delete from board_posts");
        jdbc.execute("delete from pets");
        jdbc.execute("delete from users");
        jdbc.execute("delete from boards");
        authorId = createUser("author", "4113111500");
        authorPetId = createPet(authorId, "작성견");
        jdbc.update("update users set active_pet_id = ? where id = ?", authorPetId, authorId);
        boardId = boards.saveAndFlush(Board.create("자유")).getId();
        postId = createPublishedPost(authorId, authorPetId, "4113111500");
    }

    @Test
    void createUsesAuthenticatedActivePetSnapshotAndRejectsStrictShapeOrInvalidContent() throws Exception {
        for (String body : new String[] {
                "null", "[]", "{}", "{\"content\":null}", "{\"content\":1}",
                "{\"content\":\"text\",\"authorPetId\":1}",
                "{\"content\":\"text\",\"version\":0}",
                "{\"content\":\" \"}",
                "{\"content\":\"" + "😀".repeat(5001) + "\"}"
        }) {
            create(authorId, postId, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }

        long secondPet = createPet(authorId, "둘째견");
        create(authorId, postId, "{\"content\":\"  원문  \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.postId").value(postId))
                .andExpect(jsonPath("$.data.content").value("  원문  "))
                .andExpect(jsonPath("$.data.authorPet.petId").value(authorPetId));
        long commentId = latestCommentId();
        jdbc.update("update users set active_pet_id = ? where id = ?", secondPet, authorId);
        assertThat(jdbc.queryForMap("select author_user_id, author_pet_id, content from board_post_comments where id = ?", commentId))
                .containsEntry("AUTHOR_USER_ID", authorId).containsEntry("AUTHOR_PET_ID", authorPetId)
                .containsEntry("CONTENT", "  원문  ");
    }

    @Test
    void apiRequiresAuthenticationAndAUsableActivePetForEveryMutation() throws Exception {
        mockMvc.perform(post("/posts/{postId}/comments", postId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"text\"}"))
                .andExpect(status().isUnauthorized());
        long commentId = createComment(authorId, postId, "content");
        jdbc.update("update users set active_pet_id = null where id = ?", authorId);
        create(authorId, postId, "{\"content\":\"text\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACTIVE_PET_REQUIRED"));
        patchComment(authorId, commentId, "{\"content\":\"changed\",\"version\":0}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACTIVE_PET_REQUIRED"));
        mockMvc.perform(delete("/comments/{commentId}", commentId).with(user(principal(authorId))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACTIVE_PET_REQUIRED"));
    }

    @Test
    void patchIsStrictNoOpPreservesVersionAndTimeAndStaleRequestConflicts() throws Exception {
        long commentId = createComment(authorId, postId, "original");
        for (String body : new String[] {
                "null", "[]", "{}", "{\"content\":\"x\"}", "{\"version\":0}",
                "{\"content\":\"x\",\"version\":null}", "{\"content\":\"x\",\"version\":1.5}",
                "{\"content\":\"x\",\"version\":-1}", "{\"content\":\"x\",\"version\":0,\"extra\":true}"
        }) {
            patchComment(authorId, commentId, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        Instant before = jdbc.queryForObject("select updated_at from board_post_comments where id = ?", Instant.class, commentId);
        patchComment(authorId, commentId, "{\"content\":\"original\",\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(0));
        assertThat(jdbc.queryForObject("select updated_at from board_post_comments where id = ?", Instant.class, commentId))
                .isEqualTo(before);
        patchComment(authorId, commentId, "{\"content\":\"changed\",\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        patchComment(authorId, commentId, "{\"content\":\"again\",\"version\":0}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONCURRENT_UPDATE_CONFLICT"));
        assertThat(jdbc.queryForObject("select content from board_post_comments where id = ?", String.class, commentId))
                .isEqualTo("changed");
    }

    @Test
    void mutationRequiresTheOriginalUsersCurrentlyActiveAuthorPet() throws Exception {
        long commentId = createComment(authorId, postId, "original");
        long secondPet = createPet(authorId, "둘째견");
        jdbc.update("update users set active_pet_id = ? where id = ?", secondPet, authorId);

        patchComment(authorId, commentId, "{\"content\":\"hijack\",\"version\":0}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("BOARD_POST_COMMENT_FORBIDDEN"));
        mockMvc.perform(delete("/comments/{commentId}", commentId).with(user(principal(authorId))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("BOARD_POST_COMMENT_FORBIDDEN"));
        assertThat(jdbc.queryForObject("select deleted_at from board_post_comments where id = ?", Instant.class, commentId)).isNull();
    }

    @Test
    void otherUserCannotMutateCommentAndParentBlockIsBilateralWhilePostAuthorKeepsAccessAfterMoving() throws Exception {
        long commentId = createComment(authorId, postId, "original");
        long other = createUser("other", "4113111500");
        long otherPet = createPet(other, "다른견");
        jdbc.update("update users set active_pet_id = ? where id = ?", otherPet, other);
        patchComment(other, commentId, "{\"content\":\"hijack\",\"version\":0}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("BOARD_POST_COMMENT_FORBIDDEN"));
        mockMvc.perform(delete("/comments/{commentId}", commentId).with(user(principal(other))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("BOARD_POST_COMMENT_FORBIDDEN"));

        jdbc.update("insert into user_blocks (blocker_user_id, blocked_user_id, created_at) values (?, ?, current_timestamp)", other, authorId);
        create(other, postId, "{\"content\":\"blocked\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("BOARD_POST_NOT_FOUND"));
        mockMvc.perform(get("/posts/{postId}/comments", postId).with(user(principal(other))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("BOARD_POST_NOT_FOUND"));
        jdbc.execute("delete from user_blocks");
        jdbc.update("insert into user_blocks (blocker_user_id, blocked_user_id, created_at) values (?, ?, current_timestamp)", authorId, other);
        create(other, postId, "{\"content\":\"reverse blocked\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("BOARD_POST_NOT_FOUND"));
        mockMvc.perform(get("/posts/{postId}/comments", postId).with(user(principal(other))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("BOARD_POST_NOT_FOUND"));

        jdbc.execute("delete from user_blocks");
        jdbc.update("update users set neighborhood_code = ? where id = ?", "4113111600", authorId);
        create(authorId, postId, "{\"content\":\"author keeps access\"}")
                .andExpect(status().isCreated());
        mockMvc.perform(get("/posts/{postId}/comments", postId).with(user(principal(authorId))))
                .andExpect(status().isOk());
    }

    @Test
    void parentVisibilityIsAppliedToCreateAndListAndDeletedParentAllowsOnlyOwnedDelete() throws Exception {
        long otherRegion = createUser("otherRegion", "4113111600");
        long otherPet = createPet(otherRegion, "먼견");
        jdbc.update("update users set active_pet_id = ? where id = ?", otherPet, otherRegion);
        create(otherRegion, postId, "{\"content\":\"hidden\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("BOARD_POST_NOT_FOUND"));
        mockMvc.perform(get("/posts/{postId}/comments", postId).with(user(principal(otherRegion))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("BOARD_POST_NOT_FOUND"));

        long commentId = createComment(authorId, postId, "before delete");
        jdbc.update("update board_posts set status = 'DELETED', deleted_at = current_timestamp where id = ?", postId);
        create(authorId, postId, "{\"content\":\"after delete\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("BOARD_POST_NOT_FOUND"));
        mockMvc.perform(get("/posts/{postId}/comments", postId).with(user(principal(authorId))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("BOARD_POST_NOT_FOUND"));
        patchComment(authorId, commentId, "{\"content\":\"blocked\",\"version\":0}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("BOARD_POST_NOT_FOUND"));
        mockMvc.perform(delete("/comments/{commentId}", commentId).with(user(principal(authorId))))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select deleted_at is not null from board_post_comments where id = ?", Boolean.class, commentId))
                .isTrue();
    }

    private ResultActions create(long userId, long targetPostId, String body) throws Exception {
        return mockMvc.perform(post("/posts/{postId}/comments", targetPostId).with(user(principal(userId)))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions patchComment(long userId, long commentId, String body) throws Exception {
        return mockMvc.perform(patch("/comments/{commentId}", commentId).with(user(principal(userId)))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long createComment(long userId, long targetPostId, String content) throws Exception {
        String response = create(userId, targetPostId, "{\"content\":\"%s\"}".formatted(content))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.data.commentId")).longValue();
    }

    private long createPublishedPost(long userId, long petId, String neighborhood) {
        jdbc.update("""
                insert into board_posts (board_id, author_user_id, author_pet_id, neighborhood_code, title, content, status, version, created_at, updated_at)
                values (?, ?, ?, ?, 'title', 'content', 'PUBLISHED', 0, current_timestamp, current_timestamp)
                """, boardId, userId, petId, neighborhood);
        return jdbc.queryForObject("select max(id) from board_posts", Long.class);
    }

    private long createUser(String nickname, String neighborhood) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                insert into users (email, password_hash, nickname, public_tag, role, account_status, neighborhood_code, version, created_at, updated_at)
                values (?, 'encoded', ?, ?, 'USER', 'ACTIVE', ?, 0, current_timestamp, current_timestamp)
                """, unique + "@test.com", nickname, nickname + unique.substring(0, 8), neighborhood);
        return jdbc.queryForObject("select max(id) from users", Long.class);
    }

    private long createPet(long ownerId, String nickname) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                insert into pets (owner_user_id, public_tag, nickname, status, version, created_at, updated_at)
                values (?, ?, ?, 'ACTIVE', 0, current_timestamp, current_timestamp)
                """, ownerId, nickname + unique.substring(0, 6), nickname);
        return jdbc.queryForObject("select max(id) from pets", Long.class);
    }

    private long latestCommentId() {
        return jdbc.queryForObject("select max(id) from board_post_comments", Long.class);
    }

    private CurrentUser principal(long userId) {
        return new CurrentUser(userId, "user" + userId + "@test.com", Role.USER);
    }
}
