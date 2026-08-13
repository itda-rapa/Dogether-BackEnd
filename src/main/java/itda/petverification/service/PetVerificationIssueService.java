package itda.petverification.service;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.pet.service.MyPetQueryService;
import itda.petverification.PetVerificationEvidence;
import itda.petverification.PetVerificationFlowType;
import itda.petverification.PetVerificationRedisStore;
import itda.petverification.dto.PetVerificationPrefill;
import itda.petverification.dto.PetVerificationRequest;
import itda.petverification.dto.PetVerificationResponse;
import itda.petverification.dto.PetVerificationIdentifierType;
import itda.petverification.provider.AnimalInfoV3Adapter;
import itda.petverification.provider.AnimalInfoV3Request;
import itda.petverification.repository.PetVerificationRepository;
import org.springframework.stereotype.Service;

@Service
public class PetVerificationIssueService {
    private final MyPetQueryService myPetQueryService;
    private final PetVerificationRepository verificationRepository;
    private final AnimalInfoV3Adapter provider;
    private final PetVerificationRedisStore redisStore;

    public PetVerificationIssueService(MyPetQueryService myPetQueryService,
                                       PetVerificationRepository verificationRepository,
                                       AnimalInfoV3Adapter provider,
                                       PetVerificationRedisStore redisStore) {
        this.myPetQueryService = myPetQueryService;
        this.verificationRepository = verificationRepository;
        this.provider = provider;
        this.redisStore = redisStore;
    }

    public PetVerificationResponse issue(Long userId, PetVerificationRequest request) {
        validate(request);
        if (request.flowType() == PetVerificationFlowType.EXISTING_PET_VERIFY) {
            myPetQueryService.requireOwnedUndeletedPet(userId, request.petId());
            if (verificationRepository.existsByPet_Id(request.petId())) {
                throw new BusinessException(ErrorCode.PET_VERIFICATION_CONFLICT);
            }
        }
        var normalized = provider.verify(new AnimalInfoV3Request(toProviderIdentifierType(request.identifierType()), request.identifier(),
                normalizedOwnerName(request.ownerName()), request.ownerBirthDate()));
        PetVerificationEvidence evidence = new PetVerificationEvidence(normalized.provider(),
                normalized.registrationNumberHmac(), normalized.deviceType(), normalized.registeredName(),
                normalized.birthDate(), normalized.sex(), normalized.breedName(), normalized.neutered());
        if (verificationRepository.existsByRegistrationNumberHmac(evidence.registrationNumberHmac())) {
            throw new BusinessException(ErrorCode.PET_VERIFICATION_CONFLICT);
        }
        var issued = redisStore.issue(userId, request.flowType(), request.petId(), evidence);
        return new PetVerificationResponse(issued.rawToken(), issued.expiresAt(),
                request.flowType() == PetVerificationFlowType.PET_CREATE ? prefill(evidence) : null);
    }

    private void validate(PetVerificationRequest request) {
        if (request == null || request.flowType() == null || request.identifierType() == null
                || request.identifier() == null || request.identifier().isBlank()) throw validation();
        boolean hasName = request.ownerName() != null && !request.ownerName().isBlank();
        boolean hasBirth = request.ownerBirthDate() != null;
        if (hasName == hasBirth) throw validation();
        if (request.flowType() == PetVerificationFlowType.PET_CREATE && request.petId() != null) throw validation();
        if (request.flowType() == PetVerificationFlowType.EXISTING_PET_VERIFY
                && (request.petId() == null || request.petId() <= 0)) throw validation();
    }

    private String normalizedOwnerName(String ownerName) {
        return ownerName == null ? null : ownerName.strip();
    }
    private AnimalInfoV3Request.IdentifierType toProviderIdentifierType(
            PetVerificationIdentifierType identifierType
    ) {
        return switch (identifierType) {
            case REGISTRATION_NUMBER -> AnimalInfoV3Request.IdentifierType.REGISTRATION_NUMBER;
            case RFID -> AnimalInfoV3Request.IdentifierType.RFID;
        };
    }
    private PetVerificationPrefill prefill(PetVerificationEvidence evidence) {
        return new PetVerificationPrefill(evidence.registeredName(), evidence.breedName(), evidence.birthDate(),
                evidence.sex(), evidence.neutered());
    }
    private BusinessException validation() { return new BusinessException(ErrorCode.VALIDATION_FAILED); }
}
