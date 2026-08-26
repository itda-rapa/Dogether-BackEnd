package itda.medicalsupport.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Getter @Entity @Table(name = "medical_support_ingestion_attempts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MedicalSupportIngestionAttempt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private String sourceKey;
    @Column(nullable = false) private String sourceUrl;
    @Column(nullable = false) private Instant attemptedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private MedicalSupportIngestionOutcome outcome;
    private String failureReason; private String contentType; private String sourceHash;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "revision_id") private MedicalSupportRevision revision;
    private MedicalSupportIngestionAttempt(String key, String url, MedicalSupportIngestionOutcome outcome, String reason, String contentType, String hash, MedicalSupportRevision revision) { sourceKey=key; sourceUrl=url; attemptedAt=Instant.now(); this.outcome=outcome; failureReason=reason; this.contentType=contentType; sourceHash=hash; this.revision=revision; }
    public static MedicalSupportIngestionAttempt succeeded(String key, String url, String contentType, String hash, MedicalSupportRevision revision) { return new MedicalSupportIngestionAttempt(key,url,MedicalSupportIngestionOutcome.SUCCEEDED,null,contentType,hash,revision); }
    public static MedicalSupportIngestionAttempt failed(String key, String url, String reason, String contentType, String hash) { return new MedicalSupportIngestionAttempt(key,url,MedicalSupportIngestionOutcome.FAILED,reason,contentType,hash,null); }
}
