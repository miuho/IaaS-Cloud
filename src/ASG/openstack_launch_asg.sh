#!/bin/bash
# Our submitter will call this bash scripts to launch an ASG

# IMPORTANT:
#   This scripts reads parameters from ./configure using the source command
#   Reference: https://bash.cyberciti.biz/guide/Source_command
#   DO NOT CHANGE THE FOLLOWING LINE
source ./configure

# IMPORTANT:
#   Shell scripts is not enough for implementing an ASG. What you need to do is
#   to write a Java/Python program and call it by this shell scripts. Make sure
#   all parameters specified in ./configure is passed to your program.

# IMPORTANT:
#   Run your program in background

#   Call your program here

python asg.py $ASG_IMAGE $ASG_FLAVOR $ASG_NAME $LB_IPADDR $CPU_UPPER_TRES $CPU_LOWER_TRES $MIN_INSTANCE $MAX_INSTANCE $EVAL_PERIOD $EVAL_COUNT $COOLDOWN $DELTA > /dev/null &

# echo the job id
echo $!
