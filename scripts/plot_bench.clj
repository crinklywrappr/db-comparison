;; Render one box-and-whisker PNG per db function from the committed benchmark
;; output in bench/*.edn. Fastest box green, slowest red, middle neutral; log-Y.
;;
;;   guix shell openjdk@21:jdk clojure-tools -- clojure -M scripts/plot_bench.clj
;;
;; Drawn by hand with Java2D (java.awt + javax.imageio, both in the JDK — no
;; charting dep). XChart 4.0.3's BoxChart ignores per-series colours (always a
;; black-outlined white box with a red median), so it can't express the
;; green=winner / red=loser coding the comparison is about; a few dozen lines of
;; Java2D give exact control. Reads bench/{datomic,xtdb,datalevin}.edn (per-fn
;; {:samples [ns…] :execution-count n …}), writes images/bench-<fn>.png.

(require '[clojure.edn :as edn] '[clojure.java.io :as io] '[clojure.string :as str])
(import '[java.awt Color Font BasicStroke RenderingHints]
        '[java.awt.image BufferedImage]
        '[javax.imageio ImageIO])

(def dbs ["datomic" "xtdb" "datalevin" "datalevin-cs"])
(def fns [:get-employee :search :asof-payroll :list-employees :report :touch-login!])
(def green (Color. 46 178 92)) (def red (Color. 214 48 48)) (def gray (Color. 150 150 156))
(defn lighter [^Color c] (Color. (int (+ (* 0.45 (.getRed c)) 140))
                                 (int (+ (* 0.45 (.getGreen c)) 140))
                                 (int (+ (* 0.45 (.getBlue c)) 140))))

(defn per-call-ms [{:keys [samples execution-count]}]
  (let [ec (max 1 (or execution-count 1))]
    (mapv #(/ (double %) ec 1.0e6) samples)))          ; ns/batch → ms/call

(def data ; {db {fn [ms…]}}
  (into {} (for [db dbs]
             [db (let [f (io/file "bench" (str db ".edn"))]
                   (when (.exists f)
                     (into {} (for [[k v] (edn/read-string (slurp f))] [k (per-call-ms v)]))))])))

(defn pctl [sorted p]
  (let [n (count sorted)]
    (if (= n 1) (first sorted)
        (let [i (* p (dec n)) lo (int (Math/floor i)) hi (int (Math/ceil i)) f (- i lo)]
          (+ (* (nth sorted lo) (- 1 f)) (* (nth sorted hi) f))))))

(defn stats [xs]
  (let [s (vec (sort xs))]
    {:min (first s) :q1 (pctl s 0.25) :med (pctl s 0.50) :q3 (pctl s 0.75) :max (peek s)}))

(defn plot! [fkey]
  (let [series  (for [db dbs :let [xs (get-in data [db fkey])] :when (seq xs)]
                  [db xs (stats xs) (pctl (vec (sort xs)) 0.5)])
        _       (when (empty? series) (throw (ex-info "no data" {:fn fkey})))
        med     (into {} (map (fn [[db _ _ m]] [db m])) series)
        fastest (key (apply min-key val med))
        slowest (key (apply max-key val med))
        W 780 H 470 left 92 right 28 top 54 bot 52
        pw (- W left right) ph (- H top bot)
        allv    (mapcat (fn [[_ xs _ _]] xs) series)
        lo      (apply min allv) hi (apply max allv)
        ylo     (Math/log10 (* lo 0.7)) yhi (Math/log10 (* hi 1.4))
        img     (BufferedImage. W H BufferedImage/TYPE_INT_RGB)
        g       (.createGraphics img)
        ->y     (fn [v] (int (+ top (* ph (/ (- yhi (Math/log10 v)) (- yhi ylo))))))]
    (doto g
      (.setRenderingHint RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
      (.setRenderingHint RenderingHints/KEY_TEXT_ANTIALIASING RenderingHints/VALUE_TEXT_ANTIALIAS_ON)
      (.setColor Color/WHITE) (.fillRect 0 0 W H)
      (.setColor (Color. 245 245 247)) (.fillRect left top pw ph)
      (.setColor Color/WHITE))
    ;; log gridlines + labels at every power of 10 (and 2,5·10^k) in range
    (.setFont g (Font. "SansSerif" Font/PLAIN 11))
    (doseq [k (range (int (Math/floor ylo)) (inc (int (Math/ceil yhi))))
            m [1 2 5]]
      (let [v (* m (Math/pow 10 k))]
        (when (<= ylo (Math/log10 v) yhi)
          (let [y (->y v)]
            (.setColor g (Color. 220 220 224)) (.setStroke g (BasicStroke. 1.0))
            (.drawLine g left y (+ left pw) y)
            (.setColor g (Color. 90 90 96))
            (let [lbl (cond (>= v 1000) (format "%.0fs" (/ v 1000.0))
                            (>= v 1)    (format "%.0fms" v)
                            :else       (format "%.0fµs" (* v 1000)))]
              (.drawString g lbl (- left 8 (.stringWidth (.getFontMetrics g) lbl)) (+ y 4)))))))
    ;; axis border
    (.setColor g (Color. 120 120 126)) (.setStroke g (BasicStroke. 1.5))
    (.drawRect g left top pw ph)
    ;; boxes
    (let [n (count series) slot (/ pw (double n)) bw (min 120 (* slot 0.44))]
      (doseq [[i [db _ st _]] (map-indexed vector series)]
        (let [cx (+ left (* slot (+ i 0.5)))
              c  (cond (= db fastest) green (= db slowest) red :else gray)
              x0 (int (- cx (/ bw 2))) x1 (int (+ cx (/ bw 2)))
              yq1 (->y (:q1 st)) yq3 (->y (:q3 st))
              ymin (->y (:min st)) ymax (->y (:max st)) ymed (->y (:med st))
              boxh (max 2 (- yq1 yq3))]
          (.setStroke g (BasicStroke. 1.5))
          ;; whiskers
          (.setColor g (.darker c))
          (.drawLine g (int cx) ymax (int cx) yq3)
          (.drawLine g (int cx) yq1 (int cx) ymin)
          (.drawLine g (int (- cx (/ bw 4))) ymax (int (+ cx (/ bw 4))) ymax)
          (.drawLine g (int (- cx (/ bw 4))) ymin (int (+ cx (/ bw 4))) ymin)
          ;; box
          (.setColor g (lighter c)) (.fillRect g x0 yq3 (- x1 x0) boxh)
          (.setColor g (.darker c)) (.setStroke g (BasicStroke. 2.0)) (.drawRect g x0 yq3 (- x1 x0) boxh)
          ;; median
          (.setStroke g (BasicStroke. 2.5)) (.drawLine g x0 ymed x1 ymed)
          ;; x label
          (.setColor g (Color. 40 40 46)) (.setFont g (Font. "SansSerif" Font/BOLD 13))
          (let [fm (.getFontMetrics g)]
            (.drawString g db (int (- cx (/ (.stringWidth fm db) 2))) (- H 18)))
          ;; median value annotation above the box
          (.setFont g (Font. "SansSerif" Font/PLAIN 11)) (.setColor g (Color. 70 70 76))
          (let [mv (:med st)
                lbl (cond (>= mv 1000) (format "%.1fs" (/ mv 1000.0))
                          (>= mv 10)   (format "%.0fms" mv)
                          (>= mv 1)    (format "%.1fms" mv)
                          :else        (format "%.0fµs" (* mv 1000)))
                fm (.getFontMetrics g)]
            (.drawString g lbl (int (- cx (/ (.stringWidth fm lbl) 2))) (- ymax 6))))))
    ;; title
    (.setColor g (Color. 20 20 26)) (.setFont g (Font. "SansSerif" Font/BOLD 16))
    (let [t (str (name fkey) "  —  ms per call at 50k  (log scale)")]
      (.drawString g t (int (- (/ W 2) (/ (.stringWidth (.getFontMetrics g) t) 2))) 28))
    (.dispose g)
    (io/make-parents "images/x")
    (let [out (io/file (str "images/bench-" (str/replace (name fkey) "!" "") ".png"))]
      (ImageIO/write img "png" out)
      (println (str out "  fastest=" fastest " slowest=" slowest)))))

(doseq [f fns] (plot! f))
(println "DONE") (System/exit 0)
