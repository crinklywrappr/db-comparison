(ns hr.ragtime-adaptor
  "Both halves of the ragtime protocol surface for Datalevin:

  - DatalevinStore (DataStore): applied migrations are plain entities.
    No bootstrap needed — Datalevin's optional schema happily accepts
    the undeclared :ragtime/* attributes (contrast the Datomic adaptor,
    which must install its tracking attributes first).
  - FileMigration (Migration): wraps the evaled :up/:down fns loaded
    from resources/migrations/*.edn by hr.migrations."
  (:require [datalevin.core :as d]
            [ragtime.protocols :as rp])
  (:import [java.util Date]))

(defrecord FileMigration [mig-id up down]
  rp/Migration
  (id [_] mig-id)
  (run-up! [_ store] (up (:conn store)))
  (run-down! [_ store] (down (:conn store))))

(defrecord DatalevinStore [conn]
  rp/DataStore
  (add-migration-id [_ id]
    (d/transact! conn [{:ragtime/id id :ragtime/applied-at (Date.)}]))
  (remove-migration-id [_ id]
    (when-let [e (d/q '[:find ?e . :in $ ?id :where [?e :ragtime/id ?id]]
                      (d/db conn) id)]
      (d/transact! conn [[:db/retractEntity e]])))
  (applied-migration-ids [_]
    (try (->> (d/q '[:find ?id ?at
                     :where [?e :ragtime/id ?id] [?e :ragtime/applied-at ?at]]
                   (d/db conn))
              (sort-by second)
              (mapv first))
         (catch Exception _ []))))          ;attributes never yet seen
