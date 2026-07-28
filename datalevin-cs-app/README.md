# HR Triad — Datalevin (client/server)

The **same toy HR app** as `datalevin-app`, but talking to a **networked
Datalevin server** over `dtlv://` instead of an embedded LMDB file. Its reason
for existing is to answer two questions:

- Does [syncopate](../../syncopate) (our ragtime adaptor) work when Datalevin
  runs client/server, keeping its first promise — *applied-migration state lives
  in a dedicated key-value DBI on the same environment your connection holds,
  never as datoms*?  **Yes.** (see *Findings*.)
- What does client/server cost versus embedded Datalevin and versus Datomic?
  (the `../bench` numbers.)

Everything downstream of the connection — schema, seed, queries, and syncopate's
migrations — is byte-for-byte the embedded app. The **only** source differences
are forced by one Datalevin fact (below): the connection "path" is a `dtlv://`
URI, and because of it `:hr.db/migrated` boots in two phases — the first boot
applies the creation migration and the app comes up; after you **bounce both the
server and the app**, the second boot seeds and finishes. The seed itself is the
same legacy-shape seed as the embedded app.

## The one thing that's different: the fulltext engine

Datalevin builds a database's **fulltext search engine on the server, once, when
the server opens the environment**, from the schema present at that moment. A
migration that *adds* a fulltext attribute (here, `001` adds `:review/text
{:db/fulltext true}`) does **not** retroactively build that engine — and a
**client cannot reopen the server's environment** to force a rebuild the way the
embedded app reopens its own LMDB handle. Until the server reopens, any write of
a fulltext value fails with:

```
No implementation of method: :add-doc of protocol: ISearchEngine ... for class: nil
```

So a schema change that touches fulltext requires a **server bounce**. This is a
Datalevin operational property, entirely orthogonal to syncopate — syncopate's
migrations themselves run fine over `dtlv://`.

## Running

Start the server (keeps LMDB files under `data/dtlv-root`, default creds
`datalevin`/`datalevin`):

```
clojure -M:server
```

Then start the app. It's self-directing across a bounce: on the first boot the
creation migration `001` isn't applied yet, so `:hr.db/migrated` applies it and
the app comes up (serving an empty db) with a notice to bounce. **Bounce both the
server and the app** — the server restart is what rebuilds the fulltext engine
from `001`'s schema — and the second boot seeds and serves.

```
clojure -M:run     # boot 1: applies 001 (declares :review/text fulltext), comes up
                   #         on :3004 (empty db), logs "bounce BOTH server and app"
# ^C BOTH the app and the server, then start both again:
clojure -M:server
clojure -M:run     # boot 2: 001 applied ⇒ seeds 50k (legacy shape), applies 002
                   #         (split-names), serves on :3004
```

The bounce is needed *only because* `001` adds a fulltext attribute — see
[When migrations must run offline](#when-migrations-must-run-offline). Effective
order on the data is `001 → [bounce] → seed → 002`, exactly the embedded app's
order; the embedded `datalevin-app` just does it in one process because it can
reopen its own LMDB handle in-process instead of bouncing a server.

## When migrations must run offline

Taking the server offline (or bouncing it) around a migration is required **only
when that migration adds a `:db/fulltext` attribute**. Everything else runs **live**
against a `dtlv://` connection with the server up and serving:

| Migration adds…                                    | Server        |
|----------------------------------------------------|---------------|
| a fulltext attribute (e.g. `001` `:review/text`)   | **bounce**    |
| a non-fulltext attribute (uniqueness, ref, …)      | live          |
| only data changes (e.g. `002` split-names, 50k rows) | live        |

Why: Datalevin builds a database's fulltext search engine on the server **once, when
it opens the environment**, and does not rebuild it when a fulltext attribute is
added later (see [the fulltext engine](#the-one-thing-thats-different-the-fulltext-engine)). Rebuilding it means reopening the environment — which, on a server, means a
restart.

That's exactly why `:hr.db/migrated` splits its work across a bounce: the first
boot applies `001` (the fulltext-adding one) and the app comes up; you bounce both
the server and the app; the second boot seeds and applies `002` (which adds no
fulltext, so it runs live). The bounce lands **between `001` and the seed** — the
seed writes `:review/text`, so the engine must exist first.

### Avoiding the bounce

The one workaround: **migrate against the data directory with the server down.**
Stop the server, open the database's on-disk LMDB directory *embedded*, run the
migration (persisting the fulltext schema), close, then start the server — it now
opens with the fulltext schema already present and builds the engine. LMDB is
single-writer, so the server must be down for this; the server also stores each db
under a name-encoded subdirectory of `data/dtlv-root`, not a plain `hr-db/`.

(Connecting *with* a schema — `(d/get-conn uri schema)` — does **not** help over
client/server: the server opens a fresh db with `(st/open dir)` sans schema and then
applies the schema via `set-schema`, which doesn't build the fulltext engine.)

The durable fix belongs upstream: the server's `Store` refreshes its cached
`schema`/`attrs`/idoc indices when the schema changes but *not* its fulltext
`search-engines`, so making those refresh the same way would remove the bounce
entirely.

## Findings

**Q: does syncopate work client/server, keeping promise #1?  — Yes.** Migrating
over a `dtlv://` connection, applied-migration state lands in a
`__syncopate_migrations` KV DBI sitting right beside `datalevin/eav`,
`datalevin/ave`, `datalevin/schema`, … **on the server's own environment** — and
`(d/schema conn)` contains **no** `:syncopate`/`:ragtime` datoms. Migrate,
applied-id ordering, and rollback all work remotely; the schema split and
fulltext search both work end-to-end (after the server bounce above).

The one seam: syncopate's *embedded* path folds the KV bookkeeping write into the
same `with-transaction` as the datalog steps (fully atomic); the *remote* path
can't join a separate KV session to the server's datalog transaction, so it uses
a documented two-write sequence. Promise #1 — bookkeeping in a KV DBI on the same
environment, never datoms — holds identically in both.
