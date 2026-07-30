package com.rag.notebook.rag;

import java.util.HashMap;
import java.util.Map;

public class RagChunk {

    private int chunkIndex;
    private String content;
    private String retrievalText;
    private String contentType = "text";
    private String sectionPath = "";
    private Integer pageStart;
    private Integer pageEnd;
    private Integer parentIndex;
    private String parentId;
    private String previousChunkId;
    private String nextChunkId;

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getRetrievalText() {
        return retrievalText != null && !retrievalText.isBlank() ? retrievalText : content;
    }

    public void setRetrievalText(String retrievalText) {
        this.retrievalText = retrievalText;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getSectionPath() {
        return sectionPath;
    }

    public void setSectionPath(String sectionPath) {
        this.sectionPath = sectionPath;
    }

    public Integer getPageStart() {
        return pageStart;
    }

    public void setPageStart(Integer pageStart) {
        this.pageStart = pageStart;
    }

    public Integer getPageEnd() {
        return pageEnd;
    }

    public void setPageEnd(Integer pageEnd) {
        this.pageEnd = pageEnd;
    }

    public Integer getParentIndex() {
        return parentIndex;
    }

    public void setParentIndex(Integer parentIndex) {
        this.parentIndex = parentIndex;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getPreviousChunkId() {
        return previousChunkId;
    }

    public void setPreviousChunkId(String previousChunkId) {
        this.previousChunkId = previousChunkId;
    }

    public String getNextChunkId() {
        return nextChunkId;
    }

    public void setNextChunkId(String nextChunkId) {
        this.nextChunkId = nextChunkId;
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("content_type", contentType);
        metadata.put("section_path", sectionPath);
        metadata.put("chunk_index", chunkIndex);
        if (pageStart != null) metadata.put("page_start", pageStart);
        if (pageEnd != null) metadata.put("page_end", pageEnd);
        if (parentIndex != null) metadata.put("parent_index", parentIndex);
        if (parentId != null) metadata.put("parent_id", parentId);
        if (previousChunkId != null) metadata.put("previous_chunk_id", previousChunkId);
        if (nextChunkId != null) metadata.put("next_chunk_id", nextChunkId);
        return metadata;
    }

    public static RagChunk fromContent(int index, String content) {
        RagChunk chunk = new RagChunk();
        chunk.setChunkIndex(index);
        chunk.setContent(content);
        chunk.setRetrievalText(content);
        return chunk;
    }
}
