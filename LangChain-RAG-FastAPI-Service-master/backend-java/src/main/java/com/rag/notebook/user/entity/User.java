package com.rag.notebook.user.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "user_service")
public class User {

    @Id
    @Column(name = "uuid", length = 32)
    private String uuid;

    @Column(name = "username", length = 150)
    private String username;

    @Column(name = "email", unique = true, nullable = false)
    private String email;

    @Column(name = "telephone", unique = true, length = 11)
    private String telephone;

    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "status")
    private Integer status = 1; // 0=DISABLED, 1=ACTIVE, 2=LOCKED

    @Column(name = "gender")
    private Integer gender; // 1=MALE, 2=FEMALE, 3=OTHER

    @Column(name = "bio", columnDefinition = "TEXT")
    private String bio;

    @Column(name = "avatar", length = 255)
    private String avatar;

    @CreationTimestamp
    @Column(name = "date_joined", updatable = false)
    private LocalDateTime dateJoined;

    @UpdateTimestamp
    @Column(name = "last_login")
    private LocalDateTime lastLogin;
}
