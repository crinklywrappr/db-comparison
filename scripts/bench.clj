;; Criterium benchmark harness for the HR Triad db.clj functions at 50k.
;;
;; Run from inside each app dir (it reuses that app's classpath + system):
;;   cd <app> && guix shell openjdk@21:jdk clojure-tools -- \
;;     clojure -M:bench ../scripts/bench.clj
;;
;; It brings the db handle up through integrant (:hr.db/migrated — seeds 50k on
;; first run, no web server) and quick-benchmarks the six curated functions with
;; realistic args: the browse/search reads take ONE PAGE (limit/offset), the
;; analytics (report, asof-payroll) run whole-population but as single efficient
;; queries, get-employee is a point read, touch-login! a write. Every function is
;; timed with criterium (the implementations are now efficient enough that none
;; needs a fixed-N fallback). The full criterium output (minus :results) lands in
;; ../bench/<db>.edn for plotting + the record, and a parity line is printed per
;; function so the three engines can be confirmed to do COMPARABLE work.

(require '[hr.system]
         '[hr.db :as db]
         '[integrant.core :as ig]
         '[criterium.core :as crit]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.pprint]
         '[clojure.string :as str])

(defn db-key []
  (let [n (str/lower-case (:db db/info))]
    (cond (str/includes? n "datomic")       "datomic"
          (str/includes? n "xtdb")          "xtdb"
          ;; the client/server Datalevin variant benches into its own file so it
          ;; never clobbers the embedded datalevin.edn record
          (str/includes? n "client/server") "datalevin-cs"
          (str/includes? n "datalevin")     "datalevin"
          :else "unknown")))

;; Preserve the ENTIRE criterium statistical result map (mean, variance,
;; quantiles, outliers, os-/runtime-details, options, the raw :samples, …) —
;; the committed record — dropping only :results (criterium's collected return
;; values, which for the 50k reads is a multi-megabyte data dump, not a stat).
(defn capture [r]
  (-> r (dissoc :results) (update :samples vec)))

(defn qbench [thunk opts]
  (capture (crit/benchmark* thunk (merge crit/*default-quick-bench-opts* opts))))

;; Direct-timed fallback: N warm calls, timed. Used for functions where
;; criterium is the wrong tool — (a) the WRITE, which mutates state (a
;; latency figure wants a fixed comparable count, not statistical resampling,
;; and on XTDB each write is a new immutable Arrow file so thousands of
;; iterations pile up files), and (b) multi-second reads, where criterium's
;; JIT-warmup phase runs for many minutes on a 2 s call. Same shape either way.
(defn time-calls [thunk n]
  (dotimes [_ 2] (thunk))                                   ; warm
  {:method          :direct-timed
   :samples         (vec (repeatedly n #(let [t0 (System/nanoTime)]
                                          (thunk) (- (System/nanoTime) t0))))
   :execution-count 1 :sample-count n})

;; A short, comparable fingerprint of a function's result, printed for every
;; run so the three engines can be checked to return the SAME shape of work
;; (e.g. a non-empty search — the earlier Datalevin "quota" hit ~0 rows).
(defn parity [k v]
  (case k
    (:list-employees :search) (str "rows=" (count v) " first=" (:name (first v)) " match=" (:match (first v)))
    :get-employee             (str "name=" (:name v) " reviews=" (count (:reviews v)) " timeline=" (count (:salary-timeline v)))
    :report                   (str "depts=" (count v) " " (first v))
    :asof-payroll             (str "rows=" (count v) " first=" (first v))
    :touch-login!             (str "-> " v)))

;; Optional first arg = a single function to benchmark, MERGED into the db's edn.
(def only (some-> (first *command-line-args*) not-empty keyword))

(let [cfg (ig/read-string (slurp "resources/system.edn"))
      sys (ig/init cfg [:hr.db/migrated])
      dbh (:hr.db/migrated sys)]
  (try
    (println "warming + fetching a sample id via list-employees ...")
    (let [id   (:id (first (db/list-employees dbh {:limit 1 :offset 0})))
          page {:limit 25 :offset 0}
          all  [[:get-employee   #(db/get-employee dbh id)]
                [:search         #(db/search dbh "quota" page)]
                [:asof-payroll   #(db/asof-payroll dbh {:valid "2025-06-01" :known nil})]
                [:list-employees #(db/list-employees dbh page)]
                [:report         #(db/report dbh)]
                [:touch-login!   #(db/touch-login! dbh id)]]
          benches (cond->> all only (filter #(= (first %) only)))
          out (str "../bench/" (db-key) ".edn")
          existing (if (.exists (io/file out)) (edn/read-string (slurp out)) {})
          results (reduce (fn [m [k thunk]]
                            (println "  benching" k "...")
                            ;; time the one correctness/parity call — it doubles
                            ;; as a probe to pick the method (see time-calls).
                            (let [t0 (System/nanoTime)
                                  r  (thunk)
                                  ms (/ (- (System/nanoTime) t0) 1.0e6)]
                              (println (format "    parity: %s  (~%.0f ms/call)" (parity k r) ms))
                              (assoc m k (cond
                                           (= k :touch-login!) (time-calls thunk 20) ;write
                                           (> ms 400)          (time-calls thunk 15) ;multi-second read
                                           :else               (qbench thunk {})))))  ;fast read
                          existing benches)]
      (io/make-parents out)
      (spit out (with-out-str (clojure.pprint/pprint results)))
      (println "wrote" out (keys results)))
    (finally
      (ig/halt! sys)))                    ;closes the pool / releases the conn
  (shutdown-agents)
  (System/exit 0))
