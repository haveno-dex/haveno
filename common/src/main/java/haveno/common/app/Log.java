/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.common.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.util.FileSize;
import org.slf4j.LoggerFactory;

public class Log {
    private static Logger logbackLogger;

    public static void setLevel(Level logLevel) {
        logbackLogger.setLevel(logLevel);
    }

    public static void setup(String fileName) {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

        RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
        appender.setContext(loggerContext);
        appender.setFile(fileName + ".log");

        FixedWindowRollingPolicy rollingPolicy = new FixedWindowRollingPolicy();
        rollingPolicy.setContext(loggerContext);
        rollingPolicy.setParent(appender);
        rollingPolicy.setFileNamePattern(fileName + "_%i.log");
        rollingPolicy.setMinIndex(1);
        rollingPolicy.setMaxIndex(20);
        rollingPolicy.start();

        SizeBasedTriggeringPolicy<ILoggingEvent> triggeringPolicy = new SizeBasedTriggeringPolicy<>();
        triggeringPolicy.setMaxFileSize(FileSize.valueOf("10MB"));
        triggeringPolicy.setContext(loggerContext);
        triggeringPolicy.start();

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(loggerContext);
        encoder.setPattern("%d{MMM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{15}: %msg%n");
        encoder.start();

        appender.setEncoder(encoder);
        appender.setRollingPolicy(rollingPolicy);
        appender.setTriggeringPolicy(triggeringPolicy);
        appender.start();

        // log errors in separate file
        PatternLayoutEncoder errorEncoder = new PatternLayoutEncoder();
        errorEncoder.setContext(loggerContext);
        errorEncoder.setPattern("%d{MMM-dd HH:mm:ss.SSS} [%thread] %-5level %logger: %msg%n%ex");
        errorEncoder.start();

        RollingFileAppender<ILoggingEvent> errorAppender = new RollingFileAppender<>();
        errorAppender.setEncoder(errorEncoder);
        errorAppender.setName("Error");
        errorAppender.setContext(loggerContext);
        errorAppender.setFile(fileName + "_error.log");

        FixedWindowRollingPolicy errorRollingPolicy = new FixedWindowRollingPolicy();
        errorRollingPolicy.setContext(loggerContext);
        errorRollingPolicy.setParent(errorAppender);
        errorRollingPolicy.setFileNamePattern(fileName + "_error_%i.log");
        errorRollingPolicy.setMinIndex(1);
        errorRollingPolicy.setMaxIndex(20);
        errorRollingPolicy.start();

        SizeBasedTriggeringPolicy<ILoggingEvent> errorTriggeringPolicy = new SizeBasedTriggeringPolicy<>();
        errorTriggeringPolicy.setMaxFileSize(FileSize.valueOf("10MB"));
        errorTriggeringPolicy.start();

        ThresholdFilter thresholdFilter = new ThresholdFilter();
        thresholdFilter.setLevel("ERROR");
        thresholdFilter.start();

        errorAppender.setRollingPolicy(errorRollingPolicy);
        errorAppender.setTriggeringPolicy(errorTriggeringPolicy);
        errorAppender.addFilter(thresholdFilter);
        errorAppender.start();

        logbackLogger = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logbackLogger.addAppender(errorAppender);
        logbackLogger.addAppender(appender);
        logbackLogger.setLevel(Level.INFO);
    }

    public static void setCustomLogLevel(String pattern, Level logLevel) {
        ((Logger) LoggerFactory.getLogger(pattern)).setLevel(logLevel);
    }
}
