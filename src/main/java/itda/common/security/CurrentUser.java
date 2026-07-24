package itda.common.security;

import itda.user.domain.Role;
import itda.user.domain.User;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record CurrentUser(
        Long id,
        String email,
        Role role
) implements UserDetails {

    public static CurrentUser from(User user) {
        return new CurrentUser(
                user.getId(),
                user.getEmail(),
                user.getRole()
        );
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public String getPassword() {
        return null;
    }
}
