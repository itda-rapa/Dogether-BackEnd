package itda.common.constants;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "검증에 실패했습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류입니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증에 실패했습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "현재 계정에 권한이 없습니다."),
    CONCURRENT_UPDATE_CONFLICT(
            HttpStatus.CONFLICT,
            "동시 요청으로 상태가 변경되었습니다. 다시 시도해주세요."
    ),


    USER_EMAIL_DUPLICATED(HttpStatus.CONFLICT, "이미 등록된 이메일입니다."),
    PUBLIC_TAG_GENERATION_FAILED(HttpStatus.CONFLICT, "공개 사용자 태그를 생성하지 못했습니다."),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "잘못된 비밀번호 또는 존재하지 않는 이메일입니다."),
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "해당 Refresh Token은 만료되었습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 계정을 찾을 수 없습니다."),
    NEIGHBORHOOD_NOT_FOUND(HttpStatus.UNPROCESSABLE_CONTENT, "선택할 수 없는 동네입니다."),
    ACCOUNT_NOT_ACTIVE(HttpStatus.FORBIDDEN, "현재 이용할 수 없는 계정입니다."),

    EMAIL_VERIFICATION_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "이메일 인증 재전송 가능 시간 전입니다."),
    EMAIL_VERIFICATION_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "인증번호가 일치하지 않습니다."),
    EMAIL_VERIFICATION_UNAVAILABLE(HttpStatus.GONE, "사용할 수 없는 이메일 인증 요청입니다."),
    EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED(HttpStatus.GONE, "인증번호 입력 가능 횟수를 초과했습니다."),
    EMAIL_VERIFICATION_TOKEN_INVALID(HttpStatus.GONE, "유효하지 않은 이메일 인증 토큰입니다."),
    EMAIL_DELIVERY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "이메일 발송 요청을 처리할 수 없습니다."),

    MEDIA_NOT_FOUND(HttpStatus.NOT_FOUND, "미디어 자산을 찾을 수 없습니다."),
    MEDIA_NOT_UPLOADED(HttpStatus.UNPROCESSABLE_CONTENT, "업로드된 객체를 확인할 수 없습니다."),
    MEDIA_EXPIRED(HttpStatus.GONE, "미디어 업로드 요청이 만료되었습니다."),
    MEDIA_NOT_OWNED(HttpStatus.FORBIDDEN, "해당 미디어 자산의 소유자가 아닙니다."),
    INVALID_MEDIA_TYPE(HttpStatus.UNPROCESSABLE_CONTENT, "허용되지 않는 미디어 형식입니다."),
    MEDIA_SIZE_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "허용된 미디어 크기를 초과했습니다."),
    MEDIA_STATE_CONFLICT(HttpStatus.CONFLICT, "현재 미디어 상태에서는 요청을 처리할 수 없습니다."),
    MEDIA_PURPOSE_FORBIDDEN(HttpStatus.FORBIDDEN, "현재 마일스톤에서 해당 미디어를 업로드할 수 없습니다."),
    SETLOG_MEDIA_ALREADY_USED(HttpStatus.CONFLICT, "이미 셋로그에 사용된 미디어입니다."),

    PET_REQUIRED(HttpStatus.FORBIDDEN, "반려견 등록이 필요한 기능입니다."),
    ACTIVE_PET_REQUIRED(HttpStatus.FORBIDDEN, "활동할 반려견을 먼저 선택해주세요."),
    PET_NOT_FOUND(HttpStatus.NOT_FOUND, "반려견을 찾을 수 없습니다."),
    PET_NOT_OWNED(HttpStatus.FORBIDDEN, "해당 반려견의 소유자가 아닙니다."),
    PET_NOT_ACTIVE(HttpStatus.FORBIDDEN, "활동 가능한 반려견이 아닙니다."),
    PET_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "반려견은 최대 5마리까지 등록할 수 있습니다."),
    PET_PUBLIC_TAG_GENERATION_FAILED(
            HttpStatus.CONFLICT,
            "반려견 공개 태그를 생성하지 못했습니다."
    ),
    ACTIVE_PET_DELETE_FORBIDDEN(HttpStatus.CONFLICT, "활동 중인 반려견은 삭제할 수 없습니다."),
    SAME_OWNER_INTERACTION_FORBIDDEN(HttpStatus.BAD_REQUEST, "같은 사용자가 소유한 반려견끼리는 상호작용할 수 없습니다."),

    GREETING_ALREADY_USED(HttpStatus.CONFLICT, "이미 인사한 상대에게 다시 인사할 수 없습니다."),
    GREETING_DAILY_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "하루 인사 가능 인원을 초과했습니다."),
    GREETING_SELF_FORBIDDEN(HttpStatus.FORBIDDEN, "본인 소유 반려견에게 인사할 수 없습니다."),
    GREETING_REPLY_REQUIRED(HttpStatus.CONFLICT, "상대가 답변한 뒤 추가 메시지를 보낼 수 있습니다."),
    FRIEND_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "친구는 반려견당 최대 50명까지 등록할 수 있습니다."),
    FRIEND_REQUEST_ALREADY_PENDING(HttpStatus.CONFLICT, "이미 친구 요청을 보낸 상대입니다."),
    FRIENDSHIP_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 친구 관계입니다."),
    FRIENDSHIP_NOT_FOUND(HttpStatus.NOT_FOUND, "친구 관계를 찾을 수 없습니다."),
    FRIEND_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "친구 요청을 찾을 수 없습니다."),
    FRIEND_REQUEST_NOT_PENDING(HttpStatus.CONFLICT, "처리 가능한 친구요청 상태가 아닙니다."),
    BLOCKED_USER(HttpStatus.FORBIDDEN, "차단 관계에서는 요청을 처리할 수 없습니다."),

    SETLOG_NOT_FOUND(HttpStatus.NOT_FOUND, "셋로그를 찾을 수 없습니다."),
    SETLOG_SELF_REACTION_FORBIDDEN(HttpStatus.FORBIDDEN, "본인 소유 반려견의 셋로그에는 반응할 수 없습니다."),
    MEETING_CARD_NOT_EDITABLE(HttpStatus.CONFLICT, "M1에서는 약속 카드를 수정할 수 없습니다."),
    MEETING_CARD_CANCEL_FORBIDDEN(HttpStatus.FORBIDDEN, "약속 참여 반려견만 카드를 취소할 수 있습니다."),
    MEETING_CARD_ALREADY_CANCELED(HttpStatus.CONFLICT, "이미 취소된 약속 카드입니다."),
    MEETING_CARD_NOT_FOUND(HttpStatus.NOT_FOUND, "약속 카드를 찾을 수 없습니다."),
    MEETING_CARD_ROOM_REQUIRED(HttpStatus.BAD_REQUEST, "약속 카드는 DIRECT 채팅방에서만 만들 수 있습니다."),
    REPORT_ROOM_REQUIRED(HttpStatus.BAD_REQUEST, "신고할 DIRECT 채팅방이 필요합니다."),
    REPORT_SELF_FORBIDDEN(HttpStatus.BAD_REQUEST, "자기 자신을 신고할 수 없습니다."),

    CHAT_ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."),
    CHAT_ROOM_SAME_PET_FORBIDDEN(HttpStatus.BAD_REQUEST, "같은 반려견만으로 채팅방을 만들 수 없습니다."),
    CHAT_SENDER_REQUIRED(HttpStatus.BAD_REQUEST, "PET 메시지에는 발신자 정보가 필요합니다."),
    CHAT_SENDER_NOT_PARTICIPANT(HttpStatus.FORBIDDEN, "채팅방 참여자만 메시지를 보낼 수 있습니다."),
    CHAT_CLIENT_MESSAGE_ID_REQUIRED(HttpStatus.BAD_REQUEST, "TEXT 메시지는 clientMessageId가 필요합니다."),
    CHAT_DUPLICATE_MESSAGE(HttpStatus.CONFLICT, "동일한 clientMessageId의 메시지가 이미 존재합니다."),

    BOARD_NOT_FOUND(HttpStatus.NOT_FOUND, "게시판을 찾을 수 없습니다."),
    BOARD_NAME_DUPLICATED(HttpStatus.CONFLICT, "이미 등록된 게시판 이름입니다.");

    private final HttpStatus status;
    private final String description;
}
