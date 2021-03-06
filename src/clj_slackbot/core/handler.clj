(ns clj-slackbot.core.handler
  (:require [clojure.string :as str]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [clojail.core :refer [sandbox]]
            [clojail.testers :refer [secure-tester-without-def blanket]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]
            [environ.core :refer [env]]
            [ring.adapter.jetty :refer [run-jetty]]
            [clj-http.client :as client])
  (:import java.io.StringWriter)
  (:gen-class))

(def ^:private clj-slackbot-tester
  (conj secure-tester-without-def (blanket "clj-slackbot")))

(defonce ^:private sb (sandbox clj-slackbot-tester))

(def ^:private post-url (:post-url env))

(def ^:private command-token (:command-token env))

(defn- post-to-slack
  [s channel]
  (println "Posting to slack" s)
  (println "channel" channel ", url" post-url)
  (try
    (client/post post-url {:content-type :json
                           :body         (format "{\"channel\":\"%s\",\"text\":\"%s\"}" channel s)})
    (println "posted")
    (catch Exception ^Exception e
      (println "Error" (.getMessage e)))))

(defn- eval-expr
  "Evaluate the given string"
  [s]
  (println "Attempting evaluation" s)
  (try
    (with-open [out (StringWriter.)]
      (let [form (binding [*read-eval* false] (read-string s))
            result (sb form {#'*out* out})]
        {:status true
         :input s
         :form form
         :result result
         :output (.toString out)}))
    (catch Exception e
      {:status false
       :input s
       :result (.getMessage e)})))

(defn- format-result
  [r]
  (println "Formatting result" r)
  (if (:status r)
    (format "```=> %s\n%s%s```"
            (:form r)
            (:output r)
            (or (:result r) nil))
    (format "```==> %s\n%s```"
            (or (:form r) (:input r))
            (or (:result r) "Unknown Error"))))

(defn- eval-and-post
  [s channel]
  (let [cleansed (str/replace (str/replace s #"“" "\"") #"”" "\"")
        result (-> cleansed eval-expr format-result)]
    (post-to-slack result channel)))

(defn handle-clj
  [params]
  (println "Handling request" params)
  (if-not (= (:token params) command-token)
    {:status 403 :body "Unauthorized"}
    (let [channel (condp = (:channel_name params)
                    "directmessage" (str "@" (:user_name params))
                    "privategroup" (:channel_id params)
                    (str "#" (:channel_name params)))]
      (eval-and-post (:text params) channel)
      {:status 200
       :body "..."
       :headers {"Content-Type" "text/plain"}})))

(defroutes approutes
  (POST "/clj" req (handle-clj (:params req)))
  (GET "/status" _ {:status 200
                    :body "OK"
                    :headers {"Content-Type" "text/plain"}})
  (route/not-found "Not Found"))

(def app (wrap-params (wrap-keyword-params approutes)))

(defn -main [& args]
  (run-jetty (var app)
             {:port (Integer/parseInt (or (:port env) "3000"))
              :join? false}))
