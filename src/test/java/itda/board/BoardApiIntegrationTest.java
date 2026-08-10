package itda.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.board.domain.Board;
import itda.board.dto.BoardCreateRequest;
import itda.board.dto.BoardResponse;
import itda.board.dto.BoardUpdateRequest;
import itda.board.repository.BoardRepository;
import itda.board.service.BoardService;
import itda.common.security.CurrentUser;
import itda.user.domain.Role;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class BoardApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BoardRepository boardRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BoardService boardService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetBoards() {
        boardRepository.deleteAll();
    }

    @Test
    void createsNormalizedBoardAndReturnsIdNameAndVersion() throws Exception {
        create(Role.ADMIN, "  자유게시판  ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.boardId").isNumber())
                .andExpect(jsonPath("$.data.name").value("자유게시판"))
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    void rejectsInvalidPostNamesIncludingThirtyOneUnicodeCodePoints()
            throws Exception {
        String thirtyOneCodePoints = "😀".repeat(31);
        assertThat(thirtyOneCodePoints.length()).isEqualTo(62);
        assertThat(thirtyOneCodePoints.codePointCount(
                0,
                thirtyOneCodePoints.length()
        )).isEqualTo(31);

        for (String body : new String[]{
                "{\"name\":null}",
                "{\"name\":\" \\t\\n\"}",
                "{\"name\":\"  " + thirtyOneCodePoints + "  \"}"
        }) {
            createWithBody(Role.ADMIN, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value(validationFailed()));
        }
    }

    @Test
    void listsBoardsInIdOrderWithVersionsAndRequiresAuthentication()
            throws Exception {
        Board first = boardRepository.saveAndFlush(Board.create("첫 게시판"));
        Board second = boardRepository.saveAndFlush(Board.create("둘째 게시판"));

        mockMvc.perform(get("/boards"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        mockMvc.perform(get("/boards").with(user(principal(Role.USER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].boardId").value(first.getId()))
                .andExpect(jsonPath("$.data[0].name").value("첫 게시판"))
                .andExpect(jsonPath("$.data[0].version").value(0))
                .andExpect(jsonPath("$.data[1].boardId").value(second.getId()))
                .andExpect(jsonPath("$.data[1].version").value(0));
    }

    @Test
    void permitsAdminAndSuperAdminButForbidsUserFromAdministration()
            throws Exception {
        create(Role.USER, "사용자 금지")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        create(Role.ADMIN, "관리자 게시판")
                .andExpect(status().isCreated());
        create(Role.SUPER_ADMIN, "최고관리자 게시판")
                .andExpect(status().isCreated());
    }

    @Test
    void rejectsMissingNullNonObjectEmptyAndUnknownPatchBodies() throws Exception {
        Board board = saved("기존");

        patch(board.getId(), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(validationFailed()));
        patch(board.getId(), "null")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(validationFailed()));
        patch(board.getId(), "[]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(validationFailed()));
        patch(board.getId(), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(validationFailed()));
        patch(board.getId(), "{\"name\":\"변경\",\"version\":0,\"extra\":true}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(validationFailed()));
    }

    @Test
    void rejectsInvalidPatchFieldShapesAndInvalidNormalizedNames()
            throws Exception {
        Board board = saved("기존");

        for (String body : new String[]{
                "{\"version\":0}",
                "{\"name\":null,\"version\":0}",
                "{\"name\":7,\"version\":0}",
                "{\"name\":\"변경\"}",
                "{\"name\":\"변경\",\"version\":null}",
                "{\"name\":\"변경\",\"version\":0.5}",
                "{\"name\":\"변경\",\"version\":-1}",
                "{\"name\":\" \\t\\n\",\"version\":0}",
                "{\"name\":\"가" + "가".repeat(30) + "\",\"version\":0}"
        }) {
            patch(board.getId(), body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value(validationFailed()));
        }
    }

    @Test
    void stripsBeforeLengthValidationAndUpdatesBoard() throws Exception {
        Board board = saved("기존");
        String thirtyCharacters = "가".repeat(30);

        patch(board.getId(), "{\"name\":\"  " + thirtyCharacters
                + "  \",\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.boardId").value(board.getId()))
                .andExpect(jsonPath("$.data.name").value(thirtyCharacters))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    void returnsNotFoundBeforeWritingWhenBoardDoesNotExist() throws Exception {
        patch(999_999L, "{\"name\":\"변경\",\"version\":0}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BOARD_NOT_FOUND"));
    }

    @Test
    void staleVersionWinsOverSameCurrentNameButSemanticInvalidityStillIsBadRequest()
            throws Exception {
        Board board = saved("기존");
        patch(board.getId(), "{\"name\":\"새 이름\",\"version\":0}")
                .andExpect(status().isOk());

        patch(board.getId(), "{\"name\":\"새 이름\",\"version\":0}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("CONCURRENT_UPDATE_CONFLICT"));
        patch(board.getId(), "{\"name\":\"   \",\"version\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(validationFailed()));
    }

    @Test
    void normalizedNoOpKeepsVersionAndUpdatedAtUnchanged() throws Exception {
        Board board = saved("동일 이름");
        Instant expectedUpdatedAt = Instant.parse("2026-01-01T00:00:00Z");
        jdbcTemplate.update(
                "update boards set updated_at = ? where id = ?",
                expectedUpdatedAt,
                board.getId()
        );
        Map<String, Object> before = boardState(board.getId());

        patch(board.getId(), "{\"name\":\"  동일 이름  \",\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("동일 이름"))
                .andExpect(jsonPath("$.data.version").value(0));

        assertThat(boardState(board.getId())).isEqualTo(before);
    }

    @Test
    void permitsBoardWritesOutsideTransactionsAndRejectsThemInsideTransactions() {
        BoardResponse created = boardService.create(new BoardCreateRequest("정상 생성"));
        BoardResponse updated = boardService.update(
                created.boardId(),
                new BoardUpdateRequest("정상 수정", created.version())
        );
        assertThat(updated.name()).isEqualTo("정상 수정");

        TransactionTemplate transactionTemplate = new TransactionTemplate(
                transactionManager
        );
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                boardService.create(new BoardCreateRequest("트랜잭션 생성"))
        )).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                boardService.update(
                        updated.boardId(),
                        new BoardUpdateRequest("트랜잭션 수정", updated.version())
                )
        )).isInstanceOf(IllegalTransactionStateException.class);

        Board persisted = boardRepository.findById(updated.boardId()).orElseThrow();
        assertThat(persisted.getName()).isEqualTo("정상 수정");
        assertThat(boardRepository.findAll()).extracting(Board::getName)
                .doesNotContain("트랜잭션 생성");
    }

    private ResultActions create(Role role, String name) throws Exception {
        return createWithBody(role, "{\"name\":\"" + name + "\"}");
    }

    private ResultActions createWithBody(Role role, String body) throws Exception {
        return mockMvc.perform(post("/admin/boards")
                .with(user(principal(role)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions patch(Long boardId, String body) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/admin/boards/{boardId}", boardId)
                .with(user(principal(Role.ADMIN)));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request);
    }

    private Board saved(String name) {
        return boardRepository.saveAndFlush(Board.create(name));
    }

    private Map<String, Object> boardState(Long boardId) {
        return jdbcTemplate.queryForMap("""
                select name, version, updated_at
                  from boards
                 where id = ?
                """, boardId);
    }

    private CurrentUser principal(Role role) {
        return new CurrentUser(1L, role.name().toLowerCase() + "@example.com", role);
    }

    private String validationFailed() {
        return "VALIDATION_FAILED";
    }
}
