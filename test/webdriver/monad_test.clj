(ns webdriver.monad-test
  (:require [clojure.test :as t]
            [webdriver.monad :refer :all]
            [webdriver.test.helpers :refer :all]
            [clojure.java.io :as io]
            [webdriver.core :as wd])
  (:import org.openqa.selenium.WebDriver
           org.openqa.selenium.firefox.FirefoxDriver))

(def driv (atom nil))

(defn restart-browser
  [f]
  (when-not @driv
    (reset! driv (driver (FirefoxDriver.))))
  ((drive
    (to *base-url*)
    :done) @driv)
  (f))

(defn quit-browser
  [f]
  (f)
  (wd/quit (:webdriver @driv)))

(t/use-fixtures :once start-system! stop-system! quit-browser)
(t/use-fixtures :each restart-browser)

;; Example of writing everything in a single deftest
(t/deftest browser-basics
  (let [test (drive
              current-url <- (current-url)
              title <- (title)
              page-source <- (page-source)
              (identity-map current-url title page-source))
        [results driver] (test @driv)]
    (t/is (map? driver))
    (t/is (instance? WebDriver (:webdriver driver)))
    (t/is (= {:current-url *base-url*
              :title "Ministache"}
             (select-keys results [:current-url :title])))
    (t/is (re-find #"(?i)html>" (:page-source results)))))

(def back-forward-should-traverse-browser-history
  "Test steps for browser history operations"
  (drive
   (click "//a[text()='example form']")
   (wait-until (drive
                url <- (current-url)
                (return
                 (= url (str *base-url* "example-form")))))
   url-form <- (current-url)
   (is (= (str *base-url* "example-form") url-form))
   (back)
   url-orig <- (current-url)
   (is (= *base-url* url-orig))
   (forward)
   url-form2 <- (current-url)
   (is (= (str *base-url* "example-form") url-form2))))

(t/deftest test-to
  (let [test (drive
              (to (str *base-url* "example-form"))
              url <- (current-url)
              title <- (title)
              [url title])
        ;; Example of simply pulling out values
        [[url title]] (test @driv)]
    (t/is (= (str *base-url* "example-form") url))
    (t/is (= "Ministache" title))))

(def test-find-by-and-attributes
  (drive
   (click {:tag :a, :text "example form"})
   (wait-until (drive
                el <- (find-element-by (by-id "first_name"))
                el))
   id <- (attribute (by-name "first_name") :id)
   name <- (attribute (by-id "first_name") :name)
   link-text-full <- (text (by-link-text "home"))
   link-text-xpath <- (text (by-xpath "//a[text()='home']"))
   link-text-tag <- (text (by-tag "a"))
   link-text-class <- (text (by-class-name "menu-item"))
   link-text-css <- (text (by-css-selector "#footer a.menu-item"))
   (are [x y] (= x y)
     id "first_name"
     name "first_name"
     link-text-full "home"
     link-text-xpath "home"
     link-text-tag "home"
     link-text-class "home"
     link-text-css "home")))

(def test-find-by-and-attributes-part-2
  (drive
   (click {:tag :a, :text "example form"})
   (wait-until (drive
                el <- (find-element-by (by-id "first_name"))
                el))
   partial-text <- (text (by-partial-link-text "example"))
   by-contains <- (attribute (by-attr-contains :option :value "cial_")
                             :value)
   by-starts <- (attribute (by-attr-starts :option :value "social_")
                           :value)
   by-ends <- (attribute (by-attr-ends :option :value "_media") :value)
   by-has <- (attribute (by-has-attr :option :value) :value)
   (back)
   by-class <- (attribute (by-class-name "first odd") :class)
   (are [x y] (= x y)
     partial-text "example form"
     by-contains "social_media"
     by-starts "social_media"
     by-ends "social_media"
     by-has "france"
     by-class "first odd")))

(def test-find-elements
  (drive
   links <- (find-elements {:tag :a})
   (is (= 10 (count links)))
   text-first <- (text (nth links 1))
   (is (= "Moustache" text-first))
   text-external <- (text {:class "external"})
   (is (= "Moustache" text-external))
   class-odd <- (attribute {:class "first odd"} :class)
   (is (= "first odd" class-odd))
   class-odd2 <- (attribute {:tag :li :class "first odd"} :class)
   (is (= "first odd" class-odd2))
   href <- (attribute {:text "Moustache"} :href)
   (is (= "https://github.com/cgrand/moustache" href))
   (click {:tag :a :text "example form"})
   (wait-until (drive
                (find-element {:type "text"})
                :done))
   id <- (attribute {:type "text"} :id)
   (is (= "first_name" id))
   id2 <- (attribute {:tag :input :type "text"} :id)
   (is (= "first_name" id2))
   id3 <- (attribute {:tag :input :type "text" :name "first_name"} :id)
   (is (= "first_name" id3))))

(def test-hierarchical-queries
  (drive
   text-external <- (text [{:tag :div, :id "content"}, {:tag :a, :class "external"}])
   (is (= "Moustache" text-external))
   text-home <- (text [{:tag :*, :id "footer"}, {:tag :a}])
   (is (= "home" text-home))
   els <- (find-elements [{:tag :*, :id "footer"}, {:tag :a}])
   (is (= 5 (count els)))))

(t/deftest test-hierarchical-prohibits-queries
  (let [test (drive
              el <- (find-element [{:tag :div, :id "content", :css "div#content"}, {:tag :a, :class "external"}])
              el)]
    (t/is (instance? IllegalArgumentException (.getCause (first (test @driv))))))
  (let [test (drive
              el <- (find-element [{:tag :div, :id "content", :xpath "//div[@id='content']"}, {:tag :a, :class "external"}])
              el)]
    (t/is (instance? IllegalArgumentException (.getCause (first (test @driv)))))))

(def test-visible?
  (drive
   (is (visible? {:tag :a, :text "Moustache"}))
   vis? <- (visible? {:tag :a, :href "#pages"})
   (is (not vis?))))

(def test-present?
  (drive
   (is (present? {:tag :a, :text "Moustache"}))
   pres? <- (present? {:tag :a, :href "#pages"})
   there? <- (exists? {:tag :a, :href "#pages"})
   (is there?)
   (is (not pres?))))