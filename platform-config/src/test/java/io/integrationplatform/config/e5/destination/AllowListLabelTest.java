package io.integrationplatform.config.e5.destination;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.config.destination.AllowList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** E16 security F2: a wildcard entry only matches real DNS labels in front of its suffix, never URL syntax. */
class AllowListLabelTest {

    private final AllowList list = AllowList.of(List.of("*.partner.example"));

    @ParameterizedTest
    @ValueSource(strings = {"a.partner.example", "a-b.c.partner.example", "A1.Partner.Example"})
    void realLabelsMatchTheWildcard(String host) {
        assertThat(list.allows(host)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a?b.partner.example", "a/b.partner.example", "a#b.partner.example", "a@b.partner.example",
        "a b.partner.example", "a;b.partner.example", "a,b.partner.example", "-a.partner.example",
        "a-.partner.example", "a..partner.example", "a%2fb.partner.example", "a`b.partner.example",
        "evil.com/.partner.example", "x:1.partner.example"})
    @Tag("negative")
    @Tag("security")
    void urlSyntaxInFrontOfTheSuffixDoesNotMatch(String host) {
        assertThat(list.allows(host)).isFalse();
    }
}
