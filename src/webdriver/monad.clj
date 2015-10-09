(ns webdriver.monad
  "Monadic API for clj-webdriver"
  (:require [clojure.algo.monads :refer [defmonad domonad maybe-t
                                         m-bind m-chain
                                         m-lift m-result
                                         maybe-m
                                         monad-transformer
                                         state-m with-monad]]
            [clojure.test :as test]
            [clojure.template :as temp]
            [clojure.string :refer [join]]
            [webdriver.core :as wd]
            [webdriver.util :refer [copy-docs defalias]])
  (:import clojure.lang.ExceptionInfo
           [org.openqa.selenium WebDriver WebElement]))

(defn ensure-webdriver
  "Accept either a map with `:webdriver` or assume the thing is a WebDriver."
  [driver]
  (if (instance? WebDriver driver)
    driver
    (:webdriver driver)))

(declare wait-element)
(defn ensure-element
  "Make it possible to pass in a WebElement or a selector that `webdriver.core/find-element` can find."
  [{:keys [webdriver] :as driver} selector]
  (cond
    (nil? selector) nil
    (instance? WebElement selector) selector
    (:wait? driver) (wait-element driver selector)
    ;; TODO these raw wd/find-element calls likely need to be in the wait-until function
    :else (wd/find-element webdriver selector)))

(defn driver
  "This is the base state for the WebDriver monads. It includes an entry for the underlying WebDriver object that does the heavy lifting, as well as configuration options for various features inside the monad."
  ([^WebDriver webdriver] (driver webdriver {}))
  ([^WebDriver webdriver {:keys [record-history?
                                 wait?
                                 wait-pred
                                 wait-timeout
                                 wait-interval
                                 wait-throws?]
                          :or {record-history? true
                               wait? false
                               wait-pred (fn [driver element] (wd/find-element (ensure-webdriver driver) element))
                               wait-timeout wd/wait-timeout
                               wait-interval wd/wait-interval
                               wait-throws? true}
                          :as opts}]
   (cond-> (assoc opts :webdriver webdriver)
     record-history? (assoc :history []))))

(defmulti format-arg :type)

(defmethod format-arg WebElement
  [element]
  (str "WebElement<"
       (pr-str
        (cond-> (:tag element)
          (seq (:id element)) (str "#" (:id element))
          (seq (:class element)) (str "." (:class element))))
       ">"))

(defmethod format-arg :default
  [arg] (pr-str arg))

(defn format-args
  [args]
  (join ", " (map format-arg args)))

(defn format-step
  [idx item]
  (let [num (inc idx)
        action (:name (meta (:action item)))]
    (str " " num ". Called `" action "`"
         (when-let [args (:args item)]
           (str " with " (format-args (:args item)))))))

(defn format-history
  "Format the history of a driver in a human-readable way, limited to `n` steps. The `steps` is a vector of maps."
  ([steps] (format-history 5 steps))
  ([n steps]
   (let [steps (if (> (count steps) n)
                 (subvec steps (- (count steps) n))
                 steps)]
     (->> steps
          (map-indexed format-step)
          (join "\n")))))

(defn handle-webdriver-error
  [throwable monadic-value driver]
  (let [history (:history driver)
        msg "WebDriver error."
        msg (if history
              (str msg " The last few steps in your test were:\n"
                   (format-history history))
              msg)
        msg (str "\nLast attempted action: " monadic-value)]
    (ex-info msg
             (cond-> {:webdriver driver
                      :attempted-action monadic-value}
               (:history driver) (assoc :history (:history driver)))
             throwable)))

;;;;;;;;;;;;
;; Monads ;;
;;;;;;;;;;;;

(def webdriver-m
  "At its simplest, the WebDriver monad can be seen as a simple State monad."
  state-m)

(def webdriver-maybe-m
  "The simple WebDriver stateful monad extended with maybe semantics."
  (maybe-t webdriver-m))

(def webdriver-test-m
  "Simple WebDriver stateful monad extended with maybe semantics for test failures, represented by :test-failure. This API's `is` form produces `:test-failure` on test failure."
  (maybe-t webdriver-m :test-failure))

