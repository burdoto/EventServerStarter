#!/usr/bin/bash

(cd japi || exit 1; git pull)
git pull

gradle test && gradle installDist && ./build/install/EventServerStarter/bin/EventServerStarter
