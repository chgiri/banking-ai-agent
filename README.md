# Banking FAQ Assistant

A Retrieval-Augmented Generation (RAG) chatbot built with Spring Boot and Spring AI, designed to answer banking customer support questions grounded in real policy documents rather than model guesswork. Built as a hands-on introduction to GenAI integration patterns in a Java/Spring stack, using a banking domain to surface the compliance and grounding concerns that matter in that industry.

## What It Does

- Answers customer questions (FD withdrawal penalties, account fees, loan FAQs) using only the content of ingested policy documents
- Explicitly declines to answer when the retrieved context doesn't cover the question, rather than hallucinating a plausible-sounding policy
- Remembers conversation context across turns — and across app restarts, since memory is persisted to Postgres
- Returns the specific document/chunk that backed each answer, alongside the answer itself
- Supports adding new documents to the knowledge base at runtime via an authenticated admin endpoint, with no redeploy required

## Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 4.1.0 |
| AI Integration | Spring AI 2.0.0 |
| LLM (chat) | Google Gemini — `gemini-3.5-flash` |
| Embeddings | Google Gemini — `gemini-embedding-2` (768 dimensions) |
| Vector Store | PostgreSQL + pgvector |
| Conversation Memory | Spring AI JDBC Chat Memory (Postgres-backed) |
| Build Tool | Maven |

## Architecture

```
                     ┌─────────────────────┐
   User Question ──► │   ChatController     │
                     └──────────┬──────────┘
                                │
                                ▼
                     ┌─────────────────────┐
                     │   RagChatService     │
                     └──────────┬──────────┘
                                │
              ┌─────────────────┼─────────────────┐
              ▼                 ▼                 ▼
     ┌────────────────┐ ┌───────────────┐ ┌──────────────────┐
     │  VectorStore    │ │  ChatMemory   │ │    ChatClient     │
     │  (pgvector)     │ │  (JDBC/       │ │  (Gemini chat)    │
     │                 │ │   Postgres)   │ │                    │
     └────────────────┘ └───────────────┘ └──────────────────┘
              │                                    │
              ▼                                    ▼
     Retrieves 4 closest                  Sends system prompt
     chunks by embedding                  + context + history
     similarity                           + question to Gemini
```

A question is embedded and matched against stored document chunks by semantic similarity (cosine distance via pgvector), the retrieved text is injected into the system prompt as grounding context, prior conversation turns are pulled in automatically via the memory advisor, and the model generates a response constrained to only what was retrieved.

## Getting Started

### Prerequisites

- Java 21
- Maven
- Docker (for Postgres/pgvector)
- A Google Gemini API key ([Google AI Studio](https://aistudio.google.com/app/apikey))

### 1. Start Postgres

```bash
docker-compose up -d
```

### 2. Configure environment variables

Create a `.env` file in the project root:

```
GEMINI_API_KEY=your-gemini-api-key
DB_USERNAME=postgres
DB_PASSWORD=postgres
ADMIN_API_KEY=choose-a-long-random-string
```

### 3. Run the application

```bash
mvn clean compile spring-boot:run
```

On first startup, the app ingests three sample banking documents (FD withdrawal policy, account fee schedule, loan FAQs) into the vector store. Subsequent restarts skip re-ingestion automatically.

## API Endpoints

### Chat

```
POST /api/chat
Content-Type: application/json

{
  "conversationId": "session-1",
  "message": "What is the penalty for early FD withdrawal?"
}
```

**Response:**
```json
{
  "answer": "A 1% penalty is deducted from the applicable interest rate...",
  "sources": [
    { "source": "fd-policy.txt", "docType": "fd-policy", "chunkIndex": 0 }
  ]
}
```

### Admin — Ingest New Document

```
POST /api/admin/ingest
Header: X-Admin-Api-Key: <your admin key>
Content-Type: multipart/form-data

file: <a .txt file>
docType: <a short label, e.g. "loan-fee-policy">
```

Adds a new document to the knowledge base immediately — no restart needed.

## Configuration Reference

Key properties in `application.properties`:

```properties
# Chat model
spring.ai.google.genai.chat.model=gemini-3.5-flash

# Embedding model + dimensions (must match pgvector dimensions below)
spring.ai.google.genai.embedding.text.model=gemini-embedding-2
spring.ai.google.genai.embedding.text.dimensions=768
spring.ai.vectorstore.pgvector.dimensions=768

# Persistent chat memory (Postgres is not embedded, so this must be explicit)
spring.ai.chat.memory.repository.jdbc.initialize-schema=always
```

## Design Decisions Worth Noting

- **Grounded answers over fluent guessing.** The system prompt explicitly instructs the model to decline rather than fabricate an answer when retrieved context doesn't cover the question — verified by testing with out-of-scope questions (e.g. asking about car loans when no car loan policy was ingested).
- **Source citations aren't cosmetic.** Every response returns the specific document and chunk that backed it. For a banking use case, being able to point to the exact policy clause behind an answer is a real requirement, not a nice-to-have.
- **Conversation memory survives restarts.** Chat history is stored in Postgres via Spring AI's JDBC chat memory repository rather than in-process memory, so context isn't lost on redeploy.
- **Observability from day one.** Every chat interaction logs the retrieved chunks and their sources, making it possible to debug *why* a given answer was produced.

## Known Limitations (Honest Scope)

This is a learning/portfolio project, and some gaps are intentional rather than overlooked:

- **Admin endpoint security is minimal.** A single static API key checked via a servlet filter — sufficient to prevent casual/accidental access, but lacking rate limiting, key rotation, or audit logging. A production system would use Spring Security with role-based access control.
- **JDBC chat memory doesn't persist tool-call messages.** Not an issue today since this project doesn't yet use tool calling, but relevant if extended to an agent-style assistant that calls backend services (e.g. checking a real account balance).
- **No re-ranking or hybrid search.** Retrieval is pure vector similarity; a production system might combine this with keyword search or a re-ranking step for higher precision.
- **Single-tenant.** No concept of per-customer document scoping or access control on retrieval.

## What's Next

This project is the first step in a broader GenAI learning path:

1. **Banking FAQ Assistant** *(this project)* — RAG fundamentals: embeddings, vector search, grounding, conversation memory
2. **Document Q&A System** — real-world PDF parsing, chunking strategy on longer documents, per-document metadata scoping
3. **Agent with Tool Calling** — letting the assistant take real actions (check balance, list transactions) via Spring service calls, with tiered authorization between read and write operations

## A Note on Model Churn

Built during a period of unusually rapid change in the Gemini API — over the course of building this, `text-embedding-004` and `gemini-embedding-001` were both retired, and `gemini-2.5-flash` was restricted for new API keys, requiring a live migration to `gemini-embedding-2` and `gemini-3.5-flash` mid-build. Left as-is rather than glossed over, since managing model lifecycle risk is itself a real production concern.
