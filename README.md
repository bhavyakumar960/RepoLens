# Spring AI MCP — Code Intelligence & RAG Server

A Spring Boot **Model Context Protocol (MCP)** server that gives AI assistants (Claude, Copilot, etc.) deep, structured
understanding of large Java codebases, plus first-class access to Bitbucket, Jira, and Confluence. It combines:

- **Semantic search** over code/docs using vector embeddings (Qdrant or Weaviate + Ollama).
- **Structural understanding** of code using a Neo4j property graph (classes, methods, fields, packages, modules,
  `CALLS`/`USES`/`IMPLEMENTS`/`EXTENDS` relationships).
- **Adaptive retrieval routing** that picks the cheapest correct strategy per query (graph-only, vector-only, or both),
  cutting latency 40–60% versus always doing a full hybrid search.
- **Optional cross-encoder reranking** (TEI service) applied only when it's likely to help.
- **Atlassian tool integrations** (Bitbucket, Jira, Confluence) exposed as MCP tools for end-to-end workflows.

Everything is exposed as MCP `@Tool` methods, callable directly by an LLM client (this server runs in stdio mode —
`spring.main.web-application-type=none`).

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Ingestion Flow](#2-ingestion-flow-code--documents--graph--vectors)
3. [Retrieval Flow](#3-retrieval-flow-query--adaptive-routing--context)
4. [Graph-Based Code Intelligence](#4-graph-based-code-intelligence)
5. [MCP Tools Reference](#5-mcp-tools-reference)
6. [Configuration Reference](#6-configuration-reference)
7. [Data Model / Storage](#7-data-model--storage)
8. [Running Locally](#8-running-locally)
9. [Qdrant Deep Dive & Glossary (for newcomers)](#9-qdrant-deep-dive--glossary-for-newcomers)
10. [Ollama & `nomic-embed-text`: Embeddings Deep Dive](#10-ollama--nomic-embed-text-embeddings-deep-dive)
11. [Neo4j Graph Database Deep Dive](#11-neo4j-graph-database-deep-dive)
12. [TEI Reranker (Cross-Encoder) Deep Dive](#12-tei-reranker-cross-encoder-deep-dive)

---

## 1. Architecture Overview

```
                         ┌───────────────────────────┐
                         │   LLM / MCP Client         │
                         │ (Claude / Copilot / etc.)  │
                         └─────────────┬─────────────┘
                                       │ MCP (stdio)
                 ┌─────────────────────┴─────────────────────────┐
                 │            spring-ai-mcp (this app)           │
                 │                                                │
   ┌────────────►│  CodeIntelligenceMcpTool  CodeGraphMcpTool     │
   │             │  BitbucketToolService JiraToolService          │
   │             │  ConfluenceToolService                         │
   │             └──────┬───────────────────────┬─────────────────┘
   │                    │                       │
   │           ┌────────▼────────┐    ┌─────────▼──────────┐
   │           │  RAG Pipeline    │    │  Graph Pipeline     │
   │           │  (Qdrant/Weaviate│    │  (Neo4j)            │
   │           │   + Ollama)      │    │                     │
   │           └────────┬────────┘    └─────────┬──────────┘
   │                    │                        │
   │           ┌────────▼────────────────────────▼──────────┐
   │           │      HybridIngestionService (dual-write)     │
   │           └────────┬──────────────────────────────────┘
   │                    │
   │           ┌────────▼────────┐
   └───────────┤ IngestionStateStore (MySQL) — file/chunk hash tracking
               └─────────────────┘
```

**Technology stack**: Spring Boot 3, Spring AI (MCP server starter), Qdrant / Weaviate (vector store), Neo4j
(graph database, via Spring Data Neo4j), Ollama (`nomic-embed-text` embeddings, local), JavaParser
(AST parsing + symbol solving for Java), Apache Tika (PDF/DOCX/HTML/MD parsing), MySQL (ingestion state
tracking), TEI — Text Embeddings Inference (`BAAI/bge-reranker-base`, optional cross-encoder reranker).

---

## 2. Ingestion Flow (code & documents → graph & vectors)

Ingestion is **hybrid**: every source file is simultaneously turned into (a) chunked, embedded vector-store
documents for semantic search, and (b) structured Neo4j graph nodes/relationships for exact structural queries.

### 2.1 Parsing
`RoutingDocumentParser` inspects the file extension and routes to:
- **`JavaDocumentParser`** for `.java` files — uses JavaParser to build an AST and emits one `Document` per
  class/method (with metadata: package, class name, method signature, annotations, line ranges).
- **`TikaDocumentParser`** for everything else (PDF, DOCX, PPTX, XLSX, TXT, MD, HTML) via Apache Tika.

### 2.2 Chunking

Chunking happens in two layers:

**Layer 1 — semantic pre-split (parsing).** `JavaDocumentParser` doesn't emit one giant blob per file; it walks
the AST and already produces one `Document` per "unit of meaning" *before* any token splitting occurs:
- one document per top-level type (class/interface/enum/record) — header + Javadoc + field summary;
- one document per method and one per constructor — signature + Javadoc + full body.

Each of these documents carries rich metadata from the parser: `package`, `class_name`, `member_name`
(method/field/class name), `member_type` (`class`/`interface`/`enum`/`record`/`method`/`constructor`),
`signature`, `start_line`/`end_line`, `imports`, `annotations`, and (for REST handler methods) `http_method` /
`endpoint_path` / `request_headers`. Generic documents (PDF/DOCX/MD/…) instead come back from `TikaDocumentParser`
as one or few large documents per file.

**Layer 2 — token splitting.** `TokenDocumentChunker` then runs every parsed `Document` through Spring AI's
`TokenTextSplitter` (tiktoken-based), configured with:
- `defaultChunkSize` = `rag.chunk-size` (target chunk size in tokens),
- `minChunkSizeChars` = `rag.min-chunk-size-chars` (drop trivial tail fragments),
- `minChunkLengthToEmbed` = 5 (ignore near-empty pieces),
- `maxNumChunks` = 10,000 safety ceiling per document,
- `keepSeparator` = true, splitting preferentially on `.`, `!`, `?`, `\n` so sentences/statements aren't cut
  mid-way.

Because layer 1 already scopes each document to a single class/method, most methods fit in a single token
chunk; only unusually large methods or documents get split into multiple sequential chunks.

### 2.3 How chunks are linked back together

Splitting text loses the "these N chunks came from the same method/class" relationship unless it's tracked
explicitly. `DocumentIngestionService.prepareChunks(...)` restores that linkage by stamping every chunk with:

- **`chunk_index`** / **`total_chunks`** — this chunk's position and the total chunk count for its parent unit,
  so consumers can reassemble order.
- **`parent_document_id`** — a SHA-256 hash of
  `repo | module | branch | filePath | class_name | member_name | member_type | signature`. All chunks that came
  from the same class/method/constructor share the same `parent_document_id`, so they can be grouped back
  together (or re-fetched as a set) regardless of chunk order.
- **`chunk_hash`** — SHA-256 of the chunk's own text, used to detect if *this specific chunk* changed between
  ingestion runs (versus the whole file changing).
- **`file_hash`** — SHA-256 of the whole source file, used by `IngestionStateStore` (MySQL) to skip re-parsing
  files that haven't changed at all.
- **Deterministic chunk ID** — a UUID derived from `repo | branch | file_path | member_name | chunk_index`
  (`deterministicChunkId`). Because the ID is a deterministic function of identity rather than random, re-running
  ingestion on an unchanged file **overwrites the same vector-store point** instead of creating a duplicate —
  this is also what lets `areDocumentIdsPresent()` cheaply confirm a file's chunks are already indexed.
- Common identity fields (`repo`, `module`, `service`, `branch`, `file_path`, `commit_sha`) are copied onto
  every chunk of the file, so all chunks belonging to one file can be located/deleted together via
  `deleteByFileIdentity(repo, module, branch, filePath)` without knowing individual chunk IDs.

On the graph side, the same source unit is additionally identified by a stable, human-readable key from
`GraphNodeIdentity`: `class:<repo>:<fqcn>`, `method:<repo>:<fqcn>.<method>(<paramTypes>)`,
`field:<repo>:<fqcn>.<field>`. This is *not* the same ID as the vector chunk ID, but the shared `class_name` /
`member_name` / `signature` metadata on each vector chunk is what lets the retrieval layer cross-reference a
semantic hit back to its exact Neo4j node (see §3 and §4).

### 2.4 Change detection
`IngestionStateStore` persists a hash per file/chunk in **MySQL**. On re-ingestion, only changed files are
re-parsed, re-embedded, and re-written — unchanged files are skipped, making ingestion idempotent and incremental.

### 2.5 Dual-write orchestration
`HybridIngestionService` coordinates the two stores for each file:
1. Parse & chunk the file.
2. Write chunks + embeddings to the vector store via `VectorStoreDocumentIndexer` (Qdrant or Weaviate,
   selected by active Spring profile).
3. Write structural graph nodes/relationships to Neo4j via `Neo4jIngestionService` (`JavaClass`, `JavaMethod`,
   `JavaField`, `JavaPackage`, `Module`, `CodeRepository` nodes; `CALLS`, `USES`, `IMPLEMENTS`, `EXTENDS`
   relationships).
4. On partial failure, applies compensating cleanup so the two stores don't drift out of sync.
5. Updates `IngestionStateStore` with the new hash.

`RagAutoIngestionRunner` runs at startup (if `rag.auto-ingest-enabled=true`) and walks `rag.auto-ingest-java-root`,
ingesting the whole repository automatically.

### 2.6 Relationship reconciliation
When a method calls code in another class/file that hasn't been ingested yet, `Neo4jIngestionService` creates a
**stub** `CALLS` target. `RelationshipReconciliationService` resolves stub targets to their real method nodes,
preserving call-site metadata (line number, argument types) gathered during the original scan. It runs
automatically, in-process, as the last step of `HybridIngestionService.ingestRepository(...)` — there is no
standalone MCP tool for it; it's an internal pipeline step, not something callers trigger directly.

### 2.7 Cross-repository linking
`CrossRepoLinkerService` links classes across different ingested repositories (e.g., a shared library used by
multiple services) via `INJECTS`/`CALLS` relationships, enabling impact analysis and dependency queries that
span repository boundaries. It also runs automatically **immediately after** reconciliation in the same
`ingestRepository(...)` call (cross-repo linking assumes target classes have already been resolved to `REAL`,
so it must come second). A failure here is logged and swallowed — it never fails the overall ingestion, since
vector indexing and reconciliation have already committed successfully by that point.

**Target repository discovery is automatic.** Rather than requiring every dependency to be hand-declared in
`RepositoryDependencyConfig`, the service now auto-discovers candidate target repositories by querying Neo4j
for every distinct `repository` value already present in the graph (`JavaClassRepository.findAllDistinctRepositories()`)
and linking the just-ingested repository against all of them. The `code-intelligence.cross-repo-linking.dependency-map`
config entry still works as a manual override when present for a given source repository (useful for narrowing
scope in very large multi-repo graphs), but it is no longer required — cross-repo linkage works out of the box
against whatever has been ingested. The same auto-discovery is used by `Neo4jIngestionService` when resolving
declared-dependency types (constructor/field injection) that reference classes outside the current repository.

**Linking is bidirectional, not just "the repo just ingested."** After every `ingestRepository(...)` call,
`HybridIngestionService` invokes `CrossRepoLinkerService.linkAllRepositories()` rather than linking only the
newly-ingested repository as source. This matters because if repository A (already ingested earlier) depends
on repository B (ingested just now), only re-scanning B as a source would never create A's `INJECTS`/`CALLS`
edges pointing at B — A has to be re-scanned as a source too. `linkAllRepositories()` iterates every distinct
repository in the graph (`findAllDistinctRepositories()`) and re-runs `linkCrossRepoRelationships(repo, null)`
for each one. All writes use `MERGE`, so repeatedly re-linking repositories with nothing new to discover is
cheap and safe — it does not create duplicates.

**Memory/robustness characteristics** (mirroring the same constraints `RelationshipReconciliationService`
was designed around):
- No single outer `@Transactional` wraps the multi-repo, multi-phase scan — each repository/batch/relationship
  write commits independently via Spring Data Neo4j's own per-call transactions, so a failure or restart
  partway through never leaves one giant open transaction and simply resumes cleanly on the next run.
- Classes and methods are still processed in batches of 50 (`BATCH_SIZE`), same as before.
- Cross-repo class/method resolution (`findClassAcrossRepositories`, `resolveMethodInvocation`) queries **all**
  candidate target repositories in a single Neo4j round trip (`findBySimpleNameInRepositories`,
  `findByClassNameAndNameInRepositories`, `findByNameAndClassNameEndingInRepositories`, etc.) instead of looping
  once per target repository — important now that the target list can be every ingested repository.
- Method overload resolution now narrows by parameter *type* (not just parameter count) when the invocation's
  argument types are known, avoiding false-positive `CALLS` edges to the wrong overload.
- Source files are parsed with JavaParser at most once per file per linking run: a short-lived `parseCache`
  (keyed by repository + relative file path, read from the already-stored `JavaClass`/`JavaMethod.filePath`)
  is shared across the INJECTS and CALLS phases and across all methods of a class, and is discarded once that
  repository's linking pass completes — so it never grows across the full `linkAllRepositories()` run. File
  lookup no longer shells out to the `find` command; it resolves the absolute path directly from the
  registered repository root (`registerRepositoryPath(...)`) plus the stored relative `filePath`.

### 2.8 Reconciliation vs. cross-repo linking — what's the difference?

Both jobs run **automatically, back-to-back, as part of the same `ingestRepository(...)` call** — from an
operator's perspective, ingesting a repository already does "all of this in one go." Internally they remain two
separate services because they solve different problems with different mechanisms and cost profiles:

| | **`RelationshipReconciliationService`** (reconciliation) | **`CrossRepoLinkerService`** (cross-repo linking) |
|---|---|---|
| **Problem solved** | "I already recorded that a call/relationship *should* exist, but I didn't have the target node yet, so I created a placeholder." | "Repository A actually depends on repository B (e.g. a shared library), but nothing in A's own ingestion pass told the graph that — the two repos were ingested independently and know nothing about each other." |
| **Trigger** | Automatic — runs unconditionally at the end of `HybridIngestionService.ingestRepository(...)`, right after all files finish indexing. Not exposed as a standalone MCP tool. | Automatic — runs immediately after reconciliation, inside the same `ingestRepository(...)` call, via `linkAllRepositories()`: **every** repository already present in the graph is re-linked against every other one (auto-discovered via `JavaClassRepository.findAllDistinctRepositories()`), not just the repository just ingested — this is what makes newly-discovered dependencies flow in both directions. `RepositoryDependencyConfig` can still override the target list for a given source repository. Also not exposed as a standalone MCP tool. |
| **Input / scope** | Operates on nodes that already exist in the graph and are marked `nodeStatus = STUB` (created as forward-references during single-repo ingestion) — classes (`completeStubClasses`), then `EXTENDS`/`IMPLEMENTS`/`USES`/method-type relationships, then `CALLS` globally (`reconcileCallsRelationshipsGlobally`, across *all* repositories, not scoped to one). | Operates on **every auto-discovered repository as both source and target** — unless `RepositoryDependencyConfig` declares an explicit override for a given source repository. Nothing is assumed to be a stub; it actively re-parses AST-level usage to discover *new* relationships that were never recorded at all. |
| **Mechanism** | *Resolution*, not analysis: for each `STUB` node, look up whether a `REAL` node with the same identity (`fullyQualifiedName` / `methodResolutionKey`) now exists (because that repo has since been ingested) and merge/repoint the relationship onto it. Idempotent — safe to re-run; each step processes in small paginated batches (`BATCH_SIZE = 200`) to bound memory. | *Analysis*, in three explicit phases: **(1) `buildInjectsRelationships`** — detect class-level dependency injection (`@Autowired`, constructor/setter injection), resolving the injected type against all candidate repositories in one batched query, creating `INJECTS` edges; **(2)/(3) `buildCallsRelationships`** — re-walk the (cached) AST to find *actual* method invocations on injected dependencies (not just "every method of the injected class") and create `CALLS` edges only for real call sites, resolved via class/method name + parameter-type matching across all target repositories in one query. Batches classes in groups of 50 (`BATCH_SIZE = 50`); no outer transaction wraps the run. |
| **Relationship types touched** | `EXTENDS`, `IMPLEMENTS`, `USES`, method-type relationships, and `CALLS` (stub → real resolution only). | `INJECTS` (new) and `CALLS` (new, cross-repo only). |
| **Node status semantics** | Directly reads/writes `nodeStatus` (`STUB` → `REAL`) via `GraphNodeIdentity.STUB`/`REAL` constants; this is the mechanism that "graduates" placeholder nodes. | Does not deal with stub/real status at all — it assumes both sides' classes are already fully ingested (real) and simply adds relationships that cross-repo AST analysis alone couldn't produce during isolated single-repo ingestion. |
| **Failure isolation** | Runs inside the main `ingestRepository` try block — an exception here is logged by the outer catch and does surface as an ingestion-level failure (though vector + graph node data is already committed). | Wrapped in its own try/catch inside `linkCrossRepoRelationshipsAfterIngestion(...)` — exceptions are logged and swallowed, never propagated, so a cross-repo linking failure can never fail the overall repository ingestion. Per-repository failures inside `linkAllRepositories()` are also caught individually so one bad repository doesn't abort linking for the rest. |

In short: **reconciliation** completes relationships the ingester *already knew about* but couldn't fully wire
up yet (a bookkeeping/consistency pass); **cross-repo linking** *discovers* relationships that don't exist yet
because they span two independently-ingested codebases (an analysis pass), driven by auto-discovery of every
ingested repository — relinked bidirectionally after each ingestion — rather than requiring hand-maintained
dependency configuration.

### Ingestion at a glance

| Stage | Class | Responsibility |
|---|---|---|
| Parse | `JavaDocumentParser`, `TikaDocumentParser`, `RoutingDocumentParser` | AST/text → `Document` objects |
| Chunk | `TokenDocumentChunker` | Token-aware splitting |
| Index (vector) | `VectorStoreDocumentIndexer` | Embed + write to Qdrant/Weaviate |
| Index (graph) | `Neo4jIngestionService` | Create class/method/field/package nodes + relationships |
| Orchestrate | `HybridIngestionService`, `DocumentIngestionService` | Dual-write + state tracking |
| State | `IngestionStateStore` (MySQL) | Hash-based change detection |
| Auto-run | `RagAutoIngestionRunner` | Ingest configured root on app startup |
| Reconcile | `RelationshipReconciliationService` | Auto-runs at end of `ingestRepository(...)`; resolves `STUB` nodes/`CALLS` to `REAL` targets (bookkeeping) |
| Cross-repo | `CrossRepoLinkerService` | Auto-runs immediately after reconciliation via `linkAllRepositories()`; re-links every ingested repository (not just the one just ingested) against every other one, discovering `INJECTS`/`CALLS` across repository boundaries (AST analysis, cached per file, batched cross-repo queries) |

---

## 3. Retrieval Flow (query → adaptive routing → context)

### 3.1 Intent classification
`QueryIntentClassifier` classifies every natural-language query into one of ten `QueryIntent` values:

| Intent | Example | Strategy |
|---|---|---|
| `EXACT_SYMBOL` | "Where is `processOrder`?" | Neo4j direct lookup only — no vector search |
| `FILE_LOOKUP` | "Show me `OrderController.java`" | File path search |
| `CONCEPTUAL` | "How is FD interest calculated?" | Qdrant vector search (2–3 query variations) |
| `DEPENDENCY` | "What depends on `UserService`?" | Neo4j graph traversal |
| `CALL_GRAPH` | "What calls `processOrder`?" | Neo4j `CALLS` relationships |
| `IMPACT_ANALYSIS` | "What breaks if I change `processOrder`?" | Neo4j reverse dependencies + callers |
| `CONFIGURATION` | "What REST endpoints exist?" | Neo4j annotation/metadata search |
| `BUSINESS_LOGIC` | "How does order processing work end-to-end?" | Qdrant + Neo4j combined |
| `DEBUGGING` | "Why does X fail when Y fails?" | Qdrant + Neo4j + call graph (3 queries) |
| `ARCHITECTURE` | "Explain the checkout architecture" | Qdrant + Neo4j modules/packages |
| `UNKNOWN` | fallback | Full hybrid retrieval |

### 3.2 Adaptive routing
`AdaptiveRetrievalRouter` uses the classified intent to decide **how many vector-search query variations to
generate** (1 for exact/simple lookups, up to 3 for exploratory/debugging queries) and **which backend(s)** to
hit — Neo4j only, Qdrant only, or both — avoiding unnecessary vector searches and graph traversals. Before
routing, it runs two additional deterministic steps:

- **Query normalization** (`QueryNormalizer`) — trims, collapses stray whitespace, strips wrapping quotes, and
  collapses repeated punctuation (e.g. `"???"` → `"?"`). Identifier casing (`processOrder` vs `ProcessOrder`) is
  never altered, since code symbols are case-sensitive.
- **Query entity extraction** (`QueryEntityExtractor`) — pulls candidate identifier tokens out of the query
  (PascalCase/camelCase identifiers, dotted FQNs, kebab-case repo/module hints) and verifies them against the
  graph via `Neo4jSymbolService` **before** falling back to free-text heuristics — e.g. "Where is class
  `OrderService`?" resolves `OrderService` against Neo4j directly rather than naively taking the first word after
  "where is". Unresolved candidates and a graph-verified repository hint (used to auto-scope the search when the
  caller didn't specify one) are still returned for logging/debugging.

Per-intent graph traversal depth and query-variation counts (previously hardcoded numbers scattered across the
router's `handle*` methods) are now configurable via `rag.*-depth` / `rag.*-query-count` properties (§6) so they
can be tuned per environment without a code change.

### 3.2.1 Retrieval provenance / debug mode
Passing `debug=true` to `retrieveCodeContext` (or setting `rag.retrieval-debug-enabled=true` globally) attaches a
`RetrievalDebugInfo` block to the response: classified intent, chosen strategy, whether vector/graph search ran,
the query-variation count and dependency depth actually used, any graph-resolved class/method/repository hint,
and the routing latency in milliseconds. This is purely additive — normal responses stay compact unless a caller
explicitly opts in.

### 3.3 How vectors are created

Vector creation is handled transparently by Spring AI, not by hand-rolled embedding calls:

1. `VectorStoreDocumentIndexer.index(documents)` simply calls `vectorStore.add(documents)`.
2. The configured `VectorStore` bean (`QdrantVectorStore` or `WeaviateVectorStore`, chosen by the active
   `qdrant`/`weaviate` Spring profile) internally invokes the configured `EmbeddingModel` — Ollama's
   `nomic-embed-text` model at `spring.ai.ollama.base-url` — passing each chunk's **text only** (not its
   metadata) to produce a fixed-length embedding vector.
3. The vector store then upserts a point/object per chunk containing: `id` (the deterministic chunk UUID from
   §2.3), the `vector` itself, and the full metadata map as a filterable payload (`repo`, `module`, `branch`,
   `file_path`, `class_name`, `member_name`, `signature`, `chunk_index`, `parent_document_id`, `http_method`,
   `annotations`, …). Qdrant's collection is `spring-ai-mcp` (`spring.ai.vectorstore.qdrant.collection-name`);
   Weaviate's object class defaults to `SpringAiWeaviate`. Both are auto-created on startup
   (`initialize-schema=true`) — for Weaviate every metadata key used as a filter is explicitly declared as a
   `TEXT`/`NUMBER` filter field in `application-weaviate.properties` so it can be queried, not just stored.
4. Because embedding happens purely from `chunk.getText()`, two chunks with identical text but different
   metadata (e.g. the same utility method inlined in two files) get distinct vector-store points (different
   deterministic IDs) but would embed to very similar vectors — the metadata filters (§3.3.1) are what
   disambiguate them at query time.
5. Re-indexing an unchanged chunk is a no-op in effect: since the chunk ID is deterministic
   (`repo|branch|file_path|member_name|chunk_index`) and unchanged text produces the same `chunk_hash`,
   `IngestionStateStore` skips re-embedding it entirely; only chunks whose hash changed are re-embedded and
   the vector-store point at that same ID is overwritten in place.

### 3.4 How similarity search works

At query time (`VectorStoreDocumentRetriever.retrieve(...)` / `QdrantCodeSearchService`):

1. The raw natural-language query string is embedded with the **same** Ollama model used at ingestion time
   (embeddings from different models are not comparable, so the model must stay consistent between ingest and
   query).
2. Spring AI's `SearchRequest` is built with `topK` (`rag.top-k` / `codeintel.retrieval.top-k`) and
   `similarityThreshold` (`rag.similarity-threshold`, default `0.7`) — results below this similarity score are
   dropped even if they're in the top K.
3. The vector store computes distance between the query vector and every stored chunk vector using its
   configured distance metric — **cosine similarity** by default for both Qdrant and Weaviate here, since no
   `distance`/`metric` override is set — and returns the closest matches, converting distance to a
   0–1 similarity score.
4. `metadataFilters` (repo/module/branch/file_path/package/class_name/member_name/member_type) are translated
   into a `Filter.Expression` via `FilterExpressionBuilder` and applied as a **pre-filter** at the vector-store
   level (`AND`-combined), so similarity search only ranks candidates within the requested scope — e.g. "search
   only within `axis-utilities` repo, `AuthService` class."
5. When `includeRelatedContext=true` and the filter targets a specific member, a **second, broader** search is
   issued with the same query but the `member_name`/`member_type` filters dropped (falling back to class-level
   scope), and its results are merged in — this pulls in sibling methods of the same class as extra context.
6. Results from all searches are **deduplicated** by document ID (or a text hash fallback if the ID is missing),
   preserving first-seen order.
7. `AdaptiveRetrievalRouter` may run steps 1–6 multiple times with **reworded query variations** (1 query for
   `EXACT_SYMBOL`/`FILE_LOOKUP`, 2 for `CONCEPTUAL`, up to 3 for `DEBUGGING`/exploratory intents) — each
   variation's hits are merged and deduplicated the same way, improving recall for ambiguous phrasing without
   paying the full multi-query cost on simple lookups.
8. Finally, `documentReranker.rerank(query, results)` conditionally reorders the deduplicated list using the TEI
   cross-encoder (§3.5) before it's handed to `RankingService`/`ContextBuilder`.

### 3.5 Conditional reranking
`ConditionalRerankService` (code-intel path) / `DocumentReranker` → `TeiDocumentReranker` (generic RAG path) call
out to a TEI cross-encoder reranker (`BAAI/bge-reranker-base`) **only when it's likely to change the ranking** —
skipped when there are fewer than `rag.reranker-min-documents` candidates, or when the intent is in
`rag.reranker-skip-for-intents` (`EXACT_SYMBOL`, `CALL_GRAPH` by default, since graph lookups are already exact).

### 3.6 Ranking, scoring, and context assembly
- `RankingService` merges results from graph + vector paths, deduplicates, and sorts.
- `RetrievalConfidenceScorer` assigns a confidence score per result.
- `SourceCodeContextService` fetches the actual source (method/class body with surrounding context) from disk
  for the final answer when requested.
- `ContextBuilder` assembles the final retrieval context; `CompactEvidenceFormatter` renders it in a
  token-efficient format (50–70% smaller than naive concatenation) for the LLM.
- `CodeIntelligenceOrchestrator` is the top-level coordinator tying together intent classification → routing →
  search → rerank → rank → source retrieval → formatting for every `CodeIntelligenceMcpTool` call.

---

## 4. Graph-Based Code Intelligence

`CodeGraphService` (backing `CodeGraphMcpTool`) and `Neo4jSymbolService` (backing `CodeIntelligenceMcpTool`) run
Cypher traversals over the Neo4j graph for:

- **Symbol lookup** — find a class/method/field by exact or fuzzy name (`findSymbol`, `findClassById`,
  `findMethodById`).
- **Call graph** — `findCallersOfMethod` (reverse: who calls this?) and `findCalledByMethod` (forward: what does
  this call?), walking `CALLS` edges.
- **Impact analysis** — `findImpact` traverses reverse dependencies/callers up to N hops to answer "what breaks
  if I change this?", including affected API endpoints and tests when requested.
- **Dependency traversal** — `findDependencies` walks `CALLS`/`USES`/`IMPLEMENTS`/`EXTENDS` in either direction.
- **Architecture overview** — `getArchitectureContext` combines semantic search with graph traversal of
  modules/packages/controllers/services/repositories to answer high-level "how does X work" questions.
- **Spring stereotype discovery** — `findAllControllers`, `findAllServices`, `findAllRepositories`,
  `findAllApiEndpoints` query nodes tagged with Spring annotations (`@RestController`, `@Service`, `@Repository`)
  and REST mapping metadata.
- **Test coverage** — `establishTestRelationships` links test methods to the production methods they exercise
  (`TESTED_BY`), via naming convention + call analysis.
- **Sync validation** — `validateSync` checks that Neo4j and the vector store agree on which files have been
  ingested, surfacing drift between the two stores.

---

## 5. MCP Tools Reference

### Code Intelligence (`CodeIntelligenceMcpTool`)
| Tool | Purpose |
|---|---|
| `retrieveCodeContext` | Primary adaptive-retrieval entry point for implementation questions (supports `debug=true` for retrieval provenance) |
| `searchCode` | Pure semantic search with adaptive query-variation generation |
| `findSymbol` | Exact/fuzzy symbol lookup (class, method, field) |
| `getCodeContext` | Retrieve exact source code for a known symbol ID |
| `findDependencies` | Graph dependency traversal (incoming/outgoing/both) |
| `findImpact` | Change-impact analysis with risk assessment |
| `getArchitectureContext` | High-level architecture Q&A (hybrid Qdrant + Neo4j) |
| `ingest` | Ingest a Java file or repository into graph + vector store |

### Graph (`CodeGraphMcpTool`)
`ingestJavaFile`, `ingestRepository`, `validateSync`, `establishTestRelationships`,
`findImplementations`, `findCallersOfMethod`, `findCalledByMethod`, `findAllApiEndpoints`, `findAllControllers`,
`findAllServices`, `findAllRepositories`, `findClassById`, `findMethodById`.

> Note: relationship reconciliation and cross-repo linking are **not** exposed as MCP tools. They run
> automatically and internally as the last two steps of `ingestRepository` only (single-file `ingestJavaFile`
> calls do not trigger them) — there is no `reconcileGraph` tool for callers to invoke separately.

### Bitbucket (`BitbucketToolService`)
`listRepositories`, `listBranches`, `listPullRequests`, `getPullRequest`, `getPullRequestDiff`,
`getPullRequestComments`, `addPullRequestComment`, `createPullRequest`, `getFileContent`, `getCommits`.

### Jira (`JiraToolService`)
`getIssue`, `searchIssues`, `getProjectIssues`, `createIssue`, `updateIssue`, `getIssueComments`, `addComment`,
`getIssueTransitions`, `transitionIssue`, `assignIssue`.

### Confluence (`ConfluenceToolService`)
`listSpaces`, `getPagesInSpace`, `getPage`, `getChildPages`, `getPageComments`, `addPageComment`, `createPage`,
`updatePage`, `changeParentPage`, `searchContent`.

---

## 6. Configuration Reference

All settings live in `src/main/resources/application.properties` (plus profile-specific overrides).

### RAG pipeline (`rag.*`)
| Property | Meaning |
|---|---|
| `rag.top-k` | Number of documents returned per vector search |
| `rag.similarity-threshold` | Minimum cosine similarity to keep a match |
| `rag.chunk-size` | Target chunk size (tokens) |
| `rag.min-chunk-size-chars` | Minimum chunk size to keep (chars) |
| `rag.documents-path` | Default folder scanned for generic document ingestion |
| `rag.embedding-model` | Ollama embedding model name (`nomic-embed-text`) |
| `rag.auto-ingest-enabled` | Ingest `rag.auto-ingest-java-root` automatically on startup |
| `rag.auto-ingest-java-root` | Root path of the Java repo to auto-ingest |
| `rag.reconciliation-enabled` | Run relationship reconciliation automatically on startup |
| `rag.reranker-enabled` | Enable/disable the TEI cross-encoder reranker |
| `rag.reranker-base-url` | TEI reranker service URL |
| `rag.reranker-min-documents` | Minimum candidate count before reranking kicks in |
| `rag.reranker-skip-for-intents` | Comma-separated `QueryIntent`s that bypass reranking |
| `rag.call-graph-depth` | Graph traversal depth for `CALL_GRAPH` intent (was hardcoded `2`) |
| `rag.dependency-graph-depth` | Graph traversal depth for `DEPENDENCY` intent (was hardcoded `3`) |
| `rag.impact-analysis-depth` | Reverse-dependency traversal depth for `IMPACT_ANALYSIS` intent (was hardcoded `4`) |
| `rag.business-logic-depth` | Graph traversal depth for `BUSINESS_LOGIC` intent (was hardcoded `2`) |
| `rag.debugging-depth` | Graph traversal depth for `DEBUGGING` intent (was hardcoded `3`) |
| `rag.architecture-depth` | Graph traversal depth for `ARCHITECTURE` intent (was hardcoded `2`) |
| `rag.unknown-intent-depth` | Graph traversal depth for the `UNKNOWN` fallback intent (was hardcoded `2`) |
| `rag.conceptual-query-count` | Vector-search query variations for `CONCEPTUAL` intent (was hardcoded `2`) |
| `rag.business-logic-query-count` | Vector-search query variations for `BUSINESS_LOGIC` intent (was hardcoded `3`) |
| `rag.debugging-query-count` | Vector-search query variations for `DEBUGGING` intent (was hardcoded `3`) |
| `rag.architecture-query-count` | Vector-search query variations for `ARCHITECTURE` intent (was hardcoded `3`) |
| `rag.unknown-intent-query-count` | Vector-search query variations for the `UNKNOWN` fallback intent (was hardcoded `2`) |
| `rag.retrieval-debug-enabled` | Globally attach retrieval provenance/debug info to every response (see §3.2.1); can also be requested per-call via `debug=true` |

### Code intelligence (`codeintel.retrieval.*`)
`top-k`, `max-depth`, `max-results`, `max-lines`, `timeout-ms`, `enable-caching`, `cache-ttl-seconds` — tune the
size/depth/timeout of graph traversals and result caching for `CodeIntelligenceMcpTool` calls.

### Vector store profile
`spring.profiles.active=${VECTOR_STORE_PROFILE:qdrant}` switches between:
- **`application-qdrant.properties`** — Qdrant connection settings (default, `localhost:6334`).
- **`application-weaviate.properties`** — Weaviate connection settings (`localhost:8080`).

Set `VECTOR_STORE_PROFILE=weaviate` to switch backends without code changes.

### Embeddings
`spring.ai.ollama.base-url`, `spring.ai.ollama.embedding.options.model` — local Ollama instance and embedding
model (`nomic-embed-text`).

### Graph database
`spring.neo4j.uri`, `spring.neo4j.authentication.username/password`, `spring.data.neo4j.database`.

### Ingestion state (MySQL)
`spring.datasource.url/username/password/driver-class-name` — connection to the MySQL instance backing
`IngestionStateStore` (auto-creates `spring_ai_mcp` database).

### Atlassian / Bitbucket integrations
`bitbucket.base-url/username/app-password/workspace`, `jira.base-url/username/api-token`,
`confluence.base-url/username/api-token/default-space` — credentials/config for the respective tool services
(via env vars: `BITBUCKET_USERNAME`, `BITBUCKET_APP_PASSWORD`, `JIRA_USERNAME`, `JIRA_API_TOKEN`,
`CONFLUENCE_USERNAME`, `CONFLUENCE_API_TOKEN`).

---

## 7. Data Model / Storage

- **Neo4j (graph)** — nodes: `Class` (`JavaClass`), `Method` (`JavaMethod`), `Field` (`JavaField`), `Package`
  (`JavaPackage`), `Module`, `Repository` (`CodeRepository`). Relationships: `CALLS`, `USES`, `IMPLEMENTS`,
  `EXTENDS`, `TESTED_BY`, plus containment edges (class→method, package→class, module→package).
  IDs follow the pattern `class:<repo>:<fqcn>`, `method:<repo>:<fqcn>.<method>(<params>)`.
- **Qdrant / Weaviate (vector store)** — chunked `Document`s with embeddings and metadata payload (file path,
  repository, class/method name, chunk type) used for semantic similarity search.
- **MySQL** — `IngestionStateStore` table(s) tracking a content hash per ingested file/chunk, enabling
  incremental re-ingestion.

---

## 8. Running Locally

### Prerequisites (see `docker/docker-compose.yml`)
| Service | Port | Purpose |
|---|---|---|
| Ollama | 11434 | Local embedding model (`nomic-embed-text`) |
| Qdrant | 6333/6334 | Default vector store |
| Weaviate | 8080/50051 | Alternate vector store |
| Neo4j | 7474 (HTTP) / 7687 (Bolt) | Graph database (`neo4j` / `password123`, APOC enabled) |
| TEI reranker | 8081 | Optional cross-encoder reranking (`BAAI/bge-reranker-base`) |
| MySQL | 3306 | Ingestion state tracking (not in compose file — run separately or point at existing instance) |

```bash
cd docker
docker compose up -d          # ollama, qdrant/weaviate, neo4j, tei_reranker
docker exec -it ollama ollama pull nomic-embed-text
```

### Environment variables
```
BITBUCKET_USERNAME, BITBUCKET_APP_PASSWORD
JIRA_USERNAME, JIRA_API_TOKEN
CONFLUENCE_USERNAME, CONFLUENCE_API_TOKEN
VECTOR_STORE_PROFILE=qdrant   # or weaviate
MYSQL_HOST, MYSQL_PORT, MYSQL_DATABASE, MYSQL_USERNAME, MYSQL_PASSWORD
```

### Build & run
```bash
./mvnw clean package -DskipTests
./mvnw spring-boot:run
```

The app runs as an MCP **stdio** server (`spring.main.web-application-type=none`) — attach it to an MCP-capable
client (Claude Desktop, Copilot CLI, etc.) rather than hitting it over HTTP.

---

## 9. Qdrant Deep Dive & Glossary (for newcomers)

This section explains **what a vector database is, what Qdrant specifically is, exactly what gets stored in it
by this project, and how a search request travels through it end-to-end** — written for someone who has never
worked with vector search before.

### 9.1 What is a "vector database" and why do we need one?

A traditional database (MySQL, Neo4j) finds rows/nodes by **exact or structural match** — "find the method named
`processOrder`". That's great when you know exactly what you're looking for. But a question like *"how is FD
interest calculated?"* has no exact keyword to match against every relevant line of code.

To answer fuzzy, meaning-based questions, each piece of text (a method, a class, a paragraph of documentation)
is converted into a **vector embedding** — a fixed-length list of floating-point numbers (768 numbers/dimensions
in this project) produced by an embedding model such that texts with **similar meaning end up as vectors that
are numerically close together** in that 768-dimensional space. A **vector database** is a specialized store
built to hold millions of these vectors and answer, extremely fast: *"of everything you're holding, which N
vectors are closest to this query vector?"* — this operation is called **similarity search** or **nearest-neighbor
search**. **Qdrant** is the vector database this project uses by default (Weaviate is a swappable alternative,
see §6).

### 9.2 Qdrant core vocabulary

| Term | Meaning in Qdrant |
|---|---|
| **Collection** | A named container of vectors, analogous to a SQL table. This project uses one collection, named `spring-ai-mcp` (`spring.ai.vectorstore.qdrant.collection-name` in `application-qdrant.properties`). |
| **Point** | A single record inside a collection: one **ID**, one **vector**, and one **payload**. One point = one chunk of code/text (see §2.2–2.3 for how chunks are produced). This project currently holds `14,323` points (see `qudrant_config.json`, `points_count`). |
| **Vector** | The embedding itself — an array of `768` floats (`config.params.vectors.size` in `qudrant_config.json`) produced by the Ollama `nomic-embed-text` model. This is the thing similarity search compares. |
| **Payload** | The metadata attached to a point (everything *except* the vector) — stored as key/value pairs (repo, file path, class name, signature, etc. — see §9.3). The payload is what lets you *filter* results ("only in repo X") in addition to *ranking* them by similarity. |
| **Distance metric** | The mathematical formula used to compare two vectors and produce a similarity/distance score. `qudrant_config.json → config.params.vectors.distance = "Cosine"` — this project uses **cosine similarity** (the angle between two vectors; ignores magnitude, only cares about direction — well-suited to text embeddings). Score ranges roughly 0 (unrelated) to 1 (identical meaning). |
| **HNSW index** (`hnsw_config`) | **H**ierarchical **N**avigable **S**mall **W**orld graph — the data structure Qdrant builds over the vectors so it doesn't have to compare the query against *every* point (which would be slow at scale); it instead does an approximate but very fast graph walk to find the closest points. Key tuning knobs (from `qudrant_config.json`): `m: 16` (how many graph edges per node — higher = more accurate but more memory), `ef_construct: 100` (search breadth used while *building* the index — higher = better index quality, slower build), `full_scan_threshold: 10000` (below this many points, Qdrant just brute-force-scans instead of using the graph, since it's cheap enough at small scale). |
| **`indexed_vectors_count`** | How many vectors currently have an HNSW index entry. In `qudrant_config.json` this shows `0` — meaning `full_scan_threshold` hasn't been crossed *per-segment* yet, or indexing is still catching up; Qdrant transparently falls back to exact brute-force scan in the meantime, so search correctness is unaffected, only speed. |
| **Segment** (`segments_count`) | Qdrant splits a collection's points into multiple internal storage segments (here: `5`) for parallelism and easier background maintenance (merging, compaction) — invisible to callers, purely an internal storage detail. |
| **Optimizer** (`optimizer_config`) | Background process that merges/cleans segments. `deleted_threshold: 0.2` — trigger a cleanup once 20% of a segment's points are soft-deleted; `vacuum_min_vector_number: 1000` / `indexing_threshold: 10000` — minimum sizes before those maintenance actions kick in; `flush_interval_sec: 5` — how often in-memory state is flushed to disk. |
| **WAL** (`wal_config`, Write-Ahead Log) | Every write (upsert/delete) is first appended to a log on disk before being applied, so Qdrant can recover in-flight writes after a crash/restart without data loss. `wal_capacity_mb: 32` is the size of each WAL segment. |
| **`on_disk_payload`** | `true` here — payload (metadata) is kept on disk rather than fully in RAM, trading a little latency for much lower memory usage; sensible since payloads are only read for matched results, not scanned during the vector comparison itself. |
| **Quantization** (`quantization_config`) | An optional technique to compress vectors (e.g., float32 → int8) to save memory/speed at a small accuracy cost. `null` here — **not enabled** in this deployment; all 768 dimensions are stored at full precision. |
| **`shard_number` / `replication_factor` / `write_consistency_factor`** | All `1` here — this is a **single-node, non-replicated** Qdrant instance (no horizontal sharding across nodes, no replica copies). Fine for local/dev use; a production, high-availability deployment would raise these. |
| **gRPC port `6334` / REST port `6333`** | Qdrant exposes both a gRPC API (used here — see `spring.ai.vectorstore.qdrant.port=6334` and the raw `QdrantClient` used for `retrieveAsync`/`scrollAsync` in `VectorStoreDocumentIndexer`/`QdrantStatsUtil`) and a REST API (port `6333`, used e.g. to fetch `qudrant_config.json` via `GET /collections/spring-ai-mcp`). |
| **`initialize-schema=true`** | Spring AI's Qdrant starter auto-creates the collection (with the configured vector size/distance) on application startup if it doesn't already exist — no manual `docker exec` / curl setup required. |

### 9.3 Every metadata field stored on a vector point (the "payload")

Every point's payload is a flat map of these keys (defined once, centrally, in `RagMetadataKeys`, so the writer
side — `DocumentIngestionService`/`VectorStoreDocumentIndexer` — and the reader side — `QdrantCodeSearchService`/
`VectorStoreDocumentRetriever` — can never drift out of sync on field names). Not every field is populated for
every chunk (e.g. HTTP fields only apply to REST controller methods) — Spring AI simply omits absent keys.

| Payload key | Populated by | What it's for |
|---|---|---|
| `source` | Spring AI (built-in) | Original document source identifier (e.g. file path) set by the document reader. |
| `language` | Parser | Programming/document language (`java`, etc.). |
| `package` | `JavaDocumentParser` | Java package name — lets you filter/scope a search to one package. |
| `file_name` | Parser | Just the file name (no path). |
| `repo` | Ingestion caller | Repository name — the top-level scope for almost every filtered search (`repo`/`module`/`branch` triple). |
| `module` | Ingestion caller | Sub-module within a multi-module repo (Maven/Gradle module). |
| `service` | Ingestion caller | Logical service name, when a repo hosts multiple services. |
| `branch` | Ingestion caller | Git branch the content was ingested from — supports branch-scoped search. |
| `file_path` | Parser | Path to the source file, relative to the repo root. |
| `commit_sha` | Ingestion caller | Git commit the file was ingested at, for traceability. |
| `class_name` | `JavaDocumentParser` | Fully/simple class name the chunk belongs to. |
| `member_name` | `JavaDocumentParser` | Method/field/class member name — the finest-grained filter (used by `searchWithContext`). |
| `member_type` | `JavaDocumentParser` | `class` / `interface` / `enum` / `record` / `method` / `constructor` — used to classify results into `CodeSymbol.SymbolType`. |
| `signature` | `JavaDocumentParser` | Full method/constructor signature (params + return type). |
| `chunk_index` / `total_chunks` | `DocumentIngestionService.prepareChunks` | This chunk's position and sibling count within its parent unit — lets you reassemble a method/class split across multiple chunks in order. |
| `parent_document_id` | `DocumentIngestionService.prepareChunks` | SHA-256 hash grouping all chunks from the same class/method/constructor (see §2.3) — used to fetch a full multi-chunk unit as a set. |
| `file_hash` / `chunk_hash` | `DocumentIngestionService.prepareChunks` | SHA-256 of the whole file / just this chunk's text — powers incremental re-ingestion (`IngestionStateStore` skips unchanged content). |
| `embedding_model` | Ingestion caller | Records which embedding model produced this vector (`nomic-embed-text`) — guards against silently mixing incompatible embedding spaces if the model is ever changed. |
| `rerank_score` | `TeiDocumentReranker`/`ConditionalRerankService` (post-search) | Cross-encoder relevance score written back onto the result *after* search — not present at ingestion time, only after a rerank pass (§3.5). |
| `start_line` / `end_line` | `JavaDocumentParser` | Line range in the source file the chunk came from — used by `SourceCodeContextService` to re-fetch exact source with surrounding context. |
| `document_type` / `chunk_type` | Parser/Chunker | Broad classification of the document/chunk (e.g. code vs. generic doc) for filtering/formatting. |
| `http_method` / `endpoint_path` / `request_headers` / `request_params` / `request_body_type` / `response_type` | `JavaDocumentParser` | Only for REST controller handler methods — HTTP verb, route, and request/response shape, so "what REST endpoints exist" queries can be answered directly from vector payloads. |
| `imports` / `annotations` | `JavaDocumentParser` | Import list and annotations (e.g. `@Service`, `@Transactional`) present on the class/method — used for Spring-stereotype-aware filtering/formatting. |
| `injected_dependencies` / `called_methods` / `called_classes` | `JavaDocumentParser` | Lightweight, chunk-local record of what this method injects/calls — a cheap semantic hint that complements (but doesn't replace) the exact `CALLS`/`INJECTS` edges recorded in Neo4j. |

The point **ID** itself is *not* a payload field — it's the deterministic UUID described in §2.3
(`repo|branch|file_path|member_name|chunk_index`), which is what makes re-ingesting an unchanged file overwrite
the same point instead of creating a duplicate.

### 9.4 End-to-end walkthrough: from `ingest` call to search result

**Ingestion side (write path):**
1. A caller invokes `ingest`/`ingestRepository` (MCP tool) with a file or directory path.
2. `RoutingDocumentParser` picks `JavaDocumentParser` (AST-based) or `TikaDocumentParser` (generic files) and
   emits one Spring AI `Document` per class/method/field (or per file/section for generic docs), each carrying
   the rich metadata described in §9.3.
3. `TokenDocumentChunker` splits any oversized `Document` into token-bounded pieces using `TokenTextSplitter`.
4. `DocumentIngestionService.prepareChunks(...)` stamps every resulting chunk with `chunk_index`, `total_chunks`,
   `parent_document_id`, `file_hash`, `chunk_hash`, and a deterministic point ID.
5. `IngestionStateStore` (MySQL) is consulted — if this exact chunk's hash was already ingested, it's skipped.
6. For everything new/changed, `VectorStoreDocumentIndexer.index(documents)` calls `vectorStore.add(documents)`.
   Spring AI's `QdrantVectorStore` then: (a) calls the Ollama `nomic-embed-text` `EmbeddingModel` on each chunk's
   **text only** to get a 768-float vector, (b) converts the chunk's metadata map into a Qdrant payload, and
   (c) issues an **upsert** (insert-or-overwrite) gRPC call to Qdrant with `{id, vector, payload}` — this is the
   **point** landing in the `spring-ai-mcp` collection.
7. In parallel, `Neo4jIngestionService` writes the same unit as structural graph nodes/edges (§4) — the vector
   point and the graph node share `class_name`/`member_name`/`signature` values so results can be cross-referenced
   later, even though their IDs are computed differently (deterministic UUID vs. `class:<repo>:<fqcn>` key).

**Search side (read path)**, triggered by an MCP tool call like `retrieveCodeContext`/`searchCode`:
1. `QueryIntentClassifier` looks at the natural-language question and assigns a `QueryIntent` (§3.1).
2. `AdaptiveRetrievalRouter` decides, based on that intent, how many reworded query variations to try (1–3) and
   whether to hit Qdrant, Neo4j, or both.
3. For each query variation, `VectorStoreDocumentRetriever`/`QdrantCodeSearchService`:
   a. Embeds the query text with the **same** `nomic-embed-text` model used at ingestion (embeddings from
      different models live in incompatible spaces and cannot be compared).
   b. Builds a Spring AI `SearchRequest` with `topK` and `similarityThreshold` (default `0.7`).
   c. Translates any caller-supplied filters (`repo`, `branch`, `class_name`, `member_name`, …) into a Qdrant
      **payload filter** via `FilterExpressionBuilder` — this is applied by Qdrant itself as a pre-filter, so
      only points matching the filter are candidates for the similarity comparison at all.
   d. Qdrant compares the query vector against candidate point vectors using **cosine similarity** (walking the
      HNSW graph for speed, or brute-force if below `full_scan_threshold`), returning the closest points above
      `similarityThreshold`, each with its payload and a similarity score.
4. If `includeRelatedContext=true`, a second broader search (same query, `member_name`/`member_type` filters
   dropped) pulls in sibling methods of the same class, and results are merged.
5. All variations' results are deduplicated by point ID (or text hash fallback), preserving first-seen order.
6. `documentReranker`/`ConditionalRerankService` optionally re-scores the deduplicated list with the TEI
   cross-encoder (§3.5), writing a `rerank_score` back onto each result.
7. `RankingService` merges vector results with any Neo4j graph results, `RetrievalConfidenceScorer` assigns a
   confidence score, `SourceCodeContextService` optionally re-reads the exact source lines from disk using
   `file_path`/`start_line`/`end_line`, and `ContextBuilder`/`CompactEvidenceFormatter` produce the final,
   token-efficient answer returned to the LLM client.

### 9.5 Glossary

| Term | Definition |
|---|---|
| **Embedding** | A vector of floating-point numbers representing the meaning of a piece of text, produced by an embedding model. |
| **Embedding model** | The neural network that converts text → vector. Here: `nomic-embed-text`, served locally by Ollama. |
| **Chunk** | A bounded piece of a larger document (one method, one class header, one token-window slice) — the actual unit that gets embedded and stored as a point. |
| **Similarity search / nearest-neighbor search** | Finding the K stored vectors closest to a query vector. |
| **topK** | How many top results to return from a similarity search. |
| **Similarity threshold** | Minimum similarity score a result must reach to be kept, regardless of whether it's in the top K. |
| **Cosine similarity** | A distance metric measuring the angle between two vectors (1 = identical direction/meaning, 0 = unrelated, can go negative for opposite meaning). |
| **Payload / metadata filter** | A structured (non-vector) condition — e.g. `repo = "axis-fd"` — applied alongside vector similarity to scope results. |
| **Upsert** | "Insert or update" — writing a point with a given ID either creates it (if new) or overwrites it in place (if that ID already exists). |
| **Point ID** | The unique identifier of a vector-store record; in this project, a deterministic UUID so re-ingestion overwrites rather than duplicates. |
| **HNSW** | Hierarchical Navigable Small World graph — the approximate-nearest-neighbor index structure Qdrant builds for fast search at scale. |
| **Reranking / cross-encoder** | A second, more expensive but more accurate relevance-scoring pass (here, TEI's `BAAI/bge-reranker-base`) applied to a small candidate set *after* the cheap vector search narrows it down. |
| **Intent classification** | Categorizing a natural-language query (e.g. `EXACT_SYMBOL`, `CONCEPTUAL`) to decide the cheapest retrieval strategy that will still answer it correctly. |
| **Hybrid retrieval** | Combining vector search (Qdrant) and graph traversal (Neo4j) results for one answer. |
| **Collection** (Qdrant) | The named grouping of points — this project's is `spring-ai-mcp`. |
| **Dual-write** | Writing the same logical unit of data to two different stores (vector store + graph) in one ingestion pass, kept consistent via `HybridIngestionService`. |

---

## 10. Ollama & `nomic-embed-text`: Embeddings Deep Dive

Qdrant (§9) only *stores and searches* vectors — it has no idea how to turn text into a vector in the first
place. That job belongs entirely to **Ollama** running the **`nomic-embed-text`** model. This section explains
what each of those pieces is, why it matters, and exactly where it's wired into the code.

### 10.1 What is Ollama?

**Ollama** is a lightweight local server that downloads and runs open-weight machine-learning models (LLMs and
embedding models) on your own machine/VM — no external API key, no data leaving your network. It exposes a
simple HTTP API (default `http://localhost:11434`) that other applications call to get completions or
embeddings. In this project, Ollama's *only* job is to run the embedding model — no chat/completion model is
configured here, because the LLM reasoning itself is done by whatever MCP client (Claude, Copilot, etc.) is
talking to this server; this server just supplies it *context*.

Integration point: `spring.ai.ollama.base-url=http://localhost:11434` in `application.properties`, and the
`spring-ai-starter-model-ollama` dependency in `pom.xml`, which auto-configures a Spring AI `EmbeddingModel` bean
backed by Ollama — **no custom Java code is written for this**; Spring Boot's auto-configuration wires it up
purely from these properties, and both `QdrantVectorStore`/`WeaviateVectorStore` (via Spring AI's `VectorStore`
abstraction) and any direct embedding calls use that one bean transparently.

### 10.2 What is an embedding model, and what is `nomic-embed-text` specifically?

An **embedding model** is a neural network trained specifically to convert a piece of text into a fixed-size
vector of numbers such that semantically similar texts map to nearby vectors (measured by cosine similarity —
§9.2). It is a *different kind* of model from a chat/completion LLM: it doesn't generate text, it only produces
numbers, and it's typically much smaller/faster to run.

**`nomic-embed-text`** is the specific open-weight embedding model this project uses (via Ollama):
- Produces vectors of **768 dimensions** — this is why `qudrant_config.json → config.params.vectors.size = 768`
  and the Qdrant collection is created with that exact vector size (a vector store's vector size is fixed per
  collection and must match whatever the embedding model outputs, or every write will fail).
- Trained on general English text plus a large amount of code/technical content, making it a reasonable
  general-purpose choice for both source-code chunks and prose documentation (PDF/DOCX/MD) ingested by
  `TikaDocumentParser` (§2.1) — one model serves both content types, avoiding the need to run/maintain two
  separate embedding pipelines.
- Runs entirely locally through Ollama — embedding thousands of code chunks during a full repo ingestion never
  makes an external network call or incurs per-token API cost.

Configuration: `spring.ai.ollama.embedding.options.model=nomic-embed-text` in `application.properties` selects
this model by name; Ollama must have it pulled locally first (`docker exec -it ollama ollama pull
nomic-embed-text`, per §8). `rag.embedding-model` mirrors the same value (`${spring.ai.ollama.embedding.options.model:nomic-embed-text}`)
so the rest of the app (e.g. `RagProperties.embeddingModel()`) can read "which embedding model is active" without
depending on the Spring AI-specific property name — this value is what gets stamped onto every chunk's
`embedding_model` payload field (§9.3).

### 10.3 Why the embedding model choice matters — and where the code depends on it

1. **Vector size lock-in.** Every point in a Qdrant/Weaviate collection must have a vector of the same
   dimensionality. If you ever switch `nomic-embed-text` for a model with a different output size, the existing
   collection becomes incompatible and must be recreated (or a new collection stood up) — you cannot mix vector
   sizes in one collection.
2. **Vector-space consistency between ingest and query.** Two vectors are only meaningfully comparable if they
   were produced by the *same* model — §3.4/§9.4 both call this out: the query text is embedded with the exact
   same `nomic-embed-text` model used at ingestion time. If you silently swapped models, old and new vectors
   would occupy unrelated numeric spaces and similarity scores would become meaningless noise even though the
   API calls would still "succeed."
3. **Change detection safety net.** `DocumentIngestionService` explicitly compares `ragProperties.embeddingModel()`
   against the model recorded for a file the *last* time it was ingested (`sameEmbeddingModel`, `DocumentIngestionService.java:73`).
   If the configured embedding model ever changes, previously-ingested files are treated as **stale and
   re-embedded** even if their file/chunk hash hasn't changed — this guards exactly against the failure mode in
   point 2 above, ensuring the vector store never silently ends up with a mix of old- and new-model vectors for
   the same content.
4. **Traceability.** Because `embedding_model` is stored as payload on every point (§9.3), you can always answer
   "was this specific chunk embedded with the model currently configured?" directly from Qdrant, which is exactly
   what the check in point 3 relies on, and what an operator would inspect when debugging inconsistent search
   quality after a model upgrade.

### 10.4 How it's actually invoked — the request path

There is no hand-written HTTP call to Ollama anywhere in this codebase; Spring AI abstracts it away:

- **Ingestion:** `VectorStoreDocumentIndexer.index(documents)` calls `vectorStore.add(documents)`. Internally,
  Spring AI's `QdrantVectorStore`/`WeaviateVectorStore` implementation calls the auto-configured `EmbeddingModel`
  bean once per document/chunk, sending **only `chunk.getText()`** (never the metadata map) to Ollama's
  `/api/embed` endpoint, and receives back the 768-float vector, which it then upserts alongside that chunk's
  metadata payload.
- **Query:** `VectorStoreDocumentRetriever`/`QdrantCodeSearchService` build a Spring AI `SearchRequest` with the
  raw query string; the same `VectorStore` implementation embeds that query string through the identical
  `EmbeddingModel` bean before asking Qdrant/Weaviate for nearest neighbors (§9.4 step 3a).
- Because both paths go through the **same single Spring-managed `EmbeddingModel` bean**, there is exactly one
  place (`application.properties`) controlling which model/host is used for both writes and reads — there is no
  way for ingestion and query to accidentally use different models unless the property is changed and old data
  is left unmigrated (which is exactly what §10.3 point 3 detects).

### 10.5 Terminology recap

| Term | Meaning |
|---|---|
| **Ollama** | A local model-serving runtime; here it serves only the embedding model, not a chat LLM. |
| **`nomic-embed-text`** | The specific embedding model run by Ollama; converts text → 768-dimension vector. |
| **`EmbeddingModel`** | Spring AI's abstraction interface for "something that turns text into vectors" — auto-configured by `spring-ai-starter-model-ollama` from `spring.ai.ollama.*` properties, with no custom code required. |
| **Dimensionality / vector size** | The fixed number of floats in each embedding (768 here) — must match the vector store collection's configured size exactly. |
| **`embedding_model` metadata field** | Payload value recording which model produced a given point's vector; used to detect/force re-embedding after a model change (§10.3). |
| **Base URL** | The network address of the Ollama server (`spring.ai.ollama.base-url`) that Spring AI calls for every embedding request. |
| **Local/self-hosted inference** | Running the model on infrastructure you control (via Ollama) rather than calling a third-party embedding API — no external network dependency or per-call cost for embeddings. |

---

## 11. Neo4j Graph Database Deep Dive

Qdrant (§9) answers "what text is semantically similar to this?". It cannot answer precise structural questions
like *"what calls this method?"* or *"what breaks if I delete this class?"* — those require walking exact
relationships, which is what a **graph database** is built for. This project uses **Neo4j** for that half of the
system.

### 11.1 What is a graph database, and why not just use SQL/vectors for this?

A **graph database** stores data as **nodes** (entities) and **relationships/edges** (typed, directed connections
between nodes), and is optimized for **traversal** — following chains of relationships many hops deep (e.g.
"every method that transitively calls `authenticate`, up to 3 hops away"). A relational database *can* model
this with foreign keys and recursive joins, but each additional hop means another expensive join; a graph
database walks edges in roughly constant time per hop regardless of how large the overall dataset is, because
each node physically stores pointers to its relationships. **Neo4j** is the specific graph database product used
here, queried with its native query language, **Cypher**.

Integration point: `spring.neo4j.uri=bolt://localhost:7687`, `spring.neo4j.authentication.username/password`,
`spring.data.neo4j.database=neo4j` in `application.properties`, backed by **Spring Data Neo4j** (`spring-boot-starter-data-neo4j`),
which lets Java classes annotated `@Node`/`@Relationship` map directly to graph nodes/edges without hand-written
Cypher for basic CRUD (custom Cypher is still used for complex traversals, e.g. in `JavaMethodRepository`).

### 11.2 Core vocabulary

| Term | Meaning in this project |
|---|---|
| **Node** | A single graph entity. This project has six node types: `JavaClass` (label `Class`), `JavaMethod` (label `Method`), `JavaField` (label `Field`), `JavaPackage`, `Module`, `CodeRepository` — each a `@Node`-annotated Java class managed by Spring Data Neo4j (`org.example.springaimcp.graph.model.*`). |
| **Label** | The "type tag" on a node (e.g. `Class`, `Method`) — set via `@Node("Class")`; used to filter which kind of node a Cypher query targets. |
| **Relationship / edge** | A typed, directed connection between two nodes, e.g. `(:Class)-[:HAS_METHOD]->(:Method)`. Relationship types used here: `HAS_METHOD`, `HAS_FIELD` (containment), `EXTENDS`, `IMPLEMENTS`, `USES`, `INJECTS`, `CALLS`, `ACCEPTS`, `RETURNS`, `TESTED_BY`. |
| **Cypher** | Neo4j's declarative graph query language (SQL's analogue for graphs), e.g. `MATCH (m:Method)-[:CALLS]->(t:Method) WHERE m.methodId = $id RETURN t`. Used directly for the complex traversals behind `findImpact`, `findCallersOfMethod`, `findDependencies`, etc. |
| **Property** | A key/value attribute stored directly on a node or relationship (not a separate node) — e.g. `JavaClass.repository`, `JavaMethod.httpMethod`, `JavaMethod.startLine`. Cheap to read, used for filtering without a traversal. |
| **`classId` / `methodId` / `fieldId`** | The stable, human-readable business keys computed by `GraphNodeIdentity` — `class:<repo>:<fqcn>`, `method:<repo>:<fqcn>.<method>(<paramTypes>)`, `field:<repo>:<fqcn>.<field>`. Unlike Neo4j's internal auto-generated numeric `@Id`, these are deterministic from the source code's identity, so re-ingesting the same class/method always resolves to (updates) the same node instead of creating a duplicate — the graph-side equivalent of the deterministic vector point ID in §2.3/§9.3. |
| **`resolutionKey`** | `GraphNodeIdentity.methodResolutionKey(...)` — `<className>.<methodName>(<paramTypes>)` without the repository prefix; used to match a method *by signature* across repositories during reconciliation/cross-repo linking (§2.6/§2.7), since the same method name can exist in many repos. |
| **`nodeStatus`: `STUB` vs. `REAL`** | When a method/class is referenced (e.g. called) before its own file has been ingested, `Neo4jIngestionService` creates a **placeholder** node marked `STUB` so the relationship isn't lost. Once the real file is ingested (or `RelationshipReconciliationService` runs — §2.6), the node is "graduated" to `REAL` and enriched with full properties. This is what lets partial/incremental, multi-repo ingestion still build a mostly-complete graph instead of silently dropping unresolved references. |
| **`MERGE` (Cypher)** | "Create if it doesn't exist, otherwise match the existing node/relationship" — the write mode used throughout ingestion/reconciliation/cross-repo linking so re-running these steps is **idempotent** (safe to repeat without creating duplicate nodes/edges). |
| **Containment edges** | `HAS_METHOD`, `HAS_FIELD` (class→member), plus package→class and module→package containment — model the file/package/module hierarchy so architecture-level queries (`getArchitectureContext`) can walk "what's in this package" without string-matching file paths. |
| **`CALLS`** | Directed edge `(:Method)-[:CALLS]->(:Method)` recorded when AST analysis finds one method invoking another — carries call-site metadata (line number, argument types) — the backbone of `findCallersOfMethod`/`findCalledByMethod`/`findImpact`. |
| **`USES` / `INJECTS`** | `USES` — a class references another class's type (field type, local variable, cast, etc.) without necessarily being a Spring-managed dependency. `INJECTS` — specifically a Spring dependency-injection relationship (`@Autowired`, constructor/setter injection) detected by `CrossRepoLinkerService`/`Neo4jIngestionService`. |
| **`EXTENDS` / `IMPLEMENTS`** | Standard Java inheritance/interface relationships between `JavaClass` nodes — power `findImplementations` and class-hierarchy-aware impact analysis. |
| **`ACCEPTS` / `RETURNS`** | Edges from a `JavaMethod` to the `JavaClass` nodes representing its parameter types / return type — lets a traversal answer "what methods accept/return type X" structurally. |
| **`TESTED_BY`** | Edge from a production method to the test method(s) that exercise it, created by `establishTestRelationships` (naming convention + call analysis) — not stored as an eager Java relationship field to avoid load-time blowup; queried via a dedicated repository method instead. |
| **Spring stereotype/annotation properties** | `springAnnotations` (on `JavaClass`/`JavaMethod`) records annotations like `@RestController`, `@Service`, `@GetMapping` as plain string properties — lets `findAllControllers`/`findAllServices`/`findAllApiEndpoints` filter by annotation without re-parsing source. |
| **Bolt protocol** | Neo4j's native binary wire protocol (`bolt://localhost:7687`) used for driver connections — faster than the HTTP API also exposed on port `7474` (used for the Neo4j Browser UI). |
| **APOC** | "Awesome Procedures On Cypher" — a plugin bundled in the Neo4j Docker image (`docker-compose.yml`) adding extra utility procedures beyond core Cypher; enabled here (`NEO4J_PLUGINS: '["apoc"]'`) for more advanced graph operations if/when needed. |

### 11.3 How the graph is built (write path)

Every file processed by `HybridIngestionService` is written to Neo4j by `Neo4jIngestionService` **in parallel**
with the vector-store write (§2.5), using the same parsed AST:
1. Ensure `CodeRepository` → `Module` → `JavaPackage` containment nodes exist (`MERGE`, so repeated ingestion is
   safe).
2. Upsert (`MERGE` by `classId`) one `JavaClass` node per top-level type, with its properties (`fullyQualifiedName`,
   `filePath`, `startLine`/`endLine`, `springAnnotations`, etc.) and `HAS_METHOD`/`HAS_FIELD` edges to its members.
3. Upsert one `JavaMethod`/`JavaField` node per member (`MERGE` by `methodId`/`fieldId`).
4. Record `EXTENDS`/`IMPLEMENTS`/`USES`/`INJECTS`/`CALLS` relationships discovered by walking the AST — when the
   target of a relationship (e.g. a called method in another, not-yet-ingested class) doesn't exist yet, a `STUB`
   node is created so the edge isn't lost, to be resolved later (§2.6/§11.2).
5. `RelationshipReconciliationService` then resolves any `STUB` nodes it can, and `CrossRepoLinkerService` adds
   any `INJECTS`/`CALLS` edges that span repository boundaries (§2.6/§2.7) — both run automatically at the end of
   `ingestRepository(...)`.

### 11.4 How the graph is queried (read path)

`CodeGraphService`/`Neo4jSymbolService` issue Cypher queries (via Spring Data Neo4j repositories and custom
`@Query` methods) for every graph-backed MCP tool:
- **Direct lookup** (`EXACT_SYMBOL`/`FILE_LOOKUP` intents, §3.1) — `MATCH` by `classId`/`methodId`/`filePath`, no
  vector search at all; the fastest possible path since it's an index lookup, not a similarity computation.
- **Call graph** (`CALL_GRAPH` intent) — `findCallersOfMethod`/`findCalledByMethod` walk one hop of `CALLS` in
  the reverse/forward direction.
- **Impact analysis** (`IMPACT_ANALYSIS` intent) — `findImpact` walks `CALLS`/dependency edges multiple hops
  (configurable `depth`, `codeintel.retrieval.max-depth`) to find every transitively-affected caller, optionally
  including affected API endpoints/tests.
- **Dependency traversal** (`DEPENDENCY` intent) — `findDependencies` walks `CALLS`/`USES`/`IMPLEMENTS`/`EXTENDS`
  in either direction, one relationship-type filter at a time or combined.
- **Architecture overview** (`ARCHITECTURE`/`BUSINESS_LOGIC`/`DEBUGGING` intents) — combined with Qdrant results
  in `AdaptiveRetrievalRouter`'s hybrid mode (§3.2/§9.4), since these questions need both "what does the code
  say" (vector) and "how is it structurally wired together" (graph).

### 11.5 Where the graph and vector store cross-reference each other

Neither store knows about the other's IDs directly — they're linked by **shared metadata values**, not foreign
keys: a vector chunk's `class_name`/`member_name`/`signature` payload fields (§9.3) match a `JavaClass`/`JavaMethod`
node's `simpleName`/`name`/`signature` properties. `validateSync` (`CodeGraphMcpTool`) exploits this to detect
drift — files present in one store but not the other. `getArchitectureContext` and `BUSINESS_LOGIC`/`DEBUGGING`
query intents exploit it the other way: run a Qdrant semantic search to find *candidate* relevant methods, then
use their `class_name`/`member_name` to jump into Neo4j and pull the exact structural context (callers, callees,
implementations) around them.

---

## 12. TEI Reranker (Cross-Encoder) Deep Dive

After Qdrant returns a ranked list of candidates (§9.4) and/or Neo4j returns graph matches, one more optional
step can reorder them for better precision: **reranking** via **TEI** running **`BAAI/bge-reranker-base`**.

### 12.1 Why rerank at all if Qdrant already ranks by similarity?

The embedding model (§10) that Qdrant uses is a **bi-encoder**: it embeds the query and each document
**independently**, with no awareness of each other, then compares the two vectors after the fact with a cheap
formula (cosine similarity). This scales to millions of documents (each document is embedded once, at ingestion
time, and reused for every future query) but is a coarser approximation of "is this document actually relevant
to this query" — it can't reason about how the query and document interact.

A **cross-encoder** solves that by feeding the **query and one candidate document together** into a single
transformer model, letting it attend across both texts jointly to produce one direct relevance score. This is
far more accurate, but also far more expensive — it must be run once **per query/document pair**, so it's never
run against the whole collection, only a small shortlist Qdrant/Neo4j already narrowed things down to. This is
the classic **two-stage retrieval** pattern: cheap bi-encoder retrieval for recall at scale, then expensive
cross-encoder reranking for precision on the shortlist.

### 12.2 What is TEI and `BAAI/bge-reranker-base`?

**TEI (Text Embeddings Inference)** is Hugging Face's inference server for running embedding *and* reranking
models efficiently (batching, GPU/CPU optimization) behind a simple HTTP API. Here it's run purely as a
**reranking** service (embeddings come from Ollama instead, §10) via Docker (`docker-compose.yml`, service
`tei_reranker`, container port `80` mapped to host `8081`).

**`BAAI/bge-reranker-base`** is the specific cross-encoder model TEI serves — given a query and a list of
candidate texts, it returns a relevance score per candidate. It's a separate, purpose-built model from
`nomic-embed-text` (§10.2): the embedding model answers "what vector represents this text", the reranker answers
"how relevant is this specific text to this specific query", and the two are never interchangeable.

Integration point: `rag.reranker-base-url=http://localhost:8081` (`application.properties`) — `TeiDocumentReranker`
posts to `${rag.reranker-base-url}/rerank`.

### 12.3 Core vocabulary

| Term | Meaning |
|---|---|
| **Bi-encoder** | Embeds query and document separately, compares vectors afterward — what Qdrant + `nomic-embed-text` do (fast, scales to millions, less precise). |
| **Cross-encoder** | Feeds query + document together into one model for a single joint relevance score — what TEI + `bge-reranker-base` do (slow per-pair, very precise, only usable on a small shortlist). |
| **Two-stage retrieval** | Retrieve a broad candidate set cheaply (bi-encoder/vector search), then rerank a small shortlist expensively (cross-encoder) — the overall pattern implemented across §9 (retrieval) and this section (reranking). |
| **`/rerank` endpoint** | TEI's HTTP API: POST `{query, texts[]}` → returns `[{index, score}]`, one score per input text, referencing its position in the input list (`TeiDocumentReranker.RerankRequest`/`Rank`). |
| **Raw score** | TEI's native output for `bge-reranker-base` — an unbounded real number (commonly roughly -10 to +10), *not* a 0–1 probability. |
| **Sigmoid normalization** | `TeiDocumentReranker.normalizeScore` applies `1 / (1 + e^-x)` to squash TEI's raw score into a `[0, 1]` range — done so the resulting `rerank_score` is comparable across symbols and doesn't overwhelm other heuristic scores computed elsewhere in the pipeline (e.g. `RetrievalConfidenceScorer`) that already assume a 0–1 scale. |
| **`rerank_score`** | The normalized score written back into each result's metadata (§9.3) after reranking — absent until a rerank pass actually runs (never set at ingestion time). |
| **Conditional reranking** | `ConditionalRerankService.shouldSkipReranking(...)` skips the TEI call entirely when it's unlikely to help: fewer than `rag.reranker-min-documents` (default `3`) candidates, or the query intent is in `rag.reranker-skip-for-intents` (default `EXACT_SYMBOL, CALL_GRAPH` — graph lookups are already exact matches, so reordering by textual relevance would only hurt). This is what avoids paying cross-encoder latency on every single query regardless of whether it can change the outcome. |
| **Graceful degradation** | If the TEI call fails (service down, timeout) or returns a malformed/mismatched response, `TeiDocumentReranker` logs a warning and returns the **original, un-reranked** document order rather than failing the whole request — reranking is treated as a pure quality enhancement, never a hard dependency. |
| **`rag.reranker-enabled`** | Global on/off switch (`application.properties`) — when `false`, `TeiDocumentReranker.rerank(...)` is a no-op regardless of document count/intent. |

### 12.4 How it fits into the pipeline end-to-end

1. `AdaptiveRetrievalRouter`/`QdrantCodeSearchService` produce a deduplicated candidate list from Qdrant (and
   optionally Neo4j) — see §9.4 steps 3–5.
2. `ConditionalRerankService.conditionalRerank(query, documents, intent)` (code-intel path) or
   `DocumentReranker.rerank(...)` directly (generic RAG path) is called with that candidate list.
3. It first checks the skip conditions (§12.3) — if either applies, the candidate list is returned untouched, no
   network call is made.
4. Otherwise, `TeiDocumentReranker` extracts just the **text** (`Document::getText`, never the metadata — same
   principle as embedding, §10.4) of every candidate and POSTs `{query, texts}` to TEI's `/rerank`.
5. TEI's `bge-reranker-base` cross-encoder scores every (query, text) pair jointly and returns one `{index,
   score}` per candidate.
6. Each result is re-ordered/re-scored: the raw score is sigmoid-normalized and written into that document's
   `rerank_score` metadata field, and the final list order follows TEI's ranking (not the original vector
   similarity order).
7. `RankingService`/`RetrievalConfidenceScorer` consume this reordered, `rerank_score`-annotated list downstream,
   alongside any Neo4j graph results, to assemble the final response via `ContextBuilder`/`CompactEvidenceFormatter`
   (§3.6).
