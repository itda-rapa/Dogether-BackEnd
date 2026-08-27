package itda.medicalsupport.domain;

import itda.common.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Getter @Entity @Table(name = "medical_support_programs") @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MedicalSupportProgram extends BaseEntity {
 @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
 @Column(nullable=false) private String sourceOrganization; private String stableSourceProgramId;
 @Column(nullable=false) private String regionCode; @Enumerated(EnumType.STRING) @Column(nullable=false) private MedicalSupportRegionScope regionScope; @Column(nullable=false) private String regionSidoName; private String regionSigunguName;
 @Column(nullable=false) private String normalizedProgramName; @Column(nullable=false) private int programYear; @Column(nullable=false) private String programName;
 private String summary; private String supportAmount; private String applicationPeriod;
 @Column(columnDefinition="text") private String supportTarget; @Column(columnDefinition="text") private String supportItems; @Column(columnDefinition="text") private String applicationMethod;
 @Column(columnDefinition="text") private String animalRegistrationCondition; @Column(columnDefinition="text") private String incomeWelfareCondition; @Column(columnDefinition="text") private String contact;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private MedicalSupportHospitalPolicy hospitalPolicy;
 @Enumerated(EnumType.STRING) @Column(name="program_status",nullable=false) private MedicalSupportProgramStatus programStatus;
 @Column(nullable=false) private String officialSourceUrl; @Column(nullable=false) private Instant lastVerifiedAt;
 @OneToOne(fetch=FetchType.LAZY) @JoinColumn(name="current_verified_revision_id") private MedicalSupportRevision currentVerifiedRevision;
 @Version private long version;
 private MedicalSupportProgram(MedicalSupportRevision r) { sourceOrganization=r.getSourceOrganization(); stableSourceProgramId=r.getStableSourceProgramId(); regionCode=r.getRegionCode(); regionScope=r.getRegionScope(); regionSidoName=r.getRegionSidoName(); regionSigunguName=r.getRegionSigunguName(); normalizedProgramName=r.getNormalizedProgramName(); programYear=r.getProgramYear(); apply(r); }
 public static MedicalSupportProgram from(MedicalSupportRevision r) { return new MedicalSupportProgram(r); }
 public void apply(MedicalSupportRevision r) { regionCode=r.getRegionCode(); regionScope=r.getRegionScope(); regionSidoName=r.getRegionSidoName(); regionSigunguName=r.getRegionSigunguName(); normalizedProgramName=r.getNormalizedProgramName(); programYear=r.getProgramYear(); programName=r.getProgramName(); summary=r.getSummary(); supportAmount=r.getSupportAmount(); applicationPeriod=r.getApplicationPeriod(); supportTarget=r.getSupportTarget(); supportItems=r.getSupportItems(); applicationMethod=r.getApplicationMethod(); animalRegistrationCondition=r.getAnimalRegistrationCondition(); incomeWelfareCondition=r.getIncomeWelfareCondition(); contact=r.getContact(); hospitalPolicy=r.getHospitalPolicy(); programStatus=r.getProgramStatus(); officialSourceUrl=r.getSourceUrl(); lastVerifiedAt=Instant.now(); currentVerifiedRevision=r; touchUpdatedAt(); }
}
