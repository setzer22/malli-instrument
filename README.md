# malli-instrument

This repository implements instrumentation for [malli](https://github.com/metosin/malli/) with a very similar API to clojure.spec.alpha's instrumentation.

## Rationale

Malli is awesome, but instrumentation doesn't seem to be implemented yet, so I scratched my own itch. There are libraries like [aave](https://github.com/teknql/aave) offering similar features on top of malli. However, to the best of my knowledge this is the only library that uses the same instrumentation API as clojure.spec.alpha, where instrumentation occurs in a dedicated command, and with no special `defn` wrappers.

## Tool maturity

This library is in a very early stage. It works but is not yet intended for production use. Please feel free to suggest new features or report any bugs in the issue tracker. 

## Installation

Using deps.edn and git dependencies:

```clj
{setzer22/malli-instrument {:git/url "https://github.com/setzer22/malli-instrument.git" :sha "6d61b6f6e351cbf0faf85c57ef66bd19c4dc8b88"}}
```
Being in a very early stage, this library is not published in any maven repository, so other forms of distribution are currently unsupported.

## Usage example

```clojure
(require '[malli.core :as m])
(require '[malli-instrument.core :as mi])

(m/=> foo [:=> [:cat int? int?] string?)
(defn foo [x, y]
  (+ x y))
  
(mi/instrument-all!)

(foo 1 2) ;; Throws ex-info with message "Function returned wrong output"
          ;; and data {:error ["should be a string"], :value 3}
```

 ## Integration with existing tooling
 
 ### Spacemacs
 Currently, this library offers limited support for emacs (more specifically Spacemacs).
 This enables the following features:
 - Dedicated commands in `clojure-mode`: `, e k` (instrument all functions) and `, e K` (unstrument all functions)
 - Reloading all instrumentations on buffer reload with `, e b` (i.e. `cider-eval-buffer`)
 
 To enable this configuration, copy the contents of [spacemacs-setup.el](spacemacs-setup.el) in your `.spacemacs`' `dotspacemacs/user-config` function, or any other equivalent initialization code.
 
 
