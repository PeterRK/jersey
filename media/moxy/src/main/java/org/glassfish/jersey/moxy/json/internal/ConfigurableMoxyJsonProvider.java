/*
 * Copyright (c) 2013, 2023 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.jersey.moxy.json.internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.security.AccessController;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.core.Configuration;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.ContextResolver;
import javax.ws.rs.ext.Providers;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.PropertyException;
import javax.xml.bind.Unmarshaller;

import org.eclipse.persistence.internal.core.helper.CoreClassConstants;
import org.eclipse.persistence.jaxb.MarshallerProperties;
import org.eclipse.persistence.jaxb.UnmarshallerProperties;
import org.eclipse.persistence.jaxb.rs.MOXyJsonProvider;
import org.glassfish.jersey.internal.util.ReflectionHelper;
import org.glassfish.jersey.moxy.json.MoxyJsonConfig;

/**
 * Jersey specific {@link MOXyJsonProvider} that can be configured via {@code ContextResolver<JsonMoxyConfiguration>} instance.
 *
 * @author Michal Gajdos
 */
@Singleton
public class ConfigurableMoxyJsonProvider extends MOXyJsonProvider {

    private static final Set<String> MARSHALLER_PROPERTY_NAMES;
    private static final Set<String> UNMARSHALLER_PROPERTY_NAMES;

    static {
        MARSHALLER_PROPERTY_NAMES = getPropertyNames(MarshallerProperties.class);
        UNMARSHALLER_PROPERTY_NAMES = getPropertyNames(UnmarshallerProperties.class);
    }

    private static Set<String> getPropertyNames(final Class<?> propertiesClass) {
        final Set<String> propertyNames = new HashSet<>();

        for (final Field field : AccessController.doPrivileged(ReflectionHelper.getDeclaredFieldsPA(propertiesClass))) {
            if (String.class == field.getType() && Modifier.isStatic(field.getModifiers())) {
                try {
                    propertyNames.add((String) field.get(null));
                } catch (final IllegalAccessException e) {
                    // NOOP.
                }
            }
        }

        return propertyNames;
    }

    private final Configuration config;

    @Inject
    public ConfigurableMoxyJsonProvider(Providers providers, Configuration config) {
        this.providers = providers;
        this.config = config;
    }

    private MoxyJsonConfig globalConfig;

    private MoxyJsonConfig getGlobalConfig() {
        if (globalConfig == null) {
            globalConfig = new MoxyJsonConfig()
                    .setMarshallerProperties(getConfigProperties(config, MARSHALLER_PROPERTY_NAMES))
                    .setUnmarshallerProperties(getConfigProperties(config, UNMARSHALLER_PROPERTY_NAMES));
        }
        return globalConfig;
    }

    private Map<String, Object> getConfigProperties(final Configuration config, final Set<String> propertyNames) {
        final Map<String, Object> properties = new HashMap<>();

        for (final String propertyName : propertyNames) {
            final Object property = config.getProperty(propertyName);
            if (property != null) {
                properties.put(propertyName, property);
            }
        }

        return properties;
    }

    @Override
    protected void preReadFrom(final Class<Object> type, final Type genericType, final Annotation[] annotations,
                               final MediaType mediaType, final MultivaluedMap<String, String> httpHeaders,
                               final Unmarshaller unmarshaller) throws JAXBException {
        super.preReadFrom(type, genericType, annotations, mediaType, httpHeaders, unmarshaller);

        initializeUnmarshaller(unmarshaller);
    }

    @Override
    protected void preWriteTo(final Object object, final Class<?> type, final Type genericType, final Annotation[] annotations,
                              final MediaType mediaType, final MultivaluedMap<String, Object> httpHeaders,
                              final Marshaller marshaller) throws JAXBException {
        super.preWriteTo(object, type, genericType, annotations, mediaType, httpHeaders, marshaller);

        initializeMarshaller(marshaller);
    }

    private void initializeUnmarshaller(final Unmarshaller unmarshaller) throws PropertyException {
        for (final Map.Entry<String, Object> property : getProperties(false).entrySet()) {
            unmarshaller.setProperty(property.getKey(), property.getValue());
        }
    }

    private void initializeMarshaller(final Marshaller marshaller) throws PropertyException {
        for (final Map.Entry<String, Object> property : getProperties(true).entrySet()) {
            marshaller.setProperty(property.getKey(), property.getValue());
        }
    }

    private Map<String, Object> getProperties(final boolean forMarshaller) {
        final Map<String, Object> properties = new HashMap<>(forMarshaller
                ? getGlobalConfig().getMarshallerProperties()
                : getGlobalConfig().getUnmarshallerProperties());

        final ContextResolver<MoxyJsonConfig> contextResolver =
                providers.getContextResolver(MoxyJsonConfig.class, MediaType.APPLICATION_JSON_TYPE);
        if (contextResolver != null) {
            final MoxyJsonConfig jsonConfiguration = contextResolver.getContext(MoxyJsonConfig.class);

            if (jsonConfiguration != null) {
                properties.putAll(forMarshaller
                        ? jsonConfiguration.getMarshallerProperties() : jsonConfiguration.getUnmarshallerProperties());
            }
        }

        return properties;
    }

    @Override
    public boolean isReadable(final Class<?> type,
                              final Type genericType,
                              final Annotation[] annotations,
                              final MediaType mediaType) {
        return !isPrimitiveType(type)
                && super.isReadable(type, genericType, annotations, mediaType);
    }

    @Override
    public boolean isWriteable(final Class<?> type,
                               final Type genericType,
                               final Annotation[] annotations,
                               final MediaType mediaType) {
        return !isPrimitiveType(type)
                && super.isWriteable(type, genericType, annotations, mediaType);
    }

    private boolean isPrimitiveType(final Class<?> type) {
        return CoreClassConstants.STRING == type
                || CoreClassConstants.PCHAR == type || CoreClassConstants.CHAR == type
                || CoreClassConstants.PSHORT == type || CoreClassConstants.SHORT == type
                || CoreClassConstants.PINT == type || CoreClassConstants.INTEGER == type
                || CoreClassConstants.PLONG == type || CoreClassConstants.LONG == type
                || CoreClassConstants.PFLOAT == type || CoreClassConstants.FLOAT == type
                || CoreClassConstants.PDOUBLE == type || CoreClassConstants.DOUBLE == type
                || CoreClassConstants.PBOOLEAN == type || CoreClassConstants.BOOLEAN == type
                || CoreClassConstants.PBYTE == type || CoreClassConstants.BYTE == type;
    }
}
