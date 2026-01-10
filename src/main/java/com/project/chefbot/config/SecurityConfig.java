package com.project.chefbot.config;

import com.project.chefbot.repository.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests((requests) -> requests
                        .requestMatchers("/h2-console", "/h2-console/**", "/css/**", "/js/**", "/chef/register", "/login", "/register", "/mcp/**").permitAll()
                        .requestMatchers("/scraper/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .formLogin((form) -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/chef/new", true)
                        .permitAll()
                )
                .httpBasic(withDefaults())

                .logout((logout) -> logout.permitAll())

                .csrf(csrf -> csrf.ignoringRequestMatchers("/h2-console", "/h2-console/**", "/api/**", "/mcp/**"));

        http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(UserRepository userRepository) {
        return username -> {
            com.project.chefbot.model.User user = userRepository.findByUsername(username);
            if (user == null) throw new org.springframework.security.core.userdetails.UsernameNotFoundException("User not found");
            String role = user.getRole() != null ? user.getRole() : "USER";
            return org.springframework.security.core.userdetails.User.withUsername(user.getUsername())
                    .password(user.getPassword())
                    .roles(role)
                    .build();
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}