(ns concurrent.store.protocols
  (:refer-clojure :exclude [get]))

(defprotocol IKvStore
  (put [this key value])
  (get [this key]))
