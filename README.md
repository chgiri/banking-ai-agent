# Banking AI Agent

A GenAI-powered banking assistant built with Spring Boot and Spring AI, combining Retrieval-Augmented Generation (RAG) with tool calling for real account actions, topped with a lightweight multi-agent-style orchestration layer. It covers four progressively deeper capabilities: grounded Q&A over policy documents (RAG), on-demand document analysis (per-document scoped RAG), account actions — balance checks, transactions, fund transfers — via tool calling with code-enforced authorization, and intent-based routing across all three.

Built as a hands-on, from-scratch introduction to GenAI integration in a Java/Spring stack — using a banking domain deliberately, since it surfaces the grounding, citation, and authorization concerns that matter most in that industry rather than glossing over them.

## What It Does

**Step 1 — FAQ Assistant (RAG)**
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

**Step 4 — Multi-Agent Orchestration (Router)**
- A single entry point (`/api/assistant/chat`) classifies an incoming message's intent — FAQ, DOCUMENT, or BANKING — and routes it to the matching sub-agent from Steps 1–3
- Falls back gracefully when required context is missing (e.g. a banking question with no `accountId`), asking for it rather than guessing or erroring
- Defaults to the least-privileged path (FAQ) if classification comes back malformed, rather than defaulting to banking actions

**Step 5 — True Agentic Orchestration**
- A second entry point (`/api/agent/chat`) exposes all three sub-agents as tools the orchestrator's own LLM can choose from and combine — not a one-shot classification into exactly one path
- Can invoke multiple tools within a single response to one message (e.g. a policy question and a balance check together) — something the Step 4 router structurally cannot do
- The account-scoping security boundary from Step 3 is preserved unchanged: `accountId` is still bound at tool-object construction time, never exposed as something the model or a chat message can set
- Kept alongside the Step 4 router rather than replacing it, so both architectures are directly comparable in the same codebase

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
| Orchestration (Step 4) | Single-shot LLM intent classification + Java `switch` dispatch |
| Orchestration (Step 5) | LLM tool calling over the three sub-agents themselves, treated as tools |
| Caching | Spring Cache + Caffeine (in-memory) |
| Build Tool | Maven |

## Architecture

```
                     ┌─────────────────────┐
   User Question ──► │ AssistantController │  (Step 4 entry point)
                     └──────────┬──────────┘
                                │
                                ▼
                     ┌─────────────────────┐
                     │ OrchestratorService │
                     │ (intent classifier) │
                     └──────────┬──────────┘
                                │
              ┌─────────────────┼─────────────────┐
              ▼                 ▼                 ▼
     ┌────────────────┐ ┌───────────────┐ ┌──────────────────┐
     │ RagChatService │ │DocumentQnaSvc │ │BankingActionsSvc │
     │  (FAQ, Step 1) │ │  (Step 2)     │ │   (Step 3)       │
     └───────┬────────┘ └───────┬───────┘ └────────┬─────────┘
             │                  │                  │
             ▼                  ▼                  ▼
     ┌────────────────┐ ┌───────────────┐ ┌──────────────────┐
     │  VectorStore   │ │  ChatMemory   │ │    ChatClient    │
     │  (pgvector)    │ │  (JDBC/       │ │  (Gemini chat)   │
     │                │ │   Postgres)   │ │                  │
     └────────────────┘ └───────────────┘ └──────────────────┘
```

A question is embedded and matched against stored document chunks by semantic similarity (cosine distance via pgvector), the retrieved text is injected into the system prompt as grounding context, prior conversation turns are pulled in automatically via the memory advisor, and the model generates a response constrained to only what was retrieved.

**Document Q&A** follows the same retrieval → context → generation flow, with one addition: every stored chunk is tagged with a `documentId`, and retrieval is filtered to only that ID via `SearchRequest.filterExpression(...)` — so one uploaded document's content can never leak into another's answers.

**Banking Actions Assistant** replaces the retrieval step with a tool-calling loop: instead of grounding in retrieved text, the model is given a small set of `@Tool`-annotated methods (`getBalance`, `listRecentTransactions`, `proposeTransfer`, `confirmTransfer`) bound to a specific account ID at request time. The model decides when to invoke a tool; the account ID itself is never a parameter it controls.

**Multi-Agent Orchestration (Step 4, router)** sits in front of all three: a single, tool-free, memory-free LLM call classifies the incoming message into `FAQ`, `DOCUMENT`, or `BANKING`, and `OrchestratorService` dispatches to the corresponding existing service. It is a **router**, not agents that reason or collaborate with each other — worth naming precisely rather than overstating (see Design Decisions below).

