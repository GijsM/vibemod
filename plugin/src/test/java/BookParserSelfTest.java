import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gijsm.vibemine.ui.ConfigBookParser;
import com.gijsm.vibemine.ui.ConfigBookParser.ParseResult;

/**
 * Standalone self-test (no test framework, no Bukkit on the classpath) for
 * {@link ConfigBookParser}'s grammar: separators, comments, blanks, color
 * code stripping, unknown-key suggestions, duplicate-key handling, missing
 * separators, and empty input.
 */
public class BookParserSelfTest {

    private static int failures = 0;

    public static void main(String[] args) {
        testHappyPathMultiPage();
        testEqualsSeparator();
        testCommentsAndBlankLinesSkipped();
        testColorCodeStripping();
        testUnknownKeySuggestion();
        testDuplicateKeyLastWins();
        testNoSeparatorError();
        testEmptyInput();
        testValueContainingColonPreserved();

        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
    }

    private static void testHappyPathMultiPage() {
        List<String> pages = List.of(
                "speed: 5\nstrength: strong",
                "enabled: true");
        Set<String> known = Set.of("speed", "strength", "enabled");

        ParseResult result = ConfigBookParser.parse(pages, known);

        check("no errors", result.errors().isEmpty());
        check("speed parsed", "5".equals(result.values().get("speed")));
        check("strength parsed", "strong".equals(result.values().get("strength")));
        check("enabled parsed from second page", "true".equals(result.values().get("enabled")));
        System.out.println("PASS: happy path multi-page -> " + result.values());
    }

    private static void testEqualsSeparator() {
        List<String> pages = List.of("speed = 7");
        Set<String> known = Set.of("speed");

        ParseResult result = ConfigBookParser.parse(pages, known);

        check("no errors", result.errors().isEmpty());
        check("'=' separator parsed", "7".equals(result.values().get("speed")));
        System.out.println("PASS: '=' separator -> " + result.values());
    }

    private static void testCommentsAndBlankLinesSkipped() {
        List<String> pages = List.of("# a comment\n// another comment\n\nspeed: 3\n   \n");
        Set<String> known = Set.of("speed");

        ParseResult result = ConfigBookParser.parse(pages, known);

        check("no errors", result.errors().isEmpty());
        check("only speed present", result.values().size() == 1 && "3".equals(result.values().get("speed")));
        System.out.println("PASS: # / // comments and blank lines skipped -> " + result.values());
    }

    private static void testColorCodeStripping() {
        List<String> pages = List.of("§aspeed§r: §b9");
        Set<String> known = Set.of("speed");

        ParseResult result = ConfigBookParser.parse(pages, known);

        check("no errors", result.errors().isEmpty());
        check("color codes stripped from key and value", "9".equals(result.values().get("speed")));
        System.out.println("PASS: section-sign color code stripping -> " + result.values());
    }

    private static void testUnknownKeySuggestion() {
        List<String> pages = List.of("spede: 5");
        Set<String> known = Set.of("speed", "strength");

        ParseResult result = ConfigBookParser.parse(pages, known);

        check("value not recorded", result.values().isEmpty());
        check("exactly one error", result.errors().size() == 1);
        check("suggests 'speed'", result.errors().get(0).contains("did you mean 'speed'?"));
        System.out.println("PASS: unknown key with suggestion -> " + result.errors());
    }

    private static void testDuplicateKeyLastWins() {
        List<String> pages = List.of("speed: 1\nspeed: 2");
        Set<String> known = Set.of("speed");

        ParseResult result = ConfigBookParser.parse(pages, known);

        check("last value wins", "2".equals(result.values().get("speed")));
        check("one warning recorded", result.errors().size() == 1);
        check("warning mentions duplicate", result.errors().get(0).contains("duplicate key 'speed'"));
        System.out.println("PASS: duplicate key last-wins + warning -> "
                + result.values() + " / " + result.errors());
    }

    private static void testNoSeparatorError() {
        List<String> pages = List.of("speed: 1", "bogus line without a separator");
        Set<String> known = Set.of("speed");

        ParseResult result = ConfigBookParser.parse(pages, known);

        check("speed still parsed", "1".equals(result.values().get("speed")));
        check("one error", result.errors().size() == 1);
        check("error names page 2, line 1", result.errors().get(0).contains("page 2, line 1"));
        check("error explains expected format", result.errors().get(0).contains("expected 'key: value'"));
        System.out.println("PASS: no-separator error with page/line numbers -> " + result.errors());
    }

    private static void testEmptyInput() {
        ParseResult resultEmptyList = ConfigBookParser.parse(List.of(), Set.of("speed"));
        check("empty page list -> empty values", resultEmptyList.values().isEmpty());
        check("empty page list -> empty errors", resultEmptyList.errors().isEmpty());

        ParseResult resultNull = ConfigBookParser.parse(null, Set.of("speed"));
        check("null pages -> empty values", resultNull.values().isEmpty());
        check("null pages -> empty errors", resultNull.errors().isEmpty());

        System.out.println("PASS: empty input -> empty result");
    }

    private static void testValueContainingColonPreserved() {
        List<String> pages = List.of("url: http://example.com:8080/path");
        Set<String> known = Set.of("url");

        ParseResult result = ConfigBookParser.parse(pages, known);

        check("no errors", result.errors().isEmpty());
        check("value with embedded colons preserved",
                "http://example.com:8080/path".equals(result.values().get("url")));
        System.out.println("PASS: value containing ':' preserved -> " + result.values());
    }

    private static void check(String label, boolean condition) {
        if (condition) {
            System.out.println("  ok: " + label);
        } else {
            System.out.println("  FAIL: " + label);
            failures++;
        }
    }
}
