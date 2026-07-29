package itda.pet.service;

public record PetCreationOutcome(
        Long petId,
        boolean firstPetCandidate
) {
}
