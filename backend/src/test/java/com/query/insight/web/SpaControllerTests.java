package com.query.insight.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SpaControllerTests {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SpaController()).build();

    @ParameterizedTest
    @ValueSource(strings = {"/", "/login", "/employees/01ARZ3NDEKTSV4RRFFQ69G5FAV/edit", "/evaluations/self"})
    void clientRoutesForwardToTheBundledIndex(String route) throws Exception {
        mvc.perform(get(route)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
    }
}
