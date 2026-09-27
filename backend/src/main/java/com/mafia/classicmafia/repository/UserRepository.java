package com.mafia.classicmafia.repository;

import com.mafia.classicmafia.entity.Role;
import com.mafia.classicmafia.entity.User;
import com.mafia.classicmafia.repository.projection.AdminUserSummaryProjection;
import com.mafia.classicmafia.repository.projection.AvatarMediaProjection;
import com.mafia.classicmafia.repository.projection.BannerMediaProjection;
import com.mafia.classicmafia.repository.projection.PlayerListProjection;
import com.mafia.classicmafia.repository.projection.PlayerProfileProjection;
import com.mafia.classicmafia.repository.projection.SecurityAccountProjection;
import com.mafia.classicmafia.repository.projection.SelfProfileProjection;
import com.mafia.classicmafia.repository.projection.LoginAccountProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsernameKey(String usernameKey);

    @Query("""
        select new com.mafia.classicmafia.repository.projection.SecurityAccountProjection(
            u.id, u.username, u.role, u.credentialVersion, u.authorizationVersion
        )
        from User u
        where u.id = :id
        """)
    Optional<SecurityAccountProjection> findSecurityAccountById(@Param("id") Long id);

    @Query("""
        select new com.mafia.classicmafia.repository.projection.SecurityAccountProjection(
            u.id, u.username, u.role, u.credentialVersion, u.authorizationVersion
        )
        from User u
        where u.usernameKey = :usernameKey
        """)
    Optional<SecurityAccountProjection> findSecurityAccountByUsernameKey(@Param("usernameKey") String usernameKey);

    @Query("""
        select new com.mafia.classicmafia.repository.projection.LoginAccountProjection(
            u.id, u.username, u.password, u.role, u.credentialVersion, u.authorizationVersion
        )
        from User u
        where u.usernameKey = :usernameKey
        """)
    Optional<LoginAccountProjection> findLoginAccountByUsernameKey(@Param("usernameKey") String usernameKey);

    @Query("""
        select new com.mafia.classicmafia.repository.projection.SelfProfileProjection(
            u.id, u.username, u.role, u.aboutMe, u.gamesPlayed, u.wins, u.losses, u.averageScore, u.rating
        )
        from User u
        where u.id = :id
        """)
    Optional<SelfProfileProjection> findSelfProfileById(@Param("id") Long id);

    @Query("""
        select new com.mafia.classicmafia.repository.projection.SelfProfileProjection(
            u.id, u.username, u.role, u.aboutMe, u.gamesPlayed, u.wins, u.losses, u.averageScore, u.rating
        )
        from User u
        where u.usernameKey = :usernameKey
        """)
    Optional<SelfProfileProjection> findSelfProfileByUsernameKey(@Param("usernameKey") String usernameKey);

    boolean existsByUsernameKey(String usernameKey);

    List<User> findAllByRoleOrderByRatingDescUsernameAsc(Role role);

    List<User> findAllByRoleInOrderByRatingDescUsernameAsc(Collection<Role> roles);

    Page<User> findAllByRoleIn(Collection<Role> roles, Pageable pageable);

    Page<User> findAllByRoleInAndUsernameContainingIgnoreCase(Collection<Role> roles, String username, Pageable pageable);

    @Query("""
        select new com.mafia.classicmafia.repository.projection.AdminUserSummaryProjection(u.id, u.username)
        from User u
        where u.role in :roles
        order by u.username asc
        """)
    Page<AdminUserSummaryProjection> findAdminUserSummaries(
        @Param("roles") Collection<Role> roles,
        Pageable pageable
    );

    @Query("""
        select new com.mafia.classicmafia.repository.projection.AdminUserSummaryProjection(u.id, u.username)
        from User u
        where u.role in :roles and lower(u.username) like lower(concat('%', :username, '%'))
        order by u.username asc
        """)
    Page<AdminUserSummaryProjection> searchAdminUserSummaries(
        @Param("roles") Collection<Role> roles,
        @Param("username") String username,
        Pageable pageable
    );

    @Query(
        value = """
            select new com.mafia.classicmafia.repository.projection.PlayerListProjection(
                u.id, u.username, u.gamesPlayed, u.wins, u.losses, u.averageScore, u.rating
            )
            from User u
            where u.role in :roles
            """,
        countQuery = "select count(u) from User u where u.role in :roles"
    )
    Page<PlayerListProjection> findPublicPlayerList(
        @Param("roles") Collection<Role> roles,
        Pageable pageable
    );

    @Query(
        value = """
            select new com.mafia.classicmafia.repository.projection.PlayerListProjection(
                u.id, u.username, u.gamesPlayed, u.wins, u.losses, u.averageScore, u.rating
            )
            from User u
            where u.role in :roles
              and lower(u.username) like lower(concat('%', :username, '%'))
            """,
        countQuery = """
            select count(u)
            from User u
            where u.role in :roles
              and lower(u.username) like lower(concat('%', :username, '%'))
            """
    )
    Page<PlayerListProjection> searchPublicPlayerList(
        @Param("roles") Collection<Role> roles,
        @Param("username") String username,
        Pageable pageable
    );

    @Query("""
        select new com.mafia.classicmafia.repository.projection.PlayerProfileProjection(
            u.id, u.username, u.aboutMe, u.gamesPlayed, u.wins, u.losses, u.averageScore, u.rating
        )
        from User u
        where u.id = :id and u.role in :roles
        """)
    Optional<PlayerProfileProjection> findPublicPlayerProfile(
        @Param("id") Long id,
        @Param("roles") Collection<Role> roles
    );

    @Query("""
        select new com.mafia.classicmafia.repository.projection.AvatarMediaProjection(
            u.avatarData, u.avatarContentType, u.avatarSafetyVersion
        )
        from User u
        where u.id = :id and u.role in :roles
        """)
    Optional<AvatarMediaProjection> findPublicAvatarMedia(
        @Param("id") Long id,
        @Param("roles") Collection<Role> roles
    );

    @Query("""
        select new com.mafia.classicmafia.repository.projection.BannerMediaProjection(
            u.profileBannerData, u.profileBannerContentType, u.profileBannerSafetyVersion
        )
        from User u
        where u.id = :id and u.role in :roles
        """)
    Optional<BannerMediaProjection> findPublicBannerMedia(
        @Param("id") Long id,
        @Param("roles") Collection<Role> roles
    );

    List<User> findAllByRoleOrderByUsernameAsc(Role role);

    long countByRole(Role role);

    long countByRoleIn(Collection<Role> roles);

    @Query("select u.id from User u where u.role in :roles order by u.id asc")
    List<Long> findPublicPlayerIds(@Param("roles") Collection<Role> roles);

    long countByRegistrationIpHashAndCreatedAtAfter(String registrationIpHash, LocalDateTime createdAt);

    @Modifying(flushAutomatically = true)
    @Query("""
        update User u
        set u.gamesPlayed = :gamesPlayed,
            u.wins = :wins,
            u.losses = :losses,
            u.averageScore = :averageScore
        where u.id = :id and u.role in :roles
        """)
    int updatePublicPlayerStats(
        @Param("id") Long id,
        @Param("roles") Collection<Role> roles,
        @Param("gamesPlayed") int gamesPlayed,
        @Param("wins") int wins,
        @Param("losses") int losses,
        @Param("averageScore") double averageScore
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.authorizationVersion = u.authorizationVersion + 1 where u.role = :role")
    int incrementAuthorizationVersionForRole(@Param("role") Role role);
}
