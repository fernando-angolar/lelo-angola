package ao.com.angotech.modules.realtime.security;

import ao.com.angotech.modules.auth.security.JwtService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

/**
 * Autentica o handshake STOMP através do JWT enviado no header {@code Authorization}
 * do frame CONNECT (SPEC §5.7).
 *
 * <p>Quando o token é válido, o {@link java.security.Principal} da sessão passa a ser o
 * utilizador autenticado — o que habilita {@code @MessageMapping} a resolver o
 * {@code Principal} e o roteamento de destinos pessoais {@code /user/queue/...}.</p>
 *
 * <p>Reutiliza a mesma lógica de validação do {@link JwtService} usada no filtro HTTP,
 * incluindo a verificação de blacklist em Redis (logout).</p>
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    public StompAuthChannelInterceptor(JwtService jwtService, UserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return message;
        }

        String jwt = authHeader.substring(7);
        try {
            String email = jwtService.extractUsername(jwt);
            if (email != null && !jwtService.isTokenBlacklisted(jwt)) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(email);
                if (jwtService.isTokenValid(jwt, userDetails)) {
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails, null, userDetails.getAuthorities());
                    accessor.setUser(authentication);
                }
            }
        } catch (Exception e) {
            // Token inválido/expirado — a conexão segue sem Principal (não autenticada).
            // A autorização por destino (SPEC §5.7) recusa depois subscrições protegidas.
        }

        return message;
    }
}
