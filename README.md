[![Release](https://jitpack.io/v/sybila/terminal-components.svg)](https://jitpack.io/#sybila/terminal-components)
[![Build Status](https://travis-ci.org/sybila/terminal-components.svg?branch=master)](https://travis-ci.org/sybila/terminal-components)
[![License](https://img.shields.io/badge/License-GPL%20v3-blue.svg?style=flat)](https://github.com/sybila/ode-generator/blob/master/LICENSE.txt)

# Terminal Components

This module is part of Pithya and enables computation of terminal components (which correspond to attractors in continuous systems).

You can try Pithya online at [pithya.ics.muni.cz](http://pithya.ics.muni.cz).

It can be used as a command line utility with the following options:
```
terminal-components [options...]
 --algorithm-type [LOCAL | DIST] : Specify the type of the algorithm. (default:
                                   LOCAL)
 --cut-to-range                  : Thresholds above and below original variable
                                   range will be discarded. (default: false)
 --disable-heuristic             : Use to disable the set size state choosing
                                   heuristic (default: false)
 --disable-self-loops            : Disable selfloop creation in transition
                                   system. (default: false)
 --fast-approximation            : Use faster, but less precise version of
                                   linear approximation. (default: false)
 --parallelism N                 : Recommended number of used threads.
                                   (default: 8)
 -h (--help)                     : Print help message (default: false)
 -lo (--log-output) VAL          : Name of stream to which logging info should
                                   be printed. Filename, stdout, stderr or
                                   null. (default: stdout)
 -m (--model) FILE               : Path to the .bio file from which the model
                                   should be loaded.
 -ro (--result-output) VAL       : Name of stream to which the results should
                                   be printed. Filename, stdout, stderr or
                                   null. (default: stdout)
```

To build this repo locally, run `./gradlew installDist` in the root folder. Binaries will then appear in `build/install/terminal-components`.
