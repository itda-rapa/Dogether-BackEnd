package itda.medicalsupport.repository;
import itda.medicalsupport.domain.*; import java.util.*; import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param;
public interface MedicalSupportProgramRepository extends JpaRepository<MedicalSupportProgram,Long> {
 @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE) @Query("select p from MedicalSupportProgram p where p.id=:id") Optional<MedicalSupportProgram> findByIdForUpdate(@Param("id") Long id);
 Optional<MedicalSupportProgram> findBySourceOrganizationAndStableSourceProgramId(String organization,String id);
 Optional<MedicalSupportProgram> findBySourceOrganizationAndRegionScopeAndRegionCodeAndNormalizedProgramNameAndProgramYear(String organization,itda.medicalsupport.domain.MedicalSupportRegionScope scope,String region,String name,int year);
 @EntityGraph(attributePaths="currentVerifiedRevision") List<MedicalSupportProgram> findByRegionScopeAndRegionCodeOrderByIdDesc(itda.medicalsupport.domain.MedicalSupportRegionScope scope,String regionCode);
 @EntityGraph(attributePaths="currentVerifiedRevision") Optional<MedicalSupportProgram> findWithCurrentVerifiedRevisionById(Long id);
}
