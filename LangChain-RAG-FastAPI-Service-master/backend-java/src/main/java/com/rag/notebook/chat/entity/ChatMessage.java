package com.rag.notebook.chat.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
// ==========================================
// Lombok 注解：用于自动生成样板代码，简化类结构
// ==========================================

/**
 * @Data 
 * Lombok 提供的复合注解。
 * 作用：自动为类中的所有属性生成 getter、setter 方法，
 * 并且还会生成 equals()、hashCode() 以及 toString() 方法。
 * 极大减少了基础的样板代码。
 */
@Data

/**
 * @NoArgsConstructor
 * Lombok 提供的注解。
 * 作用：自动生成一个无参构造函数。
 * JPA (Hibernate) 在通过反射实例化实体类时，强制要求必须有一个无参构造函数。
 */
@NoArgsConstructor

// ==========================================
// JPA (Java Persistence API) / Hibernate 注解：用于对象关系映射 (ORM)
// ==========================================

/**
 * @Entity
 * JPA 注解。
 * 作用：声明这个 Java 类是一个实体类，表示它将映射到数据库中的一张表。
 * EntityManager 将会管理这个类的实例。
 */
@Entity

/**
 * @Table(name = "chat_messages")
 * JPA 注解。
 * 作用：指定这个实体类映射到数据库中的哪张表。
 * 这里指定数据库中的表名为 "chat_messages"。如果不写此注解，默认表名与类名相同（通常转换为小写加下划线，即 chat_message）。
 */
@Table(name = "chat_messages")
public class ChatMessage {

    // ------------------------------------------
    // 主键配置
    // ------------------------------------------
    
    /**
     * @Id
     * JPA 注解。
     * 作用：标记此属性为该实体类的唯一标识（主键）。
     */
    @Id
    
    /**
     * @GeneratedValue(strategy = GenerationType.IDENTITY)
     * JPA 注解。
     * 作用：定义主键的生成策略。
     * GenerationType.IDENTITY 表示主键由数据库自动递增生成（例如 MySQL 的 AUTO_INCREMENT）。
     * 插入数据时不需要手动设置 id。
     */
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    
    /**
     * @Column(name = "id")
     * JPA 注解。
     * 作用：指定该属性映射到数据库表中的哪一列。
     * 这里指定列名为 "id"。虽然字段名本身是 id，显式写出可以避免默认命名策略的差异。
     */
    @Column(name = "id")
    private Long id;

    // ------------------------------------------
    // 关联关系配置 (多对一)
    // ------------------------------------------

    /**
     * @ManyToOne(fetch = FetchType.LAZY)
     * JPA 注解。
     * 作用：定义当前实体与其他实体之间的多对一关系。
     * 在这里表示：多条聊天记录 (ChatMessage) 对应一个聊天会话 (ChatSession)。
     * * 参数解释：
     * fetch = FetchType.LAZY：指定抓取策略为延迟加载 (懒加载)。
     * 这意味着当你查询 ChatMessage 时，不会立即去数据库查询关联的 ChatSession。
     * 只有当你实际调用 message.getSession() 时，Hibernate 才会发送额外的 SQL 去获取 Session 数据，这能有效提升性能，避免 N+1 问题。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    
    /**
     * @JoinColumn(name = "session_id", nullable = false)
     * JPA 注解。
     * 作用：在多的一方（当前类）定义外键列。
     * 这里指定在 chat_messages 表中，用于关联的外键列名为 "session_id"。
     * nullable = false 表示这个外键列在数据库中是不允许为空的 (NOT NULL)，即每条消息必须归属于一个会话。
     */
    @JoinColumn(name = "session_id", nullable = false)
    private ChatSession session;

    // ------------------------------------------
    // 普通属性列配置
    // ------------------------------------------

    /**
     * @Column(name = "role", length = 32, nullable = false)
     * JPA 注解。
     * 作用：映射角色字段。
     * name = "role"：数据库列名。
     * length = 32：指定该字符串字段在数据库中的最大长度（例如 VARCHAR(32)）。
     * nullable = false：表示该列不能为空 (NOT NULL)。
     */
    @Column(name = "role", length = 32, nullable = false)
    private String role;

    /**
     * @Column(name = "content", columnDefinition = "TEXT", nullable = false)
     * JPA 注解。
     * 作用：映射消息内容字段。
     * columnDefinition = "TEXT"：这是非常关键的一点。通常 String 映射为 VARCHAR(255)，
     * 但聊天消息可能很长，这里强制指定数据库对应的列类型为 "TEXT"，以存储长文本。
     * nullable = false：表示消息内容不能为空。
     */
    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    // ------------------------------------------
    // JSON 类型配置 (特定于 Hibernate 6.x)
    // ------------------------------------------

    /**
     * @JdbcTypeCode(SqlTypes.JSON)
     * Hibernate 注解（通常在 Hibernate 6.x 中引入）。
     * 作用：告诉 Hibernate 在通过 JDBC 交互时，将这个 Java 属性（这里是 Map）作为 JSON 类型处理。
     * 这使得 Java 的 Map 或对象可以方便地与数据库原生的 JSON 列进行序列化和反序列化交互。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    
    /**
     * @Column(name = "metadata", columnDefinition = "JSON")
     * JPA 注解。
     * 作用：映射数据库列名为 "metadata"，并明确在 DDL（如果由 Hibernate 自动建表）时，
     * 使用底层数据库原生支持的 "JSON" 数据类型来创建该列（例如 MySQL 5.7+ 或 PostgreSQL）。
     */
    @Column(name = "metadata", columnDefinition = "JSON")
    private Map<String, Object> metadata;

    // ------------------------------------------
    // 自动时间戳配置
    // ------------------------------------------

    /**
     * @CreationTimestamp
     * Hibernate 注解。
     * 作用：当这条记录第一次被保存（持久化）到数据库时，Hibernate 会自动将当前的时间戳赋值给这个属性。
     * 省去了在业务代码中手动设置 `message.setCreatedAt(LocalDateTime.now())` 的步骤。
     */
    @CreationTimestamp

    /**
     * @Column(name = "created_at", updatable = false)
     * JPA 注解。
     * 作用：映射数据库列名。
     * updatable = false：这是一个保护机制。它指示 Hibernate在生成 UPDATE 语句时，
     * 永远不要包含这个字段。这确保了记录的创建时间一旦写入就不可被修改。
     */
    @Column(name = "created_at", updatable = false)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}
