package itda.meetingreview.domain;

import itda.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 후기 제출 시 같은 트랜잭션에서 적립되는 발자국. 01_M3_통합_ERD.md §6 {@code footprints}.
 *
 * <p>발자국의 하루 기준은 Asia/Seoul 이며 {@code earned_date} 에 후기 제출 시각을 KST 로
 * 변환한 날짜를 저장한다. 같은 Pet 은 같은 KST 날짜에 발자국 최대 한 건
 * ({@code uk_footprint_pet_date})이고, 같은 만남·같은 Pet 발자국도 한 번만
 * ({@code uk_footprint_meeting_pet})이다. 이미 그날 발자국이 있으면 새 행을 만들지 않고
 * 기존 행을 재사용한다(후기는 정상 저장).
 *
 * <p>행은 불변이다. 수정·삭제 경로가 없다.
 */
@Getter
@Entity
@Table(name = "footprints", uniqueConstraints = {
        @UniqueConstraint(
                name = "uk_footprint_meeting_pet",
                columnNames = {"meeting_id", "receiver_pet_id"}),
        @UniqueConstraint(
                name = "uk_footprint_pet_date",
                columnNames = {"receiver_pet_id", "earned_date"})
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Footprint extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meeting_id", nullable = false)
    private Long meetingId;

    @Column(name = "receiver_pet_id", nullable = false)
    private Long receiverPetId;

    @Column(name = "counterpart_pet_id", nullable = false)
    private Long counterpartPetId;

    @Column(name = "earned_date", nullable = false)
    private LocalDate earnedDate;

    public Footprint(Long meetingId, Long receiverPetId, Long counterpartPetId, LocalDate earnedDate) {
        this.meetingId = meetingId;
        this.receiverPetId = receiverPetId;
        this.counterpartPetId = counterpartPetId;
        this.earnedDate = earnedDate;
    }
}
