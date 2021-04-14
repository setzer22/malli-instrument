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

(defn- wrap-with-validate-input
  "Wraps the given function `f` with code that will validate its input arguments
   with the provided malli `schema`."
  [f, schema]
  (fn [& args]
    (if (m/validate schema args)
      (apply f args)
      #_else
      (throw (ex-info "Function received wrong input"
                      {:error (safe-humanize schema args)
                       :value args})))))

(defn- wrap-with-validate-output
  "Similar to `wrap-with-validate-input`, but checks return value instead."
  [f, schema]
  (fn [& args]
    (let [result (apply f args)]
      (if (m/validate schema result)
        result
        #_else
        (throw (ex-info "Function returned wrong output"
                        {:error (safe-humanize schema result)
                         :value result}))))))

(defn- wrap-with-instrumentation
  "Combines `wrap-with-validate-input` and `wrap-with-validate-output` for
  complete instrumentation"
  [f, args-schema, ret-schema]
  (wrap-with-validate-input (wrap-with-validate-output f ret-schema) args-schema))

(defn- get-fn-schema
  "Given a function's symbol namespace and name, finds a registered malli schema in
   the global function registry. See `malli.core/function-schemas`."
  [the-ns, the-name]
  (let [fn-schema (m/form (:schema (get-in (m/function-schemas) [the-ns the-name])))]
    {:args (-> fn-schema (nth 1))
     :ret (-> fn-schema (nth 2))}))

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
  (let [{:keys [args, ret]} (get-fn-schema the-ns, the-name)
        the-var (locate-var the-ns the-name)]
    (if the-var
      (let [original-fn (or (::original-fn (meta the-var)) (deref the-var))]
        (alter-meta! the-var assoc ::original-fn original-fn)
        (alter-var-root
         the-var
         (constantly (wrap-with-instrumentation original-fn args ret))))

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
