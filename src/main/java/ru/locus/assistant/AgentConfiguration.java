package ru.locus.assistant;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Исполнитель Реплики Помощника: настройки, рабочие места и окружение
 * (ADR-0054; design.md agent-runner, Р5, Р7, Р13).
 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfiguration {

    @Bean
    public AgentWorkspaces agentWorkspaces(AgentProperties properties) {
        return new AgentWorkspaces(properties.workspaces());
    }

    @Bean
    public AgentEnvironment agentEnvironment() {
        return new AgentEnvironment(System.getenv());
    }
}
