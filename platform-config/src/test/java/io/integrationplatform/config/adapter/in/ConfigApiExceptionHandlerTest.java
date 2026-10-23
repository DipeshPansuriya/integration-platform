package io.integrationplatform.config.adapter.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.integrationplatform.config.flow.api.InterfacePublishRefused;
import io.integrationplatform.tenancy.TenantContext;
import io.integrationplatform.tenancy.port.DeniedTenantAttempt;
import io.integrationplatform.tenancy.port.TenantDenialRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Row 278 C2 (ADR-017 Decision 2): a publish a {@code BEFORE_COMMIT} listener refused with
 * {@link InterfacePublishRefused} answers through the configuration API's error seam as a 400 carrying the
 * listener's (already sanitised) reason as {@code {"error": reason}} - not the catch-all 500. It is a validation
 * refusal, not a cross-tenant attempt: no denied-attempt record, and nothing logged above DEBUG.
 *
 * <p>Row 278 T160 (Kavita K-C2-1, CWE-209 defence in depth): the handler does not trust the thrower. It strips
 * control characters from the reason and caps its length before answering; a reason that is blank after stripping
 * answers a fixed text, never an empty string.
 */
class ConfigApiExceptionHandlerTest {

    private static final String REASON = "the publish was refused: endpoint URI scheme 'exec' is not in allowedSchemes";

    /** The handler's config-local reason cap (RefusalText bounds each echoed value; this bounds the whole reason). */
    private static final int REASON_CAP = ConfigApiExceptionHandler.REASON_CAP;

    private final RefusedPublishController controller = new RefusedPublishController();
    private final List<DeniedTenantAttempt> denials = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private Logger handlerLog;
    private MockMvc mvc;

    @BeforeEach
    void theConfigApi() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("recorder", (TenantDenialRecorder) denials::add);
        beans.addBean("tenantContext", (TenantContext) () -> Optional.of(UUID.randomUUID()));
        ConfigApiExceptionHandler handler = new ConfigApiExceptionHandler(
                beans.getBeanProvider(TenantDenialRecorder.class), beans.getBeanProvider(TenantContext.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(handler).build();

        handlerLog = (Logger) LoggerFactory.getLogger(ConfigApiExceptionHandler.class);
        logged.start();
        handlerLog.addAppender(logged);
    }

    @AfterEach
    void detach() {
        handlerLog.detachAppender(logged);
    }

    @Test
    void aRefusedPublishAnswers400WithTheReasonAsTheErrorBody() throws Exception {
        mvc.perform(post("/publish/refused"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$", aMapWithSize(1)))
                .andExpect(jsonPath("$.error").value(REASON));
    }

    @Test
    void aBodyThatIsNotJsonAnswers400WithAStaticBodyAndNoDenial() throws Exception {
        for (String bad : new String[] {"{\"a\":[{", "not json", "[".repeat(10_000) + "]".repeat(10_000)}) {
            mvc.perform(post("/body").contentType("application/json").content(bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$", aMapWithSize(1)))
                    .andExpect(jsonPath("$.error").value("the request body could not be read"));
        }
        assertThat(denials).isEmpty();
    }

    @Test
    void aWrongContentTypeAnswers415NotTheCatchAll500() throws Exception {
        mvc.perform(post("/body").contentType("text/plain").content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$", aMapWithSize(1)))
                .andExpect(jsonPath("$.error").value("the request was not accepted"));
    }

    @Test
    void aMissingRequiredParameterAnswers400NotTheCatchAll500() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("the request was not accepted"));
    }

    @Test
    void aWrongMethodAnswers405WithTheAllowHeaderAndTheStaticBody() throws Exception {
        // the controller throws it: the advice is scoped by basePackages, so a 405 raised by the mapping lookup
        // (no handler chosen yet) never reaches it - see the T196 hand-back
        mvc.perform(post("/wrong-method"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Allow", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$", aMapWithSize(1)))
                .andExpect(jsonPath("$.error").value("the request was not accepted"));
    }

    @Test
    void anUnacceptableAcceptHeaderAnswers406WithAJsonBodyTheClientDidNotAskFor() throws Exception {
        // the handler must not fail while writing JSON to a client that sent Accept: text/plain
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/json")
                        .accept("text/plain"))
                .andExpect(status().isNotAcceptable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$", aMapWithSize(1)))
                .andExpect(jsonPath("$.error").value("the request was not accepted"));
    }

    @Test
    void aValueOfTheWrongTypeAnswers400NotTheCatchAll500() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/number")
                        .param("n", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$", aMapWithSize(1)))
                .andExpect(jsonPath("$.error").value("the request was not accepted"));
    }

    @Test
    void aMissingHeaderAnswers400NotTheCatchAll500() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("the request was not accepted"));
    }

    @Test
    void aMissingMultipartPartAnswers400NotTheCatchAll500() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/part"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("the request was not accepted"));
    }

    @Test
    void anUploadOverTheSizeLimitAnswers413NotTheCatchAll500() throws Exception {
        mvc.perform(post("/too-big"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$", aMapWithSize(1)))
                .andExpect(jsonPath("$.error").value("the request was not accepted"));
    }

    @Test
    void aMissingPathVariableKeepsItsStatusWithTheStaticBody() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/no-variable"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$", aMapWithSize(1)))
                .andExpect(jsonPath("$.error").value("the request was not accepted"));
    }

