package itda.medicalsupport.repository;
import itda.medicalsupport.domain.*; import java.util.*; import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param;
public interface MedicalSupportRevisionRepository extends JpaRepository<MedicalSupportRevision,Long> {
 Optional<MedicalSupportRevision> findBySourceUrlAndSourceHashAndParserVersion(String sourceUrl,String sourceHash,String parserVersion);
 @Query("select r from MedicalSupportRevision r where r.sourceOrganization=:organization and r.stableSourceProgramId=:stableId and r.semanticFingerprint=:fingerprint") Optional<MedicalSupportRevision> findByStableIdentityAndSemanticFingerprint(@Param("organization") String organization, @Param("stableId") String stableId, @Param("fingerprint") String fingerprint);
 @Query("select r from MedicalSupportRevision r where r.sourceOrganization=:organization and r.regionScope=:scope and r.regionCode=:regionCode and r.normalizedProgramName=:name and r.programYear=:year and r.semanticFingerprint=:fingerprint") Optional<MedicalSupportRevision> findByFallbackIdentityAndSemanticFingerprint(@Param("organization") String organization, @Param("scope") MedicalSupportRegionScope scope, @Param("regionCode") String regionCode, @Param("name") String name, @Param("year") Integer year, @Param("fingerprint") String fingerprint);
 @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE) @Query("select r from MedicalSupportRevision r where r.id=:id") Optional<MedicalSupportRevision> findByIdForUpdate(@Param("id") Long id);
 @Query("select r from MedicalSupportRevision r where r.program.id=:programId and r.reviewStatus=itda.medicalsupport.domain.MedicalSupportReviewStatus.VERIFIED order by r.id desc") List<MedicalSupportRevision> findVerifiedByProgramId(@Param("programId") Long programId);
 @EntityGraph(attributePaths="hospitals") Optional<MedicalSupportRevision> findWithHospitalsById(Long id);
}
