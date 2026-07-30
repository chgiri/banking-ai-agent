# Banking FAQ Assistant

A GenAI-powered banking assistant built with Spring Boot and Spring AI, combining Retrieval-Augmented Generation (RAG) with tool calling for real account actions. It covers three progressively deeper capabilities: grounded Q&A over policy documents (RAG), on-demand document analysis (per-document scoped RAG), and account actions — balance checks, transactions, fund transfers — via tool calling with code-enforced authorization.

Built as a hands-on, from-scratch introduction to GenAI integration in a Java/Spring stack — using a banking domain deliberately, since it surfaces the grounding, citation, and authorization concerns that matter most in that industry rather than glossing over them.

## What It Does

**Step 1 — Banking FAQ Assistant**
- Answers customer questions (FD withdrawal penalties, account fees, loan FAQs) using only the content of ingested policy documents
- Explicitly declines to answer when retrieved context doesn't cover the question, rather than hallucinating a plausible-sounding policy
- Remembers conversation context across turns — and across app restarts, since memory is persisted to Postgres
- Returns the specific document/chunk that backed each answer, alongside the answer itself
- Supports adding new documents to the knowledge base at runtime via an authenticated admin endpoint, with no redeploy required

**Step 2 — Document Q&A**
- Upload a real PDF (loan agreement, terms & conditions, etc.) and ask questions scoped to that specific document
- Retrieval is filtered by document ID at the vector-store level, so a question about one uploaded document can never surface chunks from another
- Duplicate uploads of the same file are detected via content hashing and return the existing document ID instead of re-ingesting

**Step 3 — Banking Actions Assistant**
- Checks account balance and lists recent transactions via tool calling
- Initiates fund transfers through a two-step propose/confirm flow — no transfer executes without an explicit confirmation code
- Account scoping is enforced in code, not left to the model's judgment: the model is never given account ID as a parameter it controls

## Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 4.1.0 |
| AI Integration | Spring AI 2.0.0 |
| LLM (chat) | Google Gemini — `gemini-3.5-flash` |
| Embeddings | Google Gemini — `gemini-embedding-2` (768 dimensions) |
| Vector Store | PostgreSQL + pgvector |
| Conversation Memory | Spring AI JDBC Chat Memory (Postgres-backed) |
| PDF Parsing | Apache Tika (via `spring-ai-tika-document-reader`) |
| Tool Calling | Spring AI `@Tool` / `ChatClient.tools()` |
| Build Tool | Maven |

## Architecture

```
                     ┌─────────────────────┐
   User Question ──► │   ChatController    │
                     └──────────┬──────────┘
                                │
                                ▼
                     ┌─────────────────────┐
                     │   RagChatService    │
                     └──────────┬──────────┘
                                │
              ┌─────────────────┼─────────────────┐
              ▼                 ▼                 ▼
     ┌────────────────┐ ┌───────────────┐ ┌──────────────────┐
     │  VectorStore   │ │  ChatMemory   │ │    ChatClient    │
     │  (pgvector)    │ │  (JDBC/       │ │  (Gemini chat)   │
     │                │ │   Postgres)   │ │                  │
     └────────────────┘ └───────────────┘ └──────────────────┘
              │                                    │
              ▼                                    ▼
     Retrieves 4 closest                  Sends system prompt
     chunks by embedding                  + context + history
     similarity                           + question to Gemini
```

A question is embedded and matched against stored document chunks by semantic similarity (cosine distance via pgvector), the retrieved text is injected into the system prompt as grounding context, prior conversation turns are pulled in automatically via the memory advisor, and the model generates a response constrained to only what was retrieved.

**Document Q&A** follows the same retrieval → context → generation flow, with one addition: every stored chunk is tagged with a `documentId`, and retrieval is filtered to only that ID via `SearchRequest.filterExpression(...)` — so one uploaded document's content can never leak into another's answers.

**Banking Actions Assistant** replaces the retrieval step with a tool-calling loop: instead of grounding in retrieved text, the model is given a small set of `@Tool`-annotated methods (`getBalance`, `listRecentTransactions`, `proposeTransfer`, `confirmTransfer`) bound to a specific account ID at request time. The model decides when to invoke a tool; the account ID itself is never a parameter it controls.

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

