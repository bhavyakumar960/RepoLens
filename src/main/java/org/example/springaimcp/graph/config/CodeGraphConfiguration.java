package org.example.springaimcp.graph.config;

import org.example.springaimcp.graph.mcp.CodeGraphMcpTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CodeGraphConfiguration {

    /**
     * Exposes Code Graph tools as a separate ToolCallbackProvider.
     * Spring AI MCP server collects ALL ToolCallbackProvider beans via List injection,
     * so this registers alongside the existing providers automatically.
     */
    @Bean
    public ToolCallbackProvider codeGraphToolCallbackProvider(CodeGraphMcpTool codeGraphMcpTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(codeGraphMcpTool)
                .build();
    }
}
