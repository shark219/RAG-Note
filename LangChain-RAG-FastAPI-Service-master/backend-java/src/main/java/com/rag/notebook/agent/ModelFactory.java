package com.rag.notebook.agent;

import com.rag.notebook.config.ApplicationProperties;
import com.rag.notebook.system.entity.LlmConfig;
import com.rag.notebook.system.service.LlmConfigService;
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

import java.time.Duration;

@Slf4j
@Component
public class ModelFactory {

    private final ApplicationProperties props;
    private final LlmConfigService llmConfigService;

    /** 温度预设：精确推理（工具选择、逻辑判断） */
    public static final double TEMP_PRECISE = 0.1;
    /** 温度预设：平衡模式（一般对话、RAG 总结） */
    public static final double TEMP_BALANCED = 0.5;
    /** 温度预设：创意模式（写作、头脑风暴） */
    public static final double TEMP_CREATIVE = 0.8;

    public ModelFactory(ApplicationProperties props, LlmConfigService llmConfigService) {
        this.props = props;
        this.llmConfigService = llmConfigService;
    }

    /**
     * 使用默认温度(0.7)创建聊天模型
     */
    public ChatLanguageModel createChatModel() {
        return createChatModel(0.7);
    }

    /**
     * 使用指定温度创建聊天模型
     *
     * @param temperature 温度值：0.0-1.0，越低越精确，越高越有创意
     */
    public ChatLanguageModel createChatModel(double temperature) {
        return createChatModel(temperature, Duration.ofSeconds(180));
    }

    /**
     * 使用指定温度和超时创建聊天模型
     */
    public ChatLanguageModel createChatModel(double temperature, Duration timeout) {
        LlmConfig activeConfig = llmConfigService.getActive();
        if (activeConfig != null) {
            return createChatModel(activeConfig, temperature, timeout);
        }

        String type = props.getLlm().getType();
        if ("ZHIPU".equalsIgnoreCase(type)) {
            return OpenAiChatModel.builder()
                    .apiKey(props.getLlm().getZhipu().getApiKey())
                    .baseUrl(props.getLlm().getZhipu().getBaseUrl())
                    .modelName(props.getLlm().getZhipu().getModel())
                    .temperature(temperature)
                    .timeout(timeout)
                    .build();
        } else if ("DEEPSEEK".equalsIgnoreCase(type)) {
            return OpenAiChatModel.builder()
                    .apiKey(props.getLlm().getDeepseek().getApiKey())
                    .baseUrl(props.getLlm().getDeepseek().getBaseUrl())
                    .modelName(props.getLlm().getDeepseek().getModel())
                    .temperature(temperature)
                    .timeout(timeout)
                    .build();
        } else if ("OLLAMA".equalsIgnoreCase(type)) {
            return OllamaChatModel.builder()
                    .baseUrl(props.getLlm().getOllama().getBaseUrl())
                    .modelName(props.getLlm().getOllama().getModel())
                    .temperature(temperature)
                    .timeout(timeout)
                    .build();
        } else {
            return QwenChatModel.builder()
                    .apiKey(props.getLlm().getAliyun().getApiKey())
                    .modelName(props.getLlm().getAliyun().getModel())
                    .temperature((float) temperature)
                    .build();
        }
    }

    /**
     * 根据数据库配置动态创建聊天模型（用于用户在页面上切换 LLM 配置后即时生效）
     */
    public ChatLanguageModel createChatModel(LlmConfig config, double temperature, Duration timeout) {
        String provider = config.getProvider();
        String modelName = config.getActualModel() != null && !config.getActualModel().isBlank()
                ? config.getActualModel() : config.getModel();
        if ("OLLAMA".equalsIgnoreCase(provider)) {
            return OllamaChatModel.builder()
                    .baseUrl(config.getApiUrl())
                    .modelName(modelName)
                    .temperature(temperature)
                    .timeout(timeout)
                    .build();
        }
        // openai-compatible / openai / zhipu / deepseek / qwen 都用 OpenAI 兼容接口
        // 规范化 baseUrl：去掉末尾的 /chat/completions，避免 LangChain4j 拼成 .../chat/completions/chat/completions
        String baseUrl = config.getApiUrl().replaceAll("/+$", "");
        baseUrl = baseUrl.replaceAll("/chat/completions$", "");
        String chatUrl = baseUrl + "/chat/completions";
        log.info("创建 LLM 模型: name={}, provider={}, model={}, chatUrl={}, apiKey={}...",
                config.getName(), config.getProvider(), modelName, chatUrl,
                config.getApiKey() != null && config.getApiKey().length() > 8
                        ? config.getApiKey().substring(0, 8) : "***");
        return OpenAiChatModel.builder()
                .apiKey(config.getApiKey())
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .timeout(timeout)
                .build();
    }

    /**
     * 创建精确推理模型（工具选择、质量审查等）
     */
    public ChatLanguageModel createPreciseModel() {
        return createChatModel(TEMP_PRECISE);
    }

    /**
     * 创建平衡模型（RAG 总结、一般对话）
     */
    public ChatLanguageModel createBalancedModel() {
        return createChatModel(TEMP_BALANCED);
    }

    /**
     * 创建创意模型（写作、笔记生成）
     */
    public ChatLanguageModel createCreativeModel() {
        return createChatModel(TEMP_CREATIVE);
    }

    /**
     * 创建评估专用模型（temperature=0，确定性输出，超时 120 秒）
     * 用于 RAGAS 质量评估，保证同一输入多次评估结果一致
     */
    public ChatLanguageModel createEvaluationModel() {
        LlmConfig activeConfig = llmConfigService.getActive();
        if (activeConfig != null) {
            return createChatModel(activeConfig, 0.0, Duration.ofSeconds(120));
        }

        String type = props.getLlm().getType();
        if ("ZHIPU".equalsIgnoreCase(type)) {
            return OpenAiChatModel.builder()
                    .apiKey(props.getLlm().getZhipu().getApiKey())
                    .baseUrl(props.getLlm().getZhipu().getBaseUrl())
                    .modelName(props.getLlm().getZhipu().getModel())
                    .temperature(0.0)
                    .timeout(Duration.ofSeconds(120))
                    .build();
        } else if ("DEEPSEEK".equalsIgnoreCase(type)) {
            return OpenAiChatModel.builder()
                    .apiKey(props.getLlm().getDeepseek().getApiKey())
                    .baseUrl(props.getLlm().getDeepseek().getBaseUrl())
                    .modelName(props.getLlm().getDeepseek().getModel())
                    .temperature(0.0)
                    .timeout(Duration.ofSeconds(120))
                    .build();
        } else if ("OLLAMA".equalsIgnoreCase(type)) {
            return OllamaChatModel.builder()
                    .baseUrl(props.getLlm().getOllama().getBaseUrl())
                    .modelName(props.getLlm().getOllama().getModel())
                    .temperature(0.0)
                    .timeout(Duration.ofSeconds(120))
                    .build();
        } else {
            return QwenChatModel.builder()
                    .apiKey(props.getLlm().getAliyun().getApiKey())
                    .modelName(props.getLlm().getAliyun().getModel())
                    .temperature(0.0f)
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
                    .timeout(Duration.ofSeconds(120))
                    .dimensions(1024)  // 指定输出维度为1024
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
