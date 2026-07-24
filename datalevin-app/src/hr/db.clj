(ns hr.db
  "Datalevin implementation: embedded, mutable, no history.

  The database is a library call away — no server, no transactor, the
  LMDB file lives under ./data. Deletion is real deletion. Because the
  engine keeps no history at all, EVERY temporal question must be
  answered by reified event entities (salary changes carry both their
  effective date and their recorded date), which — interestingly —
  gives us app-level bitemporality: both 'what was true on X?' and
  'what did we know on X?' are derivable, they just aren't the
  database's job.

  This namespace is stateless: every function takes the connection as
  its first argument. Lifecycle (open/migrate/seed/close) lives in
  hr.system."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datalevin.core :as d])
  (:import [java.util UUID Date]))

(def info
  {:db "Datalevin 1.0.0"
   :personality "embedded · mutable · zero-ops · search built in"
   :caps {:valid-axis "derived from reified salary events"
          :known-axis "derived from the events' recorded dates"
          :purge "plain deletion — data is simply gone"
          :search "native full-text index"
          :migrations "ragtime (custom DataStore over plain entities); old attributes are truly REMOVED"}})

(defn- today [] (str (java.time.LocalDate/now)))

;; ---------- seed ----------

(def ^:private seed-batch 1000)     ;employees per transaction — amortizes commit
                                    ;overhead; larger plateaus (~20 s for 50k on
                                    ;datalevin 1.0.0). fill-db is ~2.5× faster but
                                    ;drops to raw datoms + manual eids (see README).

(defn- employee-tx [{:keys [name email hired dept role salary-history reviews]}]
  (let [eid (UUID/randomUUID)]
    (concat
     [{:employee/id eid :employee/name name :employee/email email
       :employee/hired hired :employee/active true}
      {:assignment/employee [:employee/id eid] :assignment/dept dept
       :assignment/role role :assignment/effective hired}]
     (for [{:keys [amount effective recorded]} salary-history]
       {:salary/employee [:employee/id eid] :salary/amount amount
        :salary/effective effective :salary/recorded recorded})
     (for [{:keys [date text]} reviews]
       {:review/employee [:employee/id eid] :review/date date
        :review/text text}))))

(defn seed! [conn]
  ;; Bulk load: BATCH transactions instead of one-per-employee. Every
  ;; d/transact! is an LMDB commit (~1.5 ms measured), so a per-employee
  ;; loop costs ~77 s for 50k; batching ~1000 employees per transaction is
  ;; ~20× faster. (LMDB's default map size comfortably holds this — no
  ;; MDB_MAP_FULL at 50k.) Stream the seed (never slurp).
  (with-open [r (io/reader (io/file "../seed.edn"))]
    (doseq [batch (->> (line-seq r) (map edn/read-string) (partition-all seed-batch))]
      (d/transact! conn (vec (mapcat employee-tx batch))))))

