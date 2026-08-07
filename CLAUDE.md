# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project shape
This repository contains a Java backend and a Vue 3 frontend under `LangChain-RAG-FastAPI-Service-master/`.

- `backend-java/` is a Spring Boot 3.4 + Java 17 service.
- `front-v2/` is the current Vue 3 + Vite + TypeScript UI.
- `front/` exists alongside `front-v2/`, but the newer UI lives in `front-v2/`.
- The backend is organized by product area: `chat`, `note`, `knowledge`, `review`, `evaluation`, `user`, `auth`, `rag`, `agent`, `skill`, `system`.
- Shared web concerns live in `common`, `config`, `health`, and `cache`.
- The frontend uses Vue Router, Pinia, Arco Design, and an Axios wrapper at `src/api/index.ts`.

## Backend architecture
The backend is a modular monolith with one Spring Boot application entry point.

- `chat` handles conversational RAG flows, session storage, file attachments, prompts, and message history.
- `note` handles note CRUD, editor support, and note-related retrieval.
- `knowledge` handles document ingestion, chunk visibility, and vector-store-backed knowledge browsing.
- `rag` contains retrieval primitives such as BM25, vector store access, reranking, hybrid retrieval, and cleanup jobs.
- `agent` contains the newer agent pipeline, planning, memory, tool execution, and orchestration services.
- `skill` manages imported skills, package loading, Git import, and Skill Center manifests.
- `evaluation` contains regression/evaluation flows and report generation.
- `auth` and `user` provide JWT-based login and user management.
- `common.result.ApiResponse` and `common.exception.GlobalExceptionHandler` define the API response shape and error mapping.

## Frontend architecture
The UI is a route-driven SPA.

- `src/router/index.ts` defines the main app shell and system pages.
- `src/views/` holds feature pages such as notes, chat, knowledge, review, settings, and system administration screens.
- `src/views/system/SkillsManage.vue` is the skill administration page, including upload, Git import, and Skill Center install.
- `src/store/` holds Pinia stores for app and user state.
- `src/api/index.ts` centralizes backend calls and the shared Axios error handling.
- Vite proxies `/api` to `http://localhost:8000` during development.

## Common commands
Run commands from `LangChain-RAG-FastAPI-Service-master/` unless noted otherwise.

### Backend
- Build: `cd backend-java && mvn clean package`
- Test all: `cd backend-java && mvn test`
- Test one class: `cd backend-java && mvn -Dtest=Bm25ServiceTest test`
- Test one method: `cd backend-java && mvn -Dtest=Bm25ServiceTest#testName test`
- Run app: `cd backend-java && mvn spring-boot:run`
- Run with wrapper on Windows: `cd backend-java && .\mvnw.cmd test`

### Frontend
- Install deps: `cd front-v2 && npm install`
- Dev server: `cd front-v2 && npm run dev`
- Type-check and build: `cd front-v2 && npm run build`
- Preview build: `cd front-v2 && npm run preview`

## Configuration notes
- Backend config lives in `backend-java/src/main/resources/application.yml`.
- The backend expects MySQL, Redis, and external LLM endpoints to be available when those features are exercised.
- Frontend API calls assume the backend is reachable on the dev proxy target in `vite.config.ts`.

## Working conventions
- Prefer the existing package and feature boundaries when making changes.
- Keep frontend error handling aligned with the API response envelope returned by `ApiResponse`.
- For Skill Center work, the backend manifest endpoint is `/system/skills/store/manifest`, and the frontend Skill management page consumes it through `skillApi.storeManifest()`.