(defmonad webdriver-error-m
  "The simple WebDriver stateful monad extended with error-handling semantics."
  [m-result (fn m-result-state [v]
              (fn [driver] [v driver]))
   ;; Since this is the state monad, `mv` is the function
   ;; which accepts a state (named `driver` here) and returns
   ;; a monadic value.
   m-bind (fn m-bind-state [mv f]
            (fn [driver]
              (let [results (try
                              (mv driver)
                                 (catch Throwable e
                                   (handle-webdriver-error e mv driver)))]
                (if (instance? ExceptionInfo results)
                  [results driver]
                  (let [g (f (first results))
                        next-results (try
                                       (g (second results))
                                       (catch Throwable e
                                         (handle-webdriver-error e g (second results))))]
                    (if (instance? ExceptionInfo next-results)
                      [next-results (second results)]
                      next-results))))))])
(alter-meta! #'webdriver-error-m assoc ::catch true)

(def ^::catch webdriver-maybe-error-m
  "The stateful WebDriver monad extended with maybe and error-handling semantics."
  (maybe-t webdriver-error-m))

(def ^::catch webdriver-test-error-m
  "The stateful WebDriver monad extended with test and error-handling semantics."
  (maybe-t webdriver-error-m :test-failure))

(def default-monad #'webdriver-test-error-m)
(alter-meta! #'default-monad
             merge
             (select-keys (meta #'webdriver-test-error-m) [:doc ::catch]))

;;;;;;;;;;;;;;;;;;;;;
;; Monad Utilities ;;
;;;;;;;;;;;;;;;;;;;;;

;; Print utilities that work with the state monad
(defn pr-m      [x] (fn [state] (pr x) [:void state]))
(defn prn-m     [x] (fn [state] (prn x) [:void state]))
(defn pr-str-m  [x] (fn [state] [(pr-str x) state]))
(defn prn-str-m [x] (fn [state] [(prn-str x) state]))
(defn print-m   [& xs] (fn [state] (apply print xs) [:void state]))
(defn println-m [& xs] (fn [state] (apply print xs) [:void state]))

(defn steps-as-bindings
  "Receives a collection of Clojure forms as `steps`. Statements are standalone, but expressions with bindings are written `a-name <- monadic-application`. This function transforms this collection into a vector of bindings that `domonad` will accept as its steps."
  [steps]
  ;; Support `domonad` vector of bindings
  (if (vector? (first steps))
    (first steps)
    ;; Support optional Haskell-style `return` for result expression.
    ;; Actual value is accounted for by `return-expr` function
    ;; when parsing the `drive` macro's arguments.
    (loop [steps (if (= (last steps) 'return) (butlast steps) steps)
           bindings []]
      (if-not (seq steps)
        ;; Return where last thing handled was a `<-` binding
        bindings
        (if (= (count steps) 2)
          ;; Return where last things handled are statements
          (conj bindings
                (gensym "step") (first steps)
                (gensym "step") (second steps))
          (if (= (count steps) 1)
            ;; Return where last thing handled is a statement
            (conj bindings (gensym "step") (first steps))
            ;; Handle either single statement or Haskell-style `name <- value` syntax
            (if (= (nth steps 1) '<-)
              ;; Binding
              (recur (drop 3 steps)
                     (conj bindings (nth steps 0) (nth steps 2)))
              ;; Statement
              (recur (rest steps)
                     (conj bindings (gensym "step") (first steps))))))))))

(defn return-expr
  "Given a sequence of steps, return the \"return\" expression of the monadic computation. This is either a simple last value or a list of `(return <value>)`. The `steps-as-bindings` helper function ignores a final `return` symbol to support the end-user writing this return expression in a Haskell style using `return` as a \"reserved\" symbol."
  [steps]
  (let [expr (last steps)]
    (if (and (list? expr)
             (= (first expr) 'return))
      (second expr)
      expr)))

(defmacro identity-map
  "Given symbols, return a map keyed with keywords of those names and values of the values they're bound to. Targeted at final \"return\" of a monadic computation in which multiple things bound during the computation need to be returned."
  [& syms]
  (let [pairs (mapv #(vector (keyword %1) %1) syms)]
    `(into {} ~pairs)))

(defn monad-specified?
  "Analyze the steps of the `drive` macro to determine if a monad is being specified.

  At a DSL level, this is true in the following cases:
  (drive my-monad [...bindings...] ...)
  (drive my-monad (action) ...)
  (drive my-monad x <- (action) ...)"
  [steps]
  (when (symbol? (first steps))
    (or (vector? (second steps))
        (list? (second steps))
        (= '<- (second (rest steps))))))

(defmacro drive
  "Drives the browser within a monad.

  If the first argument is a symbol, it is assumed to be a specific monad to use. Otherwise the default `webdriver.monad/default-monad` is used.

  If the next argument (or first, if no symbol is provided) is a vector, it is considered to be a vector of bindings to be used in `domonad` fashion, i.e., the bindings are performed using the monadic functions. A final form is expected after the vector of bindings which is the \"return\" value for the whole computation and has access to any of the preceding bindings, just as in `domonad`. This return value should be a single expression which is _not_ a monadic computation.

  If a vector is not found in this position, then `drive` assumes Haskell-style \"do\" notation is being used, in which actions are performed without binding by default, but a binding can be acquired by using the left arrow, e.g. `my-class <- (attribute my-element :class)`. The final expression is the return value of the whole computation and is _not_ a monadic computation; for this you may either use either a simple expression value, `return <expression>` or `(return <expression>)` to suit your style.

  The two syntaxes are functionally equivalent. The Haskell-style \"do\" syntax makes it more concise to write tests for which there are many side-effecting actions with few values being bound to symbols along the way; conversely, the `domonad` syntax makes it more concise when you need to bind many values.

  Examples:

  Haskell-style \"do\" syntax with `<-` binding:
  ```
  (let [test (drive
               (to \"https://github.com\")
               button <- (find-element {:text \"Sign in\"})
               (click button)
               url <- (current-url)
               url)]
    (test my-driver))
  ```

  `domonad`-style vector of bindings:
  ```
  (let [test (drive
               [_ (to \"https://github.com\")
                button (find-element {:text \"Sign in\"})
                _ (click button)
                url (current-url)]
               url)]
    (test d))
  ```

  With a custom monad specified:
  ```
  (let [test (drive webdriver-maybe-m
               (to \"https://github.com\")
               button <- (find-element {:text \"Sign in\"})
               (click button)
               url <- (current-url)
               url)]
    (test my-driver))
  ```
  "
  [& steps]
  (let [monad-specified? (monad-specified? steps)
        name (if monad-specified?
               (first steps)
               (:name (meta default-monad)))
        steps (if monad-specified? (rest steps) steps)
        do-steps (steps-as-bindings (butlast steps))
        expr (return-expr steps)]
    `(domonad ~name
              ;; By putting an initial binding here,
              ;; m-bind becomes the sole place to handle
              ;; things like exceptions.
              [identity# (fn [driver#] [:identity driver#])
               ~@do-steps]
              ~expr)))

;;;;;;;;;;;;;;
;; Test API ;;
;;;;;;;;;;;;;;

(defmacro is
  "A version of `clojure.test/is` that works within the webdriver monad(s). Returns a value of `:webdriver.monad/test-failure` if a test fails.

  NOTE: This macro still uses `clojure.test/is` under the covers. You'll get default testing reporting output in addition to the monadic qualities of this macro's return value."
  ([form]
   `(fn [driver#]
      (if-let [result# (test/is ~form)]
        [result# driver#]
        [::test-failure (assoc driver#
                              :test-form
                              '~form)])))
  ([form msg]
   `(fn [driver#]
      (if-let [result# (test/is ~form ~msg)]
        [result# driver#]
        [::test-failure (assoc driver#
                               :test-form
                               '~form)]))))

(defmacro are
  "A version of `clojure.test/are` that works within the webdriver monad(s). Returns a value of `:webdriver.monad/test-failure` if a test fails.

  NOTE: This macro uses clojure.test's facilities under the covers. You'll get default testing reporting output in addition to the monadic qualities of this macro's return value."
  [argv expr & args]
  (if (or
       ;; (are [] true) is meaningless but ok
       (and (empty? argv) (empty? args))
       ;; Catch wrong number of args
       (and (pos? (count argv))
            (pos? (count args))
            (zero? (mod (count args) (count argv)))))
    `(fn [driver#]
       (if-let [result# (temp/do-template ~argv (test/is ~expr) ~@args)]
         [result# driver#]
         [:test-failure driver#]))
    (throw (IllegalArgumentException. "The number of args doesn't match are's argv."))))

;;;;;;;;;;;;;;;;;;;
;; WebDriver API ;;
;;;;;;;;;;;;;;;;;;;

(defn history
  "If history is enabled in the driver state, append a map with `action` and `args` to it."
  ([driver action] (history driver action nil))
  ([driver action args]
   (if (:history driver)
     (update-in driver [:history] conj {:action action
                                        :args args})
     driver)))

(defn ->element
  "Partially serialize a WebElement into a Clojure map that captures important values. Intended for human consumption at this point."
  [^WebElement element]
  {:type WebElement
   :tag (.getTagName element)
   :location (.getLocation element)
   :class (.getAttribute element "class")
   :id (.getAttribute element "id")})

;; TODO Determine if predicate should accept "driver" or WebDriver
(defn wait-element
  "Implicitly wait for an element before moving on. Uses Selenium-WebDrivers _explicit_ waiting functionality, but is designed to be configured globally (implicitly) for the driver being used."
  [{:keys [webdriver wait-pred wait-timeout wait-interval] :as driver} selector]
  (wd/wait-until webdriver
                 (fn [_]
                   (wait-pred driver selector))
                 wait-timeout
                 wait-interval))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Essentially three types of WebDriver functions: ;;
;;                                                 ;;
;;  1. Driver action for side effects              ;;
;;  2. Driver action for primitive value           ;;
;;  3. Driver action for WebElement                ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn satisfied?
  "Returns a function that webdriver.core/wait-until will accept as its predicate. The `action` is a monadic expression, which is a function that takes a starting monadic value and performs the computation. If `throws-exception?` is truthy, the underlying action is wrapped in a try/catch that returns `nil` if an exception is raised."
  [driver action throws-exception?]
  (fn [driver]
    (let [[result _] (if throws-exception?
                       (try
                         (action driver)
                         (catch Throwable _ nil))
                       (action driver))]
      (when result
        (not (instance? Throwable result))))))

(defn wait-until
  "Set an explicit wait time `timeout` for a particular condition `pred`. Optionally set an `interval` for testing the given predicate. All units in milliseconds."
  ([action] (wait-until action nil))
  ([action {:keys [wait-timeout
                   wait-interval
                   wait-throws?]
            :or {wait-timeout wd/wait-timeout
                 wait-interval wd/wait-interval
                 wait-throws? true}}]
   (fn [driver]
     (let [webdriver (ensure-webdriver driver)
           driver (history driver #'wait-until [action])]
       (wd/wait-until webdriver
                      (satisfied? driver action wait-throws?)
                      wait-timeout
                      wait-interval)
       [:void driver]))))

(defn back
  ([driver] (wd/back (ensure-webdriver driver)))
  ([]
   (fn [driver]
     (let [webdriver (ensure-webdriver driver)
           value :void
           driver (history driver #'back)]
       (wd/back webdriver)
       [value driver]))))
(copy-docs 'back)

(defn forward
  ([driver] (wd/forward (ensure-webdriver driver)))
  ([]
   (fn [driver]
     (let [webdriver (ensure-webdriver driver)
           value :void
           driver (history driver #'forward)]
       (wd/forward webdriver)
       [value driver]))))
(copy-docs 'forward)

(defn to
  ([driver url] (wd/to (ensure-webdriver driver) url))
  ([url]
   (fn [driver]
     (let [webdriver (ensure-webdriver driver)
           value :void
           driver (history driver #'to [url])]
       (wd/to webdriver url)
       [value driver]))))
(copy-docs 'to)

(defn current-url
  ([driver] (wd/current-url (ensure-webdriver driver)))
  ([]
   (fn [driver]
     (let [webdriver (ensure-webdriver driver)
           value (wd/current-url webdriver)
           driver (history driver #'current-url)]
       [value driver]))))
(copy-docs 'current-url)

(defn page-source
  ([driver] (wd/page-source (ensure-webdriver driver)))
  ([]
   (fn [driver]
     (let [webdriver (ensure-webdriver driver)
           value (wd/page-source webdriver)
           driver (history driver #'page-source)]
       [value driver]))))
(copy-docs 'page-source)

(defn title
  ([driver] (wd/title (ensure-webdriver driver)))
  ([]
   (fn [driver]
     (let [webdriver (ensure-webdriver driver)
           value (wd/title webdriver)
           driver (history driver #'title)]
       [value driver]))))
(copy-docs 'title)

(defn find-element
  ([driver selector] (wd/find-element (ensure-webdriver driver) selector))
  ([selector]
   (fn [driver]
     (let [webdriver (ensure-webdriver driver)
           value (wd/find-element webdriver selector)
           driver (history driver #'find-element [selector])]
       [value driver]))))
(copy-docs 'find-element)

(defn find-element-by
  ([driver by-selector] (wd/find-element-by (ensure-webdriver driver) by-selector))
  ([by-selector]
   (fn [driver]
     (let [webdriver (ensure-webdriver driver)
           value (wd/find-element-by webdriver by-selector)
           driver (history driver #'find-element-by [by-selector])]
       [value driver]))))
(copy-docs 'find-element-by)

(defn find-elements
  ([driver selector] (wd/find-elements (ensure-webdriver driver) selector))
  ([selector]
   (fn [driver]
     (let [webdriver (ensure-webdriver driver)
           value (wd/find-elements webdriver selector)
           driver (history driver #'find-elements [selector])]
       [value driver]))))
(copy-docs 'find-elements)

;;;;;;;;;;;;;;
;; Elements ;;
;;;;;;;;;;;;;;

(defn click
  [element]
  (fn [driver]
    (let [element (ensure-element driver element)
          value :void
          driver (history driver #'click [(->element element)])]
      (wd/click element)
      [value driver])))
(copy-docs 'click)

(defn send-keys [element text]
  (fn [driver]
    (let [element (ensure-element driver element)
          value :void
          driver (history driver #'send-keys [(->element element) text])]
      (wd/send-keys element text)
      [value driver])))
(copy-docs 'send-keys)

(defn attribute
  [element attr]
  (fn [driver]
    (let [element (ensure-element driver element)
          value (wd/attribute element attr)
          driver (history driver #'attribute [(->element element) attr])]
      [value driver])))
(copy-docs 'attribute)

(defn text
  [element]
  (fn [driver]
    (let [element (ensure-element driver element)
          value (wd/text element)
          driver (history driver #'text [(->element element)])]
      [value driver])))
(copy-docs 'text)

(defn visible?
  [element]
  (fn [driver]
    (let [element (ensure-element driver element)
          value (wd/visible? element)
          driver (history driver #'visible? [(->element element)])]
      [value driver])))
(copy-docs 'visible?)

(defn present?
  [element]
  (fn [driver]
    (let [element (ensure-element driver element)
          value (wd/present? element)
          driver (history driver #'present? [(->element element)])]
      [value driver])))
(copy-docs 'present?)

(defn exists?
  [element]
  (fn [driver]
    (let [element (ensure-element driver element)
          value (wd/exists? element)
          driver (history driver #'exists? [(->element element)])]
      [value driver])))

;;;;;;;;;;;;;
;; Aliases ;;
;;;;;;;;;;;;;

(defalias by-attr-contains wd/by-attr-contains)
(defalias by-attr-ends wd/by-attr-ends)
(defalias by-attr-starts wd/by-attr-starts)
(defalias by-attr= wd/by-attr=)
(defalias by-class-name wd/by-class-name)
(defalias by-css-selector wd/by-css-selector)
(defalias by-has-attr wd/by-has-attr)
(defalias by-id wd/by-id)
(defalias by-link-text wd/by-link-text)
(defalias by-name wd/by-name)
(defalias by-partial-link-text wd/by-partial-link-text)
(defalias by-query wd/by-query)
(defalias by-tag wd/by-tag)
(defalias by-xpath wd/by-xpath)

;; Usage
(comment
  (import 'org.openqa.selenium.firefox.FirefoxDriver)
  ;; Create driver (map of WebDriver and history)
  (def d (driver (FirefoxDriver.)))

  ;; Using `domonad`
  (let [test (domonad webdriver-error-m
                      [_ (to "https://github.com")
                       url-a (current-url)
                       sign-in (find-element {:tag :a :text "Sign in"})
                       _ (click sign-in)
                       url-b (current-url)
                       login (find-element {:tag :input :id "login_field"})
                       password (find-element {:tag :input :id "password"})
                       _ (send-keys login "MR.GITHUB")
                       _ (send-keys password "WHO KNOWS?")]
                      {:url-a url-a
                       :url-b url-b})
        [result final-driver] (test d)]
    [result final-driver])

  ;; Using custom `drive` macro with Haskell-style syntax
  (let [test (drive
              (to "https://github.com")
              url-a <- (current-url)
              sign-in <- (find-element {:tag :a :text "Sign in"})
              (click sign-in)
              url-b <- (current-url)
              login <- (find-element {:tag :input :id "login_field"})
              password <- (find-element {:tag :input :id "password"})
              (send-keys login "MR.GITHUB")
              (send-keys password "WHO KNOWS?")
              ;; Can optionally use `(return <form>)`
              ;; or even `return <form>` as in Haskell
              {:url-a url-a
               :url-b url-b})]
    (test d))

  ;; String selectors instead of explicit find-element calls
  (let [test (drive
              (to "https://github.com")
              url-a <- (current-url)
              (click "//a[text()='Sign in']")
              url-b <- (current-url)
              (send-keys "input#login_field" "MR.GITHUB")
              (send-keys "input#password" "WHO KNOWS?")
              {:url-a url-a
               :url-b url-b})]
    (test d))
  )