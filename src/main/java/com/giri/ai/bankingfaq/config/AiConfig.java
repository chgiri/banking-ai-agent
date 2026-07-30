package com.giri.ai.bankingfaq.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AiConfig {

    /**
     * This overrides Spring's auto-configuration.
     * By marking OpenAI with @Primary, any general injection
     * defaults to OpenAI.
     */
    @Bean
    @Primary
    public ChatClient.Builder defaultChatClientBuilder(
            @Qualifier("googleGenAiChatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }

}