(defproject cabin "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "https://github.com/nyampass/cabin"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [selmer "0.8.2"]
                 [com.taoensso/timbre "3.4.0"]
                 [com.taoensso/tower "3.0.2"]
                 [markdown-clj "0.9.66"]
                 [environ "1.0.0"]
                 [compojure "1.3.4"]
                 [ring/ring-defaults "0.1.5"]
                 [ring/ring-session-timeout "0.1.0"]
                 [metosin/ring-middleware-format "0.6.0"]
                 [metosin/ring-http-response "0.6.2"]
                 [bouncer "0.3.3"]
                 [prone "0.8.2"]
                 [org.clojure/tools.nrepl "0.2.10"]
                 [com.novemberain/monger "2.0.1"]
                 [aleph "0.4.0"]
                 [digest "1.4.4"]
                 [org.clojure/data.json "0.2.6"]
                 [clj-time "0.10.0"]
                 [com.novemberain/monger "3.0.0"]]
  :min-lein-version "2.0.0"
  :uberjar-name "cabin.jar"
  :jvm-opts ["-server"]
;;enable to start the nREPL server when the application launches
;:env {:repl-port 7001}
  :main cabin.core
  :plugins [[lein-environ "1.0.0"]
            [lein-ancient "0.6.5"]]
  :profiles
  {:uberjar {:omit-source true
             :env {:production true}
             :aot :all}
   :dev {:dependencies [[ring-mock "0.1.5"]
                        [ring/ring-devel "1.3.2"]
                        [pjstadig/humane-test-output "0.7.0"]
                        [org.clojure/tools.namespace "0.2.11"]]
         :source-paths ["dev"]
         :injections [(require 'pjstadig.humane-test-output)
                      (pjstadig.humane-test-output/activate!)]
         :env {:dev true
               :mongo-uri "mongodb://127.0.0.1/cabin"}}})
