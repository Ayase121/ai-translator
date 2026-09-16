package com.example.aitranslator.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void mapsInvalidModelResponseToBadGateway() {
        ProblemDetail problem = handler.aiMalformed(new InvalidAiResponseException("invalid"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY.value());
        assertThat(problem.getDetail()).contains("格式");
    }

    @Test
    void mapsNetworkFailureToServiceUnavailable() {
        ProblemDetail problem = handler.aiNetwork(new ResourceAccessException("DNS failure"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    }
}
