(ns hr.main
  (:require [clojure.java.io :as io]
            [hr.db :as db]
            [hr.system]
            [integrant.core :as ig])
  (:gen-class))

(defn -main [& _]
  (let [system (ig/init (ig/read-string (slurp (io/file "resources/system.edn"))))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (println "Halting system...")
                                 (ig/halt! system)
                                 (println "Halted."))))
    (println (str "HR Triad [" (:db db/info) "] up — components: "
                  (pr-str (vec (keys system)))))
    @(promise)))
