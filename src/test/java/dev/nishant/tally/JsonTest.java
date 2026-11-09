package dev.nishant.tally;

import dev.nishant.tally.http.Json;

import java.util.List;
import java.util.Map;

import static dev.nishant.tally.TestKit.check;
import static dev.nishant.tally.TestKit.eq;

public final class JsonTest {
    public static void testRoundTripsNestedValues() {
        String s = "{\"a\":1,\"b\":[true,false,null],\"c\":{\"d\":\"x\\\"y\\n\"},\"e\":-2.5}";
        Map<String, Object> m = Json.parseObject(s);
        eq(1L, m.get("a"), "integer");
        eq(List.of(true, false), ((List<?>) m.get("b")).subList(0, 2), "array");
        eq("x\"y\n", ((Map<?, ?>) m.get("c")).get("d"), "escaped string");
        eq(-2.5, m.get("e"), "fraction");
        eq(m, Json.parseObject(Json.write(m)), "write/parse round trip");
    }

    public static void testIntegersStayExact() {
        Object v = Json.parseObject("{\"amount\":9007199254740993}").get("amount");
        check(v instanceof Long, "large integers must parse as Long, not double");
        eq(9007199254740993L, v, "no precision loss");
    }

    public static void testUnicodeEscapes() {
        eq("₹", Json.parseObject("{\"s\":\"\\u20b9\"}").get("s"), "unicode escape");
        eq("{\"s\":\"\\u0001\"}", Json.write(Map.of("s", "\u0001")), "control chars escaped on write");
    }

    public static void testRejectsMalformedInput() {
        for (String bad : List.of("", "{", "{\"a\":}", "{\"a\":1,}", "[1 2]", "{\"a\":1} x", "{'a':1}", "{\"a\":01x}", "\"\n\"")) {
            try {
                Json.parse(bad);
                throw new AssertionError("should reject: " + bad);
            } catch (IllegalArgumentException expected) { /* ok */ }
        }
    }

    public static void testRejectsDeepNesting() {
        try {
            Json.parse("[".repeat(100) + "]".repeat(100));
            throw new AssertionError("should reject deep nesting");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }
}
