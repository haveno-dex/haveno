/*
 * This file is part of Haveno.
 *
 * Haveno is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Haveno is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Haveno. If not, see <http://www.gnu.org/licenses/>.
 */

package haveno.core.user;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;

/**
 * A small unencrypted, globally scoped key-value store for UI state that must be available before
 * the account is unlocked at startup (e.g. the css theme and the main window bounds). It is the
 * unencrypted counterpart of the (encrypted, per-account) user {@link Cookie}: the same
 * {@link CookieKey} namespace and typed accessors, persisted to a single plain file in the app data
 * directory. Extend it simply by adding a {@link CookieKey}.
 *
 * <p>Writes merge into the current on-disk contents, so unrelated keys written from different places
 * (e.g. the theme and the window bounds) do not clobber each other.
 */
@Slf4j
public class StartupSettings {

    private static final String FILE_NAME = "startup_settings";

    /** Reads the full store as a cookie (empty if the file is absent or unreadable). */
    public static Cookie read(File appDataDir) {
        Properties props = new Properties();
        File file = new File(appDataDir, FILE_NAME);
        if (file.exists()) {
            try (Reader reader = Files.newBufferedReader(file.toPath())) {
                props.load(reader);
            } catch (Exception e) {
                log.warn("Could not read {}, ignoring: {}", FILE_NAME, e.getMessage());
            }
        }
        Map<String, String> map = new HashMap<>();
        props.forEach((key, value) -> map.put(key.toString(), value.toString()));
        return Cookie.fromProto(map);
    }

    /** Merges the given entries into the store and persists it. */
    public static void write(File appDataDir, Cookie updates) {
        Cookie merged = read(appDataDir);
        merged.putAll(updates);
        Properties props = new Properties();
        merged.toProtoMessage().forEach(props::setProperty);
        try (Writer writer = Files.newBufferedWriter(new File(appDataDir, FILE_NAME).toPath())) {
            props.store(writer, "Haveno startup UI state");
        } catch (Exception e) {
            log.warn("Could not persist {}: {}", FILE_NAME, e.getMessage());
        }
    }
}
