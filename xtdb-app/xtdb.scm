;;; Guix package for XTDB 2.x, built from the tagged GitHub source.
;;;
;;; XTDB itself is compiled from source with Gradle (from the nonguix
;;; channel).  Third-party dependencies are prebuilt jars from Maven
;;; Central/Clojars/the Gradle plugin portal, pinned individually in
;;; xtdb-deps.lock (next to this file) and fetched as ordinary
;;; content-addressed origins; a file-union assembles them into a
;;; maven-layout repository that the sandboxed Gradle build resolves
;;; from via file://.
;;;
;;; Build:    guix build -f xtdb.scm
;;; Install:  guix package -f xtdb.scm
;;;
;;; Bumping the version: update %xtdb-version and the source sha256
;;; (guix download), then regenerate xtdb-deps.lock by running the
;;; build once on a networked machine:
;;;   guix shell gradle openjdk@21:jdk
;;;   XTDB_VERSION=<v> gradle -g /tmp/gh --no-daemon \
;;;     -Dorg.gradle.java.installations.paths=$(guix build openjdk@21 | grep jdk) \
;;;     :docker:standalone:shadowJar
;;; then hash /tmp/gh/caches/modules-2/files-2.1 into lock entries:
;;;   ("group" "artifact" "version" "file" "nix-base32-sha256")

