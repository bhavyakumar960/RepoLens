package org.example.springaimcp.rag.utils;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import io.qdrant.client.grpc.Common.PointId;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Utility to query Qdrant statistics - repositories, modules, services indexed
 */
@Slf4j
public class QdrantStatsUtil {

    public static void main(String[] args) {
        String host = System.getProperty("qdrant.host", "localhost");
        int port = Integer.parseInt(System.getProperty("qdrant.port", "6334"));
        String collection = System.getProperty("qdrant.collection", "code_chunks");

        try (QdrantClient client = new QdrantClient(QdrantGrpcClient.newBuilder(host, port, false).build())) {
            
            System.out.println("======================================");
            System.out.println("Qdrant Statistics");
            System.out.println("======================================");
            System.out.println("Host: " + host + ":" + port);
            System.out.println("Collection: " + collection);
            System.out.println();

            Map<String, Integer> repoCount = new HashMap<>();
            Map<String, Integer> moduleCount = new HashMap<>();
            Map<String, Integer> serviceCount = new HashMap<>();
            int totalPoints = 0;
            String offsetId = null;

            // Scroll through all points
            while (true) {
                ScrollPoints.Builder scrollBuilder = ScrollPoints.newBuilder()
                        .setCollectionName(collection)
                        .setLimit(1000)
                        .setWithPayload(io.qdrant.client.grpc.Points.WithPayloadSelector.newBuilder()
                                .setEnable(true)
                                .build())
                        .setWithVectors(io.qdrant.client.grpc.Points.WithVectorsSelector.newBuilder()
                                .setEnable(false)
                                .build());

                if (offsetId != null) {
                    scrollBuilder.setOffset(PointId.newBuilder()
                            .setUuid(offsetId)
                            .build());
                }

                ScrollResponse response = client.scrollAsync(scrollBuilder.build()).get();

                if (response.getResultList().isEmpty()) {
                    break;
                }

                for (var point : response.getResultList()) {
                    totalPoints++;
                    var payload = point.getPayload();

                    // Count repos
                    if (payload.containsKey("repo")) {
                        String repo = payload.get("repo").getStringValue();
                        repoCount.put(repo, repoCount.getOrDefault(repo, 0) + 1);
                    }

                    // Count modules
                    if (payload.containsKey("module")) {
                        String module = payload.get("module").getStringValue();
                        moduleCount.put(module, moduleCount.getOrDefault(module, 0) + 1);
                    }

                    // Count services
                    if (payload.containsKey("service")) {
                        String service = payload.get("service").getStringValue();
                        serviceCount.put(service, serviceCount.getOrDefault(service, 0) + 1);
                    }

                    offsetId = point.getId().getUuid();
                }

                if (response.getResultList().size() < 1000) {
                    break;
                }
            }

            System.out.println("Total Points: " + totalPoints);
            System.out.println();

            System.out.println("Repositories (" + repoCount.size() + " unique):");
            System.out.println("--------------------------------------");
            new TreeMap<>(repoCount).forEach((repo, count) ->
                    System.out.printf("  %-40s %,8d chunks%n", repo, count));

            System.out.println();
            System.out.println("Modules (" + moduleCount.size() + " unique):");
            System.out.println("--------------------------------------");
            new TreeMap<>(moduleCount).entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(20)
                    .forEach(entry ->
                            System.out.printf("  %-40s %,8d chunks%n", entry.getKey(), entry.getValue()));

            if (moduleCount.size() > 20) {
                System.out.println("  ... and " + (moduleCount.size() - 20) + " more modules");
            }

            System.out.println();
            System.out.println("Services (" + serviceCount.size() + " unique):");
            System.out.println("--------------------------------------");
            new TreeMap<>(serviceCount).forEach((service, count) ->
                    System.out.printf("  %-40s %,8d chunks%n", service, count));

        } catch (Exception e) {
            log.error("Failed to query Qdrant: {}", e.getMessage(), e);
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
