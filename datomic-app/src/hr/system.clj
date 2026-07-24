(ns hr.system
  "Integrant components: conn → migrated → handler → server.

  Halting :hr.db/conn here means d/release — the peer library caches
  connections to a database that lives in the (external) transactor;
  we release our handle, the database itself is untouched."
  (:require [datomic.api :as d]
            [hr.db :as db]
            [hr.migrations :as migrations]
            [hr.ragtime-adaptor :as adaptor]
            [hr.web :as web]
            [integrant.core :as ig]
            [ragtime.core :as ragtime]
            [ragtime.reporter :as reporter]
            [ragtime.strategy :as strategy]
            [ring.adapter.jetty :refer [run-jetty]]))

(defmethod ig/init-key :hr.db/conn [_ {:keys [uri]}]
  (let [uri (or (System/getenv "DATOMIC_URI") uri)]
    (d/create-database uri)
    (d/connect uri)))

(defmethod ig/halt-key! :hr.db/conn [_ conn]
  (d/release conn))

(defmethod ig/init-key :hr.db/migrated [_ {:keys [conn]}]
  ;; creation migration → legacy-shape seed → transforming migration
  (let [index (ragtime/into-index migrations/migrations)
        opts {:reporter reporter/print}]
    (ragtime/migrate-all (adaptor/->DatomicStore conn) index
                         [(first migrations/migrations)]
                         (assoc opts :strategy strategy/apply-new))
    (when (db/empty-db? conn) (db/seed! conn))
    (ragtime/migrate-all (adaptor/->DatomicStore conn) index
                         migrations/migrations opts)
    conn))

(defmethod ig/init-key :hr.web/handler [_ {:keys [db]}]
  (web/app db))

(defmethod ig/init-key :hr.web/server [_ {:keys [handler port]}]
  (run-jetty handler
             {:port (parse-long (or (System/getenv "PORT") (str port)))
              :join? false}))

(defmethod ig/halt-key! :hr.web/server [_ server]
  (.stop server))
