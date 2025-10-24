package com.dousnl.balancer;

import com.dousnl.bean.VertxAggregateProject;
import com.dousnl.bean.VertxDispatchRequest;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Vert.x 负载均衡器
 * 提供多种负载均衡算法
 */
public class VertxLoadBalancer {

    private static final Logger log = LoggerFactory.getLogger(VertxLoadBalancer.class);

    private final Vertx vertx;

    public VertxLoadBalancer(Vertx vertx) {
        this.vertx = vertx;
    }

    /**
     * 选择目标项目（默认加权轮询）
     */
    public VertxAggregateProject select(List<VertxAggregateProject> projects, VertxDispatchRequest request) {
        if (projects.isEmpty()) {
            return null;
        }

        return selectWeightedRoundRobin(projects, request);
    }

    /**
     * 轮询算法
     */
    public VertxAggregateProject selectRoundRobin(List<VertxAggregateProject> projects, VertxDispatchRequest request) {
        if (projects.isEmpty()) {
            return null;
        }

        int index = (int) (System.currentTimeMillis() % projects.size());
        return projects.get(index);
    }

    /**
     * 随机算法
     */
    public VertxAggregateProject selectRandom(List<VertxAggregateProject> projects, VertxDispatchRequest request) {
        if (projects.isEmpty()) {
            return null;
        }

        int index = ThreadLocalRandom.current().nextInt(projects.size());
        return projects.get(index);
    }

    /**
     * 加权轮询算法
     */
    public VertxAggregateProject selectWeightedRoundRobin(List<VertxAggregateProject> projects, VertxDispatchRequest request) {
        if (projects.isEmpty()) {
            return null;
        }

        int totalWeight = projects.stream().mapToInt(VertxAggregateProject::getWeight).sum();
        if (totalWeight == 0) {
            return selectRandom(projects, request);
        }

        int randomWeight = ThreadLocalRandom.current().nextInt(totalWeight);
        int currentWeight = 0;

        for (VertxAggregateProject project : projects) {
            currentWeight += project.getWeight();
            if (randomWeight < currentWeight) {
                return project;
            }
        }

        return projects.get(projects.size() - 1);
    }

    /**
     * 最少连接算法（简化版）
     */
    public VertxAggregateProject selectLeastConnections(List<VertxAggregateProject> projects, VertxDispatchRequest request) {
        if (projects.isEmpty()) {
            return null;
        }

        // 简化实现：选择第一个健康的项目
        return projects.stream()
                .filter(VertxAggregateProject::isHealthy)
                .findFirst()
                .orElse(projects.get(0));
    }
}

