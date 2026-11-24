package za.co.complyza.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import za.co.complyza.mcp.RegulationTools;

@Configuration
public class AiConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /** Exposes {@link RegulationTools} to MCP clients (Claude Desktop, IDEs, other agents). */
    @Bean
    ToolCallbackProvider regulationToolCallbacks(RegulationTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
