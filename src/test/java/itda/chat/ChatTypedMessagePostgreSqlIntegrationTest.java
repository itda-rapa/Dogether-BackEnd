package itda.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.chat.domain.MessageType;
import itda.chat.domain.RoomOrigin;
import itda.chat.dto.ChatMessageCreateRequest;
import itda.chat.dto.ChatMessageResult;
import itda.chat.service.ChatMessageService;
import itda.chat.service.ChatRoomService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.security.CurrentUser;
import itda.user.domain.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * M3 typed message(IMAGE/VIDEO/SETLOG_SHARE)의 저장·권한·멱등성·lifecycle을 실제 PostgreSQL로 검증한다.
 */
@Tag("postgres")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class ChatTypedMessagePostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer(
                DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                        .asCompatibleSubstituteFor("postgres")
        );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ChatRoomService chatRoomService;

    @Autowired
    private ChatMessageService chatMessageService;

    private static final long USER_1 = 1L;
    private static final long USER_2 = 2L;
    private static final long PET_1 = 11L;
    private static final long PET_2 = 22L;
    private static final String NEIGHBORHOOD = "4113165000";

    private long roomId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                truncate chat_message_attachments, setlog_reactions, setlogs, media,
                         chat_messages, chat_room_participants, chat_rooms, pets, users
                restart identity cascade
                """);
        insertUser(USER_1);
        insertUser(USER_2);
        insertPet(PET_1, USER_1);
        insertPet(PET_2, USER_2);
        jdbcTemplate.update("update users set active_pet_id = ? where id = ?", PET_1, USER_1);
        jdbcTemplate.update("update users set active_pet_id = ? where id = ?", PET_2, USER_2);
        roomId = chatRoomService.ensureDirectRoom(PET_1, PET_2, RoomOrigin.GREETING).roomId();
    }

    // ---------- IMAGE ----------

    @Test
    @DisplayName("IMAGE 전송은 메시지와 첨부를 저장한다")
    void imageSendStoresMessageAndAttachment() {
        long mediaId = insertMedia(USER_1, "IMAGE", "COMPLETED");

        ChatMessageResult result = chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("img-1", MessageType.IMAGE, null, mediaId, null));

        assertThat(result.created()).isTrue();
        assertThat(result.message().getType()).isEqualTo(MessageType.IMAGE);
        assertThat(countOf("chat_messages")).isEqualTo(1);
        Long attachmentMediaId = jdbcTemplate.queryForObject(
                "select media_id from chat_message_attachments where message_id = ?",
                Long.class, result.message().getId());
        assertThat(attachmentMediaId).isEqualTo(mediaId);
    }

    @Test
    @DisplayName("타인 소유 Media로 IMAGE 전송은 거부된다")
    void imageWithForeignMediaIsRejected() {
        long mediaId = insertMedia(USER_2, "IMAGE", "COMPLETED");

        assertThatThrownBy(() -> chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("img-f", MessageType.IMAGE, null, mediaId, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.MEDIA_NOT_OWNED);
        assertThat(countOf("chat_messages")).isZero();
    }

    @Test
    @DisplayName("업로드 미완료 Media로 IMAGE 전송은 거부된다")
    void imageWithNotReadyMediaIsRejected() {
        long mediaId = insertMedia(USER_1, "IMAGE", "INIT");

        assertThatThrownBy(() -> chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("img-r", MessageType.IMAGE, null, mediaId, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.MEDIA_NOT_READY);
    }

    @Test
    @DisplayName("VIDEO Media를 IMAGE로 전송하면 타입 불일치로 거부된다")
    void videoMediaAsImageIsRejected() {
        long mediaId = insertMedia(USER_1, "VIDEO", "COMPLETED");

        assertThatThrownBy(() -> chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("img-t", MessageType.IMAGE, null, mediaId, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_MEDIA_TYPE);
    }

    // ---------- VIDEO ----------

    @Test
    @DisplayName("VIDEO 전송은 정상 저장된다")
    void videoSendStoresMessageAndAttachment() {
        long mediaId = insertMedia(USER_1, "VIDEO", "UPLOADED");

        ChatMessageResult result = chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("vid-1", MessageType.VIDEO, null, mediaId, null));

        assertThat(result.created()).isTrue();
        assertThat(result.message().getType()).isEqualTo(MessageType.VIDEO);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from chat_message_attachments where message_id = ?",
                Integer.class, result.message().getId())).isEqualTo(1);
    }

    // ---------- SETLOG_SHARE ----------

    @Test
    @DisplayName("SETLOG_SHARE 전송은 shared_setlog_id를 저장한다")
    void setlogShareStoresSharedSetlogId() {
        long mediaId = insertMedia(USER_1, "VIDEO", "COMPLETED");
        long setlogId = insertSetlog(PET_1, mediaId, "VISIBLE");

        ChatMessageResult result = chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("set-1", MessageType.SETLOG_SHARE, null, null, setlogId));

        assertThat(result.created()).isTrue();
        assertThat(result.message().getType()).isEqualTo(MessageType.SETLOG_SHARE);
        assertThat(result.message().getSharedSetlogId()).isEqualTo(setlogId);
    }

    @Test
    @DisplayName("타인 Setlog 공유는 거부된다")
    void sharingAnotherOwnersSetlogIsRejected() {
        long mediaId = insertMedia(USER_2, "IMAGE", "COMPLETED");
        long setlogId = insertSetlog(PET_2, mediaId, "VISIBLE");

        assertThatThrownBy(() -> chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("set-f", MessageType.SETLOG_SHARE, null, null, setlogId)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SETLOG_SHARE_FORBIDDEN);
    }

    @Test
    @DisplayName("삭제된 Setlog 공유는 NOT_FOUND로 수렴한다")
    void sharingDeletedSetlogIsNotFound() {
        long mediaId = insertMedia(USER_1, "IMAGE", "COMPLETED");
        long setlogId = insertSetlog(PET_1, mediaId, "DELETED_BY_AUTHOR");

        assertThatThrownBy(() -> chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("set-d", MessageType.SETLOG_SHARE, null, null, setlogId)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SETLOG_NOT_FOUND);
    }

    @Test
    @DisplayName("VISIBLE이지만 업로드 미완료 Media의 Setlog 공유는 거부된다")
    void sharingSetlogWithNotReadyMediaIsRejected() {
        long mediaId = insertMedia(USER_1, "IMAGE", "INIT");
        long setlogId = insertSetlog(PET_1, mediaId, "VISIBLE");

        assertThatThrownBy(() -> chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("set-r", MessageType.SETLOG_SHARE, null, null, setlogId)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SETLOG_NOT_FOUND);
    }

    // ---------- greeting gate ----------

    @Test
    @DisplayName("Greeting 발신자는 응답 전 IMAGE/VIDEO/SETLOG_SHARE 전송이 거부되고 수신자 응답 후 허용된다")
    void greetingGateAppliesToTypedMessages() {
        long greetingMedia = insertMedia(USER_2, "IMAGE", "COMPLETED");
        long greetingSetlog = insertSetlog(PET_2, greetingMedia, "VISIBLE");
        insertGreeting(PET_1, PET_2, greetingSetlog, roomId);

        // 발신자(pet 11)는 응답 전 어떤 타입이든 거부된다.
        long imgMedia = insertMedia(USER_1, "IMAGE", "COMPLETED");
        assertThatThrownBy(() -> chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("g-img", MessageType.IMAGE, null, imgMedia, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.GREETING_REPLY_REQUIRED);

        long shareSetlog = insertSetlog(PET_1, insertMedia(USER_1, "IMAGE", "COMPLETED"), "VISIBLE");
        assertThatThrownBy(() -> chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("g-set", MessageType.SETLOG_SHARE, null, null, shareSetlog)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.GREETING_REPLY_REQUIRED);

        // 수신자(pet 22)가 VIDEO로 응답하면 Greeting이 RESPONDED로 전이된다.
        long vidMedia = insertMedia(USER_2, "VIDEO", "COMPLETED");
        ChatMessageResult reply = chatMessageService.sendMessage(roomId, PET_2, USER_2,
                new ChatMessageCreateRequest("g-reply", MessageType.VIDEO, null, vidMedia, null));
        assertThat(reply.created()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select status from greetings where room_id = ?", String.class, roomId))
                .isEqualTo("RESPONDED");

        // 발신자(pet 11)가 이제 TEXT를 전송할 수 있다.
        ChatMessageResult after = chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("g-after", MessageType.TEXT, "이제 가능", null, null));
        assertThat(after.created()).isTrue();
    }

    // ---------- server-only / payload ----------

    @Test
    @DisplayName("CARD/SYSTEM은 사용자 전송이 거부된다")
    void serverOnlyTypesAreRejected() {
        assertThatThrownBy(() -> chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("c-1", MessageType.CARD, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MESSAGE_TYPE_INVALID);
    }

    @Test
    @DisplayName("IMAGE에 body가 있으면 payload 불일치로 거부된다")
    void imageWithBodyIsRejected() {
        long mediaId = insertMedia(USER_1, "IMAGE", "COMPLETED");

        assertThatThrownBy(() -> chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("img-b", MessageType.IMAGE, "caption", mediaId, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MESSAGE_PAYLOAD_INVALID);
    }

    // ---------- idempotency ----------

    @Test
    @DisplayName("동일 clientMessageId + 동일 payload 재전송은 기존 메시지를 반환한다")
    void idempotentImageRetryReturnsOriginal() {
        long mediaId = insertMedia(USER_1, "IMAGE", "COMPLETED");
        ChatMessageCreateRequest request =
                new ChatMessageCreateRequest("img-dup", MessageType.IMAGE, null, mediaId, null);

        ChatMessageResult first = chatMessageService.sendMessage(roomId, PET_1, USER_1, request);
        ChatMessageResult retry = chatMessageService.sendMessage(roomId, PET_1, USER_1, request);

        assertThat(first.created()).isTrue();
        assertThat(retry.created()).isFalse();
        assertThat(retry.message().getId()).isEqualTo(first.message().getId());
        assertThat(countOf("chat_message_attachments")).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 clientMessageId + 다른 mediaId는 CHAT_DUPLICATE_MESSAGE다")
    void reusingKeyWithDifferentMediaIsRejected() {
        long mediaA = insertMedia(USER_1, "IMAGE", "COMPLETED");
        long mediaB = insertMedia(USER_1, "IMAGE", "COMPLETED");
        chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("img-dup2", MessageType.IMAGE, null, mediaA, null));

        assertThatThrownBy(() -> chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("img-dup2", MessageType.IMAGE, null, mediaB, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_DUPLICATE_MESSAGE);
    }

    // ---------- lifecycle ----------

    @Test
    @DisplayName("ARCHIVED 방에 IMAGE 전송 시 ACTIVE로 복구된다")
    void imageSendRestoresArchivedRoom() {
        long mediaId = insertMedia(USER_1, "IMAGE", "COMPLETED");
        jdbcTemplate.update(
                "update chat_rooms set status = 'ARCHIVED', archived_at = now() where id = ?", roomId);

        chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("img-restore", MessageType.IMAGE, null, mediaId, null));

        assertThat(jdbcTemplate.queryForObject(
                "select status from chat_rooms where id = ?", String.class, roomId))
                .isEqualTo("ACTIVE");
    }

    // ---------- REST: permission / hydration ----------

    @Test
    @DisplayName("비참여자의 IMAGE 전송은 방 존재를 숨기고 404다")
    void nonParticipantImageSendIs404() throws Exception {
        insertUser(3L);
        insertPet(33L, 3L);
        jdbcTemplate.update("update users set active_pet_id = ? where id = ?", 33L, 3L);

        mockMvc.perform(post("/chat/rooms/{roomId}/messages", roomId)
                        .with(user(principal(3L)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientMessageId":"img-404","type":"IMAGE","mediaId":501,"body":null,"setlogId":null}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CHAT_ROOM_NOT_FOUND"));
    }

    @Test
    @DisplayName("방 목록 lastMessage는 typed 상세를 hydrate하지 않고 type 기반 요약만 반환한다")
    void roomListLastMessageDoesNotHydrateTypedDetails() throws Exception {
        long imageMedia = insertMedia(USER_1, "IMAGE", "COMPLETED");
        chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("room-img", MessageType.IMAGE, null, imageMedia, null));

        mockMvc.perform(get("/chat/rooms").with(user(principal(USER_1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].lastMessage.type").value("IMAGE"))
                .andExpect(jsonPath("$.data.items[0].lastMessage.attachment").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].lastMessage.sharedSetlog").value(nullValue()));

        long setlogId = insertSetlog(PET_1, insertMedia(USER_1, "VIDEO", "COMPLETED"), "VISIBLE");
        chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("room-set", MessageType.SETLOG_SHARE, null, null, setlogId));

        mockMvc.perform(get("/chat/rooms").with(user(principal(USER_1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].lastMessage.type").value("SETLOG_SHARE"))
                .andExpect(jsonPath("$.data.items[0].lastMessage.attachment").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].lastMessage.sharedSetlog").value(nullValue()));
    }

    @Test
    @DisplayName("메시지 목록은 IMAGE 첨부와 SETLOG_SHARE 요약을 hydrate한다")
    void messageListHydratesTypedMessages() throws Exception {
        long imageMedia = insertMedia(USER_1, "IMAGE", "COMPLETED");
        chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("m-img", MessageType.IMAGE, null, imageMedia, null));

        long setlogMedia = insertMedia(USER_1, "VIDEO", "COMPLETED");
        long setlogId = insertSetlog(PET_1, setlogMedia, "VISIBLE");
        chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("m-set", MessageType.SETLOG_SHARE, null, null, setlogId));

        mockMvc.perform(get("/chat/rooms/{roomId}/messages", roomId)
                        .with(user(principal(USER_1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].type").value("IMAGE"))
                .andExpect(jsonPath("$.data.items[0].attachment.mediaId").value((int) imageMedia))
                .andExpect(jsonPath("$.data.items[0].attachment.url").exists())
                .andExpect(jsonPath("$.data.items[1].type").value("SETLOG_SHARE"))
                .andExpect(jsonPath("$.data.items[1].sharedSetlog.setlogId").value((int) setlogId))
                .andExpect(jsonPath("$.data.items[1].sharedSetlog.available").value(true));
    }

    @Test
    @DisplayName("Media가 삭제되면 IMAGE 첨부의 url/expiresAt은 null로 내려간다")
    void deletedMediaAttachmentHydratesWithNullUrl() throws Exception {
        long mediaId = insertMedia(USER_1, "IMAGE", "COMPLETED");
        chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("m-del", MessageType.IMAGE, null, mediaId, null));
        jdbcTemplate.update("update media set deleted_at = now() where id = ?", mediaId);

        mockMvc.perform(get("/chat/rooms/{roomId}/messages", roomId)
                        .with(user(principal(USER_1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].type").value("IMAGE"))
                .andExpect(jsonPath("$.data.items[0].attachment.mediaId").value((int) mediaId))
                .andExpect(jsonPath("$.data.items[0].attachment.mediaType").value("IMAGE"))
                .andExpect(jsonPath("$.data.items[0].attachment.contentType").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].attachment.fileSize").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].attachment.url").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].attachment.expiresAt").value(nullValue()));
    }

    // ---------- DB constraints ----------

    @Test
    @DisplayName("IMAGE 메시지는 body를 허용하지 않는다(DB CHECK)")
    void imageWithBodyViolatesPayloadCheck() {
        long mediaId = insertMedia(USER_1, "IMAGE", "COMPLETED");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into chat_messages (room_id, sender_type, sender_pet_id, type, body, shared_setlog_id)
                values (?, 'PET', ?, 'IMAGE', 'caption', null)
                """, roomId, PET_1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_chat_message_payload");
    }

    @Test
    @DisplayName("SETLOG_SHARE는 shared_setlog_id가 필수다(DB CHECK)")
    void setlogShareWithoutSetlogIdViolatesPayloadCheck() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into chat_messages (room_id, sender_type, sender_pet_id, type, body, shared_setlog_id)
                values (?, 'PET', ?, 'SETLOG_SHARE', null, null)
                """, roomId, PET_1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_chat_message_payload");
    }

    @Test
    @DisplayName("같은 media는 여러 메시지 첨부로 재사용할 수 없다")
    void mediaCannotBeAttachedToMultipleMessages() {
        long mediaId = insertMedia(USER_1, "IMAGE", "COMPLETED");
        chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("img-a", MessageType.IMAGE, null, mediaId, null));

        assertThatThrownBy(() -> chatMessageService.sendMessage(roomId, PET_1, USER_1,
                new ChatMessageCreateRequest("img-b", MessageType.IMAGE, null, mediaId, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MEDIA_ALREADY_ATTACHED);

        // 첨부 insert 실패 시 같은 트랜잭션의 메시지 insert도 rollback된다(부분 저장 없음).
        assertThat(countOf("chat_messages")).isEqualTo(1);
        assertThat(countOf("chat_message_attachments")).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 Media 동시 전송은 한 건만 저장되고 나머지는 409다")
    void concurrentMediaSendsStoreOneAndRejectTheOther() throws Exception {
        long mediaId = insertMedia(USER_1, "IMAGE", "COMPLETED");
        long otherUserId = 3L;
        long otherPetId = 33L;
        insertUser(otherUserId);
        insertPet(otherPetId, otherUserId);
        jdbcTemplate.update("update users set active_pet_id = ? where id = ?", otherPetId, otherUserId);
        long otherRoomId = chatRoomService
                .ensureDirectRoom(PET_1, otherPetId, RoomOrigin.FRIEND)
                .roomId();
        assertThat(otherRoomId).isNotEqualTo(roomId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Future<ChatMessageResult>> futures = new ArrayList<>();
        List<Long> attemptedRoomIds = List.of(roomId, otherRoomId);
        try {
            for (int index = 0; index < attemptedRoomIds.size(); index++) {
                long attemptedRoomId = attemptedRoomIds.get(index);
                String clientMessageId = "img-concurrent-" + (index == 0 ? "a" : "b");
                futures.add(executor.submit(() -> {
                    barrier.await();
                    return chatMessageService.sendMessage(attemptedRoomId, PET_1, USER_1,
                            new ChatMessageCreateRequest(
                                    clientMessageId, MessageType.IMAGE, null, mediaId, null));
                }));
            }

            int created = 0;
            int rejected = 0;
            long failedRoomId = -1L;
            for (int index = 0; index < futures.size(); index++) {
                Future<ChatMessageResult> future = futures.get(index);
                try {
                    assertThat(future.get().created()).isTrue();
                    created++;
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause())
                            .isInstanceOf(BusinessException.class)
                            .extracting(ex -> ((BusinessException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.CHAT_MEDIA_ALREADY_ATTACHED);
                    failedRoomId = attemptedRoomIds.get(index);
                    rejected++;
                }
            }
            assertThat(created).isEqualTo(1);
            assertThat(rejected).isEqualTo(1);
            assertThat(failedRoomId).isPositive();
            assertThat(jdbcTemplate.queryForObject(
                    "select last_message_at is null from chat_rooms where id = ?",
                    Boolean.class, failedRoomId)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(countOf("chat_messages")).isEqualTo(1);
        assertThat(countOf("chat_message_attachments")).isEqualTo(1);
    }

    // ---------- helpers ----------

    private void insertUser(long userId) {
        jdbcTemplate.update("""
                        insert into users (
                            id, email, password_hash, nickname, public_tag,
                            role, account_status, neighborhood_code
                        ) values (?, ?, 'encoded', ?, ?, 'USER', 'ACTIVE', ?)
                        """,
                userId,
                "user" + userId + "@test.com",
                "사용자" + userId,
                "user" + userId + "#" + String.format("%04d", userId),
                NEIGHBORHOOD);
    }

    private void insertPet(long petId, long ownerUserId) {
        jdbcTemplate.update("""
                        insert into pets (id, owner_user_id, public_tag, nickname, status)
                        values (?, ?, ?, ?, 'ACTIVE')
                        """,
                petId,
                ownerUserId,
                "pet" + petId + "#" + String.format("%04d", petId),
                "펫" + petId);
    }

    private long insertMedia(long userId, String mediaType, String status) {
        String path = "users/" + userId + "/chat-" + System.nanoTime() + ".mp4";
        return jdbcTemplate.queryForObject("""
                        insert into media (media_type, path, status, user_id, content_type, file_size)
                        values (?, ?, ?, ?, ?, 100)
                        returning id
                        """,
                Long.class, mediaType, path, status, userId,
                "IMAGE".equals(mediaType) ? "image/jpeg" : "video/mp4");
    }

    private long insertSetlog(long authorPetId, long mediaId, String status) {
        return jdbcTemplate.queryForObject("""
                        insert into setlogs (author_pet_id, media_id, caption, status)
                        values (?, ?, '공유 캡션', ?)
                        returning id
                        """,
                Long.class, authorPetId, mediaId, status);
    }

    private void insertGreeting(long fromPetId, long toPetId, long setlogId, long roomId) {
        jdbcTemplate.update("""
                        insert into greetings (from_pet_id, to_pet_id, setlog_id, room_id, expires_at)
                        values (?, ?, ?, ?, ?)
                        """,
                fromPetId, toPetId, setlogId, roomId,
                java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(3600)));
    }

    private CurrentUser principal(long userId) {
        return new CurrentUser(userId, "user" + userId + "@test.com", Role.USER);
    }

    private int countOf(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }
}
