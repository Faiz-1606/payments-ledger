package com.example.ledger.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Small HTTP "client" for tests, built on MockMvc (which calls the real controllers, filters,
 * security chain and exception handlers in-process, without opening a network socket).
 *
 * <p>It does the authentication bookkeeping so tests read like the business scenario:
 * every registered user's token is remembered, every opened account remembers its owner,
 * and {@link #transfer} automatically acts as the owner of the source account. Deposits and
 * plain reads use the admin token.
 */
public final class TestApi {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final MockMvc mvc;
    private final String adminToken;
    private final Map<Long, String> tokenByUserId = new ConcurrentHashMap<>();
    private final Map<Long, Long> ownerByAccountId = new ConcurrentHashMap<>();
    private volatile String bystanderToken;

    public TestApi(MockMvc mvc, String adminToken) {
        this.mvc = mvc;
        this.adminToken = adminToken;
    }

    public String adminToken() {
        return adminToken;
    }

    public ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request);
    }

    // ---- raw requests ----------------------------------------------------------------

    /** POST JSON with no Authorization header. */
    public ResultActions postJsonAnonymous(String url, String json) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    public ResultActions postJsonAs(String token, String url, String json) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json).header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    /** GET as the admin (who may read everything). */
    public ResultActions getJson(String url) throws Exception {
        return getJsonAs(adminToken, url);
    }

    public ResultActions getJsonAs(String token, String url) throws Exception {
        return mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    public ResultActions getJsonAnonymous(String url) throws Exception {
        return mvc.perform(get(url));
    }

    /** POST with a fresh random Idempotency-Key (for money endpoints). */
    public ResultActions postMoney(String token, String url, String json) throws Exception {
        return postMoney(token, url, json, newKey());
    }

    /** POST with the given Idempotency-Key; {@code null} sends no header at all. */
    public ResultActions postMoney(String token, String url, String json, String idempotencyKey) throws Exception {
        MockHttpServletRequestBuilder request = post(url).contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, bearer(token));
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    // ---- users & accounts ------------------------------------------------------------

    /** Registers and logs in a new user. */
    public TestUser registerUser() throws Exception {
        String email = "user" + SEQUENCE.incrementAndGet() + "-" + UUID.randomUUID() + "@example.com";
        String password = "password-" + SEQUENCE.get();
        MvcResult registered = postJsonAnonymous("/api/auth/register", """
                {"fullName": "Test User", "email": "%s", "password": "%s"}""".formatted(email, password))
                .andExpect(status().isCreated())
                .andReturn();
        long userId = readLong(registered, "$.id");
        String token = login(email, password);
        tokenByUserId.put(userId, token);
        return new TestUser(userId, email, password, token);
    }

    public String login(String email, String password) throws Exception {
        MvcResult result = postJsonAnonymous("/api/auth/login", """
                {"email": "%s", "password": "%s"}""".formatted(email, password))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    /** Registers a user and returns only the id (the token is remembered internally). */
    public long createUser() throws Exception {
        return registerUser().id();
    }

    public long openAccount(long ownerId) throws Exception {
        MvcResult result = mvc.perform(post("/api/accounts").header(HttpHeaders.AUTHORIZATION, bearer(tokenOf(ownerId))))
                .andExpect(status().isCreated())
                .andReturn();
        long accountId = readLong(result, "$.id");
        ownerByAccountId.put(accountId, ownerId);
        return accountId;
    }

    /** New user with one wallet holding {@code paise} (0 means no deposit). */
    public long newAccountWithBalance(long paise) throws Exception {
        long accountId = openAccount(createUser());
        if (paise > 0) {
            deposit(accountId, paise).andExpect(status().isCreated());
        }
        return accountId;
    }

    public String tokenOf(long userId) {
        String token = tokenByUserId.get(userId);
        if (token == null) {
            throw new IllegalArgumentException("No token known for user " + userId);
        }
        return token;
    }

    public long ownerOf(long accountId) {
        return ownerByAccountId.get(accountId);
    }

    /** Token of the account's owner; for unknown or system accounts, a user who owns nothing relevant. */
    public String tokenForAccount(long accountId) throws Exception {
        Long owner = ownerByAccountId.get(accountId);
        return owner != null ? tokenOf(owner) : bystanderToken();
    }

    private String bystanderToken() throws Exception {
        if (bystanderToken == null) {
            bystanderToken = registerUser().token();
        }
        return bystanderToken;
    }

    // ---- money -----------------------------------------------------------------------

    /** Deposits are ADMIN-only, so they always use the admin token. */
    public ResultActions deposit(long accountId, long amountPaise) throws Exception {
        return deposit(newKey(), accountId, amountPaise);
    }

    public ResultActions deposit(String idempotencyKey, long accountId, long amountPaise) throws Exception {
        return postMoney(adminToken, "/api/deposits", """
                {"accountId": %d, "amountPaise": %d}""".formatted(accountId, amountPaise), idempotencyKey);
    }

    /** Transfers act as the owner of the source account. */
    public ResultActions transfer(long fromAccountId, long toAccountId, long amountPaise) throws Exception {
        return transfer(newKey(), fromAccountId, toAccountId, amountPaise);
    }

    public ResultActions transfer(String idempotencyKey, long fromAccountId, long toAccountId, long amountPaise)
            throws Exception {
        return transferAs(tokenForAccount(fromAccountId), idempotencyKey, fromAccountId, toAccountId, amountPaise);
    }

    public ResultActions transferAs(String token, String idempotencyKey, long fromAccountId, long toAccountId,
                                    long amountPaise) throws Exception {
        return postMoney(token, "/api/transfers", """
                {"fromAccountId": %d, "toAccountId": %d, "amountPaise": %d}"""
                .formatted(fromAccountId, toAccountId, amountPaise), idempotencyKey);
    }

    /** Raw JSON transfer as the owner of {@code fromAccountId} (for malformed-body tests). */
    public ResultActions postTransferJson(long fromAccountId, String json) throws Exception {
        return postMoney(tokenForAccount(fromAccountId), "/api/transfers", json);
    }

    public long balance(long accountId) throws Exception {
        MvcResult result = getJson("/api/accounts/" + accountId).andExpect(status().isOk()).andReturn();
        return readLong(result, "$.balancePaise");
    }

    public static long readLong(MvcResult result, String jsonPath) throws Exception {
        Number value = JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
        return value.longValue();
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    public record TestUser(long id, String email, String password, String token) {
    }
}
