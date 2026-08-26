package itda.medicalsupport.domain;

import itda.common.BaseEntity;
import itda.medicalsupport.ingestion.MedicalSupportCandidate;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import lombok.*;

@Getter @Entity @Table(name="medical_support_revisions") @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class MedicalSupportRevision extends BaseEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="program_id") private MedicalSupportProgram program;
 @Column(nullable=false) private String sourceUrl; private String sourceDocumentUrl; @Column(nullable=false) private String sourceOrganization; private Instant sourcePublishedAt; @Column(nullable=false) private Instant fetchedAt; @Column(nullable=false) private String sourceHash; @Column(nullable=false) private String contentType; @Column(nullable=false) private String parserVersion;
 private String stableSourceProgramId; private String regionCode; @Enumerated(EnumType.STRING) @Column(nullable=false) private MedicalSupportRegionScope regionScope; private String regionSidoName; private String regionSigunguName; private Integer programYear; private String programName; private String normalizedProgramName; private String summary; private String supportAmount; private String applicationPeriod;
 @Column(columnDefinition="text") private String supportTarget; @Column(columnDefinition="text") private String supportItems; @Column(columnDefinition="text") private String applicationMethod; @Column(columnDefinition="text") private String animalRegistrationCondition; @Column(columnDefinition="text") private String incomeWelfareCondition; @Column(columnDefinition="text") private String contact;
 @Enumerated(EnumType.STRING) private MedicalSupportHospitalPolicy hospitalPolicy; @Enumerated(EnumType.STRING) private MedicalSupportProgramStatus programStatus; private String semanticFingerprint;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private MedicalSupportReviewStatus reviewStatus; @Enumerated(EnumType.STRING) private MedicalSupportChangeType changeType; private Long reviewerId; private Instant reviewedAt; private String rejectionReason;
 @OneToMany(mappedBy="revision", cascade=CascadeType.ALL, orphanRemoval=true) private List<MedicalSupportRevisionHospital> hospitals=new ArrayList<>();
 private MedicalSupportRevision(MedicalSupportCandidate c) { sourceUrl=c.sourceUrl(); sourceDocumentUrl=c.sourceDocumentUrl(); sourceOrganization=c.sourceOrganization(); sourcePublishedAt=c.sourcePublishedAt(); fetchedAt=c.fetchedAt(); sourceHash=c.sourceHash(); contentType=c.contentType(); parserVersion=c.parserVersion(); stableSourceProgramId=c.stableSourceProgramId(); regionCode=c.regionCode(); regionScope=c.regionScope(); regionSidoName=c.regionSidoName(); regionSigunguName=c.regionSigunguName(); programYear=c.programYear(); programName=c.programName(); normalizedProgramName=c.normalizedProgramName(); summary=c.summary(); supportAmount=c.supportAmount(); applicationPeriod=c.applicationPeriod(); supportTarget=c.supportTarget(); supportItems=c.supportItems(); applicationMethod=c.applicationMethod(); animalRegistrationCondition=c.animalRegistrationCondition(); incomeWelfareCondition=c.incomeWelfareCondition(); contact=c.contact(); hospitalPolicy=c.hospitalPolicy(); programStatus=c.programStatus(); semanticFingerprint=c.semanticFingerprint(); reviewStatus=MedicalSupportReviewStatus.PENDING_REVIEW; c.hospitals().forEach(h->hospitals.add(new MedicalSupportRevisionHospital(this,h))); }
 public static MedicalSupportRevision pending(MedicalSupportCandidate c){return new MedicalSupportRevision(c);} public void attach(MedicalSupportProgram p){program=p;} public void verify(long admin, MedicalSupportChangeType change){ if(reviewStatus!=MedicalSupportReviewStatus.PENDING_REVIEW) throw new IllegalStateException("revision closed"); reviewStatus=MedicalSupportReviewStatus.VERIFIED; reviewerId=admin; reviewedAt=Instant.now(); changeType=change; } public void reject(long admin,String reason){ if(reviewStatus!=MedicalSupportReviewStatus.PENDING_REVIEW) throw new IllegalStateException("revision closed"); reviewStatus=MedicalSupportReviewStatus.REJECTED; reviewerId=admin; reviewedAt=Instant.now(); rejectionReason=reason; }
}
