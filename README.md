Flight Recorder Runner
======================

* To build: ./gradlew build
* To start a recording: RECORDING=$(java -jar build/libs/flightrecorderrunner.jar start 192.168.61.161:6969 5000)
* To dump a recording: java -jar build/libs/flightrecorderrunner.jar dump 192.168.61.161:6969 $RECORDING dump.jfr
