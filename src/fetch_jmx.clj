(ns fetch_jmx
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.jmx :as jmx]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.cli :as cli])
  (:gen-class :main true))

(defn now []
  (new java.util.Date))

(defn format-date [date]
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ssz") date))

(defn read-lines [path]
  (if path
    (with-open [rdr (io/reader path)]
      (doall (line-seq rdr)))
    []))

(defn parse-input [input]
  (if input
    (string/split input #";")
    []))

(defrecord Metric [host port metric timestamp data])

(defn get-bean [bean]
  (dissoc (jmx/mbean bean) :ObjectName))

(defn get-attribute [bean attribute]
  (jmx/read bean attribute))

(defn get-metric [host port metric]
  (let [[bean attribute] (string/split metric #";")]
    (Metric.
     host
     port
     metric
     (now)
     (try
       (if attribute
         (get-attribute bean attribute)
         (get-bean bean))
       (catch Exception e nil)))))

(defn list-beans [host port input]
  (jmx/with-connection {:host host, :port port}
    (sort #(compare (string/upper-case %1) (string/upper-case %2))
          (map #(.toString %1) (jmx/mbean-names input)))))

(defn get-metrics [host port metrics]
  (jmx/with-connection {:host host, :port port}
    (doall (map (partial get-metric host port) metrics))))

(defn print-metric [metric]
  (if (coll? (:data metric))
    (doseq [[attribute values] (:data metric)]
      (printf "%s | %s:%d | %s | %s | %s\r\n" (format-date (:timestamp metric)) (:host metric) (:port metric) (:metric metric) (name attribute) (json/write-str values)))
    (printf "%s | %s:%d | %s | %s\r\n" (format-date (:timestamp metric)) (:host metric) (:port metric) (:metric metric) (json/write-str (:data metric))))
  (flush))

(defn metrics-printer [mchan]
  (async/go
   (loop []
     (when-let [s (async/<!! mchan)]
       (doall (map print-metric s))
       (recur)))))

(defn monitor-host [mchan interval host port metrics]
  (async/go
   (loop []
     (let [results (get-metrics host port metrics)]
       (async/>! mchan results)
       (Thread/sleep (* 1000 interval))
       (recur)))))

(defn -main [& args]
  (let [[options args banner] (cli/cli args
                                       ["-h" "--help" "print this message" :default false :flag true]
                                       ["-s" "--server" "JMX host to connect to" :default "localhost"]
                                       ["-p" "--port" "JMX port to connect to" :default 7199 :parse-fn #(Integer. %)]
                                       ["-j" "--jmx" "JMX metrics to collect delinated by ';'"]
                                       ["-l" "--list" "List available beans using supplied pattern (*:*)"]
                                       ["-f" "--file" "Input file containing JMX metrics to collect, one per line"]
                                       ["-t" "--time" "Total time to run the monitor (seconds)" :default -1 :parse-fn #(Integer. %)]
                                       ["-i" "--interval" "Intverval between metrics fetch (seconds)" :parse-fn #(Integer. %)])]

    (when (:help options)
      (println banner)
      (System/exit 0))

    (if-not (or (:jmx options) (:file options))
      (do
        (println "ERROR: no metrics to collect")
        (println banner)
        (System/exit 0)))

    (let [host (:server options)
          port (:port options)]

      (cond
       (:list options) (doall (map println (list-beans host port (:list options))))
       :else (let [mchan (async/chan 10000)
                   metrics (doall
                            (concat
                             (parse-input (:jmx options))
                             (read-lines (:file options))))]

               (metrics-printer mchan)
               (monitor-host mchan (:interval options) host port metrics)

               (Thread/sleep (* 1000 (:time options)))
               (async/close! mchan)



               )))))