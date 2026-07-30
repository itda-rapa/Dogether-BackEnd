package itda.interaction.dto;

public record InteractionPairContext(
        LockedUserContext sourceUser,
        LockedUserContext targetUser,
        LockedPetContext sourcePet,
        LockedPetContext targetPet
) {
}
