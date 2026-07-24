(ns hr.migrations
  "Loads migrations from resources/migrations/*.edn — one file per
  migration, ids derived from filenames. Each file is a map of
  :up/:down function forms in the EDN-readable subset of Clojure
  (fully-qualified symbols, (quote ...), (deref ...)); they are evaled
  here and wrapped in hr.ragtime-adaptor/FileMigration."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datalevin.core]                 ;for the evaled fn bodies
            [hr.ragtime-adaptor :as adaptor]))

(defn- load-migration [file]
  (let [{:keys [up down]} (edn/read-string (slurp file))]
    (adaptor/->FileMigration (str/replace (.getName file) #"\.edn$" "")
                             (eval up)
                             (eval down))))

(def migrations
  (->> (.listFiles (io/file "resources/migrations"))
       (filter #(str/ends-with? (.getName %) ".edn"))
       (sort-by #(.getName %))
       (mapv load-migration)))
