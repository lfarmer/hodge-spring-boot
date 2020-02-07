package uk.co.hodge.boot.actuate.props;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Value;
import org.springframework.boot.actuate.endpoint.Sanitizer;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.env.EnvironmentEndpoint.PropertySummaryDescriptor;
import org.springframework.boot.context.properties.bind.PlaceholdersResolver;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.core.env.*;
import org.springframework.lang.Nullable;
import org.springframework.util.PropertyPlaceholderHelper;
import org.springframework.util.SystemPropertyUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.Comparator.comparing;

/**
 * {@link Endpoint @Endpoint} to expose properties from the {@link ConfigurableEnvironment environment}
 * information.
 *
 * Implementation mainly acquired from {@link org.springframework.boot.actuate.env.EnvironmentEndpoint}, and amended
 * to only return spring properties that are currently in use/
 */
@Endpoint(id = "props")
public class PropsEndpoint {

    private final Sanitizer sanitizer = new Sanitizer();

    private final Environment environment;

    public PropsEndpoint(Environment environment) {
        this.environment = environment;
    }

    public void setKeysToSanitize(String... keysToSanitize) {
        this.sanitizer.setKeysToSanitize(keysToSanitize);
    }

    @ReadOperation
    public PropertiesDescriptor properties(@Nullable String pattern) {
        EnvironmentDescriptor environmentDescriptor = getEnvironmentDescriptor((name) -> true);
        List<PropertyEntryDescriptor> properties = new ArrayList<>();
        List<PropertySourceDescriptor> propertySources = environmentDescriptor.getPropertySources();
        for (PropertySourceDescriptor propertySource : propertySources) {
            Map<String, PropertyValueDescriptor> propertySourceProperties = propertySource.getProperties();
            for (Map.Entry<String, PropertyValueDescriptor> stringPropertyValueDescriptorEntry : propertySourceProperties.entrySet()) {
                properties.add(getEnvironmentEntryDescriptor(stringPropertyValueDescriptorEntry.getKey()));
            }
        }
        return new PropertiesDescriptor(properties);
    }

    private EnvironmentDescriptor getEnvironmentDescriptor(Predicate<String> propertyNamePredicate) {
        PlaceholdersResolver resolver = getResolver();
        List<PropertySourceDescriptor> propertySources = new ArrayList<>();
        getPropertySourcesAsMap().forEach((sourceName, source) -> {
            if (source instanceof EnumerablePropertySource) {
                propertySources.add(
                        describeSource(sourceName, (EnumerablePropertySource<?>) source,
                                resolver, propertyNamePredicate));
            }
        });
        return new EnvironmentDescriptor(
                asList(this.environment.getActiveProfiles()), propertySources);
    }

    private PropertyEntryDescriptor getEnvironmentEntryDescriptor(String propertyName) {

        Map<String, PropertyValueDescriptor> descriptors = getPropertySourceDescriptors(propertyName);
        PropertySummaryDescriptor summary = getPropertySummaryDescriptor(descriptors);
        return new PropertyEntryDescriptor(propertyName, summary.getValue(), summary.getSource());
    }

    private PropertySourceDescriptor describeSource(String sourceName,
                                                    EnumerablePropertySource<?> source, PlaceholdersResolver resolver,
                                                    Predicate<String> namePredicate) {

        Map<String, PropertyValueDescriptor> properties = new LinkedHashMap<>();
        Stream.of(source.getPropertyNames())
                .filter(namePredicate)
                .forEach((name) -> properties.put(name, describeValueOf(name, source, resolver)));
        return new PropertySourceDescriptor(sourceName, properties);
    }

    private Map<String, PropertyValueDescriptor> getPropertySourceDescriptors(String propertyName) {

        Map<String, PropertyValueDescriptor> propertySources = new LinkedHashMap<>();
        PlaceholdersResolver resolver = getResolver();
        getPropertySourcesAsMap()
                .forEach((sourceName, source) ->
                        propertySources.put(sourceName, source.containsProperty(propertyName) ? describeValueOf(propertyName, source, resolver) : null));
        return propertySources;
    }

