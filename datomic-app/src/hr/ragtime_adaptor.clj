(ns hr.ragtime-adaptor
  "Both halves of the ragtime protocol surface for Datomic:

  - DatomicStore (DataStore): applied migrations are datoms. Unlike
    Datalevin (optional schema) it must bootstrap its own tracking
    attributes before it can record anything — querying or asserting an
    uninstalled attribute throws — so each method ensures the
    (idempotent) tracking schema first.
  - FileMigration (Migration): wraps the evaled :up/:down fns loaded
    from resources/migrations/*.edn by hr.migrations."
  (:require [datomic.api :as d]
            [ragtime.protocols :as rp])
  (:import [java.util Date]))

(defrecord FileMigration [mig-id up down]
  rp/Migration
  (id [_] mig-id)
  (run-up! [_ store] (up (:conn store)))
  (run-down! [_ store] (down (:conn store))))

(def ^:private tracking-schema
  [{:db/ident :ragtime/id
    :db/valueType :db.type/string :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :ragtime/applied-at
    :db/valueType :db.type/instant :db/cardinality :db.cardinality/one}])

(defrecord DatomicStore [conn]
  rp/DataStore
  (add-migration-id [_ id]
    @(d/transact conn tracking-schema)
    @(d/transact conn [{:ragtime/id id :ragtime/applied-at (Date.)}]))
  (remove-migration-id [_ id]
    @(d/transact conn [[:db/retractEntity [:ragtime/id id]]]))
  (applied-migration-ids [_]
    @(d/transact conn tracking-schema)
    (->> (d/q '[:find ?id ?at
                :where [?e :ragtime/id ?id] [?e :ragtime/applied-at ?at]]
              (d/db conn))
         (sort-by second)
         (mapv first))))
