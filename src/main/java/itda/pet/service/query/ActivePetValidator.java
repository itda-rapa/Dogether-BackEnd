package itda.pet.service.query;

import itda.pet.domain.Pet;
import itda.user.domain.User;
import org.springframework.stereotype.Component;

/** Shared active-pet policy for read queries and locked commands. */
@Component
public class ActivePetValidator {

    public boolean isValid(User user, Pet pet) {
        return user != null && user.isActive() && user.hasActivePet()
                && pet != null && pet.getId().equals(user.getActivePetId())
                && pet.belongsTo(user.getId()) && pet.isActive()
                && pet.getDeletedAt() == null;
    }
}