(defn empty-db? [conn]
  (empty? (d/q '[:find ?e :where [?e :employee/id]] (d/db conn))))

;; ---------- helpers ----------

(defn- latest-until
  "Latest event whose effective date is <= valid, optionally only
  considering events recorded <= known. ISO date strings sort correctly."
  [events valid known]
  (->> events
       (filter #(<= (compare (:effective %) valid) 0))
       (filter #(or (nil? known) (<= (compare (:recorded % (:effective %)) known) 0)))
       (sort-by (juxt :effective :recorded))
       last))

(defn- salary-events [db eid]
  (->> (d/q '[:find ?amount ?eff ?rec ?note
              :in $ ?e
              :where [?s :salary/employee ?e]
                     [?s :salary/amount ?amount]
                     [?s :salary/effective ?eff]
                     [?s :salary/recorded ?rec]
                     [(get-else $ ?s :salary/note "") ?note]]
            db eid)
       (map (fn [[a e r n]] {:amount a :effective e :recorded r :note n}))
       (sort-by (juxt :effective :recorded))))

(defn- assignments [db eid]
  (->> (d/q '[:find ?dept ?role ?eff
              :in $ ?e
              :where [?a :assignment/employee ?e]
                     [?a :assignment/dept ?dept]
                     [?a :assignment/role ?role]
                     [?a :assignment/effective ?eff]]
            db eid)
       (map (fn [[d r e]] {:dept d :role r :effective e}))
       (sort-by :effective)))

(defn- employee-row [db e]
  ;; no dual-shape handling: after the split migration the single-name
  ;; shape exists nowhere — mutation leaves no archaeology
  (let [m (d/pull db '[*] e)
        sal (latest-until (salary-events db e) (today) nil)
        asg (last (assignments db e))]
    {:id (str (:employee/id m))
     :given-name (:employee/given-name m)
     :family-name (:employee/family-name m)
     :name (str/trim (str (:employee/given-name m) " " (:employee/family-name m)))
     :email (:employee/email m)
     :hired (:employee/hired m)
     :active (:employee/active m)
     :last-login (some-> ^Date (:employee/last-login m) .toInstant str)
     :salary (:amount sal)
     :dept (:dept asg)
     :role (:role asg)}))

(defn- eid-of [conn id-str]
  (d/q '[:find ?e . :in $ ?id :where [?e :employee/id ?id]]
       (d/db conn) (UUID/fromString id-str)))

(defn- latest-by-e
  "Reduce rows shaped [?e & _] to a map ?e→row, keeping per employee the
  row that sorts last by (order-fn row). The bulk replacement for the
  per-employee 'latest event' sub-query: ONE scan, grouped in memory."
  [rows order-fn]
  (reduce-kv (fn [m e rs] (assoc m e (last (sort-by order-fn rs))))
             {} (group-by first rows)))

;; ---------- public api ----------

(defn list-employees
  "One PAGE of the active roster, ordered by family name. The old 'build a
  detail row for every active employee, then sort' shape fans an N+1 across
  the whole population and returns 50k rows to a browser; a browse view
  wants a page. Scan the (e, family-name) pairs once, sort, take the
  window, and build detail rows ONLY for that window."
  [conn {:keys [limit offset] :or {limit 50 offset 0}}]
  (let [db (d/db conn)]
    (->> (d/q '[:find ?e ?fam
                :where [?e :employee/active true]
                       [?e :employee/family-name ?fam]] db)
         (sort-by (fn [[_ fam]] fam))
         (drop offset)
         (take limit)
         (map (fn [[e _]] (employee-row db e))))))

(defn get-employee [conn id-str]
  (let [db (d/db conn)
        e (eid-of conn id-str)]
    (when e
      (assoc (employee-row db e)
             :salary-timeline (salary-events db e)
             :assignments (assignments db e)
             :reviews (->> (d/q '[:find ?d ?t :in $ ?e
                                  :where [?r :review/employee ?e]
                                         [?r :review/date ?d]
                                         [?r :review/text ?t]]
                                db e)
                           (map (fn [[d t]] {:date d :text t}))
                           (sort-by :date))))))

(defn create-employee! [conn {:keys [given-name family-name email hired dept role salary]}]
  (let [eid (UUID/randomUUID)]
    (d/transact! conn
      [{:employee/id eid
        :employee/given-name given-name :employee/family-name family-name
        :employee/email email
        :employee/hired hired :employee/active true}
       {:assignment/employee [:employee/id eid] :assignment/dept dept
        :assignment/role role :assignment/effective hired}
       {:salary/employee [:employee/id eid] :salary/amount salary
        :salary/effective hired :salary/recorded (today)}])
    (str eid)))

(defn add-salary-change! [conn id-str {:keys [amount effective note]}]
  (d/transact! conn
    [{:salary/employee (eid-of conn id-str) :salary/amount amount
      :salary/effective effective :salary/recorded (today)
      :salary/note (or note "")}]))

;; A "correction" in a mutable event store is just another event whose
;; effective date lies in the past; the recorded date preserves the
;; knowledge timeline. Nothing engine-level distinguishes it.
(def correct-salary! add-salary-change!)

(defn assign! [conn id-str {:keys [dept role effective]}]
  (d/transact! conn
    [{:assignment/employee (eid-of conn id-str) :assignment/dept dept
      :assignment/role role :assignment/effective effective}]))

(defn add-review! [conn id-str text]
  (d/transact! conn
    [{:review/employee (eid-of conn id-str) :review/date (today) :review/text text}]))

(defn touch-login! [conn id-str]
  (d/transact! conn
    [{:db/id (eid-of conn id-str) :employee/last-login (Date.)}]))

(defn purge!
  "GDPR is trivial here: delete the entities. No history remains
  because none was ever kept."
  [conn id-str]
  (let [db (d/db conn)
        e (eid-of conn id-str)
        refs (d/q '[:find [?x ...] :in $ ?e
                    :where (or [?x :salary/employee ?e]
                               [?x :assignment/employee ?e]
                               [?x :review/employee ?e])]
                  db e)]
    (d/transact! conn (mapv (fn [x] [:db/retractEntity x]) (conj refs e)))))

(defn search
  "Native full-text over review notes — Datalevin's headline feature.
  Paginated: the hit set is deduped to one row per employee and a page
  taken before the (bounded) per-row detail build."
  [conn q {:keys [limit offset] :or {limit 50 offset 0}}]
  (let [db  (d/db conn)
        ;; Datalevin's fulltext is relevance-ranked and defaults to :top 10;
        ;; ask for enough hits to fill a page after de-duping to one row per
        ;; employee (the other two return every match and paginate the lot).
        top (+ offset limit 50)]
    (->> (d/q '[:find ?e ?text
                :in $ ?q ?opts
                :where [(fulltext $ ?q ?opts) [[?r _ ?text]]]
                       [?r :review/employee ?e]]
              db q {:top top})
         (reduce (fn [m [e text]] (cond-> m (not (contains? m e)) (assoc e text))) {})
         (sort-by key)
         (drop offset)
         (take limit)
         (map (fn [[e text]] (assoc (employee-row db e) :match text))))))