On first startup, the app ingests three sample banking documents (FD withdrawal policy, account fee schedule, loan FAQs) into the vector store, and seeds three mock accounts (`ACC1001`, `ACC1002`, `ACC1003`) with sample transactions. Subsequent restarts skip re-ingestion of the FAQ documents automatically.

## API Endpoints

### Chat — Banking FAQ Assistant

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

### Admin — Ingest New FAQ Document

```
POST /api/admin/ingest
Header: X-Admin-Api-Key: <your admin key>
Content-Type: multipart/form-data

file: <a .txt file>
docType: <a short label, e.g. "loan-fee-policy">
```

Adds a new document to the FAQ knowledge base immediately — no restart needed.

### Documents — Upload a PDF

```
POST /api/documents/upload
Header: X-Admin-Api-Key: <your admin key>
Content-Type: multipart/form-data

file: <a .pdf file>
```

**Response:**
```json
{
  "documentId": "a1b2c3d4-...",
  "filename": "sample-loan-agreement.pdf",
  "message": "Document uploaded and ingested. Use this documentId to ask questions about it."
}
```

Re-uploading the exact same file (detected via SHA-256 content hash) returns the existing `documentId` with a `200 OK` instead of creating a duplicate.

### Documents — Check Ingestion Status

```
GET /api/documents/{documentId}
```

**Response:**
```json
{
  "documentId": "a1b2c3d4-...",
  "filename": "sample-loan-agreement.pdf",
  "chunkCount": 6,
  "found": true
}
```

### Documents — Ask a Question Scoped to One Document

```
POST /api/documents/{documentId}/ask
Content-Type: application/json

{
  "question": "What is the interest rate mentioned in this agreement?"
}
```

**Response:**
```json
{
  "answer": "The agreement specifies an interest rate of...",
  "sources": [
    { "source": "sample-loan-agreement.pdf", "docType": "uploaded-pdf", "chunkIndex": 2 }
  ]
}
```

### Banking Actions — Balance, Transactions, Transfers

```
POST /api/banking-actions/chat
Content-Type: application/json

{
  "accountId": "ACC1001",
  "conversationId": "banking-test-1",
  "message": "What is my balance?"
}
```

Other example messages for the same endpoint:
- `"Show my last 3 transactions"`
- `"Transfer 500 to ACC1002"` → returns a confirmation code, does not move funds
- `"Confirm CONF-XXXXXXXX"` → executes the transfer only if that code matches a pending proposal

## Configuration Reference

Key properties in `application.properties`:

```properties
# Chat model
spring.ai.google.genai.chat.model=gemini-3.5-flash

# Embedding model + dimensions (must match pgvector dimensions below)
spring.ai.google.genai.embedding.text.model=gemini-embedding-2
spring.ai.google.genai.embedding.text.dimensions=768
spring.ai.vectorstore.pgvector.dimensions=768

# Both chat and embedding connections need their own api-key property
spring.ai.google.genai.api-key=${GEMINI_API_KEY}
spring.ai.google.genai.embedding.api-key=${GEMINI_API_KEY}

# Persistent chat memory (Postgres is not embedded, so this must be explicit)
spring.ai.chat.memory.repository.jdbc.initialize-schema=always
```

## Design Decisions Worth Noting

