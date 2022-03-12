(ns concurrent.store
  (:refer-clojure :exclude [get])
  (:require
   [clojure.core :as clj]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [concurrent.store.protocols :as proto])
  (:import
   (java.io File PushbackReader)
   (java.util.concurrent.locks Lock ReentrantLock)))

(set! *warn-on-reflection* true)

(defn get
  "Get value stored at key from store."
  [store key]
  (proto/get store key))

(defn put
  "Store value at key into store."
  [store key value]
  (proto/put store key value))

(deftype KvStorePart
    [^Lock lock ^:volatile-mutable data file]
  proto/IKvStore
  (get [this key]
    ;; No lock is necessary because the volatility of the `data` field means each read
    ;; sees all previous writes from other threads, and the value of the `data` field is
    ;; immutable, so once it is read from `data` it cannot change out from under us.
    (let [not-found (Object.)
          value     (clj/get data key not-found)]
      (if (= value not-found)
        (throw (IllegalArgumentException. (format "missing key: %s" key)))
        value)))
  (put [this key value]
    (let [key-length (count key)]
      (when (> key-length 1024)
        (throw (IllegalArgumentException. (format "key too long: %d" key-length)))))
    (let [value-length (count (pr-str value))]
      (when (> value-length 2048)
        (throw (IllegalArgumentException. (format "value too long: %d" value-length)))))
    ;; A lock is necessary here because we read, modify, then write the `data` field.  If
    ;; those operations are not done together in a lock-protected critical section, then
    ;; concurrent threads can stomp on each other and we could lose writes.
    ;;
    ;; Also we want to ensure that the write is persisted to disk before any other writes
    ;; can occur.  Ideally we'd use some kind of file lock, but we're assuming that there
    ;; is only one OS process reading/writing the store files at a time.
    (.lock lock)
    (try
      (let [data* (assoc data key value)]
        (spit file (pr-str data*))
        ;; if a reader reads our `put` then it has already been persisted to disk.
        (set! data data*)
        nil)
      (finally
        (.unlock lock)))))

(deftype KvStore
    [parts]
  proto/IKvStore
  (get [this key]
    (let [i (mod (hash key) (count parts))]
      (get (nth parts i) key)))
  (put [this key value]
    (let [i (mod (hash key) (count parts))]
      (put (nth parts i) key value)
      nil)))

(defn- make-part
  [dir]
  (fn [i]
    (let [file (io/file dir (str i))]
      (->KvStorePart
        ;; initialize a lock with fairness enabled; threads will not be starved waiting
        ;; for the lock
        (ReentrantLock. true)
        (if (.exists file)
          (with-open [rdr (PushbackReader. (io/reader file))]
            (edn/read rdr))
          (do (.createNewFile file)
              (spit file "{}")
              {}))
        file))))

(defn create
  "Create a new partitioned kv store in dir.  If dir already exists and the number of
  partitions on disk is different than partition-count, then behavior is undefined."
  [dir partition-count]
  (.mkdirs (io/file dir))
  (->KvStore (mapv (make-part dir) (range partition-count))))

(defn restore
  "Restore a partitioned kv store from dir.  If dir has been modified by some other process,
  then behavior is undefined."
  [dir]
  (let [partition-count (count (filter #(.isFile ^File %) (file-seq dir)))]
    (->KvStore (mapv (make-part dir) (range partition-count)))))
