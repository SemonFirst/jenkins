/*
 * The MIT License
 *
 * Copyright (c) 2017, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.security;

import com.gargoylesoftware.htmlunit.CookieManager;
import com.gargoylesoftware.htmlunit.WebResponse;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.util.Cookie;
import hudson.security.captcha.CaptchaSupport;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;
import java.io.OutputStream;

import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Random;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public class SecurityRealmTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    @Issue("JENKINS-43852")
    public void testCacheHeaderInResponse() throws Exception {
        SecurityRealm securityRealm = j.createDummySecurityRealm();
        j.jenkins.setSecurityRealm(securityRealm);

        WebResponse response = j.createWebClient()
                .goTo("securityRealm/captcha", "")
                .getWebResponse();
        assertEquals(response.getContentAsString(), "");

        securityRealm.setCaptchaSupport(new DummyCaptcha());

        response = j.createWebClient()
                .goTo("securityRealm/captcha", "image/png")
                .getWebResponse();

        assertThat(response.getResponseHeaderValue("Cache-Control"), is("no-cache, no-store, must-revalidate"));
        assertThat(response.getResponseHeaderValue("Pragma"), is("no-cache"));
        assertThat(response.getResponseHeaderValue("Expires"), is("0"));
    }

    private class DummyCaptcha extends CaptchaSupport {
        @Override
        public boolean validateCaptcha(String id, String text) {
            return false;
        }

        @Override
        public void generateImage(String id, OutputStream ios) throws IOException {
        }
    }

    static void addSessionCookie(CookieManager manager, String domain, String path, Date date) {
        manager.addCookie(new Cookie(domain, "JSESSIONID."+Integer.toHexString(new Random().nextInt()),
                "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
                path,
                date,
                false));
    }

    @Test
    public void many_sessions_logout() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        CookieManager manager = wc.getCookieManager();
        manager.setCookiesEnabled(true);
        wc.goTo("login");

        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, 1);
        Date tomorrow = calendar.getTime();
        byte[] array = new byte[7];
        Collections.nCopies(8, 1)
                .stream()
                .forEach(i -> addSessionCookie(manager, "localhost", "/jenkins", tomorrow));
        addSessionCookie(manager, "localhost", "/will-not-be-sent", tomorrow);

        HtmlPage page = wc.goTo("logout");

        StringBuilder builder = new StringBuilder();
        int sessionCookies = 0;

        boolean debugCookies = false;
        for (Cookie cookie : manager.getCookies()) {
            if (cookie.getName().startsWith("JSESSIONID")) {
                ++sessionCookies;

                if (debugCookies) {
                    builder.append(cookie.getName());
                    String path = cookie.getPath();
                    if (path != null)
                        builder.append("; Path=").append(path);
                    builder.append("\n");
                }
            }
        }
        if (debugCookies) {
            System.err.println(builder.toString());
        }
        /* There should be two surviving JSESSION* cookies:
         *   path:"/will-not-be-sent" -- because it wasn't sent and thus wasn't deleted.
         *   name:"JSESSIONID" -- because this test harness isn't winstone and the cleaning
         *                        code is only responsible for deleting "JSESSIONID." cookies.
         *
         * Note: because we aren't running/testing winstone, it isn't sufficient for us to have
         * this test case, we actually need an acceptance test where we're run against winstone.
         */
        assertThat(sessionCookies, is(2));
    }
}
