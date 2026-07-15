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

package haveno.core.util;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import haveno.common.util.JsonExclude;
import haveno.common.util.Utilities;
import haveno.core.offer.OfferPayload;
import haveno.core.trade.Contract;
import java.lang.reflect.Type;


public class JsonUtil {
    public static String objectToJson(Object object) {
        return objectToJson(object, false);
    }

    // Display-only variant that encodes byte[] fields as hex strings instead of number arrays.
    // Must not be used for the signed contract json, which would change the hash and break trades.
    public static String objectToJsonWithHexBytes(Object object) {
        return objectToJson(object, true);
    }

    private static String objectToJson(Object object, boolean hexBytes) {
        GsonBuilder gsonBuilder = new GsonBuilder()
                .setExclusionStrategies(new AnnotationExclusionStrategy())
                .setPrettyPrinting();
        if (object instanceof Contract || object instanceof OfferPayload) {
            gsonBuilder.registerTypeAdapter(OfferPayload.class,
                    new OfferPayload.JsonSerializer());
        }
        if (hexBytes) {
            gsonBuilder.registerTypeAdapter(byte[].class, new ByteArrayHexSerializer());
        }
        return gsonBuilder.create().toJson(object);
    }

    private static class ByteArrayHexSerializer implements com.google.gson.JsonSerializer<byte[]> {
        @Override
        public JsonElement serialize(byte[] bytes, Type type, JsonSerializationContext context) {
            return new JsonPrimitive(Utilities.bytesAsHexString(bytes));
        }
    }

    private static class AnnotationExclusionStrategy implements ExclusionStrategy {
        @Override
        public boolean shouldSkipField(FieldAttributes f) {
            return f.getAnnotation(JsonExclude.class) != null;
        }

        @Override
        public boolean shouldSkipClass(Class<?> clazz) {
            return false;
        }
    }
}
