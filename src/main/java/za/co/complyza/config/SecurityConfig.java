package za.co.complyza.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Two roles:
 *  - ANALYST: ask questions (UI, /api/ask, MCP tools)
 *  - ADMIN:   everything an analyst can do, plus ingest documents and read metrics
 *
 * In-memory users keep the demo simple. Swap for OAuth2/OIDC (e.g. Entra ID) in a real bank.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/info", "/css/**", "/favicon.ico", "/error").permitAll()
                .requestMatchers("/admin/**", "/actuator/**").hasRole("ADMIN")
                .anyRequest().hasAnyRole("ANALYST", "ADMIN"))
            // JSON/multipart APIs are called with HTTP Basic, not browser forms
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/admin/**", "/sse", "/mcp/**"))
            .formLogin(Customizer.withDefaults())
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService users(AppProperties props, PasswordEncoder encoder) {
        var admin = User.withUsername("admin")
                .password(encoder.encode(props.security().adminPassword()))
                .roles("ADMIN", "ANALYST")
                .build();
        var analyst = User.withUsername("analyst")
                .password(encoder.encode(props.security().analystPassword()))
                .roles("ANALYST")
                .build();
        return new InMemoryUserDetailsManager(admin, analyst);
    }
}
