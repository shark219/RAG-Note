package com.rag.notebook.user.service;

import com.rag.notebook.common.exception.BusinessException;
import com.rag.notebook.user.entity.User;
import com.rag.notebook.user.repo.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public User createDefaultUser() {
        User user = new User();
        user.setUuid(generateUuid());
        user.setUsername("default_user");
        user.setEmail("default@example.com");
        user.setPassword(passwordEncoder.encode("default_password"));
        user.setStatus(1);
        return userRepository.save(user);
    }

    public String generateUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException("用户不存在"));
    }

    public User findByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException("用户不存在"));
    }

    public User findByUuid(String uuid) {
        return userRepository.findByUuid(uuid)
                .orElseThrow(() -> new BusinessException("用户不存在"));
    }

    public User save(User user) {
        return userRepository.save(user);
    }

    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }

    public boolean existsByTelephone(String telephone) {
        return userRepository.existsByTelephone(telephone);
    }

    public boolean checkPassword(String rawPassword, String encodedPassword) {
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }

    public String encodePassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }
}