    @Test
    void anUnreadableBodyLogsNothingOfItsContent() throws Exception {
        // N4: a credential-shaped body that is not JSON must not reach the log, even at DEBUG
        handlerLog.setLevel(Level.DEBUG);
        mvc.perform(post("/body").contentType("application/json").content("{\"password\": hunter2SECRET}"))
                .andExpect(status().isBadRequest());
        assertThat(logged.list).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("hunter2SECRET");
            assertThat(event.getThrowableProxy() == null ? "" : ch.qos.logback.classic.spi.ThrowableProxyUtil
                    .asString(event.getThrowableProxy())).doesNotContain("hunter2SECRET");
        });
    }

    @Test
    void aRefusedPublishRecordsNoTenantDenialAndLogsNothingAboveDebug() throws Exception {
        mvc.perform(post("/publish/refused")).andExpect(status().isBadRequest());

        assertThat(denials).as("a validation refusal is not a cross-tenant attempt").isEmpty();
        assertThat(logged.list).as("the reason is never logged above DEBUG")
                .noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.INFO));
    }

    @Test
    void controlCharactersInTheReasonAreStrippedBeforeTheAnswer() throws Exception {
        controller.reason = "the publish\r\n was refused\t\u0000: scheme\u001b 'exec'\u007f";

        mvc.perform(post("/publish/refused"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$", aMapWithSize(1)))
                .andExpect(jsonPath("$.error").value("the publish was refused: scheme 'exec'"));
    }

    @Test
    void anOverLongReasonIsCutToExactlyTheCap() throws Exception {
        controller.reason = "x".repeat(REASON_CAP) + "TAIL-NEVER-ANSWERED";

        mvc.perform(post("/publish/refused"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("x".repeat(REASON_CAP)));
    }

    @Test
    void aReasonBlankAfterStrippingAnswersTheFixedText() throws Exception {
        controller.reason = "\r\n\t\u0000 \u001b";

        mvc.perform(post("/publish/refused"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("the publish was refused"));
    }

    @Test
    void lineSeparatorsBidiControlsAndFormatCharactersAreStrippedBeforeTheAnswer() throws Exception {
        // T160 L-5 (CWE-451/CWE-117): U+2028/U+2029 (Zl/Zp), bidi embeddings and isolates U+202A-202E and
        // U+2066-2069, and other Cf characters (zero-width space, BOM, soft hyphen) are not ISO controls.
        controller.reason = "the\u2028 publish\u2029 was\u202a\u202b\u202c\u202d\u202e refused"
                + "\u2066\u2067\u2068\u2069:\u200b scheme\ufeff\u00ad 'exec'";

        mvc.perform(post("/publish/refused"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("the publish was refused: scheme 'exec'"));
    }

    @Test
    void theCapCountsCodePointsAndNeverSplitsASurrogatePair() throws Exception {
        // T160 L-4 (CWE-176): an emoji (two UTF-16 units) at code point 128 is kept whole, never cut to a lone
        // high surrogate; the answer is exactly REASON_CAP code points.
        String emoji = new String(Character.toChars(0x1F600));
        controller.reason = "x".repeat(REASON_CAP - 1) + emoji + "TAIL-NEVER-ANSWERED";

        mvc.perform(post("/publish/refused"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("x".repeat(REASON_CAP - 1) + emoji));
    }

    @Test
    void aReasonOfOnlyNonBmpCharactersIsCutAtTheCapInCodePoints() throws Exception {
        String emoji = new String(Character.toChars(0x1F600));
        controller.reason = emoji.repeat(REASON_CAP + 5);

        mvc.perform(post("/publish/refused"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(emoji.repeat(REASON_CAP)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"exec", "file", "http"})
    void aRefusalWrappedOnTheWayOutStillAnswers400NamingTheSchemeNeverTheGeneric500(String scheme) throws Exception {
        controller.reason = "the publish was refused: endpoint URI scheme '" + scheme + "' is not in allowedSchemes";

        mvc.perform(post("/publish/refused-wrapped"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$", aMapWithSize(1)))
                .andExpect(jsonPath("$.error").value(controller.reason));
        assertThat(logged.list).as("T71 crit 5 / F15: a refusal is not a failure - nothing above DEBUG")
                .noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.INFO));
    }

    /** A configuration publish whose BEFORE_COMMIT listener refused it, with the reason the test sets. */
    @RestController
    static class RefusedPublishController {

        String reason = REASON;

        @PostMapping("/body")
        String body(@org.springframework.web.bind.annotation.RequestBody tools.jackson.databind.JsonNode node) {
            return "ok";
        }

        @org.springframework.web.bind.annotation.GetMapping("/json")
        java.util.Map<String, String> json() {
            return java.util.Map.of("a", "b"); // only a JSON converter can write this: Accept text/plain gives 406
        }

        @PostMapping("/wrong-method")
        String wrongMethod() throws org.springframework.web.HttpRequestMethodNotSupportedException {
            throw new org.springframework.web.HttpRequestMethodNotSupportedException("POST", java.util.List.of("GET"));
        }

        @org.springframework.web.bind.annotation.GetMapping("/number")
        String number(@org.springframework.web.bind.annotation.RequestParam int n) {
            return String.valueOf(n);
        }

        @org.springframework.web.bind.annotation.GetMapping("/header")
        String header(@org.springframework.web.bind.annotation.RequestHeader("X-Required") String required) {
            return required;
        }

        @PostMapping("/part")
        String part(@org.springframework.web.bind.annotation.RequestPart("file")
                org.springframework.web.multipart.MultipartFile file) {
            return "ok";
        }

        @PostMapping("/too-big")
        String tooBig() {
            throw new org.springframework.web.multipart.MaxUploadSizeExceededException(1);
        }

        @org.springframework.web.bind.annotation.GetMapping("/no-variable")
        String noVariable(@org.springframework.web.bind.annotation.PathVariable String id) {
            return id;
        }

        @org.springframework.web.bind.annotation.GetMapping("/param")
        String param(@org.springframework.web.bind.annotation.RequestParam String required) {
            return required;
        }

        @PostMapping("/publish/refused")
        String publish() {
            throw new InterfacePublishRefused(reason, new IllegalStateException("routing detail, never answered"));
        }

        /** The same refusal, wrapped by a commit path on its way out (e.g. a transaction or event wrapper). */
        @PostMapping("/publish/refused-wrapped")
        String publishWrapped() {
            throw new IllegalStateException("commit failed", new InterfacePublishRefused(reason,
                    new IllegalStateException("routing detail, never answered")));
        }
    }
}