(use-modules (guix packages)
             (guix download)
             (guix gexp)
             (guix build-system gnu)
             ((guix licenses) #:prefix license:)
             (gnu packages)
             (gnu packages bash)
             (gnu packages java)
             (ice-9 match))

(define %xtdb-version "2.1.0")

(define %xtdb-source
  (origin
    (method url-fetch)
    (uri (string-append "https://github.com/xtdb/xtdb/archive/refs/tags/v"
                        %xtdb-version ".tar.gz"))
    (file-name (string-append "xtdb-" %xtdb-version ".tar.gz"))
    (sha256
     (base32 "0fpqb0qd8m9zirc69g8da3hq8qhhrfr17kc8ravkvl32vvvkk8sb"))))

;; Gradle comes from the nonguix channel; look it up by spec so this
;; file doesn't depend on that channel's module layout.
(define gradle (specification->package "gradle"))

;; The proto files use edition = "2023"; the `protobuf' module variable
;; is the old 3.21 default, so resolve the newest by spec (6.31.1 ==
;; upstream v31.1, matching the protoc 4.31.1 the build requests).
(define protobuf (specification->package "protobuf"))

;; Gradle 9 refuses to configure projects whose directory is missing
;; (Gradle 8 tolerated this), and the release tags omit several project
;; dirs (lang/test-harness, monitoring/docker-image, ...).  Create every
;; directory mentioned by include() in settings.gradle.kts.
(define %ensure-project-dirs
  #~(let ((settings (call-with-input-file "settings.gradle.kts"
                      get-string-all)))
      (for-each
       (lambda (m)
         (for-each
          (lambda (qm)
            (mkdir-p (string-map (lambda (c) (if (char=? c #\:) #\/ c))
                                 (match:substring qm 1))))
          (list-matches "\"([^\"]+)\"" (match:substring m 1))))
       (list-matches "include\\(([^)]*)\\)" settings))))

;; `ProjectDependency.dependencyProject` was removed in Gradle 9; the
;; lines using it only wire root-project dev-REPL source dirs, which the
;; standalone jar build doesn't need.
(define %patch-gradle9
  #~(begin
      (substitute* "build.gradle.kts"
        ((".*dependencyProject\\.sourceSets.*") "")
        ((".*devImplementation\\(mainSourceSet.*") ""))
      ;; The build sandbox has no loopback interface, so Gradle's
      ;; "single-use daemon" (forked whenever the requested build JVM
      ;; args differ from the client JVM's) cannot be reached over TCP.
      ;; Pin identical JVM args on both sides so Gradle deems the
      ;; client JVM compatible and runs the build in-process.
      (substitute* "gradle.properties"
        ((".*org\\.gradle\\.jvmargs.*")
         "org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m\n"))
      (setenv "GRADLE_OPTS" "-Xmx2g -XX:MaxMetaspaceSize=768m")))

;; Pinned third-party artifacts, one origin per file, assembled into a
;; maven-layout repository.  Entries live in xtdb-deps.lock:
;;   ("group" "artifact" "version" "file" "nix-base32-sha256")
(define %xtdb-deps
  (let* ((loc (current-source-location))
         (file (or (and loc (assq-ref loc 'filename)) "xtdb.scm"))
         (lock (string-append (dirname file) "/xtdb-deps.lock")))
    (call-with-input-file lock read)))

(define (dep->union-entry entry)
  (match entry
    ((group artifact version file hash)
     (let ((path (string-append
                  (string-join (string-split group #\.) "/")
                  "/" artifact "/" version "/" file))
           ;; Plugin markers and plugin jars exist on both Maven Central
           ;; and the Gradle plugin portal, sometimes with *different*
           ;; bytes; Gradle resolved them from the portal, so try it
           ;; first for those.
           (repos (if (or (string-suffix? ".gradle.plugin" artifact)
                          (string-suffix? "-gradle-plugin" artifact))
                      '("https://plugins.gradle.org/m2/"
                        "https://repo1.maven.org/maven2/"
                        "https://repo.clojars.org/")
                      '("https://repo1.maven.org/maven2/"
                        "https://repo.clojars.org/"
                        "https://plugins.gradle.org/m2/"))))
       (list path
             (origin
               (method url-fetch)
               (uri (map (lambda (base) (string-append base path)) repos))
               (file-name file)
               (sha256 (base32 hash))))))))

(define xtdb-maven-repo
  (file-union (string-append "xtdb-maven-repo-" %xtdb-version)
              (map dep->union-entry %xtdb-deps)))

(define-public xtdb
  (package
    (name "xtdb")
    (version %xtdb-version)
    (source %xtdb-source)
    (build-system gnu-build-system)
    (arguments
     (list
      #:tests? #f                     ;tests need running services
      #:modules '((guix build gnu-build-system)
                  (guix build utils)
                  (ice-9 match)
                  (ice-9 regex)
                  (ice-9 textual-ports))
      #:phases
      #~(modify-phases %standard-phases
          (delete 'configure)
          (replace 'build
            (lambda* (#:key inputs #:allow-other-keys)
              (let ((repo (string-append "file://" #$xtdb-maven-repo)))
                #$%ensure-project-dirs
                #$%patch-gradle9
                ;; The protobuf plugin's protoc-from-maven is an FHS
                ;; binary that cannot run on Guix; use native protoc
                ;; (same upstream release: 6.31.1 == protoc 31.1).
                (substitute* (find-files "." "build\\.gradle\\.kts$")
                  (("artifact = \"com\\.google\\.protobuf:protoc:.*")
                   (string-append
                    "path = \""
                    (search-input-file inputs "/bin/protoc")
                    "\"\n")))
                ;; Route every repository declaration at the local
                ;; dependency repo; the build sandbox has no network.
                ;; An init script covers all builds (including the
                ;; separate buildSrc build): plugin resolution, project
                ;; dependencies (PREFER_SETTINGS overrides the repos
                ;; declared in build scripts), and buildscript
                ;; classpaths.
                (call-with-output-file "init-repo.gradle"
                  (lambda (port)
                    (format port "
import org.gradle.api.initialization.resolve.RepositoriesMode

beforeSettings { settings ->
    settings.pluginManagement.repositories {
        clear()
        maven { url = '~a' }
    }
    settings.dependencyResolutionManagement {
        repositories {
            maven { url = '~a' }
        }
        repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    }
}

beforeProject { project ->
    project.buildscript.repositories {
        maven { url = '~a' }
    }
}
" repo repo repo)))
                (setenv "HOME" (getenv "TMPDIR"))
                (setenv "XTDB_VERSION" #$%xtdb-version)
                (setenv "JAVA_HOME" #$openjdk21:jdk)
                (setenv "GRADLE_USER_HOME"
                        (string-append (getenv "TMPDIR") "/gradle-home"))
                (invoke "gradle" "--no-daemon" "--console=plain"
                        "-I" "init-repo.gradle"
                        "-Dorg.gradle.java.installations.auto-download=false"
                        (string-append
                         "-Dorg.gradle.java.installations.paths="
                         #$openjdk21:jdk)
                        "-Dkotlin.compiler.execution.strategy=in-process"
                        ":docker:standalone:shadowJar"))))
          (replace 'install
            (lambda* (#:key inputs #:allow-other-keys)
              (let* ((lib (string-append #$output "/lib/xtdb"))
                     (bin (string-append #$output "/bin"))
                     (doc (string-append #$output "/share/doc/xtdb"))
                     (bash (search-input-file inputs "/bin/bash")))
                (install-file "docker/standalone/build/libs/xtdb-standalone.jar"
                              lib)
                (install-file "docker/standalone/local_config.yaml" doc)
                (mkdir-p bin)
                (call-with-output-file (string-append bin "/xtdb-standalone")
                  (lambda (port)
                    ;; JVM flags mirror upstream's docker entrypoint.  A
                    ;; pre-set JAVA_HOME selects the JVM; the packaged
                    ;; JDK is only the fallback.
                    (format port "#!~a
export JAVA_HOME=\"${JAVA_HOME:-~a}\"
exec \"$JAVA_HOME/bin/java\" \\
  -Dclojure.main.report=stderr \\
  --add-opens=java.base/java.nio=ALL-UNNAMED \\
  --enable-native-access=ALL-UNNAMED \\
  -Dio.netty.tryReflectionSetAccessible=true \\
  $XTDB_JAVA_OPTS \\
  -cp \"~a/xtdb-standalone.jar\" \\
  clojure.main -m xtdb.main \"$@\"
"
                            bash #$openjdk21 lib)))
                (chmod (string-append bin "/xtdb-standalone") #o555)))))))
    (native-inputs
     (list gradle protobuf `(,openjdk21 "jdk")))
    (inputs
     (list bash-minimal openjdk21))
    (home-page "https://xtdb.com/")
    (synopsis "Immutable bitemporal SQL database")
    (description
     "XTDB is an immutable database with native bitemporality, queryable
via SQL (PostgreSQL wire protocol, port 5432) and XTQL (HTTP, port 3000).
This package builds the standalone node from source and provides the
@command{xtdb-standalone} launcher.  Pass a YAML config with @option{-f};
see @file{share/doc/xtdb/local_config.yaml} for an example.")
    (license license:mpl2.0)))

xtdb
