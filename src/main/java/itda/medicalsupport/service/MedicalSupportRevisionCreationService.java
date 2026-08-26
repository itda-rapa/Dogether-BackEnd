package itda.medicalsupport.service;

import itda.medicalsupport.domain.MedicalSupportRevision;
import itda.medicalsupport.ingestion.MedicalSupportCandidate;
import itda.medicalsupport.repository.MedicalSupportRevisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Separates a failed PostgreSQL insert from the transaction that resolves its winner. */
@Service
@RequiredArgsConstructor
class MedicalSupportRevisionCreationService {
    private final MedicalSupportRevisionRepository revisions;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RevisionResolution createOrFind(MedicalSupportCandidate candidate) {
        return findExisting(candidate).map(revision -> new RevisionResolution(revision, false))
                .orElseGet(() -> new RevisionResolution(revisions.saveAndFlush(MedicalSupportRevision.pending(candidate)), true));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public RevisionResolution findBySemanticIdentity(MedicalSupportCandidate candidate) {
        return new RevisionResolution(findExisting(candidate).orElseThrow(), false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public RevisionResolution findByRawIdentity(MedicalSupportCandidate candidate) {
        return new RevisionResolution(revisions.findBySourceUrlAndSourceHashAndParserVersion(
                candidate.sourceUrl(), candidate.sourceHash(), candidate.parserVersion()).orElseThrow(), false);
    }

    private java.util.Optional<MedicalSupportRevision> findExisting(MedicalSupportCandidate candidate) {
        var raw = revisions.findBySourceUrlAndSourceHashAndParserVersion(candidate.sourceUrl(), candidate.sourceHash(), candidate.parserVersion());
        if (raw.isPresent()) return raw;
        if (candidate.stableSourceProgramId() != null && !candidate.stableSourceProgramId().isBlank()) {
            return revisions.findByStableIdentityAndSemanticFingerprint(candidate.sourceOrganization(), candidate.stableSourceProgramId(), candidate.semanticFingerprint());
        }
        return revisions.findByFallbackIdentityAndSemanticFingerprint(candidate.sourceOrganization(), candidate.regionScope(), candidate.regionCode(), candidate.normalizedProgramName(), candidate.programYear(), candidate.semanticFingerprint());
    }

    record RevisionResolution(MedicalSupportRevision revision, boolean created) { }
}
