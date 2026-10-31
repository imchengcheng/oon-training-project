package edu.campus.rag.service.impl;

import static edu.campus.rag.common.Api.*;

import edu.campus.rag.common.RateLimits;
import edu.campus.rag.common.config.AppProperties;
import edu.campus.rag.common.config.SecurityConfig;
import edu.campus.rag.dto.LoginRequest;
import edu.campus.rag.entity.Account;
import edu.campus.rag.mapper.DataMapper;
import edu.campus.rag.service.AuthService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceImpl implements AuthService {
  private final DataMapper mapper;
  private final AppProperties properties;
  private final PasswordEncoder passwords;
  private final JwtEncoder jwt;
  private final RateLimits limits;
  private final String dummyHash;

  public AuthServiceImpl(
      DataMapper mapper,
      AppProperties properties,
      PasswordEncoder passwords,
      JwtEncoder jwt,
      RateLimits limits) {
    this.mapper = mapper;
    this.properties = properties;
    this.passwords = passwords;
    this.jwt = jwt;
    this.limits = limits;
    this.dummyHash = passwords.encode(UUID.randomUUID().toString());
  }

  @Override
  public Map<String, Object> login(LoginRequest request, String remoteAddress) {
    limits.take("login:" + remoteAddress, properties.loginLimit);
    String username = text(request.username(), 64, "C-0001");
    require(
        request.password() != null
            && !request.password().isEmpty()
            && request.password().getBytes(StandardCharsets.UTF_8).length <= 72,
        400,
        "C-0001",
        "密码长度无效");
    var account =
        mapper.tx(() -> mapper.one(Account.class, "from Account where username=?1", username));
    boolean match =
        passwords.matches(request.password(), account == null ? dummyHash : account.passwordHash);
    require(account != null && match, 401, "A-0001", "用户名或密码错误");
    require(account.enabled, 403, "A-0004", "账号已禁用");
    return map(
        "token",
        SecurityConfig.token(jwt, properties, account),
        "tokenType",
        "Bearer",
        "expiresIn",
        properties.tokenSeconds,
        "user",
        map(
            "userId",
            account.id.toString(),
            "username",
            account.username,
            "realName",
            account.realName,
            "roleType",
            account.roleType,
            "studentNo",
            account.studentNo));
  }
}
