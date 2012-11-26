(ns cloverage.instrument
  (:import  [java.io InputStreamReader]
            [clojure.lang LineNumberingPushbackReader])
  (:use [slingshot.slingshot :only [throw+]]
        [clojure.java.io :only [writer]]
        [cloverage.debug])
  (:require [clojure.set :as set]
            [clojure.test :as test])

  (:gen-class))

(def ^:dynamic *instrumenting-file*)

(def stop-symbols
  "These are special forms that can't be evaled, so leave them completely alone."
  '#{. do if var quote try finally throw recur monitor-enter monitor-exit})

(defn form-type
  "Classifies the given form"
  [form]
  (let [res
        (cond

         (stop-symbols form) :stop

         ;; No macro symbols should be present by this point
         ;; so we can take the value of any symbold that's not in stop-symbols
         (symbol? form)  :atomic

         ;; These are eval'able elements that we can wrap, but can't
         ;; descend down into them.
         (string? form)  :atomic
         (char? form)    :atomic
         (number? form)  :atomic
         (keyword? form) :atomic
         (= true form)   :atomic
         (= false form)  :atomic
         (var? form)     :atomic
         (nil? form)     :atomic

         ;; Data structures
         (map?    form) :map
         (vector? form) :vector

         ;; If it's a list or a seq, check the first element to see if
         ;; it's a special form.
         (or (list? form) (seq? form))
         (let [x (first form)]
           (cond
            ;; Don't attempt to descend into these forms yet
            (= 'var x) :stop
            (= 'clojure.core/import* x) :stop
            (= 'catch x) :stop ;; TODO: descend into catch
            (= 'set! x) :stop ;; TODO: descend into set!
            (= 'finally x) :stop
            (= 'quote x) :stop
            (= 'deftest x) :stop ;; TODO: don't stop at deftest

            (= '. x) :dotjava

            (= 'fn x)  :fn
            (= 'fn* x) :fn

            ;; let* and loop* seem to have identical syntax
            (= 'let* x)  :let
            (= 'loop* x) :let

            (= 'def x)  :def
            (= 'new x)  :new
            :else       :list)))]
    (tprnl "Type of" form "is" res)
    (tprnl "Meta of" form "is" (meta form))
    res))

(defmulti do-wrap
  "Traverse the given form and wrap all its sub-forms in a
function that evals the form and records that it was called."
  (fn [f line form]
    (form-type form)))

(defn wrap [f line-hint form]
  (let [form-line (:line (meta form))
        line      (if form-line form-line line-hint)]
    (do-wrap f line form)))

(defn wrapper
  "Return a function that when called, wraps f through its argument."
  [f line]
  (partial wrap f line))

;; Don't attempt to do anything with :stop or :default forms
(defmethod do-wrap :stop [f line form]
  form)

(defmethod do-wrap :default [f line form]
  (tprnl "Don't know how to wrap " form)
  form)

;; Don't descend into atomic forms, but do wrap them
(defmethod do-wrap :atomic [f line form]
  (f line form))

;; For a vector, just recur on its elements.
;; TODO: May want to wrap the vector itself.
(defmethod do-wrap :vector [f line form]
  `[~@(doall (map (wrapper f line) form))])

;; Wrap a single function overload, e.g. ([a b] (+ a b))
(defn wrap-overload [f line [args & body]]
  (tprnl "Wrapping overload" args body)
  (let [wrapped (doall (map (wrapper f line) body))]
    `([~@args] ~@wrapped)))

;; Wrap a list of function overloads, e.g.
;;   (([a] (inc a))
;;    ([a b] (+ a b)))
(defn wrap-overloads [f line form]
  (tprnl "Wrapping overloads " form)
  (if (vector? (first form))
    (wrap-overload f line form)
    (try
     (doall (map (partial wrap-overload f line) form))
     (catch Exception e
       (tprnl "ERROR: " form)
       (tprnl e)
       (throw (Exception. (apply str "While wrapping" form) e))))))

;; Wrap a fn form
(defmethod do-wrap :fn [f line form]
  (tprnl "Wrapping fn " form)
  (let [fn-sym (first form)
        res    (if (symbol? (second form))
                 ;; If the fn has a name, include it
                 (f line `(~fn-sym ~(second form)
                              ~@(wrap-overloads f line (rest (rest form)))))
                 (f line `(~fn-sym
                      ~@(wrap-overloads f line (rest form)))))]
    (tprnl "Wrapped is" res)
    res))

(defmethod do-wrap :let [f line [let-sym bindings & body :as form]]
  (f line
   `(~let-sym
     [~@(doall (mapcat (fn [[name val]] `(~name ~(wrap f line val)))
                       (partition 2 bindings)))]
      ~@(doall (map (wrapper f line) body)))))

;; TODO: Loop seems the same as let.  Can we combine?
(defmethod do-wrap :loop [f line [let-sym bindings & body :as form]]
  (f
   `(~let-sym
     [~@(doall (mapcat (fn [[name val]] `(~name ~(wrap f line val)))
                       (partition 2 bindings)))]
     ~@(doall (map (wrapper f line) body)))))

(defmethod do-wrap :def [f line form]
  (let [def-sym (first form)
        name    (second form)]
    (if (= 3 (count form))
      (let [val (nth form 2)]
        (f line `(~def-sym ~name ~(wrap f line val))))
      (f line `(~def-sym ~name)))))

(defmethod do-wrap :new [f line [new-sym class-name & args :as form]]
  (f line `(~new-sym ~class-name ~@(doall (map (wrapper f line) args)))))

(defmethod do-wrap :dotjava [f line [dot-sym obj-name attr-name & args :as form]]
  ;; either (. obj meth args*) or (. obj (meth args*))
  (if (= :list (form-type attr-name))
    (do
      (tprnl "List dotform, recursing on" (rest attr-name))
      (f line `(~dot-sym ~obj-name (~(first attr-name)
                                    ~@(doall (map (wrapper f line) (rest attr-name))))))
    )
    (do
      (tprnl "Simple dotform, recursing on" args)
      (f line `(~dot-sym ~obj-name ~attr-name ~@(doall (map (wrapper f line) args))))
    )
    ))


(defn remove-nil-line
  "Dissoc :line from the given map if it's val is nil."
  [m]
  (if (:line m)
    m
    (dissoc m :line)))

(defn add-line-number [hint old new]
  (if (instance? clojure.lang.IObj new)
    (let [line  (or (:line (meta new)) hint)
          recs  (if (or (seq? new) (list? new))
                  (doall (map (partial add-line-number line old) new))
                  new)
          ret   (vary-meta recs assoc :line line)]
      ret)
    new))


(defn add-original [old new]
  (tprnl "Meta for" old "is" (meta old))
  (if (instance? clojure.lang.IObj new)
    (let [res (-> (add-line-number (:line (meta old)) old new)
                  (vary-meta merge (meta old))
                  (vary-meta remove-nil-line)
                  (vary-meta assoc :original old))]
      (binding [*print-meta* true]
        (tprn "Updated meta on" new "to" res "based on" old))
      res)
    new))

(defmethod do-wrap :list [f line form]
  (tprnl "Wrapping " (class form) form)
  (let [expanded (macroexpand form)]
    (tprnl "Meta on expanded is" (meta expanded))
    (if (= :list (form-type expanded))
      (let [wrapped (doall (map (wrapper f line) expanded))]
        (f line (add-original form wrapped)))
      (wrap f line (add-original form expanded)))))

(defmethod do-wrap :map [f line form]
  (doall (zipmap (doall (map (wrapper f line) (keys form)))
                 (doall (map (wrapper f line) (vals form))))))

(defn resource-path
  "Given a symbol representing a lib, return a classpath-relative path.  Borrowed from core.clj."
  [lib]
  (tprnl "Getting resource for " lib)
  (str (.. (name lib)
           (replace \- \_)
           (replace \. \/))
       ".clj"))

(defn resource-reader [resource]
  (tprnl "Getting reader for " resource)
  (tprnl "Classpath is")
  (tprnl (seq (.getURLs (java.lang.ClassLoader/getSystemClassLoader))))
  (InputStreamReader.
   (.getResourceAsStream (clojure.lang.RT/baseLoader) resource)))

(defn instrument
  "Reads all forms from the file referenced by the given lib name, and
  returns a seq of all the forms, decorated with a function that when
  called will record the line and file of the code that was executed."
  [f lib]
  (tprnl "Instrumenting" lib)
  (when-not (symbol? lib)
    (throw+ "instrument needs a symbol"))
  (let [file (resource-path lib)]
    (binding [*instrumenting-file* file]
      (with-open [in (LineNumberingPushbackReader. (resource-reader file))]
        (loop [forms nil]
          (if-let [form (read in false nil true)]
            (let [wrapped (try (wrap f (:line (meta form)) form)
                               (catch Throwable t
                                 (throw+ t "Couldn't wrap form %s at line %s"
                                         form (:line form))))]
              (try
               (eval wrapped)
               (tprnl "Evalled" wrapped)
               (catch Exception e
                 (throw (Exception.
                         (str "Couldn't eval form "
                              (binding [*print-meta* false]
                                (with-out-str (prn form)))
                              "Original: " (:original form))
                         e))))
              (recur (conj forms wrapped)))
            (do
              (let [rforms (reverse forms)]
                (dump-instrumented rforms lib)
                rforms))))))))