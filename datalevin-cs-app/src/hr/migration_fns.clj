(ns hr.migration-fns
  "Data-transform steps referenced by fully-qualified symbol from the syncopate
  migrations in resources/migrations/*.edn. Each takes the connection and mutates."
  (:require [clojure.string :as str]
            [datalevin.core :as d]))

(defn split-names
  "Rewrite every :employee/name \"Given Family\" into :employee/given-name +
  :employee/family-name. (The subsequent :schema/remove step retracts the old
  :employee/name datoms and drops the attribute.)"
  [conn]
  (let [people (d/q '[:find ?e ?n :where [?e :employee/name ?n]] (d/db conn))]
    (when (seq people)
      (d/transact! conn
        (mapv (fn [[e n]]
                (let [i (str/last-index-of n " ")]
                  {:db/id e
                   :employee/given-name  (if i (subs n 0 i) n)
                   :employee/family-name (if i (subs n (inc i)) "")}))
              people)))))

(defn join-names
  "Recombine :employee/given-name + :employee/family-name back into
  :employee/name (the rollback of `split-names`)."
  [conn]
  (let [people (d/q '[:find ?e ?g ?f
                      :where [?e :employee/given-name ?g]
                             [?e :employee/family-name ?f]]
                    (d/db conn))]
    (when (seq people)
      (d/transact! conn
        (mapv (fn [[e g f]] {:db/id e :employee/name (str/trim (str g " " f))})
              people)))))
