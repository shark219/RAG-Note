package com.rag.notebook.agent;

import com.rag.notebook.config.ApplicationProperties;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ModelFactory {

    private final ApplicationProperties props;

    public ModelFactory(ApplicationProperties props) {
        this.props = props;
    }

    public ChatLanguageModel createChatModel() {
        String type = props.getLlm().getType();
        if ("ZHIPU".equalsIgnoreCase(type)) {
            return OpenAiChatModel.builder()
                    .apiKey(props.getLlm().getZhipu().getApiKey())
                    .baseUrl(props.getLlm().getZhipu().getBaseUrl())
                    .modelName(props.getLlm().getZhipu().getModel())
                    .temperature(0.7)
                    .build();
        } else if ("OLLAMA".equalsIgnoreCase(type)) {
            return OllamaChatModel.builder()
                    .baseUrl(props.getLlm().getOllama().getBaseUrl())
                    .modelName(props.getLlm().getOllama().getModel())
                    .temperature(0.7)
                    .build();
        } else {
            return QwenChatModel.builder()
                    .apiKey(props.getLlm().getAliyun().getApiKey())
                    .modelName(props.getLlm().getAliyun().getModel())
                    .temperature(0.7f)
                    .build();
        }
    }

    public EmbeddingModel createEmbeddingModel() {
        String type = props.getEmbed().getType();
        if ("ZHIPU".equalsIgnoreCase(type)) {
            return OpenAiEmbeddingModel.builder()
                    .apiKey(props.getEmbed().getZhipu().getApiKey())
                    .baseUrl(props.getEmbed().getZhipu().getBaseUrl())
                    .modelName(props.getEmbed().getZhipu().getModel())
                    .build();
        } else if ("ALIYUN".equalsIgnoreCase(type)) {
            return QwenEmbeddingModel.builder()
                    .apiKey(props.getEmbed().getAliyun().getApiKey())
                    .modelName(props.getEmbed().getAliyun().getModel())
                    .build();
        } else {
            return OllamaEmbeddingModel.builder()
                    .baseUrl(props.getEmbed().getOllama().getBaseUrl())
                    .modelName(props.getEmbed().getOllama().getModel())
                    .build();
        }
    }

    public ChatLanguageModel createVisionModel() {
        String type = props.getVision().getType();
        if (type == null || type.isEmpty()) {
            type = props.getLlm().getType();
        }

        if ("OLLAMA".equalsIgnoreCase(type)) {
            return OllamaChatModel.builder()
                    .baseUrl(props.getLlm().getOllama().getBaseUrl())
                    .modelName(props.getVision().getOllama().getModel())
                    .temperature(0.7)
                    .build();
        } else {
            return QwenChatModel.builder()
                    .apiKey(props.getLlm().getAliyun().getApiKey())
                    .modelName(props.getVision().getAliyun().getModel())
                    .temperature(0.7f)
                    .build();
        }
    }
}
