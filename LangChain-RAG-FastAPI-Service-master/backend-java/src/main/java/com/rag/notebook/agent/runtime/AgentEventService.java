package com.rag.notebook.agent.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.notebook.agent.entity.AgentTaskEvent;
import com.rag.notebook.agent.repo.AgentTaskEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class AgentEventService {

    private final AgentTaskEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    public AgentEventService(AgentTaskEventRepository eventRepository, ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
    }

    public AgentTaskEvent record(String taskId, AgentTaskEventType eventType, Map<String, Object> payload) {
        AgentTaskEvent event = new AgentTaskEvent();
        event.setId(UUID.randomUUID().toString().replace("-", ""));
        event.setTaskId(taskId);
        event.setEventType(eventType.name());
        event.setPayloadJson(writePayload(payload));
        return eventRepository.save(event);
    }

    public void recordAndEmit(String taskId, AgentTaskEventType eventType, Map<String, Object> payload, SseEmitter emitter) {
        record(taskId, eventType, payload);
        emit(eventType.name().toLowerCase(), payload, emitter);
    }

    public void emit(String type, Map<String, Object> payload, SseEmitter emitter) {
        if (emitter == null) {
            return;
        }
        Map<String, Object> event = new HashMap<>();
        event.put("type", type);
        if (payload != null) {
            event.putAll(payload);
        }
        try {
            emitter.send(SseEmitter.event().data(event));
        } catch (IllegalStateException e) {
            log.debug("SSE send skipped (emitter completed): type={}", type);
        } catch (IOException e) {
            log.debug("SSE send skipped (client disconnected): type={}, msg={}", type, e.getMessage());
        }
    }

    private String writePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize agent event payload: {}", e.getMessage());
            return null;
        }
    }
}