(defn asof-payroll
  "Both axes are answerable — but only because the salary events are
  reified with both dates; the engine knows nothing of time. Whole-
  population snapshot, computed in BULK: one scan of the salary events and
  one of the assignments, reduced to the latest event effective on/before
  `valid` (and, for salaries, recorded on/before `known`) per employee —
  not a per-employee sub-query fan-out."
  [conn {:keys [valid known]}]
  (let [db (d/db conn)
        cur-sal  (-> (d/q '[:find ?e ?amount ?eff ?rec
                            :where [?s :salary/employee ?e]
                                   [?s :salary/amount ?amount]
                                   [?s :salary/effective ?eff]
                                   [?s :salary/recorded ?rec]] db)
                     (->> (filter (fn [[_ _ eff rec]]
                                    (and (<= (compare eff valid) 0)
                                         (or (nil? known) (<= (compare rec known) 0))))))
                     (latest-by-e (fn [[_ _ eff rec]] [eff rec])))
        cur-dept (-> (d/q '[:find ?e ?dept ?eff
                            :where [?a :assignment/employee ?e]
                                   [?a :assignment/dept ?dept]
                                   [?a :assignment/effective ?eff]] db)
                     (->> (filter (fn [[_ _ eff]] (<= (compare eff valid) 0))))
                     (latest-by-e (fn [[_ _ eff]] eff)))
        names    (reduce (fn [m [e gn fn*]] (assoc m e (str/trim (str gn " " fn*))))
                         {} (d/q '[:find ?e ?gn ?fn
                                   :where [?e :employee/given-name ?gn]
                                          [?e :employee/family-name ?fn]] db))]
    (->> cur-sal
         (keep (fn [[e row]]
                 {:name (names e)
                  :dept (some-> (cur-dept e) (nth 1))
                  :salary (nth row 1)}))
         (sort-by :name))))

(defn report [conn]
  ;; ONE scan per relation — active ids, all salary events, all assignments
  ;; — then reduce to current-salary / current-dept per employee in memory,
  ;; not the N+1 a naive (group-by dept list-employees) incurs.
  (let [db  (d/db conn)
        td  (today)
        act (d/q '[:find [?e ...] :where [?e :employee/active true]] db)
        cur-sal  (-> (d/q '[:find ?e ?amount ?eff ?rec
                            :where [?s :salary/employee ?e]
                                   [?s :salary/amount ?amount]
                                   [?s :salary/effective ?eff]
                                   [?s :salary/recorded ?rec]] db)
                     (->> (filter (fn [[_ _ eff _]] (<= (compare eff td) 0))))
                     (latest-by-e (fn [[_ _ eff rec]] [eff rec])))
        cur-dept (-> (d/q '[:find ?e ?dept ?eff
                            :where [?a :assignment/employee ?e]
                                   [?a :assignment/dept ?dept]
                                   [?a :assignment/effective ?eff]] db)
                     (latest-by-e (fn [[_ _ eff]] eff)))]
    (->> act
         (keep (fn [e] (when-let [dept (some-> (cur-dept e) (nth 1))]
                         {:dept dept :salary (some-> (cur-sal e) (nth 1))})))
         (group-by :dept)
         (map (fn [[dept rows]]
                (let [sals (keep :salary rows)]
                  {:dept dept
                   :headcount (count rows)
                   :avg-salary (long (/ (reduce + 0 sals) (max 1 (count sals))))})))
         (sort-by :dept))))
