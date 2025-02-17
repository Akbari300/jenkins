/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
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
package hudson.logging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import hudson.model.Computer;
import hudson.remoting.VirtualChannel;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import jenkins.security.MasterToSlaveCallable;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.Url;

/**
 * @author Kohsuke Kawaguchi
 */
public class LogRecorderManagerTest {

    @Rule public JenkinsRule j = new JenkinsRule();

    /**
     * Makes sure that the logger configuration works.
     */
    @Url("http://d.hatena.ne.jp/ssogabe/20090101/1230744150")
    @Test public void loggerConfig() throws Exception {
        Logger logger = Logger.getLogger("foo.bar.zot");

        HtmlPage page = j.createWebClient().goTo("log/levels");
        HtmlForm form = page.getFormByName("configLogger");
        form.getInputByName("name").setValueAttribute("foo.bar.zot");
        form.getSelectByName("level").getOptionByValue("finest").setSelected(true);
        j.submit(form);

        assertEquals(Level.FINEST, logger.getLevel());
    }

    @Issue({"JENKINS-18274", "JENKINS-63458"})
    @Test public void loggingOnSlaves() throws Exception {
        // TODO could also go through WebClient to assert that the config UI works
        LogRecorderManager mgr = j.jenkins.getLog();
        LogRecorder r1 = new LogRecorder("r1");
        mgr.getRecorders().add(r1);
        LogRecorder.Target t = new LogRecorder.Target("ns1", Level.FINE);
        r1.getLoggers().add(t);
        r1.save();
        t.enable();
        Computer c = j.createOnlineSlave().toComputer();
        assertNotNull(c);
        t = new LogRecorder.Target("ns2", Level.FINER);
        r1.getLoggers().add(t);
        r1.save();
        t.enable();
        LogRecorder r2 = new LogRecorder("r2");
        mgr.getRecorders().add(r2);
        t = new LogRecorder.Target("ns3", Level.FINE);
        r2.getLoggers().add(t);
        r2.save();
        t.enable();
        VirtualChannel ch = c.getChannel();
        assertNotNull(ch);
        assertTrue(ch.call(new Log(Level.FINE, "ns1", "msg #1")));
        assertTrue(ch.call(new Log(Level.FINER, "ns2", "msg #2")));
        assertTrue(ch.call(new Log(Level.FINE, "ns3", "msg #3")));
        assertFalse(ch.call(new Log(Level.FINER, "ns3", "not displayed")));
        assertTrue(ch.call(new Log(Level.INFO, "ns4", "msg #4")));
        assertFalse(ch.call(new Log(Level.FINE, "ns4", "not displayed")));
        assertTrue(ch.call(new Log(Level.INFO, "other", "msg #5 {0,number,0.0} {1,number,0.0} ''OK?''", new Object[] {1.0, 2.0})));
        assertTrue(ch.call(new LambdaLog(Level.FINE, "ns1")));
        assertFalse(ch.call(new LambdaLog(Level.FINER, "ns1")));
        List<LogRecord> recs = c.getLogRecords();
        assertEquals(show(recs), 6, recs.size());
        // Would of course prefer to get "msg #5 1.0 2.0 'OK?'" but all attempts to fix this have ended in disaster (JENKINS-63458):
        assertEquals("msg #5 {0,number,0.0} {1,number,0.0} ''OK?''", new SimpleFormatter().formatMessage(recs.get(1)));
        recs = r1.getSlaveLogRecords().get(c);
        assertNotNull(recs);
        assertEquals(show(recs), 3, recs.size());
        recs = r2.getSlaveLogRecords().get(c);
        assertNotNull(recs);
        assertEquals(show(recs), 1, recs.size());
        String text = j.createWebClient().goTo("log/r1/").asNormalizedText();
        assertTrue(text, text.contains(c.getDisplayName()));
        assertTrue(text, text.contains("msg #1"));
        assertTrue(text, text.contains("msg #2"));
        assertFalse(text, text.contains("msg #3"));
        assertFalse(text, text.contains("msg #4"));
        assertTrue(text, text.contains("LambdaLog @FINE"));
        assertFalse(text, text.contains("LambdaLog @FINER"));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void addingLogRecorderToLegacyMapAddsToRecordersList() throws IOException {
        LogRecorderManager log = j.jenkins.getLog();

        assertThat(log.logRecorders.size(), is(0));
        assertThat(log.getRecorders().size(), is(0));

        LogRecorder logRecorder = new LogRecorder("dummy");
        logRecorder.getLoggers().add(new LogRecorder.Target("dummy", Level.ALL));

        log.logRecorders.put("dummy", logRecorder);
        logRecorder.save();

        assertThat(log.logRecorders.size(), is(1));
        assertThat(log.getRecorders().size(), is(1));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void addingLogRecorderToListAddsToLegacyRecordersMap() throws IOException {
        LogRecorderManager log = j.jenkins.getLog();

        assertThat(log.logRecorders.size(), is(0));
        assertThat(log.getRecorders().size(), is(0));

        LogRecorder logRecorder = new LogRecorder("dummy");
        logRecorder.getLoggers().add(new LogRecorder.Target("dummy", Level.ALL));

        log.getRecorders().add(logRecorder);
        logRecorder.save();

        assertThat(log.logRecorders.size(), is(1));
        assertThat(log.getRecorders().size(), is(1));
    }

    private static final class Log extends MasterToSlaveCallable<Boolean,Error> {
        private final Level level;
        private final String logger;
        private final String message;
        private final Object[] params;
        Log(Level level, String logger, String message) {
            this(level, logger, message, null);
        }
        Log(Level level, String logger, String message, Object[] params) {
            this.level = level;
            this.logger = logger;
            this.message = message;
            this.params = params;
        }
        @Override public Boolean call() throws Error {
            Logger log = Logger.getLogger(logger);
            if (params != null) {
                log.log(level, message, params);
            } else {
                log.log(level, message);
            }
            return log.isLoggable(level);
        }
    }

    private static final class LambdaLog extends MasterToSlaveCallable<Boolean,Error> {
        private final Level level;
        private final String logger;
        LambdaLog(Level level, String logger) {
            this.level = level;
            this.logger = logger;
        }
        @Override public Boolean call() throws Error {
            Logger log = Logger.getLogger(logger);
            log.log(level, () -> "LambdaLog @" + level);
            return log.isLoggable(level);
        }
    }

    private static String show(List<LogRecord> recs) {
        StringBuilder b = new StringBuilder();
        for (LogRecord rec : recs) {
            b.append('\n').append(rec.getLoggerName()).append(':').append(rec.getLevel()).append(':').append(rec.getMessage());
        }
        return b.toString();
    }

}
