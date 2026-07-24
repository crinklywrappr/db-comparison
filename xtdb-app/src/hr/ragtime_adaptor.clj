(ns hr.ragtime-adaptor
  "A ragtime DataStore for XTDB's pgwire — three methods, ~20 lines,
  bookkeeping written as HoneySQL data (reusing the :erase-from
  extension from hr.honey-xt).

  The stock ragtime.next-jdbc/sql-database can't bookkeep against XTDB:
  its INSERT lacks the _id XTDB requires on every record (and its
  ensure-table wants DDL, which XT doesn't have — happily, the first
  INSERT conjures the table). The :datasource field name matters:
  ragtime's SqlMigration executes against (:datasource store), so the
  stock SQL-file migrations work unchanged with this store — and by
  default they run each statement bare, which suits XT's
  no-queries-in-DML-transactions rule.

  (No FileMigration here, unlike the other two apps: ragtime.next-jdbc
  supplies SqlMigration for the .sql files.)"
  (:require [honey.sql :as sql]
            [honey.sql.helpers :as h]
            [hr.honey-xt :as xt]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [ragtime.protocols :as rp]))

(defrecord XtdbStore [datasource]
  rp/DataStore
  (add-migration-id [_ id]
    (jdbc/execute! datasource
                   (sql/format (-> (h/insert-into :ragtime_migrations)
                                   (h/columns :_id :id :created_at)
                                   (h/values [[id id :current-timestamp]])))))
  (remove-migration-id [_ id]
    (jdbc/execute! datasource
                   (sql/format (-> (xt/erase-from :ragtime_migrations)
                                   (h/where [:= :_id id])))))
  (applied-migration-ids [_]
    ;; A never-written table returns no rows in XTDB (it does NOT throw),
    ;; so no defensive catch is needed. Swallowing a real error as [] would
    ;; report "no migrations applied" and re-run every migration — and the
    ;; split-names UPDATE is not idempotent, so that would corrupt data.
    (->> (jdbc/execute! datasource
                        (sql/format (-> (h/select :id)
                                        (h/from :ragtime_migrations)
                                        (h/order-by :created_at)))
                        {:builder-fn rs/as-unqualified-lower-maps})
         (mapv :id))))
