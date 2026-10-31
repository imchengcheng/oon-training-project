package edu.campus.rag.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import edu.campus.rag.common.Api;
import edu.campus.rag.entity.Account;
import edu.campus.rag.mapper.DataMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
  public record Identity(Long id, String role) {}

  public static Identity current() {
    return (Identity) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }

  @Bean
  PasswordEncoder passwords() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  JwtDecoder decoder(AppProperties s) {
    Api.require(
        s.jwtSecret.getBytes(StandardCharsets.UTF_8).length >= 32,
        500,
        "C-0003",
        "JWT secret must be at least 32 bytes");
    return NimbusJwtDecoder.withSecretKey(
            new SecretKeySpec(s.jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
        .macAlgorithm(MacAlgorithm.HS256)
        .build();
  }

  @Bean
  JwtEncoder encoder(AppProperties s) {
    return new NimbusJwtEncoder(
        new ImmutableSecret<>(s.jwtSecret.getBytes(StandardCharsets.UTF_8)));
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(AppProperties settings) {
    var cors = new CorsConfiguration();
    cors.setAllowedOrigins(
        Arrays.stream(settings.corsOrigin.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isEmpty())
            .distinct()
            .toList());
    cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    cors.setExposedHeaders(List.of("X-Request-Id", "Retry-After"));
    // Authentication uses an explicit Bearer token, not cross-origin cookies.
    cors.setAllowCredentials(false);
    cors.setMaxAge(3600L);
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", cors);
    return source;
  }

  @Bean
  SecurityFilterChain chain(
      HttpSecurity http,
      DataMapper db,
      JwtDecoder decoder,
      ObjectMapper mapper,
      CorsConfigurationSource corsConfigurationSource)
      throws Exception {
    var filter =
        new OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(
              HttpServletRequest req, HttpServletResponse res, FilterChain chain)
              throws ServletException, IOException {
            String auth = req.getHeader("Authorization");
            if (auth != null
                && auth.startsWith("Bearer ")
                && !req.getRequestURI().equals("/api/auth/login")) {
              try {
                var jwt = decoder.decode(auth.substring(7));
                var user = db.tx(() -> db.get(Account.class, Long.valueOf(jwt.getSubject())));
                if (user == null) throw new IllegalArgumentException();
                if (!user.enabled) {
                  write(res, mapper, req, 403, "A-0004", "账号已禁用");
                  return;
                }
                var identity = new Identity(user.id, user.roleType);
                SecurityContextHolder.getContext()
                    .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                            identity,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + user.roleType))));
              } catch (JwtException | IllegalArgumentException ex) {
                write(res, mapper, req, 401, "A-0002", "令牌无效或已过期");
                return;
              } catch (Exception ex) {
                write(res, mapper, req, 500, "C-0003", "账号状态查询失败，请稍后重试");
                return;
              }
            }
            chain.doFilter(req, res);
          }
        };
    return http.csrf(csrf -> csrf.disable())
        .cors(cors -> cors.configurationSource(corsConfigurationSource))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/auth/login")
                    .permitAll()
                    .requestMatchers("/api/qa/**")
                    .hasRole("STUDENT")
                    .requestMatchers("/api/student/**")
                    .hasRole("STUDENT")
                    .requestMatchers("/api/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) -> write(res, mapper, req, 401, "A-0002", "请先登录"))
                    .accessDeniedHandler(
                        (req, res, ex) -> write(res, mapper, req, 403, "A-0003", "当前角色无权访问")))
        .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  static void write(
      HttpServletResponse res,
      ObjectMapper mapper,
      HttpServletRequest req,
      int status,
      String code,
      String message)
      throws IOException {
    res.setStatus(status);
    res.setContentType("application/json;charset=UTF-8");
    mapper.writeValue(
        res.getOutputStream(),
        Api.response(code, message, null, (String) req.getAttribute("requestId")));
  }

  public static String token(JwtEncoder encoder, AppProperties settings, Account a) {
    var now = Instant.now();
    var claims =
        JwtClaimsSet.builder()
            .subject(a.id.toString())
            .claim("roleType", a.roleType)
            .issuedAt(now)
            .expiresAt(now.plusSeconds(settings.tokenSeconds))
            .build();
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }
}
