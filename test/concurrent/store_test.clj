(ns concurrent.store-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.test :refer [deftest is]]
   [concurrent.store :as store])
  (:import
   (java.util UUID)))

(defmacro with-tmp-dir
  "Create a temporary directory for the duration of body, then delete it and all the files
  it contains."
  [sym & body]
  `(let [file# (doto (io/file (str "/tmp/" (UUID/randomUUID)))
                 .mkdirs)
         ~sym  file#]
     (try
       ~@body
       (finally
         (doseq [file# (file-seq file#)]
           (.delete file#))
         (.delete file#)))))

(deftest t-write-then-read
  (with-tmp-dir tmp-dir
    (let [store (store/create tmp-dir 10)]
      (store/put store "foo" "bar")
      (store/put store "baz" "quux")
      (is (= "bar" (store/get store "foo")))
      (is (= "quux" (store/get store "baz"))))))

(deftest t-write-then-read-separate-thread
  (with-tmp-dir tmp-dir
    ;; write and read latches are used to enforce ordering of operations across different
    ;; threads
    (let [write-latch (promise)
          read-latch  (promise)
          store       (store/create tmp-dir 10)
          reader      (future (deliver write-latch true)
                              @read-latch
                              (= "bar" (store/get store "foo")))]
      @write-latch
      (store/put store "foo" "bar")
      (deliver read-latch true)
      (is @reader))))

(deftest t-restore
  (with-tmp-dir tmp-dir
    (let [store (store/create tmp-dir 10)]
      (store/put store "foo" "bar")
      (store/put store "baz" "quux"))
    ;; pretend the process crashes here
    (let [store (store/restore tmp-dir)]
      (is (= "bar" (store/get store "foo")))
      (is (= "quux" (store/get store "baz"))))))

(deftest t-get-checks-key
  (with-tmp-dir tmp-dir
    (let [store (store/create tmp-dir 10)]
      (try
        (store/get store "missing")
        (is false "should throw for missing key")
        (catch IllegalArgumentException _e
          (is true))))))

(deftest t-put-checks-key-length
  (with-tmp-dir tmp-dir
    (let [store (store/create tmp-dir 10)]
      (try
        (store/put store (apply str (repeat 1024 "a")) true)
        (store/put store (apply str (repeat 1025 "a")) true)
        (is false "should throw for long key")
        (catch IllegalArgumentException _e
          (is true))))))

(deftest t-put-checks-value-length
  (with-tmp-dir tmp-dir
    (let [store (store/create tmp-dir 10)]
      (try
        ;; the limit is 2048, but the encoding adds double quotes
        (store/put store "foo" (apply str (repeat 2046 "a")))
        (store/put store "foo" (apply str (repeat 2047 "a")))
        (is false "should throw for long value")
        (catch IllegalArgumentException _e
          (is true))))))

(defmacro with-latency
  "Evaluated body returning a vector of: the result of body and the time body took to
  execute (respectively)."
  [& body]
  `(let [start#  (System/currentTimeMillis)
         result# (do ~@body)
         end#    (System/currentTimeMillis)]
     [result# (- end# start#)]))

(defn- run-thread
  [begin? store op-count n]
  (let [thread-name (format "thread-%d" n)]
    @begin?
    (loop [[op-i & op-is] (range op-count)
           latencies      []]
      (if op-i
        (let [[_ put-latency] (with-latency (store/put store thread-name op-i))
              [v get-latency] (with-latency (store/get store thread-name))]
          (is (= op-i v))
          (recur op-is (conj latencies put-latency get-latency)))
        latencies))))

(deftest t-concurrent-operations
  ;; Tests multiple threads hitting the same partitions simultaneously.  This isn't quite
  ;; as good as reading and writing the same key, but it does test contention over the
  ;; same partition.
  (with-tmp-dir tmp-dir
    (let [begin?          (promise)
          partition-count 1
          store           (store/create tmp-dir partition-count)
          thread-count    100
          op-count        100
          threads         (mapv #(future (run-thread begin? store op-count %))
                            (range thread-count))
          [_ total-time]  (with-latency
                            ;; start all threads simultaneously
                            (deliver begin? true)
                            ;; wait for all threads to finish
                            (doseq [thread threads]
                              @thread))
          latencies       (into [] (mapcat deref) threads)
          put-latencies   (take-nth 2 latencies)
          get-latencies   (take-nth 2 (next latencies))
          avg-put-latency (/ (double (apply + put-latencies)) (count put-latencies))
          avg-get-latency (/ (double (apply + get-latencies)) (count get-latencies))
          throughput      (/ (* thread-count (quot op-count 2)) (double total-time))]
      (printf
        (string/join "\n"
          ["partition-count %d"
           "thread-count %d"
           "op-count %d"
           "total-time %d msecs"
           "avg-put-latency %f msecs"
           "avg-get-latency %f msecs"
           "throughput %f ops/msec\n"])
        partition-count
        thread-count
        op-count
        total-time
        avg-put-latency
        avg-get-latency
        throughput))))
