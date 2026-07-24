(ns hr.system
  "Integrant components: conn → migrated → handler → server.

  :hr.db/conn is a POOLED pgwire datasource (HikariCP). Both query paths
  run over it: HoneySQL/SQL via next.jdbc, and native XTQL via xtdb-api's
  xt/q (which speaks the same pgwire protocol — XTDB 2.1 has no HTTP
  transport). The pool is the one stateful resource here (the database
  itself is the XTDB node, someone else's process), so it is closed on
  halt; nothing else needs flushing."
  (:require [hr.db :as db]
            [hr.ragtime-adaptor :as adaptor]
            [hr.web :as web]
            [integrant.core :as ig]
            [next.jdbc.connection :as connection]
            [ragtime.core :as ragtime]
            [ragtime.next-jdbc :as ragtime-jdbc]
            [ragtime.reporter :as reporter]
            [ring.adapter.jetty :refer [run-jetty]])
  (:import [com.zaxxer.hikari HikariDataSource]))

(defmethod ig/init-key :hr.db/conn [_ {:keys [host port dbname user password]}]
  ;; A POOLED connection over XTDB's OWN JDBC driver (jdbc:xtdb://…). Two
  ;; reasons it must be this driver, not stock postgres: (1) a bare
  ;; datasource opens and closes a fresh pgwire connection per statement,
  ;; which churns connections on the node under any real read/write rate —
  ;; HikariCP holds a small set open; (2) native XTQL (xt/q, used by
  ;; get-employee) returns nested documents that only the xtdb driver
  ;; decodes into Clojure data (the postgres driver hands back raw
  ;; PGobjects). So BOTH query paths — HoneySQL/SQL and XTQL — share this
  ;; one pooled datasource.
  (let [port (parse-long (or (System/getenv "XTDB_PORT") (str port)))]
    (connection/->pool HikariDataSource
                       {:jdbcUrl  (format "jdbc:xtdb://%s:%s/%s" host port dbname)
                        :username user
                        :password password})))

(defmethod ig/halt-key! :hr.db/conn [_ ^HikariDataSource ds]
  (.close ds))

(defmethod ig/init-key :hr.db/migrated [_ {:keys [conn]}]
  ;; legacy-shape seed first, then the (single) SQL-file migration
  ;; transforms it: no creation migration exists because there is no
  ;; schema to create. Returns the db handle db.clj expects.
  (let [migrations (ragtime-jdbc/load-resources "migrations")]
    (when (db/empty-db? conn) (db/seed! conn))
    (ragtime/migrate-all (adaptor/->XtdbStore conn)
                         (ragtime/into-index migrations)
                         migrations
                         {:reporter reporter/print})
    {:ds conn}))

(defmethod ig/init-key :hr.web/handler [_ {:keys [db]}]
  (web/app db))

(defmethod ig/init-key :hr.web/server [_ {:keys [handler port]}]
  (run-jetty handler
             {:port (parse-long (or (System/getenv "PORT") (str port)))
              :join? false}))

(defmethod ig/halt-key! :hr.web/server [_ server]
  (.stop server))
