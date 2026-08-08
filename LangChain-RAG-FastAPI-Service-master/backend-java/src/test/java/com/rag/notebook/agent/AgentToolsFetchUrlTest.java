package com.rag.notebook.agent;

import com.rag.notebook.note.service.NoteService;
import com.rag.notebook.rag.RagService;
import com.rag.notebook.review.service.ReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DisplayName("fetchUrl 站点样本测试")
class AgentToolsFetchUrlTest extends TestBase {

    private AgentTools agentTools;

    @BeforeEach
    void setUp() {
        RagService ragService = mock(RagService.class);
        NoteService noteService = mock(NoteService.class);
        ReviewService reviewService = mock(ReviewService.class);
        ModelFactory modelFactory = mockModelFactory(mockLlm("ok"));
        agentTools = new AgentTools(ragService, noteService, reviewService, modelFactory);
    }

    @Test
    @DisplayName("知乎正文页应提取正文并通过质量检查")
    void zhihuArticle_shouldExtractMainContent() {
        String html = """
                <html><body>
                <div class=\"QuestionHeader\">标题区域</div>
                <div class=\"RichContent-inner\">
                  <p>JVM 的垃圾回收器负责识别不再被引用的对象，并回收堆内存，避免程序长期运行后内存持续上涨。</p>
                  <p>可达性分析会从 GC Roots 出发遍历引用链，无法到达的对象会被标记为可回收对象。这一步决定了对象是否进入后续回收流程。</p>
                  <p>分代回收把堆划分为新生代和老年代，因为大部分对象朝生夕灭。新生代常用复制算法，老年代更关注压缩整理和停顿控制。</p>
                  <p>实际调优时需要同时关注吞吐、停顿时间和内存占用，不能只看单一指标，否则很容易把一个问题转移成另一个问题。</p>
                </div>
                <div class=\"Recommendations-Main\">相关推荐 点赞 收藏 评论</div>
                </body></html>
                """;

        AgentTools.PageExtraction extraction = agentTools.extractPageContent(URI.create("https://www.zhihu.com/question/1/answer/2"), html);

        assertTrue(extraction.qualityPass());
        assertEquals("知乎", extraction.strategy());
        assertTrue(extraction.content().contains("可达性分析"));
        assertTrue(extraction.contentLength() >= 200);
        assertTrue(extraction.hasStructure());
    }

    @Test
    @DisplayName("CSDN 推荐页应判定为非正文")
    void csdnRecommendationPage_shouldFailQualityCheck() {
        String html = """
                <html><body>
                <div class=\"blog-content-box\">
                  <div>相关推荐</div>
                  <div>热门推荐</div>
                  <div>点赞 收藏 评论 关注 更多内容</div>
                  <div>上一篇 下一篇 推荐阅读</div>
                </div>
                </body></html>
                """;

        AgentTools.PageExtraction extraction = agentTools.extractPageContent(URI.create("https://blog.csdn.net/test/article/details/123"), html);

        assertFalse(extraction.qualityPass());
        assertEquals("CSDN", extraction.strategy());
        assertTrue(extraction.reason().contains("导航") || extraction.reason().contains("过短"));
    }

    @Test
    @DisplayName("知乎登录拦截页应识别为站点拦截")
    void zhihuBlockedPage_shouldBeDetected() {
        String html = """
                <html><body>
                <article>
                  <div class=\"RichContent-inner\">
                    <p>请先登录</p>
                    <p>扫码登录后继续访问</p>
                    <p>登录后可阅读全文、点赞、收藏、评论、关注</p>
                    <p>展开阅读全文</p>
                  </div>
                </article>
                </body></html>
                """;

        AgentTools.PageExtraction extraction = agentTools.extractPageContent(URI.create("https://www.zhihu.com/question/99"), html);

        assertFalse(extraction.qualityPass());
        assertTrue(extraction.reason().contains("拦截") || extraction.reason().contains("登录"));
    }

    @Test
    @DisplayName("搜索结果页即使文字很多也应判定为非正文")
    void searchResultPage_shouldFailQualityCheck() {
        String html = """
                <html><body>
                <div class=\"result\">在新选项卡中打开链接</div>
                <div class=\"result\">时间不限</div>
                <div class=\"result\">搜索结果 高级搜索 图片 视频 微信 百科 意见反馈 帮助</div>
                <div class=\"result\">bugyinyin 151176278 - 搜索结果</div>
                <div class=\"result\">搜索结果 1：CSDN 博客文章摘要，点击进入。</div>
                <div class=\"result\">搜索结果 2：相关页面推荐，包含点赞评论收藏关注。</div>
                <div class=\"result\">搜索结果 3：更多内容 更多内容 更多内容。</div>
                </body></html>
                """;

        AgentTools.PageExtraction extraction = agentTools.extractPageContent(URI.create("https://www.bing.com/search?q=test"), html);

        assertFalse(extraction.qualityPass());
        assertTrue(extraction.reason().contains("导航") || extraction.reason().contains("结构") || extraction.reason().contains("过短"));
    }

    @Test
    @DisplayName("通用正文覆盖率不足时应失败")
    void genericPage_partialContentShouldFailCoverageCheck() {
        String body = "导航 导航 导航 导航 导航 导航 导航 导航 导航 导航 ".repeat(80)
                + "<article><p>这是一小段摘要。</p><p>内容还没展开。</p></article>";
        String html = "<html><body>" + body + "</body></html>";

        AgentTools.PageExtraction extraction = agentTools.extractPageContent(URI.create("https://example.com/post"), html);

        assertFalse(extraction.qualityPass());
        assertTrue(extraction.reason().contains("覆盖率") || extraction.reason().contains("过短") || extraction.reason().contains("结构"));
    }
}
