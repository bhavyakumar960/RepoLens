# Method-level `CALLS` lifecycle

## Overview

The code-ingestion pipeline now persists method-level `CALLS` edges with deterministic business keys and reconciliation support across repositories ingested in any order.

## Flow

1. **Parse Java AST**
   - `Neo4jIngestionService` parses each class with JavaParser + symbol solver.
   - Method nodes are created first so self-calls, recursion, overloads, and circular references resolve deterministically.

2. **Create/merge nodes**
   - `Class.classId = class:<repo>:<fqcn>`
   - `Method.methodId = method:<repo>:<fqcn>.<method>(<paramTypes>)`
   - `resolutionKey = <fqcn>.<method>(<paramTypes>)`
   - Real nodes are marked `nodeStatus=REAL`.

3. **Resolve each invocation**
   - Resolution priority:
     1. same class
     2. superclass / `super.foo()`
     3. field call
     4. constructor-injected field
     5. interface declared type
     6. static call
     7. exact cross-repository match
     8. stub creation
     9. unresolved/excluded library call

4. **Persist `CALLS`**
   - Outgoing `CALLS` from a source method are rebuilt idempotently on re-ingestion.
   - Multiple invocations of the same source→target pair merge into one edge with:
     - `invocationName`
     - `argumentCount`
     - `line`
     - `column`
     - `resolutionStatus`
     - `resolutionType`
     - `invocationCount`
     - `callSites`
     - `declaredType`

5. **Create stubs when needed**
   - Missing targets create `STUB` `Class` / `Method` nodes via `MERGE`.
   - Stubs reuse the same `resolutionKey` as real methods for later reconciliation.
   - External packages (`java.*`, `javax.*`, `jakarta.*`, `org.springframework.*`, etc.) are excluded from stub creation.

6. **Reconcile after ingestion**
   - `RelationshipReconciliationService.reconcileRelationships(repo)` performs normal repository reconciliation and then runs global `CALLS` reconciliation.
   - `reconcileAllRelationships()` can be run after all repositories are ingested.
   - Stub call targets are repointed to the real method when there is exactly one match.
   - Interface-declared calls are resolved to a single implementation or marked `AMBIGUOUS`.

## Statistics

Reconciliation now reports:

- total calls discovered
- resolved calls
- stub calls
- reconciled calls
- remaining stubs
- ambiguous calls
- unresolved calls
- cross-repository calls
- self calls

## Entry points

- `HybridIngestionService` runs reconciliation after repository ingestion
- `RelationshipReconciliationRunner`
- `ReconciliationUsageExample`
- `CodeGraphMcpTool.reconcileGraph(...)`
