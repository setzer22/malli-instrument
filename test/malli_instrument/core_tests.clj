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
     (= '{:cause :invalid-arity, :expected-arities [2], :num-args 1}
        #_{:clj-kondo/ignore [:invalid-arity]}
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
        (expect-ex-data (foo 1 2))))

    (defn bar
      ([] "secret arity!")
      ([x] x)
      ([x y] (+ x y))
      ([x y & zs] (apply bar (bar x y) zs)))

    (m/=> bar [:function
               [:=> :cat [:= "secret arity!"]]
               [:=> [:cat :int] :int]
               [:=> [:cat :int :int] :int]
               [:=> [:cat :int :int [:+ :int]] :int]])

    (sut/instrument-all!)

    (t/is
     (= {:error [nil nil ["should be an integer"]], :value [1 2 "3" 4 5]}
        (expect-ex-data (bar 1 2 "3" 4 5))))

    (t/is
     (= (bar 1 2 3 4 5) 15))

    (t/is
     (= (bar 1 2) 3))

    (t/is
     (= (bar 1) 1))

    (t/is
     (= (bar) "secret arity!"))

    (m/validate :cat '())


    (sut/unstrument-all!)))
