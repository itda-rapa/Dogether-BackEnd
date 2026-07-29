package itda.pet.service;

public record PetCreationResult(
        Long petId,
        ActivePetAssignmentStatus activePetAssignmentStatus
) {
}
