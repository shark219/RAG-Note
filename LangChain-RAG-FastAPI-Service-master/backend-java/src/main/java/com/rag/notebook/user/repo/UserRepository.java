package com.rag.notebook.user.repo;

import com.rag.notebook.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByEmail(String email);

    Optional<User> findByUsername(String username);

    Optional<User> findByUuid(String uuid);

    boolean existsByEmail(String email);

    boolean existsByTelephone(String telephone);
}
