#!/bin/bash

mvn clean compile assembly:single

echo "============================== Run jar =============================="

java -jar target/RepoSnapshotTool-1.0-SNAPSHOT-jar-with-dependencies.jar "$@"
