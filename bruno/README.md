# Banking FAQ Assistant — Bruno Collection

Every unique test case exercised during development, organized to match the
project's build order: FAQ RAG → Admin ingestion → Document Q&A →
Banking Actions (including adversarial security tests) → Orchestrator
(router) → Agentic Orchestrator (true tool-calling agent).

## Setup

1. Open this folder in [Bruno](https://www.usebruno.com/)
2. Select the **Local** environment (top-right in Bruno)
3. Edit these variables in the Local environment before running anything:
   - `admin_api_key` — must match `ADMIN_API_KEY` in `banking-faq-assistant`'s `.env`
   - `document_id` / `wrong_document_id` — fill in after running the upload
     request in `03-documents`, needed for the document-scoped tests

## Folder Guide

| Folder | Covers |
|---|---|
| `01-faq` | Basic RAG grounding, conversation memory, no-hallucination test |
| `02-admin` | Runtime FAQ document ingestion, API key auth (positive + negative) |
| `03-documents` | PDF upload, dedup, status check, scoped Q&A, cross-document leak test |
| `04-banking-actions` | Balance/transactions, propose/confirm transfer flow, adversarial security tests |
| `05-orchestrator` | Step 4 router: single-path intent classification across all three sub-agents, missing-context handling, an intentionally ambiguous classification case |
| `06-agent` | Step 5 true agentic orchestrator: multi-tool reasoning in one request, the same account-scoping security test re-verified under a more autonomous architecture, natural missing-context clarification |

## Running Order

Most requests are self-contained, but a few must run in sequence
(numbered accordingly within each folder):

- **03-documents**: run `01 - Upload PDF Document` first, copy the returned
  `documentId` into the `document_id` environment variable, then run 02–03.
  For `04 - Cross-Document Scoping Test`, upload a SECOND PDF and set
  `wrong_document_id` to its ID.
- **04-banking-actions**: run `03 - Propose Transfer`, copy the confirmation
  code into `04 - Confirm Transfer`'s body before sending it.
- **04-banking-actions adversarial tests (05, 06)**: run these, then run
  `07 - Check Balance After Attacks` to verify no money actually moved —
  the response text alone is never sufficient proof.
- **06-agent, request 01**: the flagship comparison test. Run the identical
  message through `05-orchestrator` against `/api/assistant/chat` first,
  then this request against `/api/agent/chat` — the router can only answer
  one half of the question, the agent answers both by calling two tools in
  one request. Watch the Spring Boot console for `[Agent] ...` log lines
  to confirm which tool(s) actually fired, rather than trusting the
  response text alone.

## Not Covered Here

`banking-mcp-server`'s tools (`answerBankingFaq`, `askAboutDocument`,
`uploadDocument`) are exposed over the MCP protocol, not plain REST/JSON —
they're not meaningfully testable as Bruno HTTP requests. Use
[MCP Inspector](https://github.com/modelcontextprotocol/inspector) for those,
as documented in that project's own README.
