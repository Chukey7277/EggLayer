package com.google.ar.core.examples.java.helloar.data;

import androidx.annotation.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Heuristic (local) quiz builder used as a fallback when Vertex AI isn't used. */
public final class QuizGenerator {

    // Fallback pools when we don't have enough distractors in the text
    private static final String[] FALLBACK_PLACES = {
            "City Library","Central Park","Main Auditorium","Science Museum",
            "Tech Hub","Town Hall","Art Gallery","Riverfront"
    };
    private static final String[] FALLBACK_NAMES = {
            "Heritage Block","Innovation Center","Discovery Hall","Unity Plaza",
            "Nexus Building","Skylark Tower","Harmony Wing","Pioneer House"
    };

    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "the","a","an","and","or","but","if","then","else","when","while","for","to","of",
            "in","on","at","by","with","as","is","am","are","was","were","be","been","being",
            "this","that","these","those","it","its","from","into","out","over","under","about",
            "we","you","they","he","she","i","me","my","your","our","their","them","us"
    ));

    /** Build up to {@code maxQ} multiple-choice questions from free text. */
    public static List<EggEntry.QuizQuestion> generate(@Nullable String rawText, int maxQ) {
        List<EggEntry.QuizQuestion> out = new ArrayList<>();
        if (rawText == null) return out;

        // Normalize whitespace
        String text = rawText.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return out;

        // Split into sentences for easier patterning
        String[] sentences = text.split("(?<=[.!?])\\s+");

        // Collect proper-noun phrases from the whole text (for distractors)
        List<String> properPhrases = extractProperNounPhrases(text);

        // Shuffle sentences so we get varied questions
        List<String> shuffled = new ArrayList<>(Arrays.asList(sentences));
        Collections.shuffle(shuffled, new Random());

        for (String s : shuffled) {
            EggEntry.QuizQuestion q;

            q = tryWhereQuestion(s, properPhrases);
            if (addIfValid(out, q, maxQ)) break;

            q = tryWhenQuestion(s);
            if (addIfValid(out, q, maxQ)) break;

            q = tryHowManyQuestion(s);
            if (addIfValid(out, q, maxQ)) break;

            q = tryWhatNameQuestion(s, properPhrases);
            if (addIfValid(out, q, maxQ)) break;
        }

        // If still nothing, fabricate a very generic location/name question from pools
        if (out.isEmpty()) {
            EggEntry.QuizQuestion q = new EggEntry.QuizQuestion();
            q.question = "Which place is mentioned here?";
            List<String> opts = new ArrayList<>();
            String correct = properPhrases.isEmpty() ? FALLBACK_PLACES[0] : properPhrases.get(0);
            opts.add(correct);
            fillWithFallbacks(opts, Arrays.asList(FALLBACK_PLACES), 4);
            shuffleKeepAnswer(opts, correct, q);
            out.add(q);
        }

        return out;
    }

    // ---------- Question builders ----------

    // WHERE: “… in/at/inside/near/opposite/beside <ProperPhrase> …”
    private static EggEntry.QuizQuestion tryWhereQuestion(String s, List<String> properPool) {
        Pattern p = Pattern.compile("\\b(in|at|inside|within|near|around|opposite|beside)\\s+([A-Z][\\w&.-]*(?:\\s+[A-Z][\\w&.-]*)*)");
        Matcher m = p.matcher(s);
        if (!m.find()) return null;

        String place = m.group(2).trim();
        if (place.isEmpty()) return null;

        EggEntry.QuizQuestion q = new EggEntry.QuizQuestion();
        q.question = "Where is this located?";
        q.options = new ArrayList<>();
        q.options.add(place);

        // Use other proper nouns as distractors; fallback if needed
        List<String> distractors = new ArrayList<>();
        for (String pp : properPool) {
            if (!pp.equalsIgnoreCase(place)) distractors.add(pp);
        }
        if (distractors.isEmpty()) distractors = Arrays.asList(FALLBACK_PLACES);
        fillWithFallbacks(q.options, distractors, 4);
        shuffleKeepAnswer(q.options, place, q);
        return q;
    }

    // WHEN: “… built/constructed/opened/founded/renovated … <YEAR>”
    private static EggEntry.QuizQuestion tryWhenQuestion(String s) {
        Matcher ym = Pattern.compile("\\b(19|20)\\d{2}\\b").matcher(s);

        boolean hasVerb = Pattern.compile("\\b(built|constructed|opened|founded|renovated|established|inaugurated)\\b",
                Pattern.CASE_INSENSITIVE).matcher(s).find();

        if (!hasVerb || !ym.find()) return null;

        int year = Integer.parseInt(ym.group());
        EggEntry.QuizQuestion q = new EggEntry.QuizQuestion();
        q.question = "In which year was it " + pickVerb(s) + "?";

        // Options around the true year
        Set<String> opts = new LinkedHashSet<>();
        opts.add(String.valueOf(year));
        Random r = new Random();
        while (opts.size() < 4) {
            int delta = (r.nextInt(5) + 1); // 1..5
            int sign = (r.nextBoolean() ? 1 : -1);
            int candidate = year + sign * delta;
            if (candidate >= 1900 && candidate <= 2100) opts.add(String.valueOf(candidate));
        }
        q.options = new ArrayList<>(opts);
        shuffleKeepAnswer(q.options, String.valueOf(year), q);
        return q;
    }

    // HOW MANY: “… <number> floors/rooms/galleries/levels …”
    private static EggEntry.QuizQuestion tryHowManyQuestion(String s) {
        Matcher m = Pattern.compile("\\b(\\d{1,3})\\s+(floors?|rooms?|galleries?|levels?|buildings?)\\b",
                Pattern.CASE_INSENSITIVE).matcher(s);
        if (!m.find()) return null;

        int n = Integer.parseInt(m.group(1));
        String noun = m.group(2).toLowerCase(Locale.US);

        EggEntry.QuizQuestion q = new EggEntry.QuizQuestion();
        q.question = "How many " + noun + " are there?";
        Set<String> opts = new LinkedHashSet<>();
        opts.add(String.valueOf(n));
        // Nearby numbers as distractors
        for (int d : new int[]{n - 1, n + 1, n + 2, n - 2, n + 3}) {
            if (d > 0) opts.add(String.valueOf(d));
            if (opts.size() >= 4) break;
        }
        while (opts.size() < 4) opts.add(String.valueOf(n + opts.size())); // fallback
        q.options = new ArrayList<>(opts);
        shuffleKeepAnswer(q.options, String.valueOf(n), q);
        return q;
    }

    // WHAT NAME: “… called/named <ProperPhrase> …”
    private static EggEntry.QuizQuestion tryWhatNameQuestion(String s, List<String> properPool) {
        Matcher m = Pattern.compile("\\b(called|named)\\s+([A-Z][\\w.-]*(?:\\s+[A-Z][\\w.-]*)*)").matcher(s);
        if (!m.find()) return null;

        String name = m.group(2).trim();
        if (name.isEmpty()) return null;

        EggEntry.QuizQuestion q = new EggEntry.QuizQuestion();
        q.question = "What is it called?";
        q.options = new ArrayList<>();
        q.options.add(name);

        List<String> distractors = new ArrayList<>();
        for (String pp : properPool) {
            if (!pp.equalsIgnoreCase(name)) distractors.add(pp);
        }
        if (distractors.isEmpty()) distractors = Arrays.asList(FALLBACK_NAMES);
        fillWithFallbacks(q.options, distractors, 4);
        shuffleKeepAnswer(q.options, name, q);
        return q;
    }

    // ---------- Helpers ----------

    private static boolean addIfValid(List<EggEntry.QuizQuestion> out, @Nullable EggEntry.QuizQuestion q, int maxQ) {
        if (q == null) return false;
        if (q.options == null || q.options.size() < 2) return false;
        if (q.correctIndex == null || q.correctIndex < 0 || q.correctIndex >= q.options.size()) return false;
        out.add(q);
        return out.size() >= Math.max(1, maxQ);
    }

    private static void fillWithFallbacks(List<String> list, List<String> pool, int targetSize) {
        for (String p : pool) {
            if (list.size() >= targetSize) break;
            boolean dup = false;
            for (String s : list) if (s.equalsIgnoreCase(p)) { dup = true; break; }
            if (!dup) list.add(p);
        }
        int i = 1;
        while (list.size() < targetSize) list.add("Option " + (i++));
    }

    private static void shuffleKeepAnswer(List<String> opts, String correct, EggEntry.QuizQuestion q) {
        Collections.shuffle(opts, new Random());
        q.options = new ArrayList<>(opts);
        int idx = 0;
        for (int i = 0; i < opts.size(); i++) {
            if (opts.get(i).equalsIgnoreCase(correct)) { idx = i; break; }
        }
        q.correctIndex = idx;
        if (q.question != null && !q.question.trim().endsWith("?")) q.question = q.question.trim() + "?";
    }

    /** Very light “proper noun phrase” extractor: sequences of Capitalized words / acronyms. */
    private static List<String> extractProperNounPhrases(String text) {
        List<String> out = new ArrayList<>();
        String[] toks = text.split("\\s+");
        StringBuilder cur = new StringBuilder();
        for (String tok : toks) {
            String w = stripPunct(tok);
            if (w.isEmpty()) continue;
            if (isProperToken(w)) {
                if (cur.length() > 0) cur.append(" ");
                cur.append(w);
            } else {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            }
        }
        if (cur.length() > 0) out.add(cur.toString());

        // Filter obvious stopwords / noise
        List<String> cleaned = new ArrayList<>();
        for (String s : out) {
            String low = s.toLowerCase(Locale.US);
            if (!STOPWORDS.contains(low) && s.length() >= 3) cleaned.add(s);
        }

        // Dedup (case-insensitive) while keeping order
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String s : cleaned) {
            boolean seen = false;
            for (String t : set) if (t.equalsIgnoreCase(s)) { seen = true; break; }
            if (!seen) set.add(s);
        }
        return new ArrayList<>(set);
    }

    private static boolean isProperToken(String w) {
        if (w.length() < 2) return false;
        char c0 = w.charAt(0);
        // Capitalized word or ALL CAPS (acronyms)
        return Character.isUpperCase(c0) || w.equals(w.toUpperCase(Locale.US));
    }

    private static String stripPunct(String s) {
        return s.replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "");
    }

    private static String pickVerb(String s) {
        String low = s.toLowerCase(Locale.US);
        if (low.contains("constructed")) return "constructed";
        if (low.contains("renovated"))   return "renovated";
        if (low.contains("founded"))     return "founded";
        if (low.contains("established")) return "established";
        if (low.contains("inaugurated")) return "inaugurated";
        if (low.contains("opened"))      return "opened";
        return "built";
    }

    private QuizGenerator() {}
}
