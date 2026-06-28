package com.rag.notebook.note.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

// Lombok 注解：自动在编译时生成 get()、set()、toString()、equals() 和 hashCode() 方法，极大地省去了写样板代码的麻烦
@Data
// Lombok 注解：生成一个无参构造函数。这是 JPA/Hibernate 强制要求的，因为框架底层需要通过反射机制来实例化这个对象
@NoArgsConstructor
// JPA 注解：声明这个类是一个与数据库映射的“实体（Entity）”类
@Entity
// JPA 注解：明确指定这个实体类对应的数据库表名叫 "notes"（如果不写，默认会是类名 "note"）
@Table(name = "notes")
public class Note {

    // JPA 注解：标记这个字段为表的主键
    @Id
    // JPA 注解：指定对应数据库列名为 "id"，长度限制为 36。
    // (剧透：长度 36 强烈暗示了你们使用的是 UUID 作为主键，而不是自增数字)
    @Column(name = "id", length = 36)
    private String id;

    // 指定对应列名为 "user_id"，长度 36，且 nullable = false（不允许为空）。
    // 这个字段完美呼应了我们上一条讨论的 UserIdArgumentResolver，用户的 ID 就是保存在这里的！
    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    // 笔记的标题，最大长度 200 个字符，不允许为空
    @Column(name = "title", length = 200, nullable = false)
    private String title;

    // 笔记的正文。
    // columnDefinition = "TEXT" 非常关键：告诉数据库不要用普通的 VARCHAR（通常受限于 255 或 65535），而是用能存大段文本的 TEXT 类型。
    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    // --- 极具亮点的高级用法 ---
    // Hibernate 6 的新特性注解：告诉 Hibernate 在 Java 和 SQL 之间交互时，将这个字段作为 JSON 类型处理
    @JdbcTypeCode(SqlTypes.JSON)
    // 指定数据库中列名为 "tags"，并且明确声明数据库那一层的类型也是 "JSON"
    @Column(name = "tags", columnDefinition = "JSON")
    // 在 Java 代码中，我们依然可以极其方便地把它当做普通的 List<String> 来操作
    private List<String> tags;

    // 笔记的分类，普通字符串，最大长度 50
    @Column(name = "category", length = 50)
    private String category;

    // Hibernate 提供的时间戳注解：在第一次将这个对象存入数据库（Insert）时，自动获取当前时间填入，不需要手写代码设置
    @CreationTimestamp
    // updatable = false 是一道非常棒的防线：保证这个“创建时间”一旦写入，未来任何 Update 操作都无法篡改它
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // Hibernate 提供的时间戳注解：每次更新（Update）这个对象时，自动将当前时间覆盖进去
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}