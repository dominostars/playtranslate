import morfologik.stemming.DictionaryLookup;
import morfologik.stemming.WordData;
import morfologik.stemming.polish.PolishStemmer;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Dumps PoliMorf as a `form\tlemma` TSV on stdout — the surface->lemma table
 * scripts/polish_morphology.py turns into position-2 alias rows.
 *
 * Emitted pairs are lowercased with Locale.ROOT (Polish has no special case
 * mapping, unlike Turkish) and filtered to single-token, form != lemma rows.
 * The Python side re-applies lower_for_lang(..., "pl") anyway, so this file is
 * only ever a source of CANDIDATE pairs — the pack key is always produced by
 * the same function that produced the position-0 keys.
 *
 * Run:
 *   java -cp "morfologik-polish-2.1.9.jar:morfologik-stemming-2.1.9.jar:morfologik-fsa-2.1.9.jar" \
 *        scripts/polish-morphology/DumpPoliMorf.java > polimorf.tsv
 *
 * Expect ~4.8M rows over ~315k lemmas.
 */
public class DumpPoliMorf {
    public static void main(String[] args) throws Exception {
        PrintWriter out = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8), 1 << 20));
        long rows = 0;
        for (WordData wd : new DictionaryLookup(new PolishStemmer().getDictionary())) {
            if (wd.getWord() == null || wd.getStem() == null) continue;
            String form = wd.getWord().toString().toLowerCase(Locale.ROOT);
            String lemma = wd.getStem().toString().toLowerCase(Locale.ROOT);
            if (form.isEmpty() || lemma.isEmpty() || form.equals(lemma)) continue;
            if (form.indexOf(' ') >= 0 || lemma.indexOf(' ') >= 0) continue;
            out.print(form);
            out.print('\t');
            out.print(lemma);
            out.print('\n');
            rows++;
        }
        out.flush();
        System.err.println("DumpPoliMorf: wrote " + rows + " form->lemma rows");
    }
}