    private PropertySummaryDescriptor getPropertySummaryDescriptor(Map<String, PropertyValueDescriptor> descriptors) {

        for (Map.Entry<String, PropertyValueDescriptor> entry : descriptors.entrySet()) {
            if (entry.getValue() != null) {
                return new PropertySummaryDescriptor(entry.getKey(), entry.getValue().getValue());
            }
        }
        return null;
    }

    private Map<String, PropertySource<?>> getPropertySourcesAsMap() {
        Map<String, PropertySource<?>> map = new LinkedHashMap<>();
        for (PropertySource<?> source : getPropertySources()) {
            if (!ConfigurationPropertySources
                    .isAttachedConfigurationPropertySource(source)) {
                extract("", map, source);
            }
        }
        return map;
    }

    private MutablePropertySources getPropertySources() {
        if (this.environment instanceof ConfigurableEnvironment) {
            return ((ConfigurableEnvironment) this.environment).getPropertySources();
        }
        return new StandardEnvironment().getPropertySources();
    }

    private void extract(String root, Map<String, PropertySource<?>> map,
                         PropertySource<?> source) {
        if (source instanceof CompositePropertySource) {
            for (PropertySource<?> nest : ((CompositePropertySource) source)
                    .getPropertySources()) {
                extract(source.getName() + ":", map, nest);
            }
        } else {
            map.put(root + source.getName(), source);
        }
    }

    @SuppressWarnings("unchecked")
    private PropertyValueDescriptor describeValueOf(String name, PropertySource<?> source,
                                                    PlaceholdersResolver resolver) {
        Object resolved = resolver.resolvePlaceholders(source.getProperty(name));
        String origin = ((source instanceof OriginLookup)
                ? getOrigin((OriginLookup<Object>) source, name) : null);
        return new PropertyValueDescriptor(sanitize(name, resolved), origin);
    }

    private String getOrigin(OriginLookup<Object> lookup, String name) {
        Origin origin = lookup.getOrigin(name);
        return (origin != null) ? origin.toString() : null;
    }

    public Object sanitize(String name, Object object) {
        return this.sanitizer.sanitize(name, object);
    }

    private PlaceholdersResolver getResolver() {
        return new PropertySourcesPlaceholdersSanitizingResolver(getPropertySources(),
                this.sanitizer);
    }

    /**
     * {@link PropertySourcesPlaceholdersResolver} that sanitizes sensitive placeholders
     * if present.
     */
    private static class PropertySourcesPlaceholdersSanitizingResolver
            extends PropertySourcesPlaceholdersResolver {

        private final Sanitizer sanitizer;

        PropertySourcesPlaceholdersSanitizingResolver(Iterable<PropertySource<?>> sources,
                                                      Sanitizer sanitizer) {
            super(sources,
                    new PropertyPlaceholderHelper(SystemPropertyUtils.PLACEHOLDER_PREFIX,
                            SystemPropertyUtils.PLACEHOLDER_SUFFIX,
                            SystemPropertyUtils.VALUE_SEPARATOR, true));
            this.sanitizer = sanitizer;
        }

        @Override
        protected String resolvePlaceholder(String placeholder) {
            String value = super.resolvePlaceholder(placeholder);
            if (value == null) {
                return null;
            }
            return (String) this.sanitizer.sanitize(placeholder, value);
        }

    }

    /**
     * A description of an {@link Environment}.
     */
    @Value
    public static final class EnvironmentDescriptor {

        private final List<String> activeProfiles;
        private final List<PropertySourceDescriptor> propertySources;

    }

    /**
     * A description of an entry of the {@link Environment}.
     */
    @Value
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class PropertiesDescriptor {

        private final List<PropertyEntryDescriptor> properties;

        private PropertiesDescriptor(List<PropertyEntryDescriptor> properties) {
            this.properties = properties;
            this.properties.sort(comparing(PropertyEntryDescriptor::getName));
        }

    }

    /**
     * A description of an entry of the {@link Environment}.
     */
    @Value
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class PropertyEntryDescriptor {

        private final String name;
        private final Object value;
        private final String source;

    }

    /**
     * A description of a {@link PropertySource}.
     */
    @Value
    public static final class PropertySourceDescriptor {

        private final String name;
        private final Map<String, PropertyValueDescriptor> properties;

    }

    /**
     * A description of a property's value, including its origin if available.
     */
    @Value
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class PropertyValueDescriptor {

        private final Object value;
        private final String origin;

    }

}
