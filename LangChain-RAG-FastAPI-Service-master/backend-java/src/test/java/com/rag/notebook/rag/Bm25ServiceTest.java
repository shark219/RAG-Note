package com.rag.notebook.rag;

import org.junit.jupiter.api.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Bm25ServiceTest {

    private Bm25Service service;

    @BeforeEach
    void setUp() {
        service = new Bm25Service();
    }

    @AfterEach
    void tearDown() {
        service.clearUserIndex("test-user");
    }

    @Test
    void searchWithStatusReturnsEmptyOnSuccessWithoutMatch() {
        service.addDocument("test-user", "doc-1", "hello world", Map.of("source", "note"));
        Bm25Service.SearchOutcome outcome = service.searchWithStatus("test-user", "xyzabc_nomatch", 10);
        assertTrue(outcome.success());
        assertNotNull(outcome.results());
        assertTrue(outcome.results().isEmpty());
    }

    @Test
    void searchWithStatusReturnsResultsOnSuccessWithMatch() {
        service.addDocument("test-user", "doc-2", "Spring事务传播机制", Map.of("source", "note", "title", "事务"));
        Bm25Service.SearchOutcome outcome = service.searchWithStatus("test-user", "Spring事务", 10);
        assertTrue(outcome.success());
        assertFalse(outcome.results().isEmpty());
        assertTrue(outcome.results().get(0).containsKey("bm25_score"));
    }

    @Test
    void searchWithStatusReturnsFailureWhenIndexDirectoryUnavailable() {
        Bm25Service.SearchOutcome outcome = service.searchWithStatus("nonexistent-user", "query", 10);
        assertFalse(outcome.success());
        assertNotNull(outcome.error());
        assertTrue(outcome.results().isEmpty());
    }

    @Test
    void searchWithStatusReturnsFailureWhenUserHasNoData() {
        Bm25Service.SearchOutcome outcome = service.searchWithStatus("no-index-user", "any query", 10);
        assertFalse(outcome.success(), "没有 BM25 索引目录的用户应返回 failure");
        assertNotNull(outcome.error());
    }
}
