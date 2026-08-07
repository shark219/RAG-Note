package com.rag.notebook.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "agent_task_events")
public class AgentTaskEvent {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "task_id", length = 64, nullable = false)
    private String taskId;

    @Column(name = "event_type", length = 64, nullable = false)
    private String eventType;

    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
