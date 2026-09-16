package com.example.aitranslator.exception;

import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException exception) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .findFirst().map(error -> error.getDefaultMessage()).orElse("请求参数无效");
        return problem(HttpStatus.BAD_REQUEST, "请求参数错误", detail);
    }

    @ExceptionHandler({InvalidTranslationRequestException.class, InvalidDocumentException.class, IllegalArgumentException.class})
    ProblemDetail badRequest(RuntimeException exception) {
        return problem(HttpStatus.BAD_REQUEST, "请求无法处理", exception.getMessage());
    }

    @ExceptionHandler(TransientAiException.class)
    ProblemDetail aiUnavailable(TransientAiException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "翻译服务暂时不可用", "DeepSeek 请求超时或触发限流，请稍后重试");
    }

    @ExceptionHandler(NonTransientAiException.class)
    ProblemDetail aiRejected(NonTransientAiException exception) {
        return problem(HttpStatus.BAD_GATEWAY, "翻译服务调用失败", "请检查 DeepSeek API Key 和模型配置");
    }

    @ExceptionHandler(InvalidAiResponseException.class)
    ProblemDetail aiMalformed(InvalidAiResponseException exception) {
        return problem(HttpStatus.BAD_GATEWAY, "翻译服务响应异常", "DeepSeek 返回的翻译结果格式异常，请稍后重试");
    }

    @ExceptionHandler(ResourceAccessException.class)
    ProblemDetail aiNetwork(ResourceAccessException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "翻译服务暂时不可用", "无法连接 DeepSeek，请检查网络后重试");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
