package io.vertx.example;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.impl.StringEscapeUtils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import javax.naming.NamingException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.net.*;
import java.io.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * LB server
 *
 */
public class Server {

    /**
     * Vert.x configuration
     */
    private static Vertx vertx;
    private static HttpClient httpClient;
    private static HttpServer httpServer;

    /**
     * data structure used for LoadBalancer
     */
    private static final int PORT = 80;
    private static List<DataCenterInstance> instances;
    private static ServerSocket serverSocket;
    // specify the interval to ping data centers for health check
    private static int cooldown = 5;
    // lock to avoid race condition on instances list
    private static Object lock = new Object();

    /**
     * Main function
     *
     * @param args
     * @throws NamingException
     */
    public static void main(String[] args) throws NamingException {
        // create dataCenterList
        instances = new ArrayList<DataCenterInstance>();

        // initial server socket
        initServerSocket();

        vertx = Vertx.vertx(new VertxOptions().setWorkerPoolSize(1024));

        // Create http server
        HttpServerOptions serverOptions = new HttpServerOptions();
        httpServer = vertx.createHttpServer(serverOptions);

        // Create Router
        Router router = Router.router(vertx);
        router.route("/add").handler(Server::handleAdd);
        router.route("/remove").handler(Server::handleRemove);
        router.route("/check").handler(Server::handleCheck);
        router.route("/cooldown").handler(Server::handleCooldown);
        router.route("/").handler(routingContext -> {
            routingContext.response().end("OK");
        });

        // Listen for the request on port 8080
        httpServer.requestHandler(router::accept).listen(8080);

        // open a new thread to run the dispatcher to handle traffic from LG
        Thread launchLoadBalancer = new Thread() {
            public void run() {
                LoadBalancer loadBalancer = new LoadBalancer(serverSocket, instances);
                try {
                    loadBalancer.start();
                } catch (IOException e) {

                }
            }
        };
        launchLoadBalancer.start();
        
    }

    // check handler
    private static void handleCheck(RoutingContext routingContext) {
        HttpServerResponse response = routingContext.response().putHeader("Content-Type", "text/plain; charset=utf-8");
	System.out.println("Check");

	String healthy_dcs = "";
	synchronized (lock) {
	    // traverse the healthy data center instances
	    for (Iterator<DataCenterInstance> it = instances.iterator(); it.hasNext(); ) {
	    	DataCenterInstance dc = it.next();
	    	healthy_dcs += (dc.getName() + ",");
	    }
	}

	// remove the last comma from string if there is any
	if (healthy_dcs.length() > 0 && healthy_dcs.lastIndexOf(",") == healthy_dcs.length() - 1)
	    healthy_dcs = healthy_dcs.substring(0, healthy_dcs.length() - 1);

	System.out.println(healthy_dcs);
        // close the connection and send the response body
        response.end(healthy_dcs);
    }

    // remove handler
    private static void handleRemove(RoutingContext routingContext) {
        HttpServerResponse response = routingContext.response().putHeader("Content-Type", "text/plain; charset=utf-8");
        // to get argument from http request
        String dnsName = routingContext.request().getParam("ip");
	System.out.println("Remove " + dnsName);

	synchronized (lock) {
	    // traverse the data center instances to find target instance to remove
	    for (Iterator<DataCenterInstance> it = instances.iterator(); it.hasNext(); ) {
	    	DataCenterInstance dc = it.next();
	    	if (dc.getName().equals(dnsName)) {
		    it.remove();
		    break;
	    	}
	    }
	}

        // close the connection and send the response body
        response.end("OK");
    }

    // add handler
    private static void handleAdd(RoutingContext routingContext) {
        HttpServerResponse response = routingContext.response().putHeader("Content-Type", "text/plain; charset=utf-8");
        // to get argument from http request
        String dnsName = routingContext.request().getParam("ip");
	System.out.println("Add " + dnsName);

        // open a new thread to verify dc's ip address
        Thread t = new Thread() {
            public void run() {
                try {
		    // ping the instance to verify ip
                    URL url = new URL("http://" + dnsName);
		    URLConnection con = url.openConnection();
		    // set timeout to avoid hang
		    con.setConnectTimeout(5000);
		    con.setReadTimeout(5000);
		    BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
		    // print the url output
		    String inputLine = in.readLine();
		    if (inputLine != null) 
			System.out.println(inputLine);
		    in.close();
	            // add the healthy instance to group
	            DataCenterInstance dc = new DataCenterInstance(dnsName, "http://" + dnsName);
		    synchronized (lock) {
		        instances.add(dc);
		    }
                } catch (Exception e) {
		    System.out.println("Bad ip");
                }
            }
        };
        t.start();

        // close the connection and send the response body
        response.end("OK");
    }

    // cooldown handler
    private static void handleCooldown(RoutingContext routingContext) {
        HttpServerResponse response = routingContext.response().putHeader("Content-Type", "text/plain; charset=utf-8");
        // to get argument from http request
        String time = routingContext.request().getParam("cooldown");
	System.out.println("Cooldown is " + time);
	// set cooldown interval
	cooldown = Integer.parseInt(time);

        // open a new thread to perform routine health check
        Thread t = new Thread() {
            public void run() {
		while (true) {
		    System.out.println("health check");
	            synchronized (lock) {
		    	// traverse all instances to look for unresponsive ones and remove them
		    	for (Iterator<DataCenterInstance> it = instances.iterator(); it.hasNext(); ) {
	    		    DataCenterInstance dc = it.next();
                            try {
		    	    	// ping the ip to check health
            	            	URL url = new URL(dc.getUrl());
		    	    	URLConnection con = url.openConnection();
		    	    	con.setConnectTimeout(5000);
		    	    	con.setReadTimeout(5000);
		    	    	BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
		    	    	String inputLine = in.readLine();
			   	// check url output
			    	if (inputLine == null || !inputLine.equals("OK")) {
			            System.out.println(dc.getName() + " failed");
				    it.remove();
			    	}
		    	    	in.close();
                            } catch (Exception e) {
			    	System.out.println(dc.getName() + " failed");
			    	it.remove();
                    	    }
		    	}
		    }
		    
		    try {
		        // check every cooldown interval
		        Thread.sleep(cooldown * 1000);
		    } catch (Exception e) {
		 	System.out.println("Fail to sleep");
		    }
		}
            }
        };
        t.start();
 
        // close the connection and send the response body
        response.end("OK");
    }

    /**
     * Initialize the socket on which the Load Balancer will receive requests from the Load Generator
     */
    private static void initServerSocket() {
        try {
            serverSocket = new ServerSocket(PORT);
        } catch (IOException e) {
            System.err.println("ERROR: Could not listen on port: " + PORT);
            e.printStackTrace();
            System.exit(-1);
        }
    }

}

