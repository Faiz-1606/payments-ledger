package com.example.ledger.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Small HTTP "client" for tests, built on MockMvc (which calls the real controllers, filters
 * and exception handlers in-process, without opening a network socket).
 */
public final class TestApi {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final MockMvc mvc;

    public TestApi(MockMvc mvc) {
        this.mvc = mvc;
    }

    public ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request);
    }

    public ResultActions postJson(String url, String json) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    public ResultActions getJson(String url) throws Exception {
        return mvc.perform(get(url));
    }

    /** POST with a fresh random Idempotency-Key (for money endpoints). */
    public ResultActions postMoney(String url, String json) throws Exception {
        return postMoney(url, json, newKey());
    }

    /** POST with the given Idempotency-Key; {@code null} sends no header at all. */
    public ResultActions postMoney(String url, String json, String idempotencyKey) throws Exception {
        MockHttpServletRequestBuilder request = post(url).contentType(MediaType.APPLICATION_JSON).content(json);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    public static String newKey() {
        return UUID.randomUUID().toString();
    }

    // ---- users & accounts ------------------------------------------------------------

    public long createUser() throws Exception {
        String email = "user" + SEQUENCE.incrementAndGet() + "-" + UUID.randomUUID() + "@example.com";
        MvcResult result = postJson("/api/users", """
                {"fullName": "Test User", "email": "%s"}""".formatted(email))
                .andExpect(status().isCreated())
                .andReturn();
        return readLong(result, "$.id");
    }

    public long openAccount(long ownerId) throws Exception {
        MvcResult result = postJson("/api/accounts", """
                {"ownerId": %d}""".formatted(ownerId))
                .andExpect(status().isCreated())
                .andReturn();
        return readLong(result, "$.id");
    }

    /** New user with one wallet holding {@code paise} (0 means no deposit). */
    public long newAccountWithBalance(long paise) throws Exception {
        long accountId = openAccount(createUser());
        if (paise > 0) {
            deposit(accountId, paise).andExpect(status().isCreated());
        }
        return accountId;
    }

    // ---- money -----------------------------------------------------------------------

    public ResultActions deposit(long accountId, long amountPaise) throws Exception {
        return deposit(newKey(), accountId, amountPaise);
    }

    public ResultActions deposit(String idempotencyKey, long accountId, long amountPaise) throws Exception {
        return postMoney("/api/deposits", """
                {"accountId": %d, "amountPaise": %d}""".formatted(accountId, amountPaise), idempotencyKey);
    }

    public ResultActions transfer(long fromAccountId, long toAccountId, long amountPaise) throws Exception {
        return transfer(newKey(), fromAccountId, toAccountId, amountPaise);
    }

    public ResultActions transfer(String idempotencyKey, long fromAccountId, long toAccountId, long amountPaise)
            throws Exception {
        return postMoney("/api/transfers", """
                {"fromAccountId": %d, "toAccountId": %d, "amountPaise": %d}"""
                .formatted(fromAccountId, toAccountId, amountPaise), idempotencyKey);
    }

    public long balance(long accountId) throws Exception {
        MvcResult result = getJson("/api/accounts/" + accountId).andExpect(status().isOk()).andReturn();
        return readLong(result, "$.balancePaise");
    }

    public static long readLong(MvcResult result, String jsonPath) throws Exception {
        Number value = JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
        return value.longValue();
    }
}
