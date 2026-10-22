package com.gmc.backend.it;

import com.gmc.backend.model.Role;
import com.gmc.backend.model.User;
import com.gmc.backend.repository.RoleRepository;
import com.gmc.backend.repository.UserRepository;
import com.gmc.backend.security.CustomUserDetails;
import com.gmc.backend.security.JwtTokenProvider;
import com.gmc.backend.service.EmailService;
import com.gmc.backend.service.StripeGateway;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Base for API integration tests: the full Spring context, real security filter chain and
 * real database (H2 locally, MySQL in CI via DB_URL). Each test runs in a transaction that is
 * rolled back afterwards, so tests do not see each other's data.
 *
 * <p>Outbound services (email, Stripe) are mocked so tests never reach the internet.
 */
@SpringBootTest(properties = "app.upload-dir=target/it-uploads/")
@AutoConfigureMockMvc
@Transactional
public abstract class IntegrationTestBase {

    protected static final String PASSWORD = "Password@123";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected RoleRepository roleRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    protected EmailService emailService;

    @MockitoBean
    protected StripeGateway stripeGateway;

    /** Saves an active user with the given role (ADMIN, CUSTOMER or TECHNICIAN) and password {@link #PASSWORD}. */
    protected User createUser(String roleName) {
        Role role = roleRepository
                .findByRoleName(roleName)
                .orElseGet(() ->
                        roleRepository.save(Role.builder().roleName(roleName).build()));
        String unique = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setName(roleName.charAt(0) + roleName.substring(1).toLowerCase() + " " + unique);
        user.setEmail(roleName.toLowerCase() + "-" + unique + "@example.com");
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(role);
        user.setPhone("0771234567");
        user.setAddress("12 Main Street, Colombo");
        user.setActive(true);
        return userRepository.save(user);
    }

    protected User createCustomer() {
        return createUser("CUSTOMER");
    }

    protected User createAdmin() {
        return createUser("ADMIN");
    }

    protected User createTechnician() {
        return createUser("TECHNICIAN");
    }

    /** Authorization header value for the user, signed the same way as a real login. */
    protected String bearer(User user) {
        return "Bearer "
                + jwtTokenProvider.generateToken(
                        new CustomUserDetails(user),
                        Map.of("role", user.getRole().getRoleName(), "userId", user.getUserId()));
    }

    /** Gives each request its own client IP so the per-IP rate limits never trip across tests. */
    protected static RequestPostProcessor uniqueClientIp() {
        return request -> {
            request.setRemoteAddr("10.0." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250));
            return request;
        };
    }

    protected String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }
}