**True Agentic Orchestration (Step 5)** inverts this: instead of the orchestrator's own code deciding which single service to call, the three sub-agents are exposed as `@Tool`-annotated methods on `OrchestratorTools`, and the orchestrator's `ChatClient` is given all three at once via `.tools(tools)`. The model itself reasons about which tool(s) the message needs and can call more than one within a single response — genuine multi-step, multi-tool decision-making, not a fixed one-of-three dispatch.

```
                     ┌─────────────────────┐
   User Question ──► │   AgentController   │  (Step 5 entry point)
                     └──────────┬──────────┘
                                │
                                ▼
                  ┌─────────────────────────────┐
                  │ AgenticOrchestratorService  │
                  │  ChatClient.tools(...)      │
                  └──────────────┬──────────────┘
                                 │  model decides which tool(s) to call,
                                 │  possibly more than one per request
              ┌──────────────────┼───────────────────┐
              ▼                  ▼                   ▼
     answerBankingFaq   answerDocumentQuestion  performBankingAction
     (→ RagChatService) (→ DocumentQnaService)  (→ BankingActionsService,
                                                    itself another
                                                    tool-using agent)
```

Note the last branch: `performBankingAction` calls `BankingActionsService`, which internally does its *own* tool calling against `AccountTools` (balance, transactions, transfer). This is hierarchical agent composition — one tool-using agent invoking another tool-using agent — not just a flat dispatch.

### Router vs. True Agent — the Concrete Difference

Both endpoints can answer any single-topic question equally well. The difference shows up on a genuinely multi-part message:

> *"What is the penalty for early FD withdrawal, and also what is my current balance?"*

- **`/api/assistant/chat` (router):** classifies into exactly one of `FAQ`/`DOCUMENT`/`BANKING` and can only ever answer one half of the question
- **`/api/agent/chat` (true agent):** calls both `answerBankingFaq` and `performBankingAction` within the same response — verified directly by watching both tools' log lines print for a single request

This was the actual verification test used during development, not just a theoretical distinction.

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

### Chat — FAQ Assistant

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

### Assistant — Orchestrated Entry Point (Step 4)

```
POST /api/assistant/chat
Content-Type: application/json

{
  "conversationId": "orch-1",
  "accountId": "ACC1001",
  "documentId": "a1b2c3d4-...",
  "message": "What is my balance?"
}
```

`accountId` and `documentId` are optional — include whichever is relevant to the message, or omit both for a plain FAQ question. `conversationId` is also optional; if omitted, a fresh one is generated per request (meaning no conversation memory carries over for that call — see Known Limitations).

**Response:**
```json
{
  "answer": "Your current balance is 45230.50",
  "routedTo": "BANKING",
  "sources": []
}
```

`routedTo` reports which sub-agent handled the request (`FAQ`, `DOCUMENT`, or `BANKING`) — useful for debugging misclassifications. If a `BANKING` or `DOCUMENT` intent is detected but the required `accountId`/`documentId` wasn't provided, the response asks for it instead of guessing or erroring.

### Agent — True Agentic Orchestration (Step 5)

```
POST /api/agent/chat
Content-Type: application/json

{
  "conversationId": "agent-1",
  "accountId": "ACC1001",
  "message": "What is the penalty for early FD withdrawal, and also what is my current balance?"
}
```

**Response:**
```json
{
  "answer": "The penalty for early FD withdrawal is 1% deducted from the applicable interest rate. Your current balance is 45230.50."
}
```

