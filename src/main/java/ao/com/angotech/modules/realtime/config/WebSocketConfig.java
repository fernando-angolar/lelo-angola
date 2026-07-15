package ao.com.angotech.modules.realtime.config;

import ao.com.angotech.modules.realtime.security.StompAuthChannelInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Configuração WebSocket + STOMP (SPEC §11.2).
 *
 * <ul>
 *     <li>Broker simples em memória para {@code /topic} (broadcast) e {@code /queue} (pessoal).</li>
 *     <li>Prefixo {@code /app} para mensagens dirigidas a {@code @MessageMapping}.</li>
 *     <li>Prefixo {@code /user} para destinos pessoais ({@code /user/queue/...}).</li>
 *     <li>Endpoint de handshake {@code /ws} com SockJS — o transporte WebSocket puro fica
 *         exposto em {@code /ws/websocket} (usado pelo lelo-mobile).</li>
 * </ul>
 *
 * A autenticação JWT do handshake é feita no canal de entrada via
 * {@link StompAuthChannelInterceptor} (SPEC §5.7).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final String[] allowedOrigins;

    public WebSocketConfig(StompAuthChannelInterceptor stompAuthChannelInterceptor,
                           @Value("${cors.allowed-origins:http://localhost:5173}") String[] allowedOrigins) {
        this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(allowedOrigins)
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
