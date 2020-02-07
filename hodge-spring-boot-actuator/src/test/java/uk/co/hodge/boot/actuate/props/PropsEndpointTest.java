package uk.co.hodge.boot.actuate.props;

import org.junit.Before;
import org.junit.Test;
import org.springframework.boot.actuate.env.EnvironmentEndpoint;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import uk.co.hodge.boot.actuate.props.PropsEndpoint.PropertyEntryDescriptor;

import java.util.Collections;

import static java.util.Collections.singletonMap;
import static org.assertj.core.api.Assertions.assertThat;

public class PropsEndpointTest {

    private ConfigurableEnvironment environment;

    @Before
    public void setUp() {
        environment = emptyEnvironment();
    }

    @Test
    public void shouldGetConfiguredProperties() {
        // Given
        addPropertyEntry("my.prop1", "mypropval1", "priority");
        addPropertyEntry("my.prop2", "mypropval2", "secondpriority");

        // When
        PropsEndpoint.PropertiesDescriptor descriptor = new PropsEndpoint(environment).properties(null);

        assertThat(descriptor.getProperties())
                .contains(
                        new PropertyEntryDescriptor("my.prop1", "mypropval1", "priority"),
                        new PropertyEntryDescriptor("my.prop2", "mypropval2", "secondpriority"));
    }

    @Test
    public void shouldOnlyGetTheHighestPrecedenceProperty() {
        // Given
        addPropertyEntry("my.prop1", "mypropval1", "priority");
        addPropertyEntry("my.prop1", "mypropval2", "secondpriority");

        // When
        PropsEndpoint.PropertiesDescriptor descriptor = new PropsEndpoint(environment).properties(null);

        assertThat(descriptor.getProperties())
                .containsOnly(new PropertyEntryDescriptor("my.prop1", "mypropval1", "priority"));
    }

    private static ConfigurableEnvironment emptyEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        return environment;
    }

    private void addPropertyEntry(String property, Object value, String source) {
        environment.getPropertySources().addLast(new MapPropertySource(source, singletonMap(property, value)));
    }

    @Configuration
    @EnableConfigurationProperties
    static class Config {

        @Bean
        public EnvironmentEndpoint environmentEndpoint(Environment environment) {
            return new EnvironmentEndpoint(environment);
        }

    }

}