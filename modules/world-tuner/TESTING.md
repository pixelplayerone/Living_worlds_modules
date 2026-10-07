# Testing

Pure checks on the tuning rules, no server needed (Java 8+):

    javac -d out scripts/WorldTuner.java tests/WorldTunerTest.java
    java -cp out modules.worldtuner.WorldTunerTest

The module is compiled by the server's script compiler at Java 8 source level when it starts. The spawn-time behavior (extra monsters appearing, spawn points switching off, respawn times changing) needs a running server: set SpawnCountMultiplier = 2.0 on a test copy (the changes are made when the server finishes starting, and the log prints a 'World Tuner summary' line), stand in a hunting ground and compare monster numbers against stock.
