(ns hr.system
  "Integrant components: conn → migrated → handler → server.

  Client/server variant. Unlike the embedded app, this process is NOT the
  database — it is a CLIENT of a networked Datalevin server (dtlv://).

  `:hr.db/migrated` boots in TWO phases, because the seed writes :review/text
  (fulltext) and the server only builds that attribute's search engine when it
  (re)opens and reads the schema the creation migration persisted — a running
  server never rebuilds it, and a client can't force it. So the app can't apply
  001 and seed in the same process; a SERVER BOUNCE has to fall between them:

    boot 1 (creation migration not applied): apply ONLY 001 (declares
      :review/text fulltext), print a notice, and return — the app comes up on
      :3004 serving an empty db. The operator then bounces BOTH the server and
      this app and starts them again.
    boot 2 (001 applied ⇒ the server has been bounced, engine now exists):
      seed-if-empty (legacy :employee/name shape) then apply the rest (002
      split-names, live) — giving the same 001 → [bounce] → seed → 002 order as
      the embedded app runs in-process."
  (:require [hr.db :as db]
            [hr.migrations :as migrations]
            [hr.web :as web]
            [integrant.core :as ig]
            [syncopate.core :as sc]
            [datalevin.core :as d]
            [ring.adapter.jetty :refer [run-jetty]]))

(defmethod ig/init-key :hr.db/conn [_ {:keys [path]}]
  (d/get-conn path))

(defmethod ig/halt-key! :hr.db/conn [_ conn]
  (try (d/close conn) (catch Exception _)))

(defn- creation-applied?
  "Has the creation migration (the first one) been recorded as applied?"
  [conn migs]
  (contains? (set (:applied (sc/status (sc/store conn) migs)))
             (:id (first migs))))

(defmethod ig/init-key :hr.db/migrated [_ {:keys [conn]}]
  (let [migs migrations/migrations]
    (if-not (creation-applied? conn migs)
      ;; boot 1: apply ONLY 001 (declares :review/text fulltext), then STOP. The
      ;; seed needs the server's fulltext engine, which won't exist until the
      ;; server reopens. The app comes up here on an empty db.
      (do (sc/migrate-all! (sc/store conn) [(first migs)])
          (println "Applied creation migration" (:id (first migs))
                   "(:review/text fulltext).")
          (println "Bounce BOTH the Datalevin server AND this app, then start")
          (println "them again to seed and apply the rest.")
          conn)
      ;; boot 2 (post-bounce): engine exists → legacy-shape seed, then 002 splits
      ;; it (live). Re-runs: not empty → seed skipped, 002 already applied → no-op.
      (do (when (db/empty-db? conn) (db/seed! conn))
          (sc/migrate-all! (sc/store conn) migs)             ;applies 002
          conn))))

(defmethod ig/halt-key! :hr.db/migrated [_ _conn]
  ;; No-op: :hr.db/migrated now hands back the very same connection it received
  ;; (no reopen), so :hr.db/conn owns closing it.
  nil)

(defmethod ig/init-key :hr.web/handler [_ {:keys [db]}]
  (web/app db))

(defmethod ig/init-key :hr.web/server [_ {:keys [handler port]}]
  (run-jetty handler
             {:port (parse-long (or (System/getenv "PORT") (str port)))
              :join? false}))

(defmethod ig/halt-key! :hr.web/server [_ server]
  (.stop server))
