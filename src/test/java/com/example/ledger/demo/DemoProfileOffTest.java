package com.example.ledger.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import com.example.ledger.support.AbstractIntegrationTest;

/** Without the demo profile, the demo endpoints simply do not exist. */
class DemoProfileOffTest extends AbstractIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void demoEndpointsAreAbsentWithoutTheDemoProfile() throws Exception {
        assertThat(context.getBeanProvider(DemoService.class).getIfAvailable()).isNull();
        api.getJsonAnonymous("/api/demo/state").andExpect(status().isNotFound());
        // No controller: a POST falls through to the static-resource handler, which only serves
        // GET/HEAD, so Spring answers 405 (or 404). Either way nothing runs.
        int status = api.postJsonAnonymous("/api/demo/race", """
                {"mode": "NAIVE", "transfers": 10}""").andReturn().getResponse().getStatus();
        assertThat(status).isIn(404, 405);
        assertThat(jdbc.queryForObject("select count(*) from accounts where kind = 'DEMO'", Long.class)).isZero();
    }
}
