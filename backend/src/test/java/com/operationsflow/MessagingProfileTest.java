package com.operationsflow;

import com.operationsflow.messaging.infrastructure.MessagingConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import static org.assertj.core.api.Assertions.*;

class MessagingProfileTest {
    @Test void defaultProfileDoesNotCreateBrokerInfrastructure() {
        new ApplicationContextRunner().withUserConfiguration(MessagingConfiguration.class)
            .run(context -> assertThat(context).doesNotHaveBean(ConnectionFactory.class));
    }
    @Test void demoOverridesExplicitMessagingProfileWithoutCredentials() {
        new ApplicationContextRunner().withPropertyValues("spring.profiles.active=demo,messaging")
            .withUserConfiguration(MessagingConfiguration.class)
            .run(context -> assertThat(context).doesNotHaveBean(ConnectionFactory.class));
    }
}
