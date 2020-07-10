(ns crux.build
  (:require [badigeon.classpath :as cp]
            [badigeon.clean :as clean]
            [badigeon.javac :as javac]
            [badigeon.jar :as jar]
            [badigeon.deploy :as deploy]
            [badigeon.sign :as sign]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.deps.alpha.reader :as tda-reader]
            [clojure.tools.deps.alpha.util.dir :as tda-dir])
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

(defn ->mvn-coords [^File module-dir]
  (symbol (name 'juxt) (.getName (.getCanonicalFile module-dir))))

(def git-version
  (let [{:keys [exit out]} (shell/sh "git" "describe" "--tags" "--dirty" "--long" "--match" "*.*-*")]
    (assert (= 0 exit))
    (parse-git-version (str/trim out))))

(defn module-qualifier [module-dir]
  (let [deps-edn (io/file module-dir "deps.edn")]
    (when (.exists deps-edn)
      (let [deps (tda-reader/slurp-deps deps-edn)]
        (when (contains? deps :crux/module-qualifier)
          {:qualifier (:crux/module-qualifier deps)})))))

(defn ->mvn-version [qualifier]
  (if qualifier
    (let [{:keys [prefix suffix]} git-version]
      (str prefix (when qualifier (str "-" (name qualifier))) suffix))))

(defn- sub-projects [selection]
  (cond-> (.listFiles (io/file "."))
    :always (->> (into #{} (comp (filter #(.exists (io/file % "deps.edn")))
                                 (remove #{(io/file "./docs") (io/file "./crux-build")}))))
    selection (->> (into #{} (filter (comp (set selection) #(.getName (.getCanonicalFile ^File %))))))))

(defn clean [module-dirs]
  (doseq [^File module-dir module-dirs]
    (println "-- clean" (.getName module-dir))
    (clean/clean (str (io/file module-dir "target"))
                 {:allow-outside-target? true})))

(defn javac [module-dirs]
  (doseq [^File module-dir module-dirs]
    (tda-dir/with-dir module-dir
      (println "-- javac" (.getName module-dir))
      (javac/javac (io/file module-dir "src")
                   {:compile-path (io/file module-dir "target/classes")
                    :javac-options ["-source" "8" "-target" "8"
                                    "-XDignore.symbol.file"
                                    "-Xlint:all,-options,-path"
                                    "-Werror"
                                    "-proc:none"]}))))

(defn jar-path [module-dir]
  (tda-dir/canonicalize (io/file "target" (str (.getName module-dir) ".jar"))))

(defn jar [module-dirs]
  (clean module-dirs)
  (javac module-dirs)
  (doseq [^File module-dir module-dirs
          :when (module-qualifier module-dir)]
    (tda-dir/with-dir module-dir
      (println "-- jar" (.getName module-dir))

      (let [deps-edn (tda-reader/read-deps [(io/file module-dir "deps.edn")])]
        (jar/jar (->mvn-coords module-dir) {:mvn/version (->mvn-version (:qualifier (module-qualifier module-dir)))}
                 {:out-path (str (jar-path module-dir))
                  :deps (-> (:deps deps-edn)
                            (->> (into {} (map (fn [[coord {local-root :local/root :as dep}]]
                                                 (or (when local-root
                                                       (when-let [{:keys [qualifier]} (module-qualifier (tda-dir/canonicalize (io/file local-root)))]
                                                         [coord {:mvn/version (->mvn-version qualifier)}]))
                                                     [coord dep]))))))
                  :mvn/repos {"clojars" clojars}})))))

(defn deploy [module-dirs]
  (jar module-dirs)

  (doseq [^File module-dir module-dirs]
    (tda-dir/with-dir module-dir
      (let [coords (->mvn-coords module-dir)
            version (->mvn-version (:qualifier (module-qualifier module-dir)))]
        (println "-- deploy" coords version)

        (let [artifacts (doto [{:file-path (jar-path module-dir) :extension "jar"}
                               {:file-path (str (io/file module-dir "pom.xml")) :extension "pom"}]
                          (sign/sign))]
          (deploy/deploy coords version artifacts clojars))))))

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

(defn test [module-dirs]
  (javac module-dirs)

  (doseq [^File module-dir module-dirs
          :let [module (.getName module-dir)]
          :when (-> ( (slurp (io/file module-dir "deps.edn")))
                    (get-in [:aliases :test]))]
    (println "-- test" module)
    (sh {:dir module-dir}
        "clojure" "-A:test")))

(defn -main [op & args]
  (try
    (case op
      "sub-javac" (javac (sub-projects args))
      "javac" (javac [(io/file ".")])

      "sub-jar" (jar (sub-projects args))
      "jar" (jar [(io/file ".")])

      "sub-deploy" (deploy (sub-projects args))
      "deploy" (deploy [(io/file ".")])

      "sub-test" (test (sub-projects args)))

    (catch Exception e
      (when-not (= "shell failed" (ex-message e))
        (binding [*out* *err*]
          (println (format "Failed: '%s', %s%n" (ex-message e) (ex-data e)))))
      (System/exit 1)))

  (shutdown-agents))