Unlike `/api/assistant/chat`, this endpoint has no `routedTo` field — because there isn't a single route. Instead, check the application console for `[Agent] ...` log lines, which print once per tool actually invoked, showing exactly which tool(s) the model chose to call for a given message. `accountId` is optional and behaves identically to the router: omitted entirely means banking-related requests get a plain-language explanation that no account context is available, never a guess.

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
- **The orchestrator is a router, not "true" multi-agent collaboration — described that way deliberately.** A single upfront LLM call classifies intent, then a Java `switch` dispatches to one of three fixed handlers. There's no back-and-forth between agents, no agent invoking another agent, no shared planning. This is a legitimate and common real-world pattern (often called a triage or supervisor pattern), but it's a materially different thing from agents that reason and collaborate — worth being precise about rather than overselling.
- **Misclassification defaults to the least-privileged path.** If the intent classifier returns something unrecognized or malformed, `OrchestratorService` defaults to `FAQ` rather than `BANKING` — a misrouted FAQ question just gives an unhelpful answer, whereas a misrouted banking question would be a materially worse failure mode.
- **A missing `conversationId` gets a freshly generated one, not a rejected request.** Trades away conversation memory for that call in exchange for not crashing — a deliberate usability choice, not an oversight (see Known Limitations for the trade-off this implies).
- **The true agentic orchestrator kept the router intact rather than replacing it.** `/api/assistant/chat` and `/api/agent/chat` coexist deliberately, so the two architectures — one-shot classification vs. genuine multi-tool reasoning — are directly comparable in the same codebase rather than the earlier approach being discarded.
- **`accountId` scoping is unchanged in the agentic version — deliberately.** `OrchestratorTools` binds `accountId` at construction time exactly like `AccountTools` in Step 3; it is never a tool parameter the model or a chat message can set. Increasing the orchestrator's autonomy elsewhere did not relax this boundary — verified by testing a message that explicitly asks the agent to check a *different* account than the one it was scoped to, which correctly failed to override the binding.
- **`documentId`, unlike `accountId`, is accepted as a normal tool parameter from conversation.** This isn't an inconsistency — `documentId` was never a security boundary in this project (no per-user document ownership exists), so letting the model gather it naturally through conversation (asking the user for it if missing) is a genuine improvement over the router's hardcoded fallback message, with no security trade-off.
- **Every agentic tool call is explicitly logged.** Unlike the router, where the code itself determines which path is taken, the agentic version's actual behavior is decided by the model at runtime — without per-tool `System.out.println` logging, there would be no way to verify which tool(s) actually ran versus what the final response text merely claims happened.
- **Observability from day one.** Every FAQ chat interaction logs the retrieved chunks and their sources, making it possible to debug *why* a given answer was produced.
- **Caching is deliberately scoped to only `DocumentQnaService.ask()`, not applied broadly.** Of the project's chat-facing services, this is the only one with no conversation-memory dependency, no side effects, and immutable inputs (uploaded documents don't change post-upload) — the cleanest possible candidate. `RagChatService` was excluded because its answers depend on conversation history, not just the question text; `BankingActionsService` was excluded because balances/transactions genuinely change between requests and transfers have side effects; the orchestrators were excluded because a single request can mix cacheable and non-cacheable sub-calls, making a single cached response at that level unsafe.
- **The cache key is normalized separately from the actual LLM/retrieval input.** A custom `@Cacheable` key expression lowercases, trims, and strips trailing punctuation from the question *only* for cache-key purposes, via a small static helper (`DocumentQnaService.normalizeForCacheKey`) — the raw, unmodified question is still what's sent to vector search and the model. This catches the common case of the same question being re-asked with different punctuation/casing without altering what the system actually retrieves or generates.
- **Cache entries carry both a size cap and a TTL, despite documents being logically immutable.** `maximumSize(500)` and a 1-hour `expireAfterWrite` are a deliberate safety net, not a contradiction of the "documents don't change" reasoning above — an unbounded, never-expiring cache is still a latent memory-growth risk in a long-running process, and the cost of an unnecessary cache miss after an hour is just one extra LLM call.

## Known Limitations (Honest Scope)

This is a learning/portfolio project, and some gaps are intentional rather than overlooked:

