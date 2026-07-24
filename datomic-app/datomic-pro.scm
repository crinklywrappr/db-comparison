;;; Guix package for Datomic Pro.
;;;
;;; Datomic Pro is distributed as a prebuilt zip (Apache 2.0, but no
;;; public source), so this repackages the official binary
;;; distribution and wraps its launcher scripts with a JDK.
;;;
;;; Build:    guix build -f datomic-pro.scm
;;; Install:  guix package -f datomic-pro.scm
;;;
;;; The upstream scripts cd into the (read-only) install directory, so
;;; any data-dir/log-dir/pid-file in a transactor properties file must
;;; be absolute paths.  The wrappers absolutize file arguments and
;;; default DATOMIC_LOG_DIR to the XDG state directory:
;;; ${XDG_STATE_HOME:-$HOME/.local/state}/datomic/log.

(use-modules (guix packages)
             (guix download)
             (guix gexp)
             (guix build-system copy)
             ((guix licenses) #:prefix license:)
             (gnu packages bash)
             (gnu packages base)
             (gnu packages compression)
             (gnu packages java))

(define-public datomic-pro
  (package
    (name "datomic-pro")
    (version "1.0.7622")
    (source
     (origin
       (method url-fetch)
       (uri (string-append "https://datomic-pro-downloads.s3.amazonaws.com/"
                           version "/datomic-pro-" version ".zip"))
       (sha256
        (base32 "1g8s24pnfjpbfhaqz1jdpdm6jssfjgr98773cwqvnzkinpjjxai9"))))
    (build-system copy-build-system)
    (arguments
     (list
      #:install-plan #~'(("." "lib/datomic-pro"))
      #:phases
      #~(modify-phases %standard-phases
          ;; bin/run and bin/console invoke "/usr/bin/env java"; rely on
          ;; the wrapper-provided PATH instead.
          (add-after 'install 'fix-env-java
            (lambda _
              (for-each
               (lambda (file)
                 (when (eq? 'regular (stat:type (lstat file)))
                   (substitute* file
                     (("/usr/bin/env java") "java"))))
               (find-files (string-append #$output "/lib/datomic-pro/bin")))))
          (add-after 'fix-env-java 'install-wrappers
            (lambda* (#:key inputs #:allow-other-keys)
              (let* ((home (string-append #$output "/lib/datomic-pro"))
                     (bin (string-append #$output "/bin"))
                     (bash (search-input-file inputs "/bin/bash"))
                     (readlink (search-input-file inputs "/bin/readlink")))
                (define (make-wrapper name target)
                  (let ((wrapper (string-append bin "/" name)))
                    (call-with-output-file wrapper
                      (lambda (port)
                        ;; A pre-set JAVA_HOME wins: the scripts find
                        ;; `java' via PATH, which follows JAVA_HOME.
                        (format port "#!~a
export JAVA_HOME=\"${JAVA_HOME:-~a}\"
export PATH=\"$JAVA_HOME/bin:~a${PATH:+:}$PATH\"
export DATOMIC_LOG_DIR=\"${DATOMIC_LOG_DIR:-${XDG_STATE_HOME:-$HOME/.local/state}/datomic/log}\"
# The upstream scripts cd into the read-only install directory, so
# turn arguments naming existing files into absolute paths first.
args=()
for a in \"$@\"; do
  if [ -e \"$a\" ]; then a=\"$(readlink -f \"$a\")\"; fi
  args+=(\"$a\")
done
exec \"~a/bin/~a\" \"${args[@]}\"
"
                                bash #$openjdk21 (dirname readlink)
                                home target)))
                    (chmod wrapper #o555)))
                (mkdir-p bin)
                (for-each (lambda (script)
                            (make-wrapper (string-append "datomic-" script)
                                          script))
                          '("console" "repl" "run" "rest" "shell"
                            "transactor"))
                (make-wrapper "datomic" "datomic")))))))
    (inputs
     (list bash-minimal coreutils openjdk21))
    (native-inputs
     (list unzip))
    (home-page "https://www.datomic.com/")
    (synopsis "Immutable, ACID database with Datalog queries")
    (description
     "Datomic Pro is a distributed database with an immutable log of facts,
ACID transactions, and Datalog queries.  This package installs the official
binary distribution under @file{lib/datomic-pro} and provides wrapped
launchers: @command{datomic-transactor}, @command{datomic-console},
@command{datomic-repl}, @command{datomic-shell}, @command{datomic-run},
@command{datomic-rest}, and the @command{datomic} CLI.  Logs default to
@file{$XDG_STATE_HOME/datomic/log} (or @file{~/.local/state/datomic/log});
set @env{DATOMIC_LOG_DIR} to override.  Transactor properties files should
use absolute @code{data-dir}, @code{log-dir}, and @code{pid-file} paths,
since the install directory is read-only.")
    (license license:asl2.0)))

datomic-pro
