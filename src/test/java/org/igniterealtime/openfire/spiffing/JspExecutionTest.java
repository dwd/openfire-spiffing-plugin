package org.igniterealtime.openfire.spiffing;

import org.junit.jupiter.api.Test;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.container.Plugin;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;


public class JspExecutionTest {
    @Test
    public void testSettingsJspExecution() throws Exception {
        // Test instantiation of JSP servlet
        Class<?> jspClass = Class.forName("org.jivesoftware.openfire.plugin.spiffing.spiffing_002dsettings_jsp");
        Object jspInstance = jspClass.getDeclaredConstructor().newInstance();
        System.out.println("JSP servlet instantiated: " + jspInstance);
    }
}
