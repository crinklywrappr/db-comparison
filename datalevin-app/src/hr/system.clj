(ns hr.system
  "Integrant components: conn → migrated → handler → server.

  Halting :hr.db/conn matters here more than in the other two apps —
  this process IS the database, and closing flushes LMDB."
  (:require [datalevin.core :as d]
            [hr.db :as db]
            [hr.migrations :as migrations]
            [hr.web :as web]
            [integrant.core :as ig]
            [syncopate.core :as sc]
            [ring.adapter.jetty :refer [run-jetty]]))

(defmethod ig/init-key :hr.db/conn [_ {:keys [path]}]
  (d/get-conn path))

(defmethod ig/halt-key! :hr.db/conn [_ conn]
  ;; may already be closed — :hr.db/migrated reopens the connection
  ;; (fulltext engine) and closes this one during init
  (try (d/close conn) (catch Exception _)))

(defmethod ig/init-key :hr.db/migrated [_ {:keys [conn path]}]
  ;; Datalevin builds its fulltext engine from the schema present at
  ;; connection-open time, so the connection is reopened at two points:
  ;; after the creation migration (the seed writes fulltext docs and
  ;; needs the engine) and after the final migrate-all (so the handle
  ;; given downstream postdates EVERY schema change — a future
  ;; migration adding a fulltext attribute stays safe).
  (let [migs    migrations/migrations
        reopen! (fn [conn] (d/close conn) (d/get-conn path))]
    (sc/migrate-all! (sc/store conn) [(first migs)])   ;creation migration
    (let [conn (reopen! conn)]
      (when (db/empty-db? conn) (db/seed! conn))
      (sc/migrate-all! (sc/store conn) migs)           ;applies the rest (002)
      (reopen! conn))))

(defmethod ig/halt-key! :hr.db/migrated [_ conn]
  (try (d/close conn) (catch Exception _)))

(defmethod ig/init-key :hr.web/handler [_ {:keys [db]}]
  (web/app db))

(defmethod ig/init-key :hr.web/server [_ {:keys [handler port]}]
  (run-jetty handler
             {:port (parse-long (or (System/getenv "PORT") (str port)))
              :join? false}))

(defmethod ig/halt-key! :hr.web/server [_ server]
  (.stop server))
