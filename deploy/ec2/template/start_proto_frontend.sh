#!/bin/bash
# Start Prototype frontend

LOG=/disk1/sparrow/protoFrontend.log

APPCHK=$(ps aux | grep -v grep | grep -c ProtoFrontend)

if [ ! $APPCHK = '0' ]; then
  echo "Frontend already running, cannot start it."
  exit 1;
fi

nohup java -cp ./sparrow/sparrow-1.0-SNAPSHOT.jar edu.berkeley.sparrow.prototype.ProtoFrontend -c frontend.conf > $LOG 2>&1 &
PID=$!
echo "Logging to $LOG"
sleep 1
if ! kill -0 $PID > /dev/null 2>&1; then
  echo "Proto frontend failed to start"
  exit 1;
else
  echo "Proto frontend started with pid $PID"
  exit 0;
fi
