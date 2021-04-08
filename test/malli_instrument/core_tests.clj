(ns malli-instrument.core-tests
  (:require [malli-instrument.core :as sut]
            [malli.core :as m]
            [clojure.test :as t]))

(defmacro expect-ex-data [& body]
  `(try (do ~@body nil)
        (catch Exception e# (ex-data e#))))

#_{:clj-kondo/ignore [:unresolved-symbol, :inline-def]}
(t/deftest malli-instrumentation-tests

  (t/testing "Basic instrumentation"
    (in-ns 'malli-instrument.core-tests)

    (m/=> foo [:=> [:cat int? int?] int?])

    (defn foo [a, b]
      (+ a b))

    (sut/instrument-all!)

    (t/is
     (= '{:error [["should be an int"]], :value ("1" "2")}
        (expect-ex-data
         (foo "1" "2"))))

    (t/is
     (= '{:error [["should be an int"]], :value ("1")}
        (expect-ex-data (foo "1"))))

    (t/is
     (= '{:error [nil ["should be an int"]], :value (1 "1")}
        (expect-ex-data (foo 1 "1"))))

    (t/is
     (= 3 (foo 1 2)))

    (defn foo [a, b]
      (str (+ a b)))

    (sut/instrument-all!)

    (t/is
     (= '{:error ["should be an int"], :value "3"}
        (expect-ex-data (foo 1 2))))))
