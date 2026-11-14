package org.example.springaimcp.codeintel.config;

import org.example.springaimcp.codeintel.mcp.CodeIntelligenceMcpTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CodeIntelConfiguration {

    /**
     * Explicitly create CodeIntelProperties bean with configuration properties binding.
     * This avoids duplicate bean definition issues.
     */
    @Bean
    @ConfigurationProperties(prefix = "codeintel.retrieval")
    public CodeIntelProperties codeIntelProperties() {
        return new CodeIntelProperties();
    }

    /**
     * Exposes Code Intelligence tools as a separate ToolCallbackProvider.
     * Spring AI MCP server collects ALL ToolCallbackProvider beans via List injection,
     * so this registers alongside the existing providers automatically.
     */
    @Bean
    public ToolCallbackProvider codeIntelligenceToolCallbackProvider(CodeIntelligenceMcpTool codeIntelligenceMcpTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(codeIntelligenceMcpTool)
                .build();
    }
}
