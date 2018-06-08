#!/usr/bin/env bash

# This bash script should kill your ASG running in the background.

# read parameters
source ./configure

# destroy the auto-scaling group
python destroy.py $LB_IPADDR

# terminate the running asg.py in background with input job id
kill -9 $1
