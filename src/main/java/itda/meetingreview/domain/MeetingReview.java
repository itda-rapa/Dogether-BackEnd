package itda.meetingreview.domain;

import itda.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 확정된 Meeting({@code meetings} 행)에 대한 Pet 한 명의 후기.
 * 01_M3_통합_ERD.md §6 {@code meeting_reviews}.
 *
 * <p>같은 {@code (meeting_id, reviewer_pet_id)} 후기는 한 번만 쓸 수 있고
 * {@code client_request_id} 는 동일 Pet 재요청 식별용 전역 멱등키다. {@code placeTag} 는
 * 필수 장소 태그(공백 불가, 최대 30자)이고 한 줄 후기({@code content})는 선택 입력이다.
 * 별점은 없다.
 *
 * <p>후기 저장과 발자국 적립은 같은 트랜잭션에서 일어난다(#150). 발자국은
 * {@link Footprint} 가 담당한다.
 */
@Getter
@Entity
@Table(name = "meeting_reviews", uniqueConstraints = {
        @UniqueConstraint(
                name = "uk_meeting_review_pet",
                columnNames = {"meeting_id", "reviewer_pet_id"}),
        @UniqueConstraint(
                name = "uk_meeting_review_client_request",
                columnNames = {"client_request_id"})
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MeetingReview extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meeting_id", nullable = false)
    private Long meetingId;

    @Column(name = "reviewer_pet_id", nullable = false)
    private Long reviewerPetId;

    @Column(name = "place_tag", nullable = false, length = 30)
    private String placeTag;

    @Column(name = "client_request_id", nullable = false)
    private UUID clientRequestId;

    @Column(name = "content", length = 500)
    private String content;

    public MeetingReview(Long meetingId,
                         Long reviewerPetId,
                         String placeTag,
                         UUID clientRequestId,
                         String content) {
        this.meetingId = meetingId;
        this.reviewerPetId = reviewerPetId;
        this.placeTag = placeTag;
        this.clientRequestId = clientRequestId;
        this.content = content;
    }
}