- **Admin/upload endpoint security is minimal.** A single static API key checked via a servlet filter — sufficient to prevent casual/accidental access, but lacking rate limiting, key rotation, or audit logging. A production system would use Spring Security with role-based access control.
- **JDBC chat memory doesn't persist tool-call messages.** This affects the Banking Actions Assistant specifically: `JdbcChatMemoryRepository` only stores plain user/assistant text, silently dropping the tool request/response messages generated during balance checks and transfers. Plain conversational turns in that same service still persist correctly across restarts — only the tool-invocation parts don't. A newer community project, Spring AI Session, addresses this with full tool-message support, but was deliberately not adopted here to avoid pulling in a pre-1.0 dependency with a different integration model partway through the build.
- **Pending transfer confirmation codes aren't scoped to the proposing account.** `AccountService` stores pending transfers in a single map keyed only by confirmation code, not by account ID. This means a code generated for one account's transfer could theoretically be confirmed from a different account's session. Identified during adversarial testing of the propose/confirm flow; a production fix would bind the confirmation code to the originating account and reject mismatches.
- **The Step 4 router is a router, not collaborating agents.** See Design Decisions above — worth not overstating in interviews or documentation as more sophisticated multi-agent architecture than it is. Step 5 addresses this gap; the two are documented separately rather than the router being quietly upgraded and its original, simpler nature obscured.
- **The agentic orchestrator inherits the router's `conversationId`-forfeiting fallback.** Same trade-off, same section above — a missing `conversationId` still means no memory for that call, not a rejected request.
- **Multiple tool calls in one request mean multiple LLM round-trips, not one.** A message that triggers two tools costs roughly two to three times the latency/token cost of a single-tool router request (each tool call and its result adds another turn to the underlying conversation with the model before a final answer is produced). Not a problem for a portfolio demo, but a real production cost consideration worth naming rather than ignoring.
- **Tool call order and count aren't guaranteed or bounded.** The model decides how many tools to call and in what order; nothing currently caps this. A pathological or adversarial message could in principle prompt an unusually large number of tool calls in one request — not observed during testing, but not structurally prevented either.
- **Omitting `conversationId` from `/api/assistant/chat` silently forfeits conversation memory** for that call, rather than rejecting the request. Reasonable as a default, but worth surfacing to any real caller that depends on multi-turn context.
- **Cache key normalization is surface-level only, not true semantic matching.** It catches punctuation, casing, and trailing-whitespace differences ("What's the rate?" vs. "what's the rate"), but two genuinely different phrasings of the same question ("What's the interest rate?" vs. "Can you tell me the interest rate on this loan?") still produce separate cache entries and separate LLM calls. A semantic/embedding-based cache would catch true paraphrases, at the cost of meaningfully more complexity (similarity thresholds, risk of over-matching two questions that sound similar but mean different things) — deliberately not attempted here.
- **No re-ranking or hybrid search.** Retrieval is pure vector similarity; a production system might combine this with keyword search or a re-ranking step for higher precision.
- **Single-tenant.** No concept of per-customer document scoping or access control on retrieval.
- **No real authentication.** The Banking Actions Assistant (and the orchestrator, by extension) takes `accountId` directly in the request body as a stand-in for what a real system would derive from a session or JWT after login.

## Learning Path (All Five Steps Complete)

This project was built as a progressive GenAI learning path:

1. **FAQ Assistant** — RAG fundamentals: embeddings, vector search, grounding, conversation memory
2. **Document Q&A System** — real-world PDF parsing (Apache Tika), chunking strategy on longer documents, per-document metadata scoping via filter expressions
3. **Banking Actions Assistant** — tool calling with code-enforced account scoping and a two-step propose/confirm pattern for irreversible actions like fund transfers
4. **Multi-Agent Orchestration (Router)** — LLM-based intent classification and routing across all three prior services, with graceful handling of missing context and misclassification
5. **True Agentic Orchestration** — the three sub-agents exposed as tools to a single reasoning LLM, capable of invoking more than one per request, with the account-scoping security boundary from Step 3 preserved unchanged

## Related Projects

- **[`banking-mcp-server`](../banking-mcp-server)** — a standalone Model Context Protocol (MCP) server that exposes this app's FAQ, document Q&A, and document upload capabilities to MCP-compatible AI clients (Claude Desktop, Claude Code, MCP Inspector), calling this app's REST API over HTTP rather than reaching into its internal Spring beans.

## Testing

A full [Bruno](https://www.usebruno.com/) collection covering every endpoint and edge case exercised during development — grounding, memory, the no-hallucination test, admin auth, document scoping, the propose/confirm transfer flow, adversarial security tests, and all orchestrator routing paths — is available alongside this project. See the collection's own README for setup and running order; a few chained requests (document upload → scoped Q&A, propose → confirm transfer) require copying an ID or code between requests.

## Possible Next Directions

- Bounding tool-call count/depth in the agentic orchestrator, addressing the unguaranteed tool-call-order limitation noted above
- Exposing the Banking Actions tools via MCP, solving the account-scoping-via-transport-context design gap noted in `banking-mcp-server`'s own README
- Automated evaluation/regression testing for RAG answer quality — a genuine differentiator most portfolio projects skip entirely
- Fixing the pending-transfer account-scoping gap identified above

## Related Portfolio Projects Beyond This One

- **[`loan-prequalification-assistant`](../loan-prequalification-assistant)** — a separate project applying different GenAI techniques (structured extraction, deterministic decisioning, cross-language ML via gRPC) to a different banking domain problem, rather than repeating this project's RAG-centric approach

## A Note on Model Churn

Built during a period of unusually rapid change in the Gemini API — over the course of building this, `text-embedding-004` and `gemini-embedding-001` were both retired, and `gemini-2.5-flash` was restricted for new API keys, requiring a live migration to `gemini-embedding-2` and `gemini-3.5-flash` mid-build. Left as-is rather than glossed over, since managing model lifecycle risk is itself a real production concern.
