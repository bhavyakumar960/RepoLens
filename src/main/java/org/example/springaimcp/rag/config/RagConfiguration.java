package org.example.springaimcp.rag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * RAG configuration.
 * Note: RAG MCP tools have been consolidated into CodeIntelligenceMcpTool for better integration.
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfiguration {
    // RAG tools now integrated into CodeIntelligenceMcpTool
}
