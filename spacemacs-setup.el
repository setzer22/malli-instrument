
;; NOTE: I use this in my .spacemacs initialization file
;; This can be used in other emacs setups with minimal adaptation

;; Using  , e k  and , e K  to instrument / unstrument code.
;; After running instrumentation, su

(defun clojure-malli-instrument/with-require-malli (code)
    (concat "(try (do (require '[malli-instrument.core]) "
                code ")
               (catch Exception e \"malli-instrument.core not found in project!\"))"))

(defun clojure-malli-instrument/instrument-code ()
  (clojure-malli-instrument/with-require-malli "(malli-instrument.core/instrument-all!)"))

(defun clojure-malli-instrument/instrument-all! ()
  (add-hook 'cider-file-loaded-hook 'clojure-malli-instrument/instrument-all!)
  (cider-nrepl-send-sync-request
   `("op" "eval"
     "code" ,(clojure-malli-instrument/instrument-code))))

(defun clojure-malli-instrument/unstrument-code ()
  (clojure-malli-instrument/with-require-malli "(malli-instrument.core/unstrument-all!)"))

(defun clojure-malli-instrument/unstrument-all! ()
  (remove-hook 'cider-file-loaded-hook 'clojure-malli-instrument/instrument-all!)
  (cider-nrepl-send-sync-request
   `("op" "eval"
     "code" ,(clojure-malli-instrument/unstrument-code))))

(add-hook 'cider-file-loaded-hook 'clojure-malli-instrument/instrument-all!)

(evil-leader/set-key-for-mode 'clojure-mode
  "e k" (lambda () (interactive) (print (clojure-malli-instrument/instrument-all!)))
  "e K" (lambda () (interactive) (print (clojure-malli-instrument/unstrument-all!))))

