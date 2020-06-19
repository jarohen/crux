(ns crux.build
  (:require [badigeon.clean :as clean]
            [badigeon.javac :as javac]
            [badigeon.jar :as jar]
            [badigeon.deploy :as deploy]
            [badigeon.sign :as sign]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import (java.io File)))

(def ^:private clojars
  {:id "clojars", :url "https://repo.clojars.org/"})

(def ^:private sdeps
  (pr-str '{:paths ["../build"]
            :deps {badigeon/badigeon {:mvn/version "0.0.13"}}}))

(defn parse-git-version [git-describe]
  ;;"20.06-1.9.0-22-g9452e91b-dirty"
  (let [[_ tag ahead sha dirty] (re-find #"(.*)\-(\d+)\-([0-9a-z]*)(\-dirty)?$" git-describe)
        ahead? (not= ahead "0")
        dirty? (not (empty? dirty))]
    (assert (re-find #"^\d+\.\d+\-\d+\.\d+\.\d+(-(alpha|beta))?$" tag) (str "Tag format unexpected: " tag))
    (if (and (not ahead?) (not dirty?))
      {:prefix tag}
      (let [[_ prefix minor-version suffix] (re-find #"^(.*\.)(\d+)(-(alpha|beta))?$" tag)]
        {:prefix (str prefix (inc (Integer/parseInt minor-version)))
         :suffix "-SNAPSHOT"}))))

(def git-version
  (let [{:keys [exit out]} (shell/sh "git" "describe" "--tags" "--dirty" "--long" "--match" "*.*-*")]
    (assert (= 0 exit))
    (parse-git-version (str/trim out))))

(defn ->mvn-coords [module]
  (symbol (name 'juxt) module))

(defn module-qualifier [module-dir]
  (let [deps-edn (io/file module-dir "deps.edn")]
    (when (.exists deps-edn)
      (let [deps (read-string (slurp deps-edn))]
        (when (contains? deps :crux/module-qualifier)
          {:qualifier (:crux/module-qualifier deps)})))))

(defn ->mvn-version [qualifier]
  (if qualifier
    (let [{:keys [prefix suffix]} git-version]
      (str prefix (when qualifier (str "-" (name qualifier))) suffix))))

(defn- sub-projects [selection]
  (cond-> (.listFiles (io/file "."))
    :always (->> (into #{} (filter module-qualifier)))
    selection (->> (into #{} (filter (comp (set selection) #(.getName ^File %)))))))

(defn sh [{:keys [dir]} & args]
  (letfn [(print-stream [stream out]
            (future
              (binding [*out* out]
                (with-open [rdr (io/reader stream)]
                  (run! println (line-seq rdr))))))]
    (let [proc (doto (.exec (Runtime/getRuntime)
                            (into-array String args)
                            nil
                            dir)
                 (-> (.getInputStream) (print-stream *out*))
                 (-> (.getErrorStream) (print-stream *err*)))
          exit (.waitFor proc)]
      (when-not (zero? exit)
        (throw (ex-info "shell failed" {:args args
                                        :dir dir
                                        :exit exit}))))))

(defn clean [module]
  (println "-- clean" module)
  (clean/clean "target"))

(defn javac [module]
  (println "-- javac" module)
  (javac/javac "src"
               {:compile-path "target/classes"
                :javac-options ["-source" "8" "-target" "8"
                                "-XDignore.symbol.file"
                                "-Xlint:all,-options,-path"
                                "-Werror"
                                "-proc:none"]}))

(defn sub-javac [& args]
  (doseq [^File module-dir (sub-projects args)
          :let [module (.getName module-dir)]]
    (sh {:dir module-dir}
        "clojure" "-Sdeps" sdeps
        "-m" "crux.build" "javac" module)))

(defn jar-path [module]
  (format "target/%s.jar" module))

(defn jar [module]
  (clean module)
  (javac module)
  (println "-- jar" module)

  (jar/jar (->mvn-coords module) {:mvn/version (->mvn-version (:qualifier (module-qualifier (io/file "."))))}
           {:out-path (jar-path module)
            :deps (->> (:deps (read-string (slurp "deps.edn")))
                       (into {} (map (fn [[coord {local-root :local/root :as dep}]]
                                       (or (when local-root
                                             (when-let [{:keys [qualifier]} (module-qualifier (io/file local-root))]
                                               [coord {:mvn/version (->mvn-version qualifier)}]))
                                           [coord dep])))))
            :mvn/repos clojars}))

(defn sub-jar [& args]
  (doseq [^File module-dir (sub-projects args)]
    (sh {:dir module-dir}
        "clojure" "-Sdeps" sdeps
        "-m" "crux.build" "jar" (.getName module-dir))))

(defn deploy [module]
  (let [coords (->mvn-coords module)
        version (->mvn-version (:qualifier (module-qualifier (io/file "."))))]
    (println "-- deploy" coords version)

    (let [artifacts (doto [{:file-path (jar-path module) :extension "jar"}
                           {:file-path "pom.xml" :extension "pom"}]
                      (sign/sign))]
      (deploy/deploy coords version artifacts clojars))))

(defn sub-deploy [& args]
  (apply sub-jar args)

  (doseq [^File module-dir (sub-projects args)]
    (sh {:dir module-dir}
        "clojure" "-Sdeps" sdeps
        "-m" "crux.build" "deploy" (.getName module-dir))))

(defn sub-test [& args]
  (apply sub-javac args)

  (doseq [^File module-dir (sub-projects args)
          :let [module (.getName module-dir)]
          :when (-> (read-string (slurp (io/file module-dir "deps.edn")))
                    (get-in [:aliases :test]))]
    (println "-- test" module)
    (sh {:dir module-dir}
        "clojure" "-A:test")))

(defn -main [op & args]
  (try
    (case op
      "sub-javac" (apply sub-javac args)
      "javac" (apply javac args)
      "sub-jar" (apply sub-jar args)
      "jar" (apply jar args)
      "deploy" (apply deploy args)
      "sub-deploy" (apply sub-deploy args)
      "sub-test" (apply sub-test args))

    (catch Exception e
      (when-not (= "shell failed" (ex-message e))
        (binding [*out* *err*]
          (println (format "Failed: '%s', %s%n" (ex-message e) (ex-data e)))))
      (System/exit 1)))

  (shutdown-agents))
