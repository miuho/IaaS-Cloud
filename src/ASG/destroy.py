#!/usr/bin/env python

import os
import sys
import time
import urllib2
from novaclient.client import Client

# fetch the argument
lb_ip = sys.argv[1]

# keep track of the list of instances
dc_list = []


# get credentials
def get_nova_credentials_v2():
	d = {}
	d['version'] = '2'
	d['username'] = 'admin'
	d['api_key'] = 'labstack'
	d['auth_url'] = 'http://127.0.0.1:5000/v2.0'
	d['project_id'] = 'demo'
	return d

# ask the load balancer for a list of connected instances
print("Destroying ASG ...")
url = "http://" + lb_ip + ":8080/check"
while (1):
	try:
		f = urllib2.urlopen(url, timeout = 5)
		dc_list = f.read().split(",")
	except:
		print "LB is not ready"
		time.sleep(5)
	else:
		break	
	
print("Removing instances ...")
# remove the instances from load balancer
for dc_ip in dc_list:
	# remove the instance from load balancer	
	url = "http://" + lb_ip + ":8080/remove?ip=" + dc_ip
	while (1):
		try:
			f = urllib2.urlopen(url, timeout = 5)
		except:
			print "LB is not ready"
			time.sleep(5)
		else:
			break	
	print("Removed " + dc_ip)


# terminate instances
credentials = get_nova_credentials_v2()
nova_client = Client(**credentials)
# get the list of servers launched
servers_list = nova_client.servers.list()

# find those servers connected to load balancers
for s in servers_list:
	# test the data center's ip address
	dc_ip = nova_client.servers.ips(s)['private'][0]['addr']
	if dc_ip in dc_list:
		print("Terminating instance " + dc_ip)
		nova_client.servers.delete(s)

