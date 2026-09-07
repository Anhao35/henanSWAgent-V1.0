package cn.edu.ha.secagent.repository;

import cn.edu.ha.secagent.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    boolean existsByUsernameIgnoreCase(String username);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByPhone(String phone);

    @Query("select u from User u left join fetch u.organization where lower(u.username)=lower(:login) " +
            "or (u.emailVerified=true and lower(u.email)=lower(:login)) " +
            "or (u.phoneVerified=true and u.phone=:login)")
    Optional<User> findForLogin(@Param("login") String login);

    @Query("select u from User u left join fetch u.organization where u.id=:id")
    Optional<User> findDetailedById(@Param("id") UUID id);

    @Query("select u from User u left join fetch u.organization order by u.createdAt desc")
    List<User> findAllDetailed();

    @Query("select u from User u left join fetch u.organization where u.organization.id=:organizationId order by u.createdAt desc")
    List<User> findAllDetailedByOrganizationId(@Param("organizationId") UUID organizationId);
}
