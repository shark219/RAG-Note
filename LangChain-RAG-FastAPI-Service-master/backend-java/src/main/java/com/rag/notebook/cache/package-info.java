/**
 * 统一缓存防护方案 —— 用一个模式同时解决雪崩、穿透、击穿。
 *
 * <h2>背景</h2>
 * RAG NoteBook 的核心读路径（笔记详情、复习列表、知识文档检索）都直接查 MySQL。
 * 随着用户量和数据量增长，三个经典缓存问题会逐步暴露：
 *
 * <h2>问题场景 & 解决方案</h2>
 *
 * <h3>1. 缓存击穿（Hot Key Invalid）</h3>
 * <b>场景：</b>多人协作的共享笔记（如团队 Wiki），缓存过期瞬间 N 个并发请求同时打到
 * {@code noteRepository.findById()}，MySQL 瞬时压力激增。
 * <b>方案：</b>{@link CacheProtectionService#rebuildWithLock} —— Redis SETNX 互斥锁，
 * 同一时刻只有一个线程执行 DB 查询重建缓存，其余线程自旋 50ms×40 次（共 2s）等待缓存就绪。
 * 锁带 10s 超时 + finally 释放，防止死锁。
 *
 * <h3>2. 缓存穿透（Null Key）</h3>
 * <b>场景：</b>攻击者遍历 UUID 调用 {@code GET /api/notes/{randomId}}，
 * 每次查询的 noteId 都不存在，缓存永远不命中，全部请求穿透到 MySQL。
 * <b>方案：</b>{@link CacheProtectionService#NULL_MARKER} —— DB 返回 null 时，
 * 将特殊标记 {@code __NULL__} 写入 Redis，TTL=60s。后续相同查询命中缓存直接返回 null，
 * 不再查 DB。
 *
 * <h3>3. 缓存雪崩（Mass Expiry）</h3>
 * <b>场景：</b>服务重启后 {@code note:list:*} 前缀的所有列表缓存同时过期，
 * 首页加载请求集中涌入 MySQL，造成瞬时高峰。
 * <b>方案：</b>{@link CacheProtectionService#applyJitter} —— 每个 key 的 TTL
 * 叠加 ±25% 随机抖动（如 1800s → 1350~2250s），过期时间自然分散，不会集中失效。
 *
 * <h2>接入方式</h2>
 * <pre>
 *   Note note = cacheProtection.getWithProtection(
 *       "note:" + noteId, Note.class, 1800L,
 *       () -> noteRepository.findById(noteId).orElse(null)
 *   );
 * </pre>
 * 写操作时调用 {@code cacheProtection.evict(key)} 保证缓存一致性。
 *
 * <h2>适用业务</h2>
 * <ul>
 *   <li>笔记详情 {@code note:{id}} —— TTL 30min，高频读取</li>
 *   <li>笔记列表 {@code note:list:{userId}} —— TTL 5min</li>
 *   <li>今日复习 {@code review:today:{userId}} —— TTL 10min</li>
 *   <li>知识文档 {@code doc:{id}} —— TTL 1h</li>
 *   <li>ChromaDB collection ID —— 已在 VectorStoreService 中实现</li>
 * </ul>
 */
package com.rag.notebook.cache;
