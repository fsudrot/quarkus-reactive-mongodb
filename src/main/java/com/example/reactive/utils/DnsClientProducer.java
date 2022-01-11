package com.example.reactive.utils;
import io.quarkus.arc.Arc;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.dns.DnsClient;
import org.eclipse.microprofile.config.ConfigProvider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

public abstract class DnsClientProducer {

    private static DnsClient client;

    public static synchronized io.vertx.mutiny.core.dns.DnsClient createDnsClient() {
        if (client == null) {
            Vertx vertx = Arc.container().instance(Vertx.class).get(); // Use the managed instance, so the client is closed and use the right event loops
            int port = ConfigProvider.getConfig().getOptionalValue("mongo.dns.port", Integer.class).orElse(53); // Extract configuration
            String server = ConfigProvider.getConfig().getOptionalValue("mongo.dns.server", String.class).orElse(nameServers().get(0));
            client = vertx.createDnsClient(port, server);
        }
        return client;
    }

    public static List<String> nameServers() {
        String file = "/etc/resolv.conf";
        Path p = Paths.get(file);
        List<String> nameServers = new ArrayList<>();
        String line;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(Files.newInputStream(p)))) {
            while ((line = br.readLine()) != null) {
                StringTokenizer st = new StringTokenizer(line);
                if (st.nextToken().startsWith("nameserver")) {
                    nameServers.add(st.nextToken());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return nameServers;
    }
}

