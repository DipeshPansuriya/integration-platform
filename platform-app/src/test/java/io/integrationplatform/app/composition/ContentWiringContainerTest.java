package io.integrationplatform.app.composition;

import static org.assertj.core.api.Assertions.assertThat;

import io.integrationplatform.PlatformTestWiring;
import io.integrationplatform.TestImages;
import io.integrationplatform.routing.adapter.db.DbRows;
import io.integrationplatform.routing.adapter.file.FileOutbound;
import io.integrationplatform.routing.adapter.file.FileTargetContent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E16 content wiring, container level (AUTHORED, NOT RUN by its author: no Docker lock in that lane). In the real
 * application boot with the FILE target gate on, the composition supplies the transform-backed DB rows and file
 * content - so no DB/FILE delivery dead-letters PERMANENT for want of a message (Varun B4, Aditya E18). A full
 * delivery-through-the-file-system scenario (seed a FILE system with rootPath and fileNamePattern, a pinned JSONata
 * mapping, post, assert the file lands as {@code <pattern>} with the transformed bytes) extends
 * {@code TwoTargetOrdersEndToEndContainerTest}'s seed and belongs to the QA lane.
 */
@Tag("container")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContentWiringContainerTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.POSTGRES);

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        PlatformTestWiring.register(registry, POSTGRES);
        registry.add("IPASS_FILE_TARGET_ENABLED", () -> "true");
    }

    @Test
    void theCompositionSuppliesTheTransformBackedRowsAndFileContent() {
        assertThat(context.getBean(DbRows.class)).isInstanceOf(TransformedDbRows.class);
        assertThat(context.getBeansOfType(FileTargetContent.class)).containsOnlyKeys("transformedFileContent");
        assertThat(context.getBean(FileOutbound.class)).isNotNull();
    }
}
