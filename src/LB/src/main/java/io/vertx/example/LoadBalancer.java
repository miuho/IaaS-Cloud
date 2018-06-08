package io.vertx.example;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.*;

public class LoadBalancer {
	private static final int THREAD_POOL_SIZE = 4;
	private final ServerSocket socket;
	private final List<DataCenterInstance> instances;
	// index of the instance to handle request
	private static int i = 0;

	public LoadBalancer(ServerSocket socket, List<DataCenterInstance> instances) {
		this.socket = socket;
		this.instances = instances;
	}

	public void start() throws IOException {
		ExecutorService executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
		while (true) {
			// do nothing if no data center instance
			if (instances.size() == 0) {
				continue;
			}

			// round robin to choose next dc
			i += 1;
			i = i % instances.size();

			// handle the request
			Runnable requestHandler = new RequestHandler(socket.accept(), instances.get(i));
			executorService.execute(requestHandler);
		}
	}
}
