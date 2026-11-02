package fi.poltsi.vempain.common.map;

import java.util.Set;

/**
 * Who asks for the map: the resources are filtered to the ones whose ACL grants the read privilege to this user or to one of the units the user belongs to
 * (transitively, as the hosting service resolves them). A principal without a user id sees nothing.
 *
 * @param userId  the authenticated user's id, null when there is no Vempain user
 * @param unitIds the ids of every unit the user belongs to, possibly empty
 */
public record MapPrincipal(Long userId, Set<Long> unitIds) {

    public static MapPrincipal anonymous() {
        return new MapPrincipal(null, Set.of());
    }

    public boolean isAuthenticated() {
        return userId != null;
    }
}
