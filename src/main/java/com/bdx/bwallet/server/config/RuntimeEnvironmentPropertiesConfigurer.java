package com.bdx.bwallet.server.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.util.PropertyPlaceholderHelper;
import org.springframework.util.StringValueResolver;

public class RuntimeEnvironmentPropertiesConfigurer extends
		PropertyPlaceholderConfigurer implements InitializingBean {
	private static final Log LOG = LogFactory.getLog(RuntimeEnvironmentPropertiesConfigurer.class);
    
    protected static final String PROPERTY_OVERRIDE = "property-override";
    
    protected static Set<String> defaultEnvironments = new LinkedHashSet<String>();
    protected static Set<Resource> defaultPropertyLocations = new LinkedHashSet<Resource>();
    
    static {
        defaultEnvironments.add("production");
        defaultEnvironments.add("staging");
        defaultEnvironments.add("integrationqa");
        defaultEnvironments.add("integrationdev");
        defaultEnvironments.add("development");
        
        defaultPropertyLocations.add(new ClassPathResource("runtime-properties/"));
    }

    protected String defaultEnvironment = "development";
    protected String determinedEnvironment = null;
    protected RuntimeEnvironmentKeyResolver keyResolver;
    protected Set<String> environments = Collections.emptySet();
    protected Set<Resource> propertyLocations;
    protected Set<Resource> overridableProperyLocations;
    protected StringValueResolver stringValueResolver;

    public RuntimeEnvironmentPropertiesConfigurer() {
        super();
        setIgnoreUnresolvablePlaceholders(true); // This default will get overriden by user options if present
    }

    public void afterPropertiesSet() throws IOException {
        // If no environment override has been specified, used the default environments
        if (environments == null || environments.size() == 0) {
            environments = defaultEnvironments;
        }
        
        // Prepend the default property locations to the specified property locations (if any)
        Set<Resource> combinedLocations = new LinkedHashSet<Resource>();
        if (!CollectionUtils.isEmpty(overridableProperyLocations)) {
            combinedLocations.addAll(overridableProperyLocations);
        }
        combinedLocations.addAll(defaultPropertyLocations);
        if (!CollectionUtils.isEmpty(propertyLocations)) {
            combinedLocations.addAll(propertyLocations);
        }
        propertyLocations = combinedLocations;
    
        if (!environments.contains(defaultEnvironment)) {
            throw new AssertionError("Default environment '" + defaultEnvironment + "' not listed in environment list");
        }

        if (keyResolver == null) {
            keyResolver = new SystemPropertyRuntimeEnvironmentKeyResolver();
        }

        String environment = determineEnvironment();
        ArrayList<Resource> allLocations = new ArrayList<Resource>();
        
        /* Process configuration in the following order (later files override earlier files
         * common.properties
         * [environment].properties
         * -Dproperty-override specified value, if any  */
                
        for (Resource resource : createCommonResource()) {
            if (resource.exists()) {
                allLocations.add(resource);
            }
        }

        for (Resource resource : createPropertiesResource(environment)) {
            if (resource.exists()) {
                allLocations.add(resource);
            }
        }
        
        Resource propertyOverride = createOverrideResource();
        if (propertyOverride != null) {
            allLocations.add(propertyOverride);
        }
        
        setLocations(allLocations.toArray(new Resource[] {}));
    }
    
    protected Resource[] createPropertiesResource(String environment) throws IOException {
        String fileName = environment.toString().toLowerCase() + ".properties";
        Resource[] resources = new Resource[propertyLocations.size()];
        int index = 0;
        for (Resource resource : propertyLocations) {
            resources[index] = resource.createRelative(fileName);
            index++;
        }
        return resources;
    }

    protected Resource[] createCommonResource() throws IOException {
        Resource[] resources = new Resource[propertyLocations.size()];
        int index = 0;
        for (Resource resource : propertyLocations) {
            resources[index] = resource.createRelative("common.properties");
            index++;
        }
        return resources;
    }
    
    protected Resource createOverrideResource() throws IOException {
        String path = System.getProperty(PROPERTY_OVERRIDE);
        return StringUtils.isBlank(path) ? null : new FileSystemResource(path);
    }

    public String determineEnvironment() {
        if (determinedEnvironment != null) {
            return determinedEnvironment;
        }
        determinedEnvironment = keyResolver.resolveRuntimeEnvironmentKey();

        if (determinedEnvironment == null) {
            LOG.warn("Unable to determine runtime environment, using default environment '" + defaultEnvironment + "'");
            determinedEnvironment = defaultEnvironment;
        }

        return determinedEnvironment.toLowerCase();
    }

    @Override
    protected void processProperties(ConfigurableListableBeanFactory beanFactoryToProcess, Properties props) throws BeansException {
        super.processProperties(beanFactoryToProcess, props);
        stringValueResolver = new PlaceholderResolvingStringValueResolver(props);
    }

    /**
     * Sets the default environment name, used when the runtime environment
     * cannot be determined.
     */
    public void setDefaultEnvironment(String defaultEnvironment) {
        this.defaultEnvironment = defaultEnvironment;
    }

    public String getDefaultEnvironment() {
        return defaultEnvironment;
    }

    public void setKeyResolver(RuntimeEnvironmentKeyResolver keyResolver) {
        this.keyResolver = keyResolver;
    }

    /**
     * Sets the allowed list of runtime environments
     */
    public void setEnvironments(Set<String> environments) {
        this.environments = environments;
    }

    /**
     * Sets the directory from which to read environment-specific properties
     * files; note that it must end with a '/'
     */
    public void setPropertyLocations(Set<Resource> propertyLocations) {
        this.propertyLocations = propertyLocations;
    }

    /**
     * Sets the directory from which to read environment-specific properties
     * files; note that it must end with a '/'. Note, these properties may be
     * overridden by those defined in propertyLocations and any "runtime-properties" directories
     *
     * @param overridableProperyLocations location containing overridable environment properties
     */
    public void setOverridableProperyLocations(Set<Resource> overridableProperyLocations) {
        this.overridableProperyLocations = overridableProperyLocations;
    }

    private class PlaceholderResolvingStringValueResolver implements StringValueResolver {

        private final PropertyPlaceholderHelper helper;

        private final PropertyPlaceholderHelper.PlaceholderResolver resolver;

        public PlaceholderResolvingStringValueResolver(Properties props) {
            this.helper = new PropertyPlaceholderHelper("${", "}", ":", true);
            this.resolver = new PropertyPlaceholderConfigurerResolver(props);
        }

        public String resolveStringValue(String strVal) throws BeansException {
            String value = this.helper.replacePlaceholders(strVal, this.resolver);
            return (value.equals("") ? null : value);
        }
    }

    private class PropertyPlaceholderConfigurerResolver implements PropertyPlaceholderHelper.PlaceholderResolver {

        private final Properties props;

        private PropertyPlaceholderConfigurerResolver(Properties props) {
            this.props = props;
        }

        public String resolvePlaceholder(String placeholderName) {
            return RuntimeEnvironmentPropertiesConfigurer.this.resolvePlaceholder(placeholderName, props, 1);
        }
    }

    public StringValueResolver getStringValueResolver() {
        return stringValueResolver;
    }
}
