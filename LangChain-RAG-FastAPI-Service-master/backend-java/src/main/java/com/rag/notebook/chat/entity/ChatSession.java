package com.rag.notebook.chat.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@Entity
@Table(name = "chat_sessions")
public class ChatSession {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "title", length = 255)
    private String title = "新的对话";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "JSON")
    private Map<String, Object> metadata;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

// ------------------------------------------
    // 一对多关联关系配置
    // ------------------------------------------

    /**
     * @OneToMany
     * JPA 注解。
     * 作用：声明这是一对多的关系。即：一个当前实体（比如聊天会话 ChatSession）可以包含多个目标实体（比如聊天记录 ChatMessage）。
     * * 参数详解：
     * * 1. mappedBy = "session"
     * - 核心概念：指明“谁”维护外键。
     * - 解释：在双向关联中，必须有一方放弃外键维护权。这里配置了 mappedBy，意味着当前类（会话）不负责更新数据库里的外键字段，外键由 `ChatMessage` 类中的名为 `session` 的属性来负责维护。
     * - 效果：极大提升数据库操作性能，避免生成多余的 UPDATE 语句。
     * * 2. cascade = CascadeType.ALL
     * - 核心概念：级联操作。
     * - 解释：当你对当前这个“会话”实体进行操作时，这些操作会“传递”给它底下的所有“消息”。
     * - ALL 包含：PERSIST(保存)、MERGE(更新)、REMOVE(删除)、REFRESH(刷新)、DETACH(分离)。
     * - 实际场景：你只需要 `sessionRepository.save(session)`，Hibernate 就会自动帮你把这个会话里新增的所有 messages 也一起 INSERT 到数据库里；如果你删除了这个会话，底下的所有消息也会被连带删除（级联删除）。
     * * 3. orphanRemoval = true
     * - 核心概念：孤儿（失去父实体的子实体）自动删除。
     * - 解释：这是极其强大的一个特性。如果你从这个 `messages` 集合中移除了某条消息（例如调用了 `session.getMessages().remove(message)`），Hibernate 发现这条消息不再属于任何会话了（变成了孤儿），就会自动在数据库中执行 DELETE 语句把它删掉。
     * - 区别于级联删除：CascadeType.REMOVE 是“父死子亡”，而 orphanRemoval 是“父不认子，子必亡”。
     */
    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    
    /**
     * @OrderBy("createdAt ASC")
     * JPA 注解。
     * 作用：控制集合的排序。
     * 解释：当 Hibernate 从数据库查出这个会话的所有聊天记录并组装成 List 时，会自动按照 `ChatMessage` 实体中的 `createdAt` 字段进行升序（ASC）排列。
     * 实际效果：确保你拿到的消息列表永远是按时间先后顺序排列的（旧消息在前，新消息在后），非常符合聊天界面的展示逻辑。
     */
    @OrderBy("createdAt ASC")
    
    /**
     * 属性定义：private List<ChatMessage> messages = new ArrayList<>();
     * 最佳实践：
     * 1. 使用 List 或 Set 接口来接收。
     * 2. 直接初始化为 `new ArrayList<>()`。
     * 作用：防止在创建一个新会话（还没有任何消息时）调用 `session.getMessages().add(msg)` 报 NullPointerException（空指针异常）。
     */
    private List<ChatMessage> messages = new ArrayList<>();
}