- **Grounded answers over fluent guessing.** The system prompt explicitly instructs the model to decline rather than fabricate an answer when retrieved context doesn't cover the question — verified by testing with out-of-scope questions (e.g. asking about car loans when no car loan policy was ingested).
- **Source citations aren't cosmetic.** Every FAQ and document Q&A response returns the specific document and chunk that backed it. For a banking use case, being able to point to the exact policy clause behind an answer is a real requirement, not a nice-to-have.
- **Conversation memory survives restarts.** Chat history is stored in Postgres via Spring AI's JDBC chat memory repository rather than in-process memory, so context isn't lost on redeploy.
- **Per-document retrieval scoping is enforced at the database level.** Document Q&A uses a metadata filter expression on `documentId`, not application-level filtering after the fact — a question about Document A physically cannot retrieve chunks from Document B.
- **Duplicate PDF uploads are detected by content, not filename.** A SHA-256 hash of the file bytes is checked before ingestion, since filenames are an unreliable way to detect "this is the same file."
- **Account scoping is enforced in code, not trusted to the model.** The Banking Actions Assistant binds the current account ID once, at tool-object construction time — it is never exposed as a parameter the model can set. A request can only ever act on the account it was explicitly scoped to, regardless of how a message is phrased.
- **Transfers are a two-step, code-enforced flow, not a prompt-engineered promise.** `proposeTransfer` returns a confirmation code without moving any money; `confirmTransfer` only executes if a matching pending proposal exists. The system prompt asks the model to always confirm first, but the actual safety boundary is the two-tool split in `AccountService` — verified deliberately by testing adversarial phrasings that try to skip straight to a fabricated confirmation code.
- **Observability from day one.** Every FAQ chat interaction logs the retrieved chunks and their sources, making it possible to debug *why* a given answer was produced.

## Known Limitations (Honest Scope)

This is a learning/portfolio project, and some gaps are intentional rather than overlooked:

- **Admin/upload endpoint security is minimal.** A single static API key checked via a servlet filter — sufficient to prevent casual/accidental access, but lacking rate limiting, key rotation, or audit logging. A production system would use Spring Security with role-based access control.
- **JDBC chat memory doesn't persist tool-call messages.** This affects the Banking Actions Assistant specifically: `JdbcChatMemoryRepository` only stores plain user/assistant text, silently dropping the tool request/response messages generated during balance checks and transfers. Plain conversational turns in that same service still persist correctly across restarts — only the tool-invocation parts don't. A newer community project, Spring AI Session, addresses this with full tool-message support, but was deliberately not adopted here to avoid pulling in a pre-1.0 dependency with a different integration model partway through the build.
- **Pending transfer confirmation codes aren't scoped to the proposing account.** `AccountService` stores pending transfers in a single map keyed only by confirmation code, not by account ID. This means a code generated for one account's transfer could theoretically be confirmed from a different account's session. Identified during adversarial testing of the propose/confirm flow; a production fix would bind the confirmation code to the originating account and reject mismatches.
- **No re-ranking or hybrid search.** Retrieval is pure vector similarity; a production system might combine this with keyword search or a re-ranking step for higher precision.
- **Single-tenant.** No concept of per-customer document scoping or access control on retrieval.
- **No real authentication.** The Banking Actions Assistant takes `accountId` directly in the request body as a stand-in for what a real system would derive from a session or JWT after login.

## Learning Path (All Three Steps Complete)

This project was built as a progressive GenAI learning path:

1. **Banking FAQ Assistant** — RAG fundamentals: embeddings, vector search, grounding, conversation memory
2. **Document Q&A System** — real-world PDF parsing (Apache Tika), chunking strategy on longer documents, per-document metadata scoping via filter expressions
3. **Banking Actions Assistant** — tool calling with code-enforced account scoping and a two-step propose/confirm pattern for irreversible actions like fund transfers

## Possible Next Directions

- A fraud/anomaly detection assistant or loan pre-qualification flow, reusing the same RAG + tool-calling patterns in a new banking domain problem
- Multi-agent orchestration — a triage agent routing between specialized sub-agents, a step up in complexity from single-agent tool calling
- Automated evaluation/regression testing for RAG answer quality — a genuine differentiator most portfolio projects skip entirely
- Fixing the pending-transfer account-scoping gap identified above

## A Note on Model Churn

Built during a period of unusually rapid change in the Gemini API — over the course of building this, `text-embedding-004` and `gemini-embedding-001` were both retired, and `gemini-2.5-flash` was restricted for new API keys, requiring a live migration to `gemini-embedding-2` and `gemini-3.5-flash` mid-build. Left as-is rather than glossed over, since managing model lifecycle risk is itself a real production concern.
