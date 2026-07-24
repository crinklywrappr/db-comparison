#!/usr/bin/env clojure
;; One-off large-seed generator for the HR Triad bulk-load showcase.
;;
;; The committed 12-row seed is too small to exercise the databases'
;; bulk-load characteristics (Datomic's ~10 MB transactor ceiling, XTDB's
;; per-round-trip cost, Datalevin's LMDB batching). This writes an
;; arbitrarily large seed instead.
;;
;; Output is NEWLINE-DELIMITED EDN — one employee map per line — so both
;; this generator and each app's seed! can stream it without holding the
;; whole set in memory (a multi-hundred-MB file would otherwise blow the
;; JVM's ~4 GB default heap). seed.edn is git-ignored; regenerate as needed.
;;
;; Usage (run from the hr-triad/ repo root):
;;   clojure -M scripts/gen_seed.clj <count> [outfile]
;; e.g.
;;   clojure -M scripts/gen_seed.clj 50000            ; -> ./seed.edn
;;   clojure -M scripts/gen_seed.clj 250000 seed.edn
;;
;; Emails are guaranteed unique (emp<N>@corp.example) because Datomic
;; declares :employee/email as :db.unique/value — a collision would abort
;; the load.

(require '[clojure.java.io :as io])

(def first-names
  ["Alice" "Bao" "Carmen" "Dmitri" "Efua" "Farid" "Grace" "Hiro" "Ines"
   "Jamal" "Kira" "Luca" "Mei" "Noah" "Omar" "Priya" "Quinn" "Rosa"
   "Sven" "Tara" "Umar" "Vera" "Wei" "Xochitl" "Yuki" "Zane"])

(def last-names
  ["Höglund" "Tran" "Reyes" "Volkov" "Mensah" "Karimi" "Okafor" "Tanaka"
   "Costa" "Ibrahim" "Novak" "Moretti" "Chen" "Andersen" "Haddad" "Sharma"
   "Kelly" "Delgado" "Larsson" "Petrov" "Farah" "Nguyen" "Wang" "Cruz"])

(def departments ["Engineering" "Sales" "People Ops" "Finance" "Support" "Marketing"])

(def roles
  {"Engineering" ["Engineer" "Senior Engineer" "SRE" "Staff Engineer" "Eng Manager"]
   "Sales"       ["Account Exec" "SDR" "Sales Manager" "Solutions Engineer"]
   "People Ops"  ["HR Generalist" "Recruiter" "People Partner"]
   "Finance"     ["Analyst" "Controller" "Accountant"]
   "Support"     ["Support Engineer" "Support Lead"]
   "Marketing"   ["Content" "Growth" "Brand Manager"]})

(def review-lines
  ["Excellent ownership of the storage migration project."
   "Solid work on the query optimizer; needs mentoring time."
   "Crushed the quota this quarter. Pipeline is healthy."
   "Reliable on-call rotations; strong incident write-ups."
   "Great cross-team collaboration during the launch."
   "Needs to delegate more; tends to bottleneck reviews."
   "Consistently ships ahead of schedule with high quality."
   "Improved onboarding docs — big win for the whole team."])

(defn iso-date [year month day]
  (format "%04d-%02d-%02d" year month day))

(defn rand-date [min-year max-year]
  (iso-date (+ min-year (rand-int (inc (- max-year min-year))))
            (inc (rand-int 12))
            (inc (rand-int 28))))

(defn gen-employee [i]
  (let [dept   (rand-nth departments)
        role   (rand-nth (roles dept))
        hired  (rand-date 2018 2025)
        base   (+ 55000 (* 5000 (rand-int 20)))
        n-sal  (inc (rand-int 4))
        sals   (loop [k 0, eff hired, amt base, acc []]
                 (if (= k n-sal)
                   acc
                   (recur (inc k)
                          (rand-date (Long/parseLong (subs eff 0 4)) 2026)
                          (+ amt (* 1000 (rand-int 12)))
                          (conj acc {:amount amt :effective eff
                                     :recorded (rand-date (Long/parseLong (subs eff 0 4)) 2026)}))))
        n-rev  (rand-int 4)]
    {:name (str (rand-nth first-names) " " (rand-nth last-names))
     :email (str "emp" i "@corp.example")
     :hired hired
     :dept dept
     :role role
     :salary-history (vec (sort-by :effective sals))
     :reviews (vec (for [_ (range n-rev)]
                     {:date (rand-date 2023 2026) :text (rand-nth review-lines)}))}))

(let [[cnt out] *command-line-args*
      n   (Long/parseLong (or cnt (throw (ex-info "usage: gen_seed.clj <count> [outfile]" {}))))
      out (or out "seed.edn")]
  (println (str "Generating " n " employees -> " out " ..."))
  (with-open [w (io/writer out)]
    (dotimes [i n]
      (.write w (pr-str (gen-employee i)))
      (.write w "\n")))
  (let [f (io/file out)]
    (println (format "Done. %d employees, %.1f MB." n (/ (.length f) 1048576.0))))
  (shutdown-agents))
