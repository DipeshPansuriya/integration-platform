package io.integrationplatform.tenancy.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * T197 R2 (karan, B33 sign-off): when the session store is down the eager session load answers 503, never "no
 * session" (a 401 or redirect that would send a signed-in user back to login) and never an escaped exception (a 500
 * whose ERROR dispatch repeats the lookup, holding a thread for 2 x 30 s). The log carries the exception class only.
 */
class ConsoleSessionStoreOutageTest {

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private Logger log;

    @BeforeEach
    void capture() {
        log = (Logger) LoggerFactory.getLogger(ConsoleSecurityConfiguration.class);
        logged.start();
        log.addAppender(logged);
    }

    @AfterEach
    void detach() {
        log.detachAppender(logged);
    }

    private static SecurityContextRepository storeThatFails(RuntimeException failure) {
        SecurityContextRepository down = mock(SecurityContextRepository.class);
        DeferredSecurityContext deferred = mock(DeferredSecurityContext.class);
        when(deferred.get()).thenThrow(failure);
        when(down.loadDeferredContext(any())).thenReturn(deferred);
        return down;
    }

    @Test
    void aDataAccessFailureOnTheEagerLoadIsAnsweredWith503AndTheChainIsNotContinued() throws Exception {
        var repository = new ConsoleSecurityConfiguration.EagerSessionContextRepository(
                storeThatFails(new DataAccessResourceFailureException("jdbc:postgresql://db-host/secret-detail")));
        MockHttpServletRequest request = new MockHttpServletRequest();

        DeferredSecurityContext context = repository.loadDeferredContext(request); // must not throw

        assertThat(context.get().getAuthentication()).as("not signed in, but not 'no session' either").isNull();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        new ConsoleSecurityConfiguration.SessionStoreUnavailableFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("session_store_unavailable")
                .doesNotContain("db-host").doesNotContain("secret-detail");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void anOutageThatSurfacesAsATransactionFailureIsTheSameAnswer() throws Exception {
        // the JDBC session repository is transactional: a dead pool fails in getTransaction, not in the SQL
        var repository = new ConsoleSecurityConfiguration.EagerSessionContextRepository(
                storeThatFails(new CannotCreateTransactionException("Could not open JDBC Connection")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        repository.loadDeferredContext(request);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ConsoleSecurityConfiguration.SessionStoreUnavailableFilter().doFilter(request, response,
                mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void theLogCarriesTheExceptionClassOnlyNeverItsMessageOrStack() {
        var repository = new ConsoleSecurityConfiguration.EagerSessionContextRepository(
                storeThatFails(new DataAccessResourceFailureException("jdbc:postgresql://db-host/secret-detail")));

        repository.loadDeferredContext(new MockHttpServletRequest());

        assertThat(logged.list).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getThrowableProxy()).as("no stack trace").isNull();
            assertThat(event.getFormattedMessage()).doesNotContain("secret-detail").doesNotContain("db-host");
        });
        assertThat(logged.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("DataAccessResourceFailureException"));
    }

    @Test
    void aHealthyStoreIsNotAnswered503AndTheChainContinues() throws Exception {
        var repository = new ConsoleSecurityConfiguration.EagerSessionContextRepository(
                new RequestAttributeSecurityContextRepository());
        MockHttpServletRequest request = new MockHttpServletRequest();
        repository.loadDeferredContext(request);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        new ConsoleSecurityConfiguration.SessionStoreUnavailableFilter().doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aFailureThatIsNotTheStoreIsNotSwallowed() {
        var repository = new ConsoleSecurityConfiguration.EagerSessionContextRepository(
                storeThatFails(new IllegalStateException("a bug, not an outage")));

        assertThatThrownBy(() -> repository.loadDeferredContext(new MockHttpServletRequest()))
                .isInstanceOf(IllegalStateException.class);
    }
}
