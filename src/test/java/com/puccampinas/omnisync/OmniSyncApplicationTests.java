package com.puccampinas.omnisync;

import com.puccampinas.omnisync.support.EmbeddedPostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresTestConfig.class)
class OmniSyncApplicationTests {

    @Test
    void contextLoads() {
    }

}
