package com.dousnl.bean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vert.x 聚合项目
 */
public class VertxAggregateProject {

    private static final Logger log = LoggerFactory.getLogger(VertxAggregateProject.class);

    private final String name;
    private final String endpoint;
    private final String serviceName;
    private final int weight;
    private volatile boolean healthy = true;

    public VertxAggregateProject(String name, String endpoint, String serviceName) {
        this(name, endpoint, serviceName, 1);
    }

    public VertxAggregateProject(String name, String endpoint, String serviceName, int weight) {
        this.name = name;
        this.endpoint = endpoint;
        this.serviceName = serviceName;
        this.weight = weight;
    }

    public String getName() { return name; }
    public String getEndpoint() { return endpoint; }
    public String getServiceName() { return serviceName; }
    public int getWeight() { return weight; }
    public boolean isHealthy() { return healthy; }

    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
        log.debug("项目 {} 健康状态更新: {}", name, healthy);
    }
}
