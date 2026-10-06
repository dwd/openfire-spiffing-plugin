package org.igniterealtime.openfire.spiffing;

import org.jivesoftware.util.SystemProperty;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SpiffingPropertiesTest {

    @Test
    void propertiesAreRegisteredAndAccessible() {
        assertNotNull(SpiffingProperties.ENFORCEMENT_MODE);
        assertNotNull(SpiffingProperties.STRIP_DEFAULT_LABEL_FOR_FEDERATION);
        assertNotNull(SpiffingProperties.OUTPUT_FORMAT);

        Optional<SystemProperty> enforcementProp = SystemProperty.getProperty("plugin.spiffing.enforcementMode");
        assertTrue(enforcementProp.isPresent());

        Optional<SystemProperty> stripProp = SystemProperty.getProperty("plugin.spiffing.stripDefaultLabelForFederation");
        assertTrue(stripProp.isPresent());

        Optional<SystemProperty> outputFormatProp = SystemProperty.getProperty("plugin.spiffing.outputFormat");
        assertTrue(outputFormatProp.isPresent());
    }

    @Test
    void reloadInSeparateClassLoaderDoesNotThrowWhenPropertiesAlreadyExist() throws Exception {
        // Ensure properties exist in SystemProperty registry
        assertNotNull(SpiffingProperties.ENFORCEMENT_MODE);
        assertTrue(SystemProperty.getProperty("plugin.spiffing.enforcementMode").isPresent());

        // Create a separate ClassLoader that loads SpiffingProperties independently while sharing Openfire classes
        String[] paths = System.getProperty("java.class.path").split(File.pathSeparator);
        URL[] urls = new URL[paths.length];
        for (int i = 0; i < paths.length; i++) {
            urls[i] = new File(paths[i]).toURI().toURL();
        }

        try (URLClassLoader isolatedLoader = new URLClassLoader(urls, getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("org.igniterealtime.openfire.spiffing.SpiffingProperties")) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Class<?> reloadedPropertiesClass = isolatedLoader.loadClass("org.igniterealtime.openfire.spiffing.SpiffingProperties");
            assertDoesNotThrow(() -> {
                // Trigger class initialization <clinit>
                Class.forName("org.igniterealtime.openfire.spiffing.SpiffingProperties", true, isolatedLoader);
            });
            Object enforcementMode = reloadedPropertiesClass.getMethod("getEnforcementMode").invoke(null);
            assertNotNull(enforcementMode);
        }
    }
}
