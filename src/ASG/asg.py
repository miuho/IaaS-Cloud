#!/usr/bin/env python

import os
import sys
import time
import urllib2
from novaclient.client import Client
import ceilometerclient.client


# fetch the arguments
asg_image = sys.argv[1]
asg_flavor = sys.argv[2]
asg_name = sys.argv[3]
lb_ip = sys.argv[4]
cpu_upper = int(sys.argv[5])
cpu_lower = int(sys.argv[6])
min_instance = int(sys.argv[7])
max_instance = int(sys.argv[8])
eval_period = int(sys.argv[9])
eval_count = int(sys.argv[10])
cooldown = int(sys.argv[11])
delta = int(sys.argv[12])

# store the launched instances' ip
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

# launch a new data center
def add_dc():
	global dc_list
	# check if there are already maximum instances launched
	if (len(dc_list) >= max_instance):
		return
	try:
		# launch instance
		credentials = get_nova_credentials_v2()
		nova_client = Client(**credentials)
		image = nova_client.images.find(name=asg_image)
		flavor = nova_client.flavors.find(name=asg_flavor)
		net = nova_client.networks.find(label="private")
		nics = [{'net-id': net.id}]
		instance = nova_client.servers.create(name=asg_name, image=image, flavor=flavor, nics=nics)
		print("Creating new DC")
		time.sleep(5)
		# test the data center's ip address
		dc_ip = nova_client.servers.ips(instance)['private'][0]['addr']
		while (1):
			try:
				f = urllib2.urlopen("http://" + dc_ip, timeout = 5)
			except:
				print "DC is not ready"
				time.sleep(5)
			else:
				break
		# add the instance to load balancer	
		print("Adding " + dc_ip)
		url = "http://" + lb_ip + ":8080/add?ip=" + dc_ip
		while (1):
			try:
				f = urllib2.urlopen(url, timeout = 5)
			except:
				print "LB is not ready"
				time.sleep(5)
			else:
				break	
	except:
		print("Failed")
	else:
		# keep track of instances added
		dc_list += [dc_ip]
		print("Succeeded")

# remove a data center
def remove_dc():
	global dc_list
	# check if there are already minimum instances removed
	if (len(dc_list) <= min_instance):
		return
	print("Removing " + dc_list[0])
	# remove the instance from load balancer	
	url = "http://" + lb_ip + ":8080/remove?ip=" + dc_list[0]
	while (1):
		try:
			f = urllib2.urlopen(url, timeout = 5)
		except:
			print "LB is not ready"
			time.sleep(5)
		else:
			break	
	# keep track of removed instance
	del dc_list[0]
	print("Succeeded")

# calculate the average cpu utilization of instances in load balancer
def get_average_cpu():
	# get credentials
	credentials = get_nova_credentials_v2()
	cclient = ceilometerclient.client.get_client('2', os_username='admin', os_password='labstack', os_tenant_name='demo', os_auth_url='http://127.0.0.1:5000/v2.0')
	# get the most recent cpu utilization samples
	query = [dict(field='meter',op='eq',value='cpu_util')]
	results = cclient.new_samples.list(q=query, limit=(1 + len(dc_list)))
	cpu = 0.0
	# sum all results of data centers
	for r in results:
		# only add data centers
		if (r.metadata['display_name'] == asg_name):
			print r.timestamp + " *** " + str(r.volume)
			cpu += r.volume
	# calculate the average
	return cpu / len(dc_list)

print("Initializing ASG...")	
# creates the minimum number of instances
for x in range(0, min_instance):
	add_dc()

# keep track of the number of hitting threshold
scale_in_count = 0
scale_out_count = 0

print("Begin ASG...")	
# begin auto-scaling
while (1):
	# check cpu utilization after each period
	time.sleep(eval_period)

	# check average cpu utilization
	cpu = get_average_cpu()
	print(cpu)

	# keep track the count of hitting threshold
	if (cpu > cpu_upper):
		print("Hit upper")
		scale_out_count += 1
		scale_in_count = 0
	if (cpu < cpu_lower):
		print("Hit lower")
		scale_out_count = 0
		scale_in_count += 1
		
	# check if ready to scale out
	if (scale_out_count >= eval_count):
		print("Scale out")
		for x in range(0, delta):
			add_dc()
		scale_out_count = 0
		# wait for cooldown period
		time.sleep(cooldown)

	# check if ready to scale in
	if (scale_in_count >= eval_count):
		print("Scale in")
		for x in range(0, delta):
			remove_dc()
		scale_in_count = 0
		# wait for cooldown period
		time.sleep(cooldown)


