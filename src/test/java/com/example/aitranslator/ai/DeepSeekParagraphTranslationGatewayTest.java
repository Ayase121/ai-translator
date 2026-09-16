package com.example.aitranslator.ai;

import com.example.aitranslator.exception.InvalidAiResponseException;
import com.example.aitranslator.domain.DocumentType;
import com.example.aitranslator.domain.Language;
import com.example.aitranslator.domain.TranslationStyle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.ResponseFormat;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DeepSeekParagraphTranslationGatewayTest {

    @Test
    void translatesSingleSegmentAsPlainParagraphAndKeepsTranslationOptions() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec call = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(call);
        when(call.system(anyString())).thenReturn(call);
        when(call.user(anyString())).thenReturn(call);
        when(call.call()).thenReturn(response);
        when(response.content()).thenReturn("完整段落译文");

        DeepSeekParagraphTranslationGateway gateway = new DeepSeekParagraphTranslationGateway(builder, new ObjectMapper());
        Map<String, String> translated = gateway.translate(request());

        assertThat(translated).containsExactlyInAnyOrderEntriesOf(Map.of("s1", "完整段落译文"));
        verify(response, times(1)).content();
        verify(call).system(org.mockito.ArgumentMatchers.<String>argThat(prompt ->
                prompt.contains("source_language") && prompt.contains("target_language")
                        && prompt.contains("document_type") && prompt.contains("translation_style")
                        && prompt.contains("glossary") && prompt.contains("protected_terms")
                        && prompt.contains("previous_context")));
        verify(call).user("source");
    }

    @Test
    void rejectsBlankSingleSegmentTranslation() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec call = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(call);
        when(call.system(anyString())).thenReturn(call);
        when(call.user(anyString())).thenReturn(call);
        when(call.call()).thenReturn(response);
        when(response.content()).thenReturn("  ", "\n");

        DeepSeekParagraphTranslationGateway gateway = new DeepSeekParagraphTranslationGateway(builder, new ObjectMapper());

        assertThatThrownBy(() -> gateway.translate(request()))
                .isInstanceOf(InvalidAiResponseException.class);
        verify(response, times(2)).content();
    }

    @Test
    void translatesMultiSegmentJsonObjectWithNativeJsonMode() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec call = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(call);
        when(call.system(anyString())).thenReturn(call);
        when(call.user(anyString())).thenReturn(call);
        when(call.options(org.mockito.ArgumentMatchers.any(DeepSeekChatOptions.Builder.class))).thenReturn(call);
        when(call.call()).thenReturn(response);
        when(response.content()).thenReturn("{\"segments\":[{\"id\":\"s1\",\"translation\":\"第一段\"},{\"id\":\"s2\",\"translation\":\"第二段\"}]}");

        DeepSeekParagraphTranslationGateway gateway = new DeepSeekParagraphTranslationGateway(builder, new ObjectMapper());
        Map<String, String> translated = gateway.translate(multiSegmentRequest());

        assertThat(translated).containsEntry("s1", "第一段").containsEntry("s2", "第二段");
        org.mockito.ArgumentCaptor<DeepSeekChatOptions.Builder> options =
                org.mockito.ArgumentCaptor.forClass(DeepSeekChatOptions.Builder.class);
        verify(call).options(options.capture());
        assertThat(options.getValue().build().getResponseFormat().getType())
                .isEqualTo(ResponseFormat.Type.JSON_OBJECT);
        assertThat(options.getValue().build().getMaxTokens()).isGreaterThanOrEqualTo(4096);
    }

    @Test
    void retriesMultiSegmentWhenTranslationContainsStructuredRequestLeakage() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec call = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(call);
        when(call.system(anyString())).thenReturn(call);
        when(call.user(anyString())).thenReturn(call);
        when(call.options(org.mockito.ArgumentMatchers.any(DeepSeekChatOptions.Builder.class))).thenReturn(call);
        when(call.call()).thenReturn(response);
        when(response.content()).thenReturn(
                "{\"segments\":[{\"id\":\"s1\",\"translation\":\"请将以下结构化文档请求作为一个连贯的段落进行翻译：源语言为英语；当前文本为第一段\"},"
                        + "{\"id\":\"s2\",\"translation\":\"第二段\"}]}",
                "{\"segments\":[{\"id\":\"s1\",\"translation\":\"第一段\"},{\"id\":\"s2\",\"translation\":\"第二段\"}]}");

        Map<String, String> translated = new DeepSeekParagraphTranslationGateway(builder, new ObjectMapper())
                .translate(multiSegmentRequest());

        assertThat(translated).containsExactlyInAnyOrderEntriesOf(Map.of("s1", "第一段", "s2", "第二段"));
        verify(response, times(2)).content();
    }

    @Test
    void retriesOnlyMissingSegmentAfterPartialJsonResponse() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec call = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(call);
        when(call.system(anyString())).thenReturn(call);
        when(call.user(anyString())).thenReturn(call);
        when(call.options(org.mockito.ArgumentMatchers.any(DeepSeekChatOptions.Builder.class))).thenReturn(call);
        when(call.call()).thenReturn(response);
        when(response.content()).thenReturn(
                "{\"segments\":[{\"id\":\"s1\",\"translation\":\"第一段\"},{\"id\":\"s2\",\"translation\":\" \"}]}",
                "第二段");

        Map<String, String> translated = new DeepSeekParagraphTranslationGateway(builder, new ObjectMapper())
                .translate(multiSegmentRequest());

        assertThat(translated).containsExactlyInAnyOrderEntriesOf(Map.of("s1", "第一段", "s2", "第二段"));
        verify(response, times(2)).content();
    }

    @Test
    void retriesSingleSegmentWhenModelEchoesTheStructuredRequest() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec call = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(call);
        when(call.system(anyString())).thenReturn(call);
        when(call.user(anyString())).thenReturn(call);
        when(call.call()).thenReturn(response);
        when(response.content()).thenReturn(
                "源语言为自动检测，目标语言为简体中文；术语表包括项目目标；"
                        + "先前上下文为……；当前文本为‘您会提出哪些建议？’",
                "您会提出哪些建议？");

        DeepSeekParagraphTranslationGateway gateway = new DeepSeekParagraphTranslationGateway(builder, new ObjectMapper());

        assertThat(gateway.translate(request())).containsExactly(Map.entry("s1", "您会提出哪些建议？"));
        verify(response, times(2)).content();
    }

    @Test
    void keepsSuccessfulSegmentsWhenSingleSegmentFallbackIsEmpty() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec call = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(call);
        when(call.system(anyString())).thenReturn(call);
        when(call.user(anyString())).thenReturn(call);
        when(call.options(org.mockito.ArgumentMatchers.any(DeepSeekChatOptions.Builder.class))).thenReturn(call);
        when(call.call()).thenReturn(response);
        when(response.content()).thenReturn(
                "{\"segments\":[{\"id\":\"s1\",\"translation\":\"第一段\"},{\"id\":\"s2\",\"translation\":\"\"}]}",
                " ");

        Map<String, String> translated = new DeepSeekParagraphTranslationGateway(builder, new ObjectMapper())
                .translate(multiSegmentRequest());

        assertThat(translated).containsExactly(Map.entry("s1", "第一段"));
        verify(response, times(2)).content();
    }

    @Test
    void retriesOnlyWhenTheModelResponseCannotBeParsed() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.options(org.mockito.ArgumentMatchers.any(DeepSeekChatOptions.Builder.class))).thenReturn(request);
        when(request.call()).thenReturn(response);
        when(response.content()).thenReturn("not json", "[{\"id\":\"s1\",\"translation\":\"译文\"},{\"id\":\"s2\",\"translation\":\"译文2\"}]");

        DeepSeekParagraphTranslationGateway gateway = new DeepSeekParagraphTranslationGateway(builder, new ObjectMapper());
        Map<String, String> result = gateway.translate(multiSegmentRequest());

        assertThat(result).containsEntry("s1", "译文");
        verify(response, times(2)).content();
    }

    @Test
    void retriesWhenSegmentIdsDoNotMatchTheParagraph() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.options(org.mockito.ArgumentMatchers.any(DeepSeekChatOptions.Builder.class))).thenReturn(request);
        when(request.call()).thenReturn(response);
        when(response.content()).thenReturn("[{\"id\":\"unknown\",\"translation\":\"译文\"}]",
                "[{\"id\":\"s1\",\"translation\":\"译文\"},{\"id\":\"s2\",\"translation\":\"译文2\"}]");

        DeepSeekParagraphTranslationGateway gateway = new DeepSeekParagraphTranslationGateway(builder, new ObjectMapper());

        assertThat(gateway.translate(multiSegmentRequest())).containsEntry("s1", "译文");
        verify(response, times(2)).content();
    }

    @Test
    void turnsNetworkAccessFailureIntoTransientAiFailureWithoutJsonRetry() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.call()).thenReturn(response);
        when(response.content()).thenThrow(new ResourceAccessException("DNS failure"));

        DeepSeekParagraphTranslationGateway gateway = new DeepSeekParagraphTranslationGateway(builder, new ObjectMapper());

        assertThatThrownBy(() -> gateway.translate(request()))
                .isInstanceOf(TransientAiException.class);
        verify(response, times(1)).content();
    }

    @ParameterizedTest
    @MethodSource("invalidMultiSegmentResponses")
    void rejectsInvalidMultiSegmentRepliesAfterOneRetry(String content,
            InvalidAiResponseException.Reason expectedReason) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec call = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(call);
        when(call.system(anyString())).thenReturn(call);
        when(call.user(anyString())).thenReturn(call);
        when(call.options(org.mockito.ArgumentMatchers.any(DeepSeekChatOptions.Builder.class))).thenReturn(call);
        when(call.call()).thenReturn(response);
        when(response.content()).thenReturn(content);

        DeepSeekParagraphTranslationGateway gateway = new DeepSeekParagraphTranslationGateway(builder, new ObjectMapper());

        assertThatThrownBy(() -> gateway.translate(multiSegmentRequest()))
                .isInstanceOf(InvalidAiResponseException.class)
                .satisfies(failure -> assertThat(((InvalidAiResponseException) failure).reason())
                        .isEqualTo(expectedReason));
        verify(response, times(2)).content();
    }

    private static Stream<Arguments> invalidMultiSegmentResponses() {
        return Stream.of(
                Arguments.of("", InvalidAiResponseException.Reason.EMPTY_TRANSLATION),
                Arguments.of("not json", InvalidAiResponseException.Reason.JSON_SYNTAX),
                Arguments.of("{\"translations\":{}}", InvalidAiResponseException.Reason.RESPONSE_STRUCTURE),
                Arguments.of("{\"segments\":[{\"id\":\"s1\",\"translation\":\"译文\"},{\"id\":\"s1\",\"translation\":\"译文2\"}]}",
                        InvalidAiResponseException.Reason.SEGMENT_MAPPING),
                Arguments.of("{\"segments\":[{\"id\":\"s1\",\"translation\":\"译文\"},{\"id\":\"unknown\",\"translation\":\"译文2\"}]}",
                        InvalidAiResponseException.Reason.SEGMENT_MAPPING));
    }

    private ParagraphTranslationRequest request() {
        return new ParagraphTranslationRequest(Language.EN, Language.ZH_CN, DocumentType.GENERAL,
                TranslationStyle.NATURAL, List.of(), List.of(), "", List.of(
                new StyledTextSegment("s1", "source", "style")));
    }

    private ParagraphTranslationRequest multiSegmentRequest() {
        return new ParagraphTranslationRequest(Language.EN, Language.ZH_CN, DocumentType.GENERAL,
                TranslationStyle.NATURAL, List.of(), List.of(), "", List.of(
                new StyledTextSegment("s1", "first", "plain"),
                new StyledTextSegment("s2", "second", "bold")));
    }
}
