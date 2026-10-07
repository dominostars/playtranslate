// Emits the README's two "Supported Languages" tables for a UI locale, with the
// Language column rendered through the JDK's CLDR data. The app names languages
// with Locale.getDisplayLanguage, so a table built this way matches what a user of
// that locale sees in the in-app picker. Rows, native names and codes are read from
// README.md so the English stays the single source of truth; only the first column
// changes. The header row is left in English for the translator.
//
//   java scripts/readme_lang_tables.java ja          # one locale (BCP-47 tag)
//   java scripts/readme_lang_tables.java             # all 13 README locales
//
// Single-file source launch; needs JDK 11+ (tested on 25). Read-only.
import java.nio.file.*;
import java.util.*;

public class readme_lang_tables {
    static final String[] ALL = {"ja","zh-CN","zh-HK","ko","ru","ar","th","vi","tr","de","fr","es","pt-BR"};

    public static void main(String[] args) throws Exception {
        List<String> lines = Files.readAllLines(Path.of("README.md"));
        List<List<String[]>> tables = new ArrayList<>();
        List<String[]> cur = null;
        for (String l : lines) {
            String t = l.trim();
            if (t.startsWith("|")) {
                if (cur == null) { cur = new ArrayList<>(); tables.add(cur); }
                if (t.matches("\\|[-| ]+\\|")) continue;            // separator row
                String[] cells = t.substring(1, t.length() - 1).split("\\|");
                for (int i = 0; i < cells.length; i++) cells[i] = cells[i].trim();
                cur.add(cells);
            } else cur = null;
        }
        if (tables.size() != 2) throw new IllegalStateException("expected 2 tables in README.md, found " + tables.size());
        String[] tags = args.length == 0 ? ALL : args;
        for (String tag : tags) {
            Locale ui = Locale.forLanguageTag(tag);
            if (tags.length > 1) System.out.println("===== " + tag + " =====");
            System.out.println("GAME TABLE");
            emit(tables.get(0), ui);
            System.out.println();
            System.out.println("TRANSLATION TABLE");
            emit(tables.get(1), ui);
            if (tags.length > 1) System.out.println();
        }
    }

    static void emit(List<String[]> rows, Locale ui) {
        List<String[]> out = new ArrayList<>();
        out.add(rows.get(0));                                     // English header, translator renders it
        for (int i = 1; i < rows.size(); i++) {
            String[] r = rows.get(i);
            String english = r[0], code = r[2];
            String tag = code;
            if (english.contains("(Simplified)")) tag = "zh-Hans"; // README labels plain `zh` as Simplified in the game table
            String name = Locale.forLanguageTag(tag).getDisplayName(ui);
            if (name.isEmpty() || name.equals(tag)) throw new IllegalStateException("no CLDR name for " + tag + " in " + ui);
            name = name.substring(0, 1).toUpperCase(ui) + name.substring(1);
            if (ui.getLanguage().equals("ja") || ui.getLanguage().equals("zh")) name = name.replace(" (", "（").replace(")", "）"); // CJK typography for the script qualifier
            if (ui.getLanguage().equals("ko")) name = name.replace(" (", "("); // Android ko writes 중국어(간체), no space
            out.add(new String[]{name, r[1], r[2]});
        }
        int[] w = new int[3];
        for (String[] r : out) for (int c = 0; c < 3; c++) w[c] = Math.max(w[c], r[c].length());
        for (int i = 0; i < out.size(); i++) {
            System.out.println(row(out.get(i), w));
            if (i == 0) System.out.println("|" + "-".repeat(w[0] + 2) + "|" + "-".repeat(w[1] + 2) + "|" + "-".repeat(w[2] + 2) + "|");
        }
    }

    static String row(String[] r, int[] w) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < 3; c++) sb.append(' ').append(r[c]).append(" ".repeat(w[c] - r[c].length())).append(" |");
        return sb.toString();
    }
}
