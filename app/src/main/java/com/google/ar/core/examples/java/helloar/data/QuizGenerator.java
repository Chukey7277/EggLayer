package com.google.ar.core.examples.java.helloar.data;

import androidx.annotation.Nullable;
import java.util.*;

public final class QuizGenerator {
    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "the","a","an","and","or","but","if","then","else","when","while","for","to","of",
            "in","on","at","by","with","as","is","am","are","was","were","be","been","being",
            "this","that","these","those","it","its","from","into","out","over","under","about",
            "we","you","they","he","she","i","me","my","your","our","their","them","us"
    ));

    /** Build up to maxQ cloze questions from transcript. */
    public static List<EggEntry.QuizQuestion> generate(@Nullable String transcript, int maxQ) {
        List<EggEntry.QuizQuestion> out = new ArrayList<>();
        if (transcript == null) return out;

        // Split by sentences
        String[] sentences = transcript.replace('\n',' ').split("(?<=[.!?])\\s+");
        List<String> candidates = new ArrayList<>(); // words for distractors
        for (String s : sentences) {
            for (String w : s.split("[^A-Za-z0-9']+")) {
                if (w.length() >= 4 && !STOP.contains(w.toLowerCase(Locale.US))) {
                    candidates.add(w);
                }
            }
        }
        if (candidates.size() < 4) return out; // not enough signal

        Random r = new Random();
        // Shuffle sentences to pick varied ones
        List<String> pool = new ArrayList<>(Arrays.asList(sentences));
        Collections.shuffle(pool, r);

        for (String s : pool) {
            // pick a keyword from the sentence
            String kw = pickKeyword(s);
            if (kw == null) continue;

            String blanked = s.replaceFirst("(?i)\\b" + java.util.regex.Pattern.quote(kw) + "\\b", "_____");
            if (blanked.equals(s)) continue; // failed to replace

            // options: correct + 3 distractors
            List<String> opts = new ArrayList<>();
            opts.add(normalize(kw));
            // pick 3 different distractors
            Collections.shuffle(candidates, r);
            for (String c : candidates) {
                String n = normalize(c);
                if (opts.size() >= 4) break;
                if (!n.equalsIgnoreCase(opts.get(0))) {
                    opts.add(n);
                }
            }
            if (opts.size() < 2) continue;

            Collections.shuffle(opts, r);
            int answer = opts.indexOf(normalize(kw));

            EggEntry.QuizQuestion q = new EggEntry.QuizQuestion();
            q.q = blanked;
            q.options = opts;
            q.answer = (answer < 0 ? 0 : answer);

            out.add(q);
            if (out.size() >= Math.max(1, maxQ)) break;
        }
        return out;
    }

    @Nullable
    private static String pickKeyword(String sentence) {
        String best = null;
        for (String w : sentence.split("[^A-Za-z0-9']+")) {
            if (w.length() >= 4 && !STOP.contains(w.toLowerCase(Locale.US))) {
                if (best == null || w.length() > best.length()) best = w;
            }
        }
        return best;
    }

    private static String normalize(String s) {
        return s.trim();
    }

    private QuizGenerator() {}
}
