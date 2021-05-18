(ns malli-instrument.core
  (:require [malli.core :as m]
            [malli.error :as me]))

(defn safe-humanize
  "Malli can throw when calling explain or humanize on some inputs. This is undesirable
   as it generates very obscure error messages. This function wraps the calls to humanize
   with try to avoid crashing in instrumented functions"
  [schema, data]
  (let [explained (try {:ok (m/explain schema data)}
                       (catch Exception e
                         {:error e}))
        humanized (if-let [explained (:ok explained)]
                    (try {:ok (me/humanize explained)}
                         (catch Exception e
                           {:error e}))
                    {:ok explained})]
    (if-let [humanized (:ok humanized)]
      humanized
      {:message (str "There was an error when generating human-readable string"
                     " (Could be this: https://github.com/metosin/malli/issues/321)")
       :explain-raw explained
       :exception (:error humanized)})))

(defn- find-matching-arity
  "Given the length of an arglist and a list of function-info, returns the
   function-info that matches the given arity, or nil when no arity matches"
  [args-count, function-infos]
  (let [arities-map (->> (map (fn [{:keys [arity] :as fn-info}]
                                [arity fn-info])
                              function-infos)
                         (into {}))
        varargs (:varargs arities-map)]
    (cond
      (arities-map args-count) (arities-map args-count)
      (and varargs
           (>= args-count (:min varargs))
           (or (not (:max varargs)) (<= args-count (:max varargs)))) varargs
      :else nil)))

(defn throw-invalid-arity
  "The error message when there's an argument count mismatch."
  [fn-name num-args, arities]
  (let [info-map {:cause :invalid-arity
                  :expected-arities arities
                  :num-args num-args}]
   (if (= (count arities) 1)
     (throw (ex-info (format "Function %s received wrong number of arguments. Expected %d, got %d."
                             fn-name (first arities) num-args)
                     info-map))
     #_else
     (throw (ex-info (format "No matching arity for function %s.\n %d arguments given. Expected one of: %s"
                             fn-name num-args (vec arities))
                     info-map)))))

(defn throw-invalid-input
  "The error message when the input doesn't match the schema."
  [fn-name schema args]
  (throw (ex-info (format "Function %s received invalid input" fn-name)
                  {:error (safe-humanize schema args)
                   :value args})))

(defn throw-invalid-output
  "The error message when the output doesn't match the expected schema."
  [fn-name schema result]
  (throw (ex-info (format "Function %s returned wrong output" fn-name)
                  {:error (safe-humanize schema result)
                   :value result})))

(defn wrap-with-instrumentation
  "Wraps the given function `f` with code that will validate its input arguments
   against the provided `fn-schema`. If at least one of the input arities matches
   the arglist, the output will also be validated against the arity's return schema."
  [fn-name f, fn-schema]
  (fn [& args]
    (let [function-infos (:function-infos fn-schema)
          {input-schema :input, ret-schema :output :as matching-arity} (find-matching-arity
                                                                        (count args) function-infos)]

      (if-not matching-arity
        (throw-invalid-arity fn-name (count args) (map :arity function-infos))

        (if-not (m/validate input-schema args)
          (throw-invalid-input fn-name input-schema args)

          (let [result (apply f args)]
            (if-not (m/validate ret-schema result)
              (throw-invalid-output fn-name ret-schema result)

              result)))))))

(defn- get-fn-schema
  "Given a function's symbol namespace and name, finds a registered malli schema in
   the global function registry. See `malli.core/function-schemas`."
  [the-ns, the-name]
  (let [fn-schema (:schema (get-in (m/function-schemas) [the-ns the-name]))]
    (case (m/type fn-schema)
      :=> {:fn-schema-type :single-arity
           :function-infos [(m/-function-info fn-schema)]}
      :function {:fn-schema-type :multi-arity
                 :function-infos (mapv m/-function-info (m/children fn-schema))}
      #_else (throw
              (ex-info
               (format (str "Invalid function schema: %s. Expected single arity with :=>"
                            "or multiple arities using :function") fn-schema))))))

(defn locate-var
  "Given a namespace and name symbols, returns the var in that namespace if found,
   nil otherwise."
  [the-ns, the-name]
  (try (find-var (symbol (str the-ns "/" the-name)))
       (catch java.lang.IllegalArgumentException _ nil)))

(defn instrument-one!
  "Given a function's symbol namespace and name, instruments the function by
  altering its current definition. The original function is preserved in the
  metadata to allow restoring via `unstrument-one!`. This operation is
  idempotent, multiple runs will not wrap the function more than once."
  [the-ns, the-name]
  (let [fn-schema (get-fn-schema the-ns, the-name)
        the-var (locate-var the-ns the-name)]
    (if the-var
      (let [original-fn (or (::original-fn (meta the-var)) (deref the-var))]
        (alter-meta! the-var assoc ::original-fn original-fn)
        (alter-var-root
         the-var
         (constantly (wrap-with-instrumentation (str the-ns "/" the-name) original-fn fn-schema))))

      (throw (ex-info (format "Attempting to instrument non-existing var %s/%s" the-ns the-name)
                      {:error :VAR_NOT_FOUND
                       :ns the-ns, :fn-name the-name})))))

(defn unstrument-one!
  "Undoes the instrumentation performed by `instrument-one!`, leaving the var as
  it was originally defined."
  [the-ns, the-name]
  (let [the-var (locate-var the-ns the-name)]
    (if the-var
      (let [original-fn (or (::original-fn (meta the-var)) (deref the-var))]
        (alter-meta! the-var dissoc ::original-fn)
        (alter-var-root
         the-var
         (constantly original-fn)))
      (throw (ex-info (format "Attempting to unstrument non-existing var %s/%s" the-ns the-name)
                      {:error :VAR_NOT_FOUND
                       :ns the-ns, :fn-name the-name})))))

(defn instrument-all!
  "Goes over all schemas in malli's function registry and performs instrumentation
   by running `instrument-one!` for each of them."
  []
  (let [errors (atom [])]
    (doseq [[namesp funs] (m/function-schemas)]
      (doseq [[fun-name _] funs]
        (try (instrument-one! namesp fun-name)
             (catch Exception e
               (swap! errors conj e)))))
    (when (seq @errors)
     (throw (ex-info "There were unexpected errors during instrumentation"
                     {:errors @errors})))))

(defn unstrument-all!
  "Goes over all schemas in malli's function registry and performs unstrumentation
   by running `unstrument-one!` for each of them."
  []
  (let [errors (atom [])]
    (doseq [[namesp funs] (m/function-schemas)]
      (doseq [[fun-name _] funs]
        (try (unstrument-one! namesp fun-name)
             (catch Exception e
               (swap! errors conj e)))))
    (when (seq @errors)
      (throw (ex-info "There were unexpected errors during instrumentation"
                      {:errors @errors})))))



